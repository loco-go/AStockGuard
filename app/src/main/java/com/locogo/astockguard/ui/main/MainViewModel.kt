package com.locogo.astockguard.ui.main

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
import com.locogo.astockguard.domain.review.ReviewRepository
import com.locogo.astockguard.domain.signal.R2Scanner
import com.locogo.astockguard.domain.trading.TTradePlanner
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
    private val aiClient: AiClient,
    private val cacheDao: CacheDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<MainEffect> = _effects.asSharedFlow()
    private var lastNewsRefreshAt = 0L
    private var replayJob: Job? = null

    fun acceptSnapshot(snapshot: com.locogo.astockguard.MonitorSnapshot) {
        val selected = _uiState.value.selectedCode ?: snapshot.quotes.firstOrNull()?.code
        _uiState.update { it.copy(snapshot = snapshot, loading = false, selectedCode = selected, error = null) }
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
                replayIndex = if (changed) -1 else it.replayIndex,
                replayReport = if (changed) null else it.replayReport
            )
        }
        viewModelScope.launch {
            val daily = runCatching { marketRepository.loadDailyBars(code, 30) }.getOrDefault(emptyList())
            val minute = runCatching { marketRepository.loadMinuteBars(code) }.getOrDefault(emptyList())
            val flow = runCatching { fundFlowRepository.stock(code) }.getOrNull()
            val referencePrice = _uiState.value.snapshot?.quotes?.firstOrNull { it.code == code }?.latest
            val level2 = runCatching { level2Repository.snapshot(code, referencePrice) }.getOrNull()
            val trades = runCatching { cacheDao.getTradeRecords().filter { it.code == code } }.getOrDefault(emptyList())
            if (_uiState.value.selectedCode == code) {
                _uiState.update {
                    it.copy(
                        dailyBars = daily,
                        minuteBars = minute,
                        stockFundFlow = flow,
                        fundFlowLoading = false,
                        level2 = level2,
                        level2Loading = false,
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
            manualAnchorPrice = state.manualBuyAnchor
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
            if (_uiState.value.selectedCode == code) _uiState.update { it.copy(level2 = level2 ?: it.level2, level2Loading = false) }
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
        _uiState.update { it.copy(replayIndex = first, replayReport = null) }
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

    fun runReplayBacktest() {
        val bars = _uiState.value.minuteBars
        if (bars.isEmpty()) { _uiState.update { it.copy(error = "没有分钟数据可做策略回放") }; return }
        val report = replayEngine.runVwapReclaim(bars)
        _uiState.update { it.copy(replayReport = report, replayIndex = bars.lastIndex) }
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
            val code = _uiState.value.selectedCode
            val tradeRecords = runCatching { cacheDao.getTradeRecords().filter { code == null || it.code == code } }.getOrDefault(emptyList())
            _uiState.update { it.copy(signalReviewStats = signals, tradeReviewStats = tradeStats, tradeRecords = tradeRecords) }
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

    override fun onCleared() { replayJob?.cancel(); super.onCleared() }

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
        private val aiClient: AiClient,
        private val cacheDao: CacheDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
            settings, marketRepository, fundFlowRepository, newsRepository, level2Repository,
            paperTradingRepository, replayEngine, r2Scanner, reviewRepository, aiClient, cacheDao
        ) as T
    }
}
