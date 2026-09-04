package com.locogo.astockguard.ui.stock

/*
 * 文件职责：组织单只证券的行情、图表、资金流和策略状态；异步刷新需保持所选证券一致，防止跨证券结果串写。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.Quote
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.chart.StockKLine
import com.locogo.astockguard.data.fundflow.StockFundFlow
import com.locogo.astockguard.data.repository.StrategySignalRepository
import com.locogo.astockguard.domain.strategy.ChartSignal
import com.locogo.astockguard.domain.strategy.IntradayChartSignal
import com.locogo.astockguard.domain.strategy.IntradayBacktestStats
import com.locogo.astockguard.domain.strategy.IntradaySignalEngine
import com.locogo.astockguard.domain.strategy.IntradaySignalBacktester
import com.locogo.astockguard.domain.strategy.StrategyScoreResult
import com.locogo.astockguard.domain.strategy.V4StrategyScorer
import com.locogo.astockguard.domain.trading.DynamicTPlan
import com.locogo.astockguard.domain.volume.IntradayVolumeAnalysis
import com.locogo.astockguard.domain.volume.IntradayVolumeCoordinator
import com.locogo.astockguard.ui.chart.ChartDataMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

data class StockDetailUiState(
    val code: String = "",
    val quote: Quote? = null,
    val period: ChartPeriod = ChartPeriod.DAY,
    val candles: List<StockKLine> = emptyList(),
    val dailySource: String = "UNKNOWN",
    val dailyFromCache: Boolean = true,
    val dailyRealtimeMerged: Boolean = false,
    val dailyUpdatedAt: Long = 0L,
    val minuteCandles: List<MinuteCandle> = emptyList(),
    val minuteSignals: List<IntradayChartSignal> = emptyList(),
    val minuteBacktest: IntradayBacktestStats = IntradayBacktestStats(),
    val minuteFundFlowAvailable: Boolean = false,
    /** 当前交易日的量能雷达结果；历史回看阶段暂不冒充实时雷达。 */
    val volumeAnalysis: IntradayVolumeAnalysis? = null,
    /** 已通过风险、持仓和全成本门禁的动态T计划。 */
    val dynamicTPlan: DynamicTPlan? = null,
    /** 雷达输入的数据源组合，用于向用户披露缺失或降级的数据能力。 */
    val radarDataSource: String = "NONE",
    val selectedMinuteDate: LocalDate = MarketRepository.marketDate(),
    val minuteDataFromCache: Boolean = false,
    val minuteDataIsHistorical: Boolean = false,
    val minuteLoading: Boolean = false,
    val strategy: StrategyScoreResult? = null,
    val signals: List<ChartSignal> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class StockDetailViewModel(
    private val marketRepository: MarketRepository,
    private val strategySignalRepository: StrategySignalRepository,
    private val settings: SettingsRepository,
    private val intradayVolumeCoordinator: IntradayVolumeCoordinator
) : ViewModel() {
    private val _state = MutableStateFlow(StockDetailUiState())
    val state: StateFlow<StockDetailUiState> = _state.asStateFlow()
    private var dailySource = emptyList<DailyBar>()
    private var fundFlow: StockFundFlow? = null
    private var minuteLoadJob: Job? = null
    private var radarLoadJob: Job? = null
    private var lastRadarRefreshAt: Long = 0L
    private var latestAssessment: MarketAssessment? = null
    private var latestExposureStatus: String? = null
    private var latestMarketDataStale: Boolean = true

    fun load(code: String) {
        if (code.isBlank() || (_state.value.code == code && dailySource.isNotEmpty())) return
        _state.value = StockDetailUiState(code = code, loading = true)
        viewModelScope.launch {
            val dailyRequest = async { runCatching { marketRepository.loadDailySeries(code, CHART_HISTORY_DAYS) } }
            val radarRequest = async {
                runCatching {
                    intradayVolumeCoordinator.load(
                        code = code,
                        position = settings.positions().firstOrNull { it.code == code },
                        assessment = latestAssessment,
                        exposureStatus = latestExposureStatus,
                        marketDataStale = latestMarketDataStale
                    )
                }
            }
            val dailyResult = dailyRequest.await()
            val radarResult = radarRequest.await()
            val radar = radarResult.getOrNull()
            val minuteResult = radarResult.map { it.minuteSeries }
            fundFlow = radar?.fundFlow
            val dailySeries = dailyResult.getOrNull()
            dailySource = dailySeries?.bars.orEmpty()
            var generatedSignal: ChartSignal? = null
            _state.update { current ->
                val minuteSeries = minuteResult.getOrNull()
                val minuteCandles = ChartDataMapper.aggregateMinutes(
                    minuteSeries?.bars.orEmpty(),
                    sourceIntervalMinutes = minuteSeries?.intervalMinutes ?: 1
                )
                val candles = ChartDataMapper.aggregate(dailySource, current.period)
                val strategy = evaluate(candles, current.period)
                val minuteEvaluation = buildMinuteEvaluation(minuteCandles, minuteSeries)
                generatedSignal = strategy?.signal
                current.copy(
                    candles = candles,
                    dailySource = dailySeries?.source ?: "UNKNOWN",
                    dailyFromCache = dailySeries?.fromCache ?: true,
                    dailyRealtimeMerged = dailySeries?.realtimeMerged == true,
                    dailyUpdatedAt = dailySeries?.updatedAt ?: 0L,
                    minuteCandles = minuteCandles,
                    minuteSignals = minuteEvaluation.first,
                    minuteBacktest = minuteEvaluation.second,
                    minuteFundFlowAvailable = minuteFlowFor(minuteSeries).isNotEmpty(),
                    volumeAnalysis = radar?.analysis,
                    dynamicTPlan = radar?.plan,
                    radarDataSource = radar?.dataSource ?: "NONE",
                    selectedMinuteDate = minuteSeries?.date ?: current.selectedMinuteDate,
                    minuteDataFromCache = minuteSeries?.fromCache == true,
                    minuteDataIsHistorical = minuteSeries?.isHistorical == true,
                    strategy = strategy,
                    signals = strategy?.signal?.let(::listOf).orEmpty(),
                    loading = false,
                    error = listOfNotNull(
                        dailyResult.exceptionOrNull()?.message,
                        minuteResult.exceptionOrNull()?.message
                    ).distinct().joinToString("；").ifBlank { null }
                )
            }
            generatedSignal?.let { strategySignalRepository.persist(code, it) }
        }
    }

    fun updateQuote(quote: Quote?) {
        if (quote?.code != _state.value.code || quote == _state.value.quote) return
        // 盘中实时快照变化时同步重建当天日K，避免详情页一直停留在首次历史请求的昨日蜡烛。
        val before = dailySource
        dailySource = MarketRepository.mergeRealtimeDailyBar(dailySource, quote)
        val realtimeMerged = before != dailySource
        _state.update { current ->
            if (current.period == ChartPeriod.MINUTE) return@update current.copy(quote = quote)
            val candles = ChartDataMapper.aggregate(dailySource, current.period)
            val strategy = evaluate(candles, current.period)
            current.copy(
                quote = quote,
                candles = candles,
                dailySource = if (realtimeMerged && !current.dailySource.endsWith("+REALTIME")) {
                    "${current.dailySource}+REALTIME"
                } else current.dailySource,
                dailyRealtimeMerged = current.dailyRealtimeMerged || realtimeMerged,
                strategy = strategy,
                signals = strategy?.signal?.let(::listOf).orEmpty()
            )
        }
        refreshRadarIfDue()
    }

    /**
     * 接收Dashboard已经获取的市场风险上下文，并触发节流后的雷达刷新。
     * Fragment只转交ViewModel状态，不直接请求网络，保持UI层单向数据流约束。
     */
    fun updateMarketContext(
        quote: Quote?,
        assessment: MarketAssessment?,
        exposureStatus: String?,
        marketDataStale: Boolean
    ) {
        latestAssessment = assessment
        latestExposureStatus = exposureStatus
        latestMarketDataStale = marketDataStale
        updateQuote(quote)
        refreshRadarIfDue()
    }

    /**
     * 最多每15秒重新组合一次分钟行情、资金流和Level2，防止每个报价刷新都触发多路网络请求。
     * 旧请求完成前不会启动并发请求；证券切换时通过code复核阻止跨证券结果写入。
     */
    private fun refreshRadarIfDue() {
        val code = _state.value.code
        val now = System.currentTimeMillis()
        if (code.isBlank() || _state.value.selectedMinuteDate != MarketRepository.marketDate()) return
        if (radarLoadJob?.isActive == true || now - lastRadarRefreshAt < RADAR_REFRESH_INTERVAL_MS) return
        lastRadarRefreshAt = now
        radarLoadJob = viewModelScope.launch {
            val result = runCatching {
                intradayVolumeCoordinator.load(
                    code = code,
                    position = settings.positions().firstOrNull { it.code == code },
                    assessment = latestAssessment,
                    exposureStatus = latestExposureStatus,
                    marketDataStale = latestMarketDataStale,
                    now = System.currentTimeMillis()
                )
            }.getOrNull() ?: return@launch
            if (_state.value.code != code || _state.value.selectedMinuteDate != MarketRepository.marketDate()) return@launch
            fundFlow = result.fundFlow
            val minuteCandles = ChartDataMapper.aggregateMinutes(
                result.minuteSeries.bars,
                sourceIntervalMinutes = result.minuteSeries.intervalMinutes
            )
            val minuteEvaluation = buildMinuteEvaluation(minuteCandles, result.minuteSeries)
            _state.update {
                it.copy(
                    minuteCandles = minuteCandles,
                    minuteSignals = minuteEvaluation.first,
                    minuteBacktest = minuteEvaluation.second,
                    minuteFundFlowAvailable = minuteFlowFor(result.minuteSeries).isNotEmpty(),
                    minuteDataFromCache = result.minuteSeries.fromCache,
                    minuteDataIsHistorical = result.minuteSeries.isHistorical,
                    volumeAnalysis = result.analysis,
                    dynamicTPlan = result.plan,
                    radarDataSource = result.dataSource
                )
            }
        }
    }

    fun selectPeriod(period: ChartPeriod) {
        if (_state.value.period == period) return
        _state.update {
            val candles = ChartDataMapper.aggregate(dailySource, period)
            val strategy = evaluate(candles, period)
            it.copy(
                period = period,
                candles = candles,
                strategy = strategy,
                signals = strategy?.signal?.let(::listOf).orEmpty()
            )
        }
    }

    fun selectMinuteDate(date: LocalDate) {
        if (date.isAfter(MarketRepository.marketDate())) return
        val code = _state.value.code
        if (code.isBlank()) return
        if (_state.value.selectedMinuteDate == date && _state.value.minuteCandles.isNotEmpty()) {
            selectPeriod(ChartPeriod.MINUTE)
            return
        }
        minuteLoadJob?.cancel()
        _state.update {
            it.copy(
                period = ChartPeriod.MINUTE,
                selectedMinuteDate = date,
                minuteCandles = emptyList(),
                minuteSignals = emptyList(),
                minuteBacktest = IntradayBacktestStats(),
                minuteFundFlowAvailable = false,
                volumeAnalysis = null,
                dynamicTPlan = null,
                radarDataSource = "HISTORICAL_VIEW",
                minuteLoading = true,
                error = null
            )
        }
        minuteLoadJob = viewModelScope.launch {
            val result = runCatching { marketRepository.loadMinuteSeries(code, date) }
            if (_state.value.code != code || _state.value.selectedMinuteDate != date) return@launch
            val series = result.getOrNull()
            val minuteCandles = ChartDataMapper.aggregateMinutes(
                series?.bars.orEmpty(),
                sourceIntervalMinutes = series?.intervalMinutes ?: 1
            )
            val minuteEvaluation = buildMinuteEvaluation(minuteCandles, series)
            _state.update {
                it.copy(
                    minuteCandles = minuteCandles,
                    minuteSignals = minuteEvaluation.first,
                    minuteBacktest = minuteEvaluation.second,
                    minuteFundFlowAvailable = minuteFlowFor(series).isNotEmpty(),
                    minuteDataFromCache = series?.fromCache == true,
                    minuteDataIsHistorical = series?.isHistorical == true,
                    minuteLoading = false,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun shiftMinuteDate(days: Long) = selectMinuteDate(_state.value.selectedMinuteDate.plusDays(days))

    private fun buildMinuteEvaluation(
        candles: List<MinuteCandle>,
        series: MarketRepository.MinuteSeries?
    ): Pair<List<IntradayChartSignal>, IntradayBacktestStats> {
        if (series == null) return emptyList<IntradayChartSignal>() to IntradayBacktestStats()
        val signals = IntradaySignalEngine.evaluate(
            candles = candles,
            dataIsFresh = true,
            fundFlow = minuteFlowFor(series)
        )
        val backtest = IntradaySignalBacktester.run(candles, signals)
        val displayed = if (!series.fromCache && !series.isHistorical) signals else signals.map {
            it.copy(recommended = true, reason = "缓存回看：${it.reason}")
        }
        return displayed to backtest
    }

    private fun minuteFlowFor(series: MarketRepository.MinuteSeries?): List<com.locogo.astockguard.data.fundflow.FundFlowPoint> {
        if (series == null || series.isHistorical || series.date != MarketRepository.marketDate()) return emptyList()
        val flow = fundFlow ?: return emptyList()
        return if (flow.minuteStale) emptyList() else flow.minute
    }

    private fun evaluate(candles: List<StockKLine>, period: ChartPeriod): StrategyScoreResult? {
        val currentFlow = fundFlow
        val capitalHorizonDays = when (period) {
            ChartPeriod.MINUTE -> 1
            ChartPeriod.DAY -> 5
            ChartPeriod.WEEK, ChartPeriod.MONTH -> 10
        }
        val summary = currentFlow?.periods?.firstOrNull { it.days == capitalHorizonDays }
        val gross = summary?.let { abs(it.smallNet) + abs(it.mediumNet) + abs(it.largeNet) + abs(it.superLargeNet) }
        return V4StrategyScorer.evaluate(
            candles = candles,
            mainNetFlow = summary?.mainNet,
            grossFlow = gross,
            capitalIsFresh = currentFlow != null && !currentFlow.stale,
            capitalHorizonDays = capitalHorizonDays
        )
    }

    class Factory(
        private val marketRepository: MarketRepository,
        private val strategySignalRepository: StrategySignalRepository,
        private val settings: SettingsRepository,
        private val intradayVolumeCoordinator: IntradayVolumeCoordinator
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StockDetailViewModel(marketRepository, strategySignalRepository, settings, intradayVolumeCoordinator) as T
    }

    private companion object {
        const val CHART_HISTORY_DAYS = 1_600
        const val RADAR_REFRESH_INTERVAL_MS = 15_000L
    }
}
