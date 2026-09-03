package com.locogo.astockguard.chart

/*
 * 文件职责：维护图表周期、加载状态和指标结果；切换周期时避免旧请求覆盖新选择。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
