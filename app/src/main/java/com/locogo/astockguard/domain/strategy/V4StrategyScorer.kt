package com.locogo.astockguard.domain.strategy

import com.locogo.astockguard.chart.StockKLine
import com.locogo.astockguard.domain.indicator.TechnicalIndicatorSet
import kotlin.math.abs
import kotlin.math.roundToInt

object V4StrategyScorer {
    fun evaluate(
        candles: List<StockKLine>,
        mainNetFlow: Double?,
        grossFlow: Double?,
        capitalIsFresh: Boolean
    ): StrategyScoreResult? {
        if (candles.size < 60) return null
        val closes = candles.map { it.close }
        val latest = candles.last()
        val ma5 = TechnicalIndicatorSet.ma(closes, 5).last() ?: return null
        val ma10 = TechnicalIndicatorSet.ma(closes, 10).last() ?: return null
        val ma20 = TechnicalIndicatorSet.ma(closes, 20).last() ?: return null
        val ma60 = TechnicalIndicatorSet.ma(closes, 60).last() ?: return null
        val macd = TechnicalIndicatorSet.macd(closes).last()
        val rsi = TechnicalIndicatorSet.rsi(closes).last() ?: return null
        val kdj = TechnicalIndicatorSet.kdj(candles).last() ?: return null
        val vwap = TechnicalIndicatorSet.vwap(candles).last()

        var trend = 50
        if (ma5 > ma10) trend += 12 else trend -= 12
        if (ma10 > ma20) trend += 12 else trend -= 12
        if (ma20 > ma60) trend += 14 else trend -= 14
        if (macd.dif > macd.dea) trend += 12 else trend -= 12
        trend = trend.coerceIn(0, 100)

        val priorVolumes = candles.takeLast(20).dropLast(1).map { it.volume }
        val volumeRatio = if (priorVolumes.isEmpty() || priorVolumes.average() <= 0.0) 1.0 else latest.volume / priorVolumes.average()
        var volume = 50
        if (volumeRatio >= 1.5) volume += if (latest.close >= latest.open) 30 else -30
        else if (volumeRatio >= 1.1) volume += if (latest.close >= latest.open) 18 else -18
        if (vwap != null) volume += if (latest.close >= vwap) 12 else -12
        volume = volume.coerceIn(0, 100)

        val capitalAvailable = capitalIsFresh && mainNetFlow != null && grossFlow != null && grossFlow > 0.0
        val capital = if (capitalAvailable) {
            (50.0 + mainNetFlow!! / grossFlow!! * 50.0).roundToInt().coerceIn(0, 100)
        } else 0

        val range = candles.takeLast(60)
        val low = range.minOf { it.low }
        val high = range.maxOf { it.high }
        val percentile = if (high == low) 0.5 else (latest.close - low) / (high - low)
        var position = ((1.0 - abs(percentile - 0.45) / 0.55) * 100.0).roundToInt().coerceIn(0, 100)
        if (rsi > 75.0 || kdj.j > 100.0) position = (position - 25).coerceAtLeast(0)

        val weighted = trend * 0.35 + volume * 0.25 + position * 0.15 + if (capitalAvailable) capital * 0.25 else 0.0
        val weight = if (capitalAvailable) 1.0 else 0.75
        val total = (weighted / weight).roundToInt().coerceIn(0, 100)
        val score = StrategyScore(trend, volume, capital, position, total, capitalAvailable)
        val reasons = buildList {
            add("MA5/10/20/60 与 MACD 趋势评分 $trend")
            add("量比 ${"%.2f".format(volumeRatio)}，量能评分 $volume")
            add(if (capitalAvailable) "实时资金评分 $capital" else "资金数据缺失或已过期，不参与综合评分")
            add("60周期价格位置与 RSI/KDJ 评分 $position")
        }
        val signal = when {
            rsi >= 80.0 || kdj.j >= 115.0 -> ChartSignal(latest.timestamp, latest.high, ChartSignalAction.RISK, total, "RSI/KDJ 进入高风险区")
            !capitalAvailable -> null
            total >= 75 -> ChartSignal(latest.timestamp, latest.low, ChartSignalAction.BUY, total, reasons.joinToString("；"))
            total <= 35 -> ChartSignal(latest.timestamp, latest.high, ChartSignalAction.SELL, total, reasons.joinToString("；"))
            else -> null
        }
        return StrategyScoreResult(score, reasons, signal)
    }
}
