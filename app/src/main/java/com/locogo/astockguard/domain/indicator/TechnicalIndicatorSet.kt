package com.locogo.astockguard.domain.indicator

import com.locogo.astockguard.chart.StockKLine
import kotlin.math.abs

data class MacdValue(val dif: Double, val dea: Double, val histogram: Double)
data class KdjValue(val k: Double, val d: Double, val j: Double)

object TechnicalIndicatorSet {
    fun ma(values: List<Double>, period: Int): List<Double?> {
        require(period > 0) { "period must be positive" }
        var sum = 0.0
        return values.mapIndexed { index, value ->
            sum += value
            if (index >= period) sum -= values[index - period]
            if (index + 1 >= period) sum / period else null
        }
    }

    fun vwap(candles: List<StockKLine>, period: Int = 20): List<Double?> {
        require(period > 0) { "period must be positive" }
        return candles.indices.map { index ->
            if (index + 1 < period) null else {
                val window = candles.subList(index + 1 - period, index + 1)
                val volume = window.sumOf { it.volume }
                if (volume <= 0.0) null else window.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / volume
            }
        }
    }

    fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdValue> {
        if (values.isEmpty()) return emptyList()
        val fastEma = ema(values, fast)
        val slowEma = ema(values, slow)
        val dif = values.indices.map { fastEma[it] - slowEma[it] }
        val dea = ema(dif, signal)
        return dif.indices.map { MacdValue(dif[it], dea[it], (dif[it] - dea[it]) * 2.0) }
    }

    fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
        require(period > 0) { "period must be positive" }
        return values.indices.map { index ->
            if (index < period) null else {
                val changes = (index - period + 1..index).map { values[it] - values[it - 1] }
                val gains = changes.sumOf { if (it > 0.0) it else 0.0 } / period
                val losses = changes.sumOf { if (it < 0.0) abs(it) else 0.0 } / period
                if (losses == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gains / losses)
            }
        }
    }

    fun kdj(candles: List<StockKLine>, period: Int = 9): List<KdjValue?> {
        require(period > 0) { "period must be positive" }
        var previousK = 50.0
        var previousD = 50.0
        return candles.indices.map { index ->
            if (index + 1 < period) null else {
                val window = candles.subList(index + 1 - period, index + 1)
                val low = window.minOf { it.low }
                val high = window.maxOf { it.high }
                val rsv = if (high == low) 50.0 else (candles[index].close - low) / (high - low) * 100.0
                val k = previousK * 2.0 / 3.0 + rsv / 3.0
                val d = previousD * 2.0 / 3.0 + k / 3.0
                previousK = k
                previousD = d
                KdjValue(k, d, 3.0 * k - 2.0 * d)
            }
        }
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        require(period > 0) { "period must be positive" }
        if (values.isEmpty()) return emptyList()
        val multiplier = 2.0 / (period + 1.0)
        val result = ArrayList<Double>(values.size)
        result += values.first()
        for (index in 1 until values.size) result += (values[index] - result.last()) * multiplier + result.last()
        return result
    }
}
