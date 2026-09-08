package com.locogo.astockguard.ui.main

/*
 * 文件职责：聚合行情、计划、复盘、模拟盘和 AI 状态并向界面暴露单一 StateFlow；一次性操作由明确方法触发，不让 UI 直接访问网络客户端。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.AiClient
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.PromptBuilder
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.ai.AiStrategyParser
import com.locogo.astockguard.data.fundflow.FundFlowRepository
import com.locogo.astockguard.data.level2.Level2Repository
import com.locogo.astockguard.data.local.AiAnalysisEntity
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.news.NewsRepository
import com.locogo.astockguard.domain.paper.PaperTradingRepository
import com.locogo.astockguard.domain.replay.ReplayEngine
import com.locogo.astockguard.domain.replay.VolumeStrategyReplayEngine
import com.locogo.astockguard.domain.review.ReviewRepository
import com.locogo.astockguard.domain.review.AccountLedgerType
import com.locogo.astockguard.domain.review.AlertHistoryRepository
import com.locogo.astockguard.domain.signal.R2Scanner
import com.locogo.astockguard.domain.trading.TTradePlanner
import com.locogo.astockguard.domain.plan.AuctionPlanEngine
import com.locogo.astockguard.domain.plan.PositionPlanEngine
import com.locogo.astockguard.domain.plan.PortfolioExposureEngine
import com.locogo.astockguard.domain.plan.ExposureBacktestEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface MainEffect { data class RunHiddenWebAi(val prompt: String) : MainEffect }

class MainViewModel(
    private val settings: SettingsRepository,
    private val marketRepository: MarketRepository,
    private val fundFlowRepository: FundFlowRepository,
    private val newsRepository: NewsRepository,
    private val level2Repository: Level2Repository,
    private val paperTradingRepository: PaperTradingRepository,
    private val replayEngine: ReplayEngine,
    private val r2Scanner: R2Scanner,
    private val reviewRepository: ReviewRepository,
    private val alertHistoryRepository: AlertHistoryRepository,
    private val aiClient: AiClient,
    private val cacheDao: CacheDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MainUiState(positions = settings.positions(), cashBalance = settings.cashBalance, monitoredCodes = settings.allCodes())
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    init {
        viewModelScope.launch {
            reviewRepository.observeImportedAccountMetrics().collect { metrics ->
                _uiState.update { it.copy(importedAccountMetrics = metrics) }
            }
        }
    }
    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<MainEffect> = _effects.asSharedFlow()
    private var lastNewsRefreshAt = 0L
    private var replayJob: Job? = null
    private var exposureBacktestJob: Job? = null
    private var exposureBacktestKey: String = ""

    /** 接收前台服务运行状态，仅更新界面，不在 ViewModel 中直接启停 Service。 */
    fun updateMonitorRunning(running: Boolean) {
        _uiState.update { it.copy(monitorRunning = running) }
    }

    /**
     * 将持久化的真实持仓同步进页面状态。
     * 同花顺识别与设置页都可能在首页不可见时修改配置，因此不能只依赖下一次行情快照触发重绘。
     */
    fun syncPortfolioSettings(): Boolean {
        val positions = settings.positions()
        val cashBalance = settings.cashBalance
        val current = _uiState.value
        val monitoredCodes = settings.allCodes()
        if (positions == current.positions && cashBalance == current.cashBalance && monitoredCodes == current.monitoredCodes) return false

        val positionCodes = positions.mapTo(hashSetOf()) { it.code }
        val nextCode = current.selectedCode?.takeIf(positionCodes::contains)
            ?: positions.firstOrNull()?.code
        val selectionChanged = nextCode != current.selectedCode
        _uiState.update {
            it.copy(
                positions = positions,
                monitoredCodes = monitoredCodes,
                cashBalance = cashBalance,
                selectedCode = nextCode,
                dailyBars = if (selectionChanged) emptyList() else it.dailyBars,
                dailySource = if (selectionChanged) "UNKNOWN" else it.dailySource,
                dailyFromCache = if (selectionChanged) true else it.dailyFromCache,
                dailyRealtimeMerged = if (selectionChanged) false else it.dailyRealtimeMerged,
                dailyUpdatedAt = if (selectionChanged) 0L else it.dailyUpdatedAt,
                minuteBars = if (selectionChanged) emptyList() else it.minuteBars,
                stockFundFlow = if (selectionChanged) null else it.stockFundFlow,
                level2 = if (selectionChanged) null else it.level2,
                tTradePlan = if (selectionChanged) null else it.tTradePlan,
                auctionPlan = if (selectionChanged) null else it.auctionPlan,
                positionPlans = current.snapshot?.let { snapshot ->
                    PositionPlanEngine.evaluate(positions, snapshot.quotes, snapshot.assessment.marketPhase, snapshot.dataHealth.isStale)
                }.orEmpty(),
                exposurePlan = current.snapshot?.let { snapshot ->
                    PortfolioExposureEngine.evaluate(
                        positions, snapshot.quotes, snapshot.assessment, snapshot.positionRatio, snapshot.dataHealth.isStale
                    )
                } ?: it.exposurePlan
            )
        }
        nextCode?.takeIf { selectionChanged }?.let(::selectStock)
        refreshExposureBacktest(positions)
        return true
    }

    fun acceptSnapshot(snapshot: com.locogo.astockguard.MonitorSnapshot) {
        val positions = settings.positions()
        val cashBalance = settings.cashBalance
        val positionCodes = positions.mapTo(hashSetOf()) { it.code }
        val selected = _uiState.value.selectedCode?.takeIf(positionCodes::contains)
            ?: positions.firstOrNull()?.code
            ?: snapshot.quotes.firstOrNull()?.code
        _uiState.update {
            it.copy(
                snapshot = snapshot,
                monitoredCodes = settings.allCodes(),
                positions = positions,
                cashBalance = cashBalance,
                loading = false,
                selectedCode = selected,
                positionPlans = PositionPlanEngine.evaluate(
                    positions, snapshot.quotes, snapshot.assessment.marketPhase, snapshot.dataHealth.isStale
                ),
                exposurePlan = PortfolioExposureEngine.evaluate(
                    positions, snapshot.quotes, snapshot.assessment, snapshot.positionRatio, snapshot.dataHealth.isStale
                ),
                error = null
            )
        }
        selected?.let(::selectStock)
        viewModelScope.launch {
            val curve = runCatching { marketRepository.buildPortfolioCurve(settings.positions()) }.getOrDefault(emptyList())
            _uiState.update { it.copy(equityCurve = curve) }
        }
        if (_uiState.value.sectorFundFlow == null) refreshSectorFlow("INDUSTRY")
        viewModelScope.launch {
            val scan = runCatching { r2Scanner.scan(settings.allCodes(), snapshot.assessment.marketPhase) }.getOrDefault(emptyList())
            val states = runCatching { cacheDao.getSignalStates() }.getOrDefault(emptyList())
            _uiState.update { it.copy(r2ScanRows = scan, signalStates = states) }
        }
        refreshReviews()
        refreshPaper()
        refreshNews(force = false)
        refreshExposureBacktest(positions)
    }

    /** 按当前持仓权重执行日K无前视回放；相同持仓不会随行情轮询重复请求历史数据。 */
    private fun refreshExposureBacktest(positions: List<com.locogo.astockguard.Position>) {
        val key = positions.filter { it.shares > 0 }.joinToString("|") { "${it.code}:${it.shares}" }
        if (key == exposureBacktestKey) return
        exposureBacktestJob?.cancel()
        exposureBacktestKey = key
        if (key.isBlank()) {
            _uiState.update { it.copy(exposureBacktest = com.locogo.astockguard.domain.plan.ExposureBacktestReport()) }
            return
        }
        _uiState.update { it.copy(exposureBacktestLoading = true) }
        exposureBacktestJob = viewModelScope.launch {
            val selected = positions.filter { it.shares > 0 }.take(MAX_BACKTEST_POSITIONS)
            val histories = selected.associate { position ->
                position.code to runCatching {
                    marketRepository.loadDailyBars(position.code, BACKTEST_DAYS)
                }.getOrDefault(emptyList())
            }
            val report = ExposureBacktestEngine.evaluate(selected, histories)
            _uiState.update { it.copy(exposureBacktest = report, exposureBacktestLoading = false) }
        }
    }

    fun selectStock(code: String) {
        val changed = _uiState.value.selectedCode != code
        if (changed) pauseReplay()
        _uiState.update {
            it.copy(
                selectedCode = code,
                fundFlowLoading = true,
                level2Loading = true,
                manualBuyAnchor = if (changed) null else it.manualBuyAnchor,
                tTradePlan = if (changed) null else it.tTradePlan,
                auctionPlan = if (changed) null else it.auctionPlan,
                replayIndex = if (changed) -1 else it.replayIndex,
                replayReport = if (changed) null else it.replayReport,
                volumeReplayComparison = if (changed) null else it.volumeReplayComparison
            )
        }
        viewModelScope.launch {
            val dailySeries = runCatching { marketRepository.loadDailySeries(code, 30) }.getOrNull()
            val daily = dailySeries?.bars.orEmpty()
            val minuteSeries = runCatching { marketRepository.loadMinuteSeries(code) }.getOrNull()
            val auctionSeries = runCatching { marketRepository.loadAuctionSeries(code) }.getOrNull()
            val minute = minuteSeries?.bars.orEmpty()
            val flow = runCatching { fundFlowRepository.stock(code) }.getOrNull()
            val referencePrice = _uiState.value.snapshot?.quotes?.firstOrNull { it.code == code }?.latest
            val level2 = runCatching { level2Repository.snapshot(code, referencePrice) }.getOrNull()
            val trades = runCatching { cacheDao.getTradeRecords().filter { it.code == code } }.getOrDefault(emptyList())
            val quote = _uiState.value.snapshot?.quotes?.firstOrNull { it.code == code }
            val position = settings.positions().firstOrNull { it.code == code }
            val auctionPlan = auctionSeries?.let { series ->
                AuctionPlanEngine.evaluate(code, position?.role, quote?.previousClose, daily, series)
            }
            if (_uiState.value.selectedCode == code) {
                _uiState.update {
                    it.copy(
                        dailyBars = daily,
                        dailySource = dailySeries?.source ?: "UNKNOWN",
                        dailyFromCache = dailySeries?.fromCache ?: true,
                        dailyRealtimeMerged = dailySeries?.realtimeMerged == true,
                        dailyUpdatedAt = dailySeries?.updatedAt ?: 0L,
                        minuteBars = minute,
                        minuteFromCache = minuteSeries?.fromCache ?: true,
                        minuteHistorical = minuteSeries?.isHistorical ?: false,
                        minuteSource = minuteSeries?.source ?: "ROOM_CACHE",
                        stockFundFlow = flow,
                        fundFlowLoading = false,
                        level2 = level2,
                        level2Loading = false,
                        auctionPlan = auctionPlan,
                        tradeRecords = trades
                    )
                }
                recalculateTPlan(code)
            }
        }
    }

    fun setBuyAnchor(price: Double) {
        if (!price.isFinite() || price <= 0.0) return
        val code = _uiState.value.selectedCode ?: return
        _uiState.update { it.copy(manualBuyAnchor = price) }
        recalculateTPlan(code)
    }

    fun clearBuyAnchor() {
        val code = _uiState.value.selectedCode ?: return
        _uiState.update { it.copy(manualBuyAnchor = null) }
        recalculateTPlan(code)
    }

    private fun recalculateTPlan(code: String) {
        val state = _uiState.value
        val snapshot = state.snapshot ?: return
        val quote = snapshot.quotes.firstOrNull { it.code == code }
        val position = settings.positions().firstOrNull { it.code == code }
        val plan = TTradePlanner.plan(
            quote = quote,
            position = position,
            minuteBars = state.minuteBars,
            fundFlow = state.stockFundFlow,
            marketPhase = snapshot.assessment.marketPhase,
            dataStale = snapshot.dataHealth.isStale,
            manualAnchorPrice = state.manualBuyAnchor,
            level2 = state.level2
        )
        if (_uiState.value.selectedCode == code) _uiState.update { it.copy(tTradePlan = plan) }
    }

    fun refreshLevel2() {
        val code = _uiState.value.selectedCode ?: return
        val referencePrice = _uiState.value.snapshot?.quotes?.firstOrNull { it.code == code }?.latest
        _uiState.update { it.copy(level2Loading = true) }
        viewModelScope.launch {
            val level2 = runCatching { level2Repository.snapshot(code, referencePrice) }
                .onFailure { t -> _uiState.update { it.copy(error = "Level2刷新失败：${t.message}") } }
                .getOrNull()
            if (_uiState.value.selectedCode == code) {
                _uiState.update { it.copy(level2 = level2 ?: it.level2, level2Loading = false) }
                // 手动刷新盘口后立即重算计划，避免界面仍展示刷新前的盘口结论。
                recalculateTPlan(code)
            }
        }
    }

    fun refreshPaper() {
        val quotes = _uiState.value.snapshot?.quotes.orEmpty()
        viewModelScope.launch {
            val summary = runCatching { paperTradingRepository.summary(quotes) }
                .onFailure { t -> _uiState.update { it.copy(error = "模拟盘刷新失败：${t.message}") } }
                .getOrNull()
            if (summary != null) _uiState.update { it.copy(paperSummary = summary, paperLoading = false) }
        }
    }

    fun paperTrade(side: String, quantity: Int = 100) {
        val code = _uiState.value.selectedCode ?: return
        val price = _uiState.value.snapshot?.quotes?.firstOrNull { it.code == code }?.latest
            ?: _uiState.value.minuteBars.lastOrNull()?.price
            ?: run { _uiState.update { it.copy(error = "没有可用的模拟成交价") }; return }
        _uiState.update { it.copy(paperLoading = true) }
        viewModelScope.launch {
            runCatching { paperTradingRepository.execute(code, side, quantity, price, source = "LIVE_PAPER") }
                .onSuccess { refreshPaper() }
                .onFailure { t -> _uiState.update { it.copy(paperLoading = false, error = "模拟交易失败：${t.message}") } }
        }
    }

    fun resetPaper() {
        _uiState.update { it.copy(paperLoading = true) }
        viewModelScope.launch {
            runCatching { paperTradingRepository.reset() }
                .onSuccess { refreshPaper() }
                .onFailure { t -> _uiState.update { it.copy(paperLoading = false, error = "重置模拟盘失败：${t.message}") } }
        }
    }

    fun resetReplay() {
        pauseReplay()
        val first = if (_uiState.value.minuteBars.isEmpty()) -1 else 0
        _uiState.update { it.copy(replayIndex = first, replayReport = null, volumeReplayComparison = null) }
    }

    fun stepReplay() {
        pauseReplay()
        val bars = _uiState.value.minuteBars
        if (bars.isEmpty()) return
        val next = (_uiState.value.replayIndex + 1).coerceIn(0, bars.lastIndex)
        _uiState.update { it.copy(replayIndex = next) }
    }

    fun startReplay(speed: Int = 5) {
        val bars = _uiState.value.minuteBars
        if (bars.isEmpty()) { _uiState.update { it.copy(error = "没有分钟数据可回放") }; return }
        replayJob?.cancel()
        val delayMs = (1_000L / speed.coerceIn(1, 20)).coerceAtLeast(50L)
        replayJob = viewModelScope.launch {
            var index = _uiState.value.replayIndex.takeIf { it >= 0 } ?: 0
            _uiState.update { it.copy(replayIndex = index, replayRunning = true) }
            while (index < bars.lastIndex) {
                delay(delayMs)
                index++
                _uiState.update { it.copy(replayIndex = index) }
            }
            _uiState.update { it.copy(replayRunning = false) }
        }
    }

    fun pauseReplay() {
        replayJob?.cancel()
        replayJob = null
        _uiState.update { it.copy(replayRunning = false) }
    }

    /**
     * 在同一份分钟数据上同时执行原有VWAP回放和A/B/C/D量能策略对照。
     * 原报告继续服务图表成交标记；新报告专门比较净收益、回撤、费用与机会损失，二者不会写入真实账户。
     */
    fun runReplayBacktest() {
        val bars = _uiState.value.minuteBars
        if (bars.isEmpty()) { _uiState.update { it.copy(error = "没有分钟数据可做策略回放") }; return }
        val report = replayEngine.runVwapReclaim(bars)
        val comparison = VolumeStrategyReplayEngine.compare(
            code = _uiState.value.selectedCode ?: "UNKNOWN",
            bars = bars
        )
        _uiState.update {
            it.copy(replayReport = report, volumeReplayComparison = comparison, replayIndex = bars.lastIndex)
        }
    }

    fun refreshSectorFlow(type: String) {
        viewModelScope.launch {
            val result = runCatching { fundFlowRepository.sectors(type) }.getOrNull()
            if (result != null) _uiState.update { it.copy(sectorFundFlow = result) }
        }
    }

    fun refreshNews(force: Boolean = true) {
        if (!settings.newsEnabled) { _uiState.update { it.copy(newsLoading = false) }; return }
        val now = System.currentTimeMillis()
        val minGap = settings.newsRefreshMinutes * 60_000L
        if (!force && now - lastNewsRefreshAt < minGap) return
        lastNewsRefreshAt = now
        _uiState.update { it.copy(newsLoading = true) }
        viewModelScope.launch {
            val assessment = runCatching { newsRepository.refresh() }.getOrElse { newsRepository.cached() }
            _uiState.update { it.copy(newsRisk = assessment, newsLoading = false) }
        }
    }

    fun refreshReviews() {
        viewModelScope.launch {
            val signals = runCatching { reviewRepository.signalStats() }.getOrDefault(com.locogo.astockguard.domain.review.SignalReviewStats())
            val tradeStats = runCatching { reviewRepository.tradeStats() }.getOrDefault(com.locogo.astockguard.domain.review.TradeReviewStats())
            val alertStats = runCatching { alertHistoryRepository.stats() }.getOrDefault(com.locogo.astockguard.domain.review.AlertHistoryStats())
            val current = _uiState.value
            val ledger = runCatching {
                reviewRepository.accountLedgerSummary(
                    positions = current.positions,
                    quotes = current.snapshot?.quotes.orEmpty(),
                    cashBalance = current.cashBalance
                )
            }.getOrDefault(com.locogo.astockguard.domain.review.AccountLedgerSummary())
            val code = _uiState.value.selectedCode
            val tradeRecords = runCatching { cacheDao.getTradeRecords().filter { code == null || it.code == code } }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    signalReviewStats = signals,
                    tradeReviewStats = tradeStats,
                    accountLedgerSummary = ledger,
                    alertHistoryStats = alertStats,
                    tradeRecords = tradeRecords
                )
            }
        }
    }

    /** 记录账户资金或收益流水，写入后立即重新计算真实收益基准。 */
    fun recordAccountLedger(type: AccountLedgerType, amount: Double, note: String = "") {
        val code = _uiState.value.selectedCode.orEmpty()
        viewModelScope.launch {
            runCatching { reviewRepository.recordAccountLedger(type, amount, code, note) }
                .onSuccess { refreshReviews() }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "账户流水记录失败") } }
        }
    }

    fun recordTrade(side: String, quantity: Int, price: Double) {
        val code = _uiState.value.selectedCode ?: return
        viewModelScope.launch {
            runCatching { reviewRepository.recordTrade(code, side, quantity, price) }
                .onSuccess { refreshReviews() }
                .onFailure { t -> _uiState.update { it.copy(error = t.message ?: "交易记录失败") } }
        }
    }

    fun refresh() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { marketRepository.refresh() }.onSuccess(::acceptSnapshot)
                .onFailure { t -> _uiState.update { it.copy(loading = false, error = t.message ?: "行情刷新失败") } }
        }
    }

    fun analyze(question: String) {
        val snapshot = _uiState.value.snapshot ?: run { _uiState.update { it.copy(error = "请先刷新行情") }; return }
        val prompt = PromptBuilder.build(
            snapshot = snapshot, positions = settings.positions(), question = question,
            stockFundFlow = _uiState.value.stockFundFlow, sectorFundFlow = _uiState.value.sectorFundFlow,
            newsRisk = _uiState.value.newsRisk, level2 = _uiState.value.level2
        )
        _uiState.update { it.copy(aiLoading = true, aiText = "AI分析中…", error = null) }
        if (settings.primaryType == "CHATGPT_WEB") { _effects.tryEmit(MainEffect.RunHiddenWebAi(prompt)); return }
        viewModelScope.launch {
            runCatching { aiClient.analyze(settings.primaryProvider(), settings.backupProvider(), prompt) }
                .onSuccess { onAiAnswer(prompt, it) }
                .onFailure { t -> _uiState.update { it.copy(aiLoading = false, aiText = "AI调用失败：${t.message}") } }
        }
    }

    fun onAiAnswer(prompt: String, answer: String) {
        val strategy = AiStrategyParser.parse(answer)
        _uiState.update { it.copy(aiLoading = false, aiText = AiStrategyParser.displayText(answer), aiStrategy = strategy, error = null) }
        viewModelScope.launch { runCatching { cacheDao.insertAiAnalysis(AiAnalysisEntity(
            createdAt = System.currentTimeMillis(), prompt = prompt, rawAnswer = answer,
            marketAction = strategy?.marketAction ?: "UNPARSED", confidence = strategy?.confidence ?: 0,
            targetPositionPct = strategy?.targetPositionPct ?: 0, strategyJson = strategy?.json.orEmpty()
        )) } }
    }

    fun onAiStatus(message: String) { _uiState.update { it.copy(aiLoading = true, aiText = message, error = null) } }
    fun onAiFailed(message: String) { _uiState.update { it.copy(aiLoading = false, aiText = "AI分析需要人工处理：$message") } }
    fun consumeError() { _uiState.update { it.copy(error = null) } }

    override fun onCleared() {
        replayJob?.cancel()
        exposureBacktestJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val BACKTEST_DAYS = 180
        private const val MAX_BACKTEST_POSITIONS = 12
    }

    class Factory(
        private val settings: SettingsRepository,
        private val marketRepository: MarketRepository,
        private val fundFlowRepository: FundFlowRepository,
        private val newsRepository: NewsRepository,
        private val level2Repository: Level2Repository,
        private val paperTradingRepository: PaperTradingRepository,
        private val replayEngine: ReplayEngine,
        private val r2Scanner: R2Scanner,
        private val reviewRepository: ReviewRepository,
        private val alertHistoryRepository: AlertHistoryRepository,
        private val aiClient: AiClient,
        private val cacheDao: CacheDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
            settings, marketRepository, fundFlowRepository, newsRepository, level2Repository,
            paperTradingRepository, replayEngine, r2Scanner, reviewRepository, alertHistoryRepository, aiClient, cacheDao
        ) as T
    }
}
