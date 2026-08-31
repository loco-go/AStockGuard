package com.locogo.astockguard.domain.strategy

data class StrategyScore(
    val trendScore: Int,
    val volumeScore: Int,
    val capitalScore: Int,
    val positionScore: Int,
    val totalScore: Int,
    val capitalAvailable: Boolean,
    val strategyVersion: String = StrategyVersions.CURRENT,
    val dataCompletenessPct: Int = if (capitalAvailable) 100 else 80,
    val factors: List<StrategyFactorScore> = emptyList()
)

data class StrategyFactorScore(
    val key: String,
    val name: String,
    val score: Int,
    val weightPct: Int,
    val available: Boolean,
    val evidence: String
)

object StrategyVersions {
    const val CURRENT = "V4.2.0"
    const val LEGACY = "LEGACY"
}

enum class ChartSignalAction { BUY, SELL, RISK }

data class ChartSignal(
    val timestamp: Long,
    val price: Double,
    val action: ChartSignalAction,
    val score: Int,
    val reason: String,
    val strategyVersion: String = StrategyVersions.CURRENT
)

data class StrategyScoreResult(
    val score: StrategyScore,
    val reasons: List<String>,
    val signal: ChartSignal?
)
