package com.locogo.astockguard.domain.replay

import com.locogo.astockguard.MinuteBar
import kotlin.math.max

/** 做T回放规则。默认值采用A股常见费率，所有参数可替换，避免把假设伪装成真实收益。 */
data class ReplayTradingRules(
    val initialCash: Double = 100_000.0,
    val initialPosition: Int = 1_000,
    val tradeQuantity: Int = 100,
    val lotSize: Int = 100,
    val commissionRate: Double = 0.0003,
    val minimumCommission: Double = 5.0,
    val stampTaxRate: Double = 0.0005,
    val slippageBps: Double = 2.0
)

data class ReplayTrade(
    val index: Int,
    val time: String,
    val side: String,
    val price: Double,
    val quantity: Int,
    val reason: String,
    val realizedPnl: Double = 0.0,
    val fee: Double = 0.0,
    val tax: Double = 0.0,
    val referencePrice: Double = price
)

data class ReplayReport(
    val strategy: String = "VWAP_RECLAIM_T1_V2",
    val initialCash: Double = 100_000.0,
    val initialEquity: Double = 100_000.0,
    val finalEquity: Double = 100_000.0,
    val returnPct: Double = 0.0,
    val benchmarkReturnPct: Double = 0.0,
    val excessPnl: Double = 0.0,
    val maxDrawdownPct: Double = 0.0,
    val closedTrades: Int = 0,
    val wins: Int = 0,
    val winRatePct: Double = 0.0,
    val profitFactor: Double = 0.0,
    val totalFees: Double = 0.0,
    val totalTax: Double = 0.0,
    val slippageCost: Double = 0.0,
    val unclosedQuantity: Int = 0,
    val rejectedTrades: Int = 0,
    val rulesDescription: String = "",
    val trades: List<ReplayTrade> = emptyList(),
    val equityCurve: List<Pair<String, Double>> = emptyList()
)

class ReplayEngine {
    fun runVwapReclaim(
        bars: List<MinuteBar>,
        rules: ReplayTradingRules = ReplayTradingRules(),
        confirmBars: Int = 3
    ): ReplayReport {
        val safeRules = rules.normalized()
        if (bars.isEmpty()) return emptyReport(safeRules)
        val firstPrice = bars.first().price
        val initialEquity = safeRules.initialCash + safeRules.initialPosition * firstPrice
        if (bars.size < confirmBars + 2) {
            val finalEquity = safeRules.initialCash + safeRules.initialPosition * bars.last().price
            return emptyReport(safeRules, initialEquity).copy(
                finalEquity = finalEquity,
                returnPct = portfolioReturn(finalEquity, initialEquity),
                benchmarkReturnPct = portfolioReturn(finalEquity, initialEquity)
            )
        }

        var cash = safeRules.initialCash
        var totalQuantity = safeRules.initialPosition
        // A股T+1：只有日初底仓可在当天卖出，当日新买数量不能加入可卖数量。
        var sellableQuantity = safeRules.initialPosition
        var openBuy: OpenBuy? = null
        var above = 0
        var below = 0
        var peakEquity = initialEquity
        var maxDrawdown = 0.0
        var wins = 0
        var closed = 0
        var grossProfit = 0.0
        var grossLoss = 0.0
        var totalFees = 0.0
        var totalTax = 0.0
        var slippageCost = 0.0
        var rejectedTrades = 0
        val trades = mutableListOf<ReplayTrade>()
        val curve = mutableListOf<Pair<String, Double>>()

        bars.forEachIndexed { index, bar ->
            when {
                bar.price > bar.avgPrice -> { above++; below = 0 }
                bar.price < bar.avgPrice -> { below++; above = 0 }
                else -> { above = 0; below = 0 }
            }

            if (openBuy == null && above >= confirmBars) {
                val quantity = safeRules.tradeQuantity
                val fillPrice = buyFillPrice(bar.price, safeRules.slippageBps)
                val amount = fillPrice * quantity
                val fee = commission(amount, safeRules)
                if (cash >= amount + fee) {
                    cash -= amount + fee
                    totalQuantity += quantity
                    totalFees += fee
                    slippageCost += (fillPrice - bar.price) * quantity
                    openBuy = OpenBuy(fillPrice, quantity, fee)
                    trades += ReplayTrade(index, bar.time, "BUY", fillPrice, quantity,
                        "连续${confirmBars}根分时线站上均价线", fee = fee, referencePrice = bar.price)
                } else rejectedTrades++
                above = 0
            } else if (openBuy != null && below >= confirmBars) {
                val buy = openBuy!!
                val quantity = buy.quantity
                if (sellableQuantity >= quantity) {
                    val fillPrice = sellFillPrice(bar.price, safeRules.slippageBps)
                    val amount = fillPrice * quantity
                    val fee = commission(amount, safeRules)
                    val tax = amount * safeRules.stampTaxRate
                    val pnl = amount - fee - tax - (buy.price * quantity + buy.fee)
                    cash += amount - fee - tax
                    totalQuantity -= quantity
                    sellableQuantity -= quantity
                    totalFees += fee
                    totalTax += tax
                    slippageCost += (bar.price - fillPrice) * quantity
                    trades += ReplayTrade(index, bar.time, "SELL", fillPrice, quantity,
                        "连续${confirmBars}根分时线跌破均价线（卖出日初底仓）", pnl, fee, tax, bar.price)
                    closed++
                    if (pnl > 0) { wins++; grossProfit += pnl } else if (pnl < 0) grossLoss += -pnl
                    openBuy = null
                } else rejectedTrades++
                below = 0
            }

            val equity = cash + totalQuantity * bar.price
            peakEquity = max(peakEquity, equity)
            if (peakEquity > 0) maxDrawdown = max(maxDrawdown, (peakEquity - equity) / peakEquity * 100.0)
            curve += bar.time to equity
        }

        // 不在收盘强平当日买入仓；未配对仓位只按收盘价计入权益并单独披露。
        val lastPrice = bars.last().price
        val finalEquity = cash + totalQuantity * lastPrice
        val benchmarkEquity = safeRules.initialCash + safeRules.initialPosition * lastPrice
        return ReplayReport(
            initialCash = safeRules.initialCash,
            initialEquity = initialEquity,
            finalEquity = finalEquity,
            returnPct = portfolioReturn(finalEquity, initialEquity),
            benchmarkReturnPct = portfolioReturn(benchmarkEquity, initialEquity),
            excessPnl = finalEquity - benchmarkEquity,
            maxDrawdownPct = maxDrawdown,
            closedTrades = closed,
            wins = wins,
            winRatePct = if (closed > 0) wins * 100.0 / closed else 0.0,
            profitFactor = when {
                grossLoss > 0 -> grossProfit / grossLoss
                grossProfit > 0 -> Double.POSITIVE_INFINITY
                else -> 0.0
            },
            totalFees = totalFees,
            totalTax = totalTax,
            slippageCost = slippageCost,
            unclosedQuantity = openBuy?.quantity ?: 0,
            rejectedTrades = rejectedTrades,
            rulesDescription = rulesDescription(safeRules),
            trades = trades,
            equityCurve = curve
        )
    }

    private fun emptyReport(rules: ReplayTradingRules, initialEquity: Double = rules.initialCash) = ReplayReport(
        initialCash = rules.initialCash,
        initialEquity = initialEquity,
        finalEquity = initialEquity,
        rulesDescription = rulesDescription(rules)
    )

    private fun ReplayTradingRules.normalized(): ReplayTradingRules {
        val validLot = lotSize.coerceAtLeast(100)
        return copy(
            initialCash = initialCash.coerceAtLeast(0.0),
            initialPosition = (initialPosition.coerceAtLeast(0) / validLot) * validLot,
            tradeQuantity = (tradeQuantity.coerceAtLeast(validLot) / validLot) * validLot,
            lotSize = validLot,
            commissionRate = commissionRate.coerceAtLeast(0.0),
            minimumCommission = minimumCommission.coerceAtLeast(0.0),
            stampTaxRate = stampTaxRate.coerceAtLeast(0.0),
            slippageBps = slippageBps.coerceAtLeast(0.0)
        )
    }

    private fun commission(amount: Double, rules: ReplayTradingRules) =
        max(amount * rules.commissionRate, rules.minimumCommission)
    private fun buyFillPrice(price: Double, slippageBps: Double) = price * (1.0 + slippageBps / 10_000.0)
    private fun sellFillPrice(price: Double, slippageBps: Double) = price * (1.0 - slippageBps / 10_000.0)
    private fun portfolioReturn(equity: Double, initialEquity: Double) =
        if (initialEquity > 0) (equity / initialEquity - 1.0) * 100.0 else 0.0
    private fun rulesDescription(rules: ReplayTradingRules) =
        "底仓${rules.initialPosition}股 / 单次${rules.tradeQuantity}股 / 佣金${rules.commissionRate * 10_000}bp（最低${rules.minimumCommission}元）" +
            " / 卖出印花税${rules.stampTaxRate * 1_000}‰ / 双边滑点${rules.slippageBps}bp / A股T+1"

    private data class OpenBuy(val price: Double, val quantity: Int, val fee: Double)
}
