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
    val name: String = "",
    val time: String = "",
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val latest: Double? = null,
    val previousClose: Double? = null,
    val changeRatio: Double? = null,
    val volume: Double? = null,
    val amount: Double? = null,
    val vwap: Double? = null,
    val ma5: Double? = null,
    val ma10: Double? = null,
    val r2Score: Int = 0,
    val r2Grade: String = "-",
    val r2Reason: String = ""
)

data class MinuteBar(
    val time: String,
    val price: Double,
    val avgPrice: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double
)

data class DailyBar(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double
)

data class R2Result(
    val score: Int,
    val grade: String,
    val reason: String
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

data class DataHealth(
    val source: String = "TENCENT",
    val isStale: Boolean = false,
    val message: String = ""
)

data class MonitorSnapshot(
    val updatedAt: Long,
    val quotes: List<Quote>,
    val marketIndices: List<Quote> = emptyList(),
    val assessment: MarketAssessment,
    val positionRatio: Double,
    val dataHealth: DataHealth = DataHealth()
)

data class AiProviderConfig(
    val type: String = "RESPONSES",
    val name: String = "primary",
    val baseUrl: String = "",
    val apiKey: String = "",
    val sessionToken: String = "",
    val cookie: String = "",
    val model: String = "",
    val extraHeadersJson: String = "{}"
)
