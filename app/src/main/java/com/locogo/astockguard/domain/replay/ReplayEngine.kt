package com.locogo.astockguard.domain.replay

import com.locogo.astockguard.MinuteBar
import kotlin.math.max

data class ReplayTrade(
    val index: Int,
    val time: String,
    val side: String,
    val price: Double,
    val quantity: Int,
    val reason: String,
    val realizedPnl: Double = 0.0
)

data class ReplayReport(
    val strategy: String = "VWAP_RECLAIM",
    val initialCash: Double = 100_000.0,
    val finalEquity: Double = 100_000.0,
    val returnPct: Double = 0.0,
    val maxDrawdownPct: Double = 0.0,
    val closedTrades: Int = 0,
    val wins: Int = 0,
    val winRatePct: Double = 0.0,
    val profitFactor: Double = 0.0,
    val trades: List<ReplayTrade> = emptyList(),
    val equityCurve: List<Pair<String, Double>> = emptyList()
)

class ReplayEngine {
    fun runVwapReclaim(
        bars: List<MinuteBar>,
        initialCash: Double = 100_000.0,
        lotSize: Int = 100,
        confirmBars: Int = 3
    ): ReplayReport {
        if (bars.size < confirmBars + 2) return ReplayReport(initialCash = initialCash, finalEquity = initialCash)
        var cash = initialCash
        var quantity = 0
        var avgCost = 0.0
        var above = 0
        var below = 0
        var peakEquity = initialCash
        var maxDrawdown = 0.0
        val trades = mutableListOf<ReplayTrade>()
        val curve = mutableListOf<Pair<String, Double>>()
        var wins = 0
        var closed = 0
        var grossProfit = 0.0
        var grossLoss = 0.0

        bars.forEachIndexed { index, bar ->
            if (bar.price > bar.avgPrice) { above++; below = 0 }
            else if (bar.price < bar.avgPrice) { below++; above = 0 }
            else { above = 0; below = 0 }

            if (quantity == 0 && above >= confirmBars) {
                val maxLots = (cash / (bar.price * lotSize)).toInt()
                if (maxLots > 0) {
                    quantity = lotSize
                    avgCost = bar.price
                    cash -= bar.price * quantity
                    trades += ReplayTrade(index, bar.time, "BUY", bar.price, quantity, "连续${confirmBars}分钟站上均价线")
                    above = 0
                }
            } else if (quantity > 0 && below >= confirmBars) {
                val pnl = (bar.price - avgCost) * quantity
                cash += bar.price * quantity
                trades += ReplayTrade(index, bar.time, "SELL", bar.price, quantity, "连续${confirmBars}分钟跌破均价线", pnl)
                closed++
                if (pnl > 0) { wins++; grossProfit += pnl } else if (pnl < 0) grossLoss += -pnl
                quantity = 0
                avgCost = 0.0
                below = 0
            }

            val equity = cash + quantity * bar.price
            peakEquity = max(peakEquity, equity)
            if (peakEquity > 0) maxDrawdown = max(maxDrawdown, (peakEquity - equity) / peakEquity * 100.0)
            curve += bar.time to equity
        }

        if (quantity > 0) {
            val last = bars.last()
            val pnl = (last.price - avgCost) * quantity
            cash += last.price * quantity
            trades += ReplayTrade(bars.lastIndex, last.time, "SELL", last.price, quantity, "回放结束强制平仓", pnl)
            closed++
            if (pnl > 0) { wins++; grossProfit += pnl } else if (pnl < 0) grossLoss += -pnl
            quantity = 0
            curve += last.time to cash
        }

        val returnPct = if (initialCash > 0) (cash / initialCash - 1.0) * 100.0 else 0.0
        return ReplayReport(
            strategy = "VWAP_RECLAIM",
            initialCash = initialCash,
            finalEquity = cash,
            returnPct = returnPct,
            maxDrawdownPct = maxDrawdown,
            closedTrades = closed,
            wins = wins,
            winRatePct = if (closed > 0) wins * 100.0 / closed else 0.0,
            profitFactor = when {
                grossLoss > 0 -> grossProfit / grossLoss
                grossProfit > 0 -> Double.POSITIVE_INFINITY
                else -> 0.0
            },
            trades = trades,
            equityCurve = curve
        )
    }
}
