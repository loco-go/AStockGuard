package com.locogo.astockguard.domain.review

import com.locogo.astockguard.data.local.AccountLedgerEntity

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

enum class AccountLedgerType(val value: String, val displayName: String) {
    OPENING_BALANCE("OPENING_BALANCE", "期初资产"),
    DEPOSIT("DEPOSIT", "资金转入"),
    WITHDRAWAL("WITHDRAWAL", "资金转出"),
    DIVIDEND("DIVIDEND", "分红"),
    INTEREST("INTEREST", "利息"),
    FEE("FEE", "费用"),
    TAX("TAX", "税费");

    companion object {
        fun from(value: String): AccountLedgerType? = entries.firstOrNull { it.value == value.uppercase() }
    }
}

data class AccountLedgerSummary(
    val entries: List<AccountLedgerEntity> = emptyList(),
    val totalAssets: Double? = null,
    val netInvestedCapital: Double? = null,
    val cumulativePnl: Double? = null,
    val unrealizedPnl: Double = 0.0,
    val investmentIncome: Double = 0.0,
    val explicitCosts: Double = 0.0,
    val hasOpeningBalance: Boolean = false
)
