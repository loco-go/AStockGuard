package com.locogo.astockguard.chart

import kotlin.math.abs

data class StrategyResult(
    val score: Int,
    val action: String,
    val buyZone: ClosedFloatingPointRange<Double>?,
    val sellZone: ClosedFloatingPointRange<Double>?,
    val reasons: List<String>
)

data class MacdPoint(val dif: Double, val dea: Double, val histogram: Double)

object StrategyEngine {
    fun evaluate(candles: List<StockKLine>): StrategyResult {
        if (candles.size < 26) return StrategyResult(50, "HOLD", null, null, listOf("Insufficient history"))
        val closes = candles.map { it.close }
        val ma5 = TechnicalIndicators.ma(closes, 5).lastOrNull()
        val ma10 = TechnicalIndicators.ma(closes, 10).lastOrNull()
        val ma20 = TechnicalIndicators.ma(closes, 20).lastOrNull()
        val macd = macd(closes).last()
        val rsi = rsi(closes, 14).last()
        val latest = candles.last()
        val vwap = candles.takeLast(20).let { bars ->
            val volume = bars.sumOf { it.volume.toDouble() }
            if (volume == 0.0) latest.close else bars.sumOf { it.close * it.volume } / volume
        }
        var score = 50
        val reasons = mutableListOf<String>()
        if (ma5 != null && ma10 != null && ma20 != null && ma5 > ma10 && ma10 > ma20) {
            score += 20; reasons += "MA bullish alignment"
        }
        if (macd.dif > macd.dea) { score += 15; reasons += "MACD bullish" } else score -= 10
        if (rsi in 35.0..65.0) { score += 10; reasons += "RSI healthy" }
        if (latest.close > vwap) { score += 10; reasons += "Price above VWAP" }
        val volumeRatio = TechnicalIndicators.volumeRatio(candles.map { it.volume.toDouble() })
        if (volumeRatio > 1.2) { score += 10; reasons += "Volume expansion" }
        score = score.coerceIn(0, 100)
        val action = when { score >= 75 -> "BUY"; score <= 35 -> "SELL"; else -> "HOLD" }
        val span = latest.close * 0.015
        return StrategyResult(score, action, (latest.close - span)..latest.close, (latest.close + span)..(latest.close + span * 2), reasons)
    }

    fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdPoint> {
        val fastEma = ema(values, fast); val slowEma = ema(values, slow)
        val dif = values.indices.map { fastEma[it] - slowEma[it] }
        val dea = ema(dif, signal)
        return dif.indices.map { MacdPoint(dif[it], dea[it], (dif[it] - dea[it]) * 2) }
    }

    fun rsi(values: List<Double>, period: Int = 14): List<Double> {
        if (values.isEmpty()) return emptyList()
        return values.indices.map { index ->
            if (index < period) 50.0 else {
                val changes = (index - period + 1..index).map { values[it] - values[it - 1] }
                val gains = changes.filter { it > 0 }.sum() / period
                val losses = abs(changes.filter { it < 0 }.sum()) / period
                if (losses == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gains / losses)
            }
        }
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val multiplier = 2.0 / (period + 1)
        val out = mutableListOf(values.first())
        for (i in 1 until values.size) out += (values[i] - out.last()) * multiplier + out.last()
        return out
    }
}
