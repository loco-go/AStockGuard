package com.locogo.astockguard.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChartUiState(
    val period: ChartPeriod = ChartPeriod.DAY,
    val candles: List<StockKLine> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

class ChartViewModel(
    private val repository: ChartRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ChartUiState())
    val state: StateFlow<ChartUiState> = _state

    fun load(code: String, period: ChartPeriod) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                period = period,
                loading = true,
                error = null
            )

            runCatching {
                repository.getChart(code, period)
            }.onSuccess {
                _state.value = _state.value.copy(
                    candles = it,
                    loading = false
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = it.message
                )
            }
        }
    }
}
