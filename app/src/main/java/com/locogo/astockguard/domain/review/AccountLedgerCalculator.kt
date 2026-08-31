package com.locogo.astockguard.domain.review

import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import com.locogo.astockguard.data.local.AccountLedgerEntity

/** 根据外部资金流水和当前资产计算真实收益；分红与利息属于收益，不计入投入本金。 */
object AccountLedgerCalculator {
    fun calculate(
        entries: List<AccountLedgerEntity>,
        positions: List<Position>,
        quotes: List<Quote>,
        cashBalance: Double?
    ): AccountLedgerSummary {
        val quoteByCode = quotes.associateBy { it.code }
        val marketValue = positions.sumOf { position ->
            (quoteByCode[position.code]?.latest ?: position.cost) * position.shares
        }
        val totalAssets = cashBalance?.let { it + marketValue }
        val opening = entries.filterType(AccountLedgerType.OPENING_BALANCE).sumOf { it.amount }
        val deposits = entries.filterType(AccountLedgerType.DEPOSIT).sumOf { it.amount }
        val withdrawals = entries.filterType(AccountLedgerType.WITHDRAWAL).sumOf { it.amount }
        val hasOpening = entries.any { it.type == AccountLedgerType.OPENING_BALANCE.value }
        val netCapital = if (hasOpening) opening + deposits - withdrawals else null
        val unrealized = positions.sumOf { position ->
            ((quoteByCode[position.code]?.latest ?: position.cost) - position.cost) * position.shares
        }
        val income = entries.filter {
            it.type == AccountLedgerType.DIVIDEND.value || it.type == AccountLedgerType.INTEREST.value
        }.sumOf { it.amount }
        val costs = entries.filter {
            it.type == AccountLedgerType.FEE.value || it.type == AccountLedgerType.TAX.value
        }.sumOf { it.amount }
        return AccountLedgerSummary(
            entries = entries.sortedWith(compareByDescending<AccountLedgerEntity> { it.occurredAt }.thenByDescending { it.id }),
            totalAssets = totalAssets,
            netInvestedCapital = netCapital,
            cumulativePnl = if (totalAssets != null && netCapital != null) totalAssets - netCapital else null,
            unrealizedPnl = unrealized,
            investmentIncome = income,
            explicitCosts = costs,
            hasOpeningBalance = hasOpening
        )
    }

    private fun List<AccountLedgerEntity>.filterType(type: AccountLedgerType) = filter { it.type == type.value }
}
