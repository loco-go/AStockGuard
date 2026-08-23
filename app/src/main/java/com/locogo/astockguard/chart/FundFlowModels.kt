package com.locogo.astockguard.chart

data class FundFlowPoint(val time: String, val netInflow: Double)
data class FundFlowRank(val name: String, val netInflow: Double, val changeRatio: Double)
data class MarketFundFlow(
    val totalAmount: Double,
    val netInflow: Double,
    val timeline: List<FundFlowPoint>,
    val industries: List<FundFlowRank>,
    val concepts: List<FundFlowRank>
)
