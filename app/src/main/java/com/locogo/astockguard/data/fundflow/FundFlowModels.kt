package com.locogo.astockguard.data.fundflow

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
    val stale: Boolean
)

data class SectorFundFlowResult(
    val type: String,
    val rows: List<SectorFundFlow>,
    val source: String,
    val stale: Boolean
)
