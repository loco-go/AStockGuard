package com.locogo.astockguard.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Quote
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.StockKLine
import com.locogo.astockguard.data.fundflow.FundFlowRepository
import com.locogo.astockguard.data.fundflow.StockFundFlow
import com.locogo.astockguard.domain.strategy.ChartSignal
import com.locogo.astockguard.domain.strategy.StrategyScoreResult
import com.locogo.astockguard.domain.strategy.V4StrategyScorer
import com.locogo.astockguard.ui.chart.ChartDataMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class StockDetailUiState(
    val code: String = "",
    val quote: Quote? = null,
    val period: ChartPeriod = ChartPeriod.DAY,
    val candles: List<StockKLine> = emptyList(),
    val minutes: List<MinuteBar> = emptyList(),
    val strategy: StrategyScoreResult? = null,
    val signals: List<ChartSignal> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class StockDetailViewModel(
    private val marketRepository: MarketRepository,
    private val fundFlowRepository: FundFlowRepository
) : ViewModel() {
    private val _state = MutableStateFlow(StockDetailUiState())
    val state: StateFlow<StockDetailUiState> = _state.asStateFlow()
    private var dailySource = emptyList<DailyBar>()
    private var fundFlow: StockFundFlow? = null

    fun load(code: String) {
        if (code.isBlank() || (_state.value.code == code && dailySource.isNotEmpty())) return
        _state.value = StockDetailUiState(code = code, loading = true)
        viewModelScope.launch {
            val dailyRequest = async { runCatching { marketRepository.loadDailyBars(code, 120) } }
            val minuteRequest = async { runCatching { marketRepository.loadMinuteBars(code) } }
            val flowRequest = async { runCatching { fundFlowRepository.stock(code) } }
            val dailyResult = dailyRequest.await()
            val minuteResult = minuteRequest.await()
            fundFlow = flowRequest.await().getOrNull()
            dailySource = dailyResult.getOrDefault(emptyList())
            _state.update { current ->
                val candles = ChartDataMapper.aggregate(dailySource, current.period)
                val strategy = evaluate(candles)
                current.copy(
                    candles = candles,
                    minutes = minuteResult.getOrDefault(emptyList()),
                    strategy = strategy,
                    signals = strategy?.signal?.let(::listOf).orEmpty(),
                    loading = false,
                    error = listOfNotNull(
                        dailyResult.exceptionOrNull()?.message,
                        minuteResult.exceptionOrNull()?.message
                    ).distinct().joinToString("；").ifBlank { null }
                )
            }
        }
    }

    fun updateQuote(quote: Quote?) {
        if (quote?.code == _state.value.code && quote != _state.value.quote) _state.update { it.copy(quote = quote) }
    }

    fun selectPeriod(period: ChartPeriod) {
        if (_state.value.period == period) return
        _state.update {
            val candles = ChartDataMapper.aggregate(dailySource, period)
            val strategy = evaluate(candles)
            it.copy(
                period = period,
                candles = candles,
                strategy = strategy,
                signals = strategy?.signal?.let(::listOf).orEmpty()
            )
        }
    }

    private fun evaluate(candles: List<StockKLine>): StrategyScoreResult? {
        val currentFlow = fundFlow
        val summary = currentFlow?.periods?.firstOrNull { it.days == 1 }
        val gross = summary?.let { abs(it.smallNet) + abs(it.mediumNet) + abs(it.largeNet) + abs(it.superLargeNet) }
        return V4StrategyScorer.evaluate(
            candles = candles,
            mainNetFlow = summary?.mainNet,
            grossFlow = gross,
            capitalIsFresh = currentFlow != null && !currentFlow.stale
        )
    }

    class Factory(
        private val marketRepository: MarketRepository,
        private val fundFlowRepository: FundFlowRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StockDetailViewModel(marketRepository, fundFlowRepository) as T
    }
}
