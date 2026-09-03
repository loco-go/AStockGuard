package com.locogo.astockguard.data.fundflow

/*
 * 文件职责：定义个股分钟累计流、周期汇总和板块排行；订单规模分类只是数据商统计口径。
 * 架构边界：外部字段缺失、过期或异常时返回不可用，不能以默认零值伪造有效数据。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

data class FundFlowPoint(
    val time: String,
    val mainNet: Double,
    val smallNet: Double,
    val mediumNet: Double,
    val largeNet: Double,
    val superLargeNet: Double
)

data class FundFlowSummary(
    val days: Int,
    val mainNet: Double,
    val smallNet: Double,
    val mediumNet: Double,
    val largeNet: Double,
    val superLargeNet: Double
)

data class SectorFundFlow(
    val code: String,
    val name: String,
    val type: String,
    val changePct: Double,
    val mainNet: Double,
    val mainPct: Double,
    val superLargeNet: Double,
    val largeNet: Double,
    val mediumNet: Double,
    val smallNet: Double,
    val leadStockName: String = "",
    val leadStockCode: String = ""
)

data class StockFundFlow(
    val code: String,
    val minute: List<FundFlowPoint>,
    val periods: List<FundFlowSummary>,
    val source: String,
    val stale: Boolean,
    /** 分钟资金流是否来自缓存；日级刷新成功不能把旧分钟流误标为实时。 */
    val minuteStale: Boolean = stale,
    val dailyStale: Boolean = stale
)

data class SectorFundFlowResult(
    val type: String,
    val rows: List<SectorFundFlow>,
    val source: String,
    val stale: Boolean
)
