package com.locogo.astockguard.domain.strategy

data class StrategyScore(
    val trendScore: Int,
    val volumeScore: Int,
    val capitalScore: Int,
    val positionScore: Int,
    val totalScore: Int,
    val capitalAvailable: Boolean
)

enum class ChartSignalAction { BUY, SELL, RISK }

data class ChartSignal(
    val timestamp: Long,
    val price: Double,
    val action: ChartSignalAction,
    val score: Int,
    val reason: String
)

data class StrategyScoreResult(
    val score: StrategyScore,
    val reasons: List<String>,
    val signal: ChartSignal?
)
