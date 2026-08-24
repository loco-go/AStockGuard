package com.locogo.astockguard.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChartViewModel(
    private val repository: ChartRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ChartUiState())
    val state: StateFlow<ChartUiState> = _state

    fun load(code: String, period: ChartPeriod = _state.value.period) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                period = period,
                loading = true,
                error = null
            )

            runCatching {
                repository.getChart(code, period)
            }.onSuccess { candles ->
                _state.value = _state.value.copy(
                    candles = candles,
                    selectedDate = _state.value.selectedDate?.takeIf { selected -> candles.any { it.date == selected } },
                    loading = false
                )
            }.onFailure { throwable ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = throwable.message ?: "Chart load failed"
                )
            }
        }
    }

    fun changePeriod(code: String, period: ChartPeriod) {
        if (period == _state.value.period && _state.value.candles.isNotEmpty()) return
        load(code, period)
    }

    fun selectDate(date: String?) {
        _state.value = _state.value.copy(selectedDate = date)
    }
}
