package com.locogo.astockguard.domain.review

data class SignalReviewItem(
    val code: String,
    val eventAt: Long,
    val action: String,
    val signalPrice: Double,
    val futurePrice: Double?,
    val edgePct: Double?,
    val success: Boolean?,
    val reason: String
)

data class SignalReviewStats(
    val total: Int = 0,
    val evaluated: Int = 0,
    val wins: Int = 0,
    val winRate: Double = 0.0,
    val averageEdgePct: Double = 0.0,
    val recent: List<SignalReviewItem> = emptyList()
)

data class TradeReviewStats(
    val trades: Int = 0,
    val closedTrades: Int = 0,
    val wins: Int = 0,
    val winRate: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val totalFees: Double = 0.0
)
