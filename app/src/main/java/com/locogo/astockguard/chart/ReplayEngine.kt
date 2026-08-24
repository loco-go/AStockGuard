package com.locogo.astockguard.chart

import kotlin.math.max

data class ReplayStats(
    val returnRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val trades: Int
)

object ReplayEngine {
    fun run(candles: List<StockKLine>, warmup: Int = 30): ReplayStats {
        if (candles.size <= warmup) return ReplayStats(0.0, 0.0, 0.0, 0)
        var cash = 1.0
        var entry: Double? = null
        var peak = 1.0
        var maxDrawdown = 0.0
        var wins = 0
        var trades = 0
        candles.indices.drop(warmup).forEach { i ->
            val result = StrategyEngine.evaluate(candles.subList(0, i + 1))
            val price = candles[i].close
            if (entry == null && result.action == "BUY") entry = price
            else if (entry != null && result.action == "SELL") {
                val ratio = price / entry!!
                cash *= ratio
                if (ratio > 1.0) wins++
                trades++
                entry = null
            }
            val equity = if (entry == null) cash else cash * price / entry!!
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak)
        }
        if (entry != null) {
            val ratio = candles.last().close / entry!!
            cash *= ratio
            if (ratio > 1.0) wins++
            trades++
        }
        return ReplayStats(cash - 1.0, maxDrawdown, if (trades == 0) 0.0 else wins.toDouble() / trades, trades)
    }
}
