package com.locogo.astockguard

data class Position(
    val code: String,
    val name: String,
    val shares: Int,
    val cost: Double,
    val role: String
)

data class Quote(
    val code: String,
    val time: String,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val latest: Double?,
    val previousClose: Double?,
    val changeRatio: Double?
)

data class StockSignal(
    val code: String,
    val level: String,
    val action: String,
    val reason: String
)

data class MarketAssessment(
    val eventRisk: String,
    val marketPhase: String,
    val maxPositionRatio: Double,
    val avgChange: Double,
    val redRatio: Double,
    val severeDropRatio: Double,
    val advice: String,
    val signals: List<StockSignal>
)

data class MonitorSnapshot(
    val updatedAt: Long,
    val quotes: List<Quote>,
    val assessment: MarketAssessment,
    val positionRatio: Double
)

data class AiProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val extraHeadersJson: String
)
