package com.locogo.astockguard.chart

/**
 * Unified OHLCV model used by day/week/month charts.
 */
data class StockKLine(
    val timestamp: Long,
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class ChartUiState(
    val period: ChartPeriod = ChartPeriod.DAY,
    val candles: List<StockKLine> = emptyList(),
    val selectedDate: String? = null,
    val loading: Boolean = false,
    val error: String? = null
)
