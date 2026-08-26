package com.locogo.astockguard.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Quote
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.StockKLine
import com.locogo.astockguard.ui.chart.ChartDataMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StockDetailUiState(
    val code: String = "",
    val quote: Quote? = null,
    val period: ChartPeriod = ChartPeriod.DAY,
    val candles: List<StockKLine> = emptyList(),
    val minutes: List<MinuteBar> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class StockDetailViewModel(
    private val marketRepository: MarketRepository
) : ViewModel() {
    private val _state = MutableStateFlow(StockDetailUiState())
    val state: StateFlow<StockDetailUiState> = _state.asStateFlow()
    private var dailySource = emptyList<com.locogo.astockguard.DailyBar>()

    fun load(code: String) {
        if (code.isBlank() || (_state.value.code == code && dailySource.isNotEmpty())) return
        _state.value = StockDetailUiState(code = code, loading = true)
        viewModelScope.launch {
            val dailyRequest = async { runCatching { marketRepository.loadDailyBars(code, 120) } }
            val minuteRequest = async { runCatching { marketRepository.loadMinuteBars(code) } }
            val dailyResult = dailyRequest.await()
            val minuteResult = minuteRequest.await()
            dailySource = dailyResult.getOrDefault(emptyList())
            _state.update { current ->
                current.copy(
                    candles = ChartDataMapper.aggregate(dailySource, current.period),
                    minutes = minuteResult.getOrDefault(emptyList()),
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
            it.copy(period = period, candles = ChartDataMapper.aggregate(dailySource, period))
        }
    }

    class Factory(private val marketRepository: MarketRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StockDetailViewModel(marketRepository) as T
    }
}
