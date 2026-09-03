package com.locogo.astockguard.chart

/*
 * 文件职责：定义图表蜡烛、指标和展示所需模型；坐标单位与时间顺序应在进入 View 前统一。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
