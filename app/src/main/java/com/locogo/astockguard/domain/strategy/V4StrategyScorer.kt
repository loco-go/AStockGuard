package com.locogo.astockguard.domain.strategy

/*
 * 文件职责：把技术、趋势与质量证据拆解为版本化分数和动作；规则修改需通过固定样本回归。
 * 架构边界：领域层不直接访问 Android View；需要网络或 Room 的输入由 Repository 在调用前准备。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

import com.locogo.astockguard.chart.StockKLine
import com.locogo.astockguard.domain.indicator.TechnicalIndicatorSet
import kotlin.math.max
import kotlin.math.roundToInt

/** 可解释的多因子排序模型；评分不代表预测收益或胜率。 */
object V4StrategyScorer {
    fun evaluate(
        candles: List<StockKLine>,
        mainNetFlow: Double?,
        grossFlow: Double?,
        capitalIsFresh: Boolean,
        capitalHorizonDays: Int = 1
    ): StrategyScoreResult? {
        if (candles.size < MIN_CANDLES) return null
        val closes = candles.map { it.close }
        val latest = candles.last()
        if (latest.close <= 0.0) return null
        val ma5 = TechnicalIndicatorSet.ma(closes, 5).last() ?: return null
        val ma20Series = TechnicalIndicatorSet.ma(closes, 20)
        val ma20 = ma20Series.last() ?: return null
        val ma60 = TechnicalIndicatorSet.ma(closes, 60).last() ?: return null
        val ma20Past = ma20Series.getOrNull(candles.lastIndex - 5) ?: ma20
        val macd = TechnicalIndicatorSet.macd(closes).last()
        val rsi = TechnicalIndicatorSet.rsi(closes).last() ?: return null
        val vwap = TechnicalIndicatorSet.vwap(candles).last()
        val atrSeries = TechnicalIndicatorSet.atr(candles)
        val atr = atrSeries.last() ?: return null
        val atrPercent = atr / latest.close
        val historicalAtrPercent = (maxOf(0, atrSeries.size - 60) until atrSeries.size).mapNotNull { index ->
            val close = candles[index].close
            atrSeries[index]?.takeIf { close > 0.0 }?.let { it / close }
        }.sorted()
        val medianAtrPercent = historicalAtrPercent.getOrNull(historicalAtrPercent.size / 2)
            ?.takeIf { it > 0.0 } ?: atrPercent
        val volatilityRatio = atrPercent / medianAtrPercent
        val momentum20 = returnOver(closes, 20)
        val momentum60 = returnOver(closes, 60)

        var trend = 50
        trend += if (latest.close > ma20) 12 else -12
        trend += if (ma20 > ma60) 14 else -14
        trend += if (ma5 > ma20) 10 else -10
        trend += if (macd.histogram > 0.0) 8 else -8
        trend += if (ma20 > ma20Past) 6 else -6
        trend = trend.coerceIn(0, 100)

        val priorVolumes = candles.takeLast(21).dropLast(1).map { it.volume }
        val averageVolume = priorVolumes.average().takeIf { it > 0.0 } ?: latest.volume.coerceAtLeast(1.0)
        val volumeRatio = latest.volume / averageVolume
        val volumeWindow = candles.takeLast(10)
        val volumeSum = volumeWindow.sumOf { it.volume }.takeIf { it > 0.0 } ?: 1.0
        val signedVolumeBalance = volumeWindow.sumOf {
            when {
                it.close > it.open -> it.volume
                it.close < it.open -> -it.volume
                else -> 0.0
            }
        } / volumeSum
        var volume = 50
        val latestDirection = if (latest.close >= latest.open) 1 else -1
        if (volumeRatio >= 1.5) volume += 18 * latestDirection
        else if (volumeRatio >= 1.1) volume += 10 * latestDirection
        volume += (signedVolumeBalance * 20.0).roundToInt()
        if (vwap != null) volume += if (latest.close >= vwap) 10 else -10
        volume = volume.coerceIn(0, 100)

        val capitalAvailable = capitalIsFresh && mainNetFlow != null && grossFlow != null && grossFlow > 0.0
        val capital = if (capitalAvailable) {
            (50.0 + mainNetFlow!! / grossFlow!! * 50.0).roundToInt().coerceIn(0, 100)
        } else 0

        val recent = candles.takeLast(20)
        val support = recent.minOf { it.low }
        val resistance = recent.maxOf { it.high }
        val downside = max(latest.close - support, atr * 1.5)
        val rewardRisk = max(resistance - latest.close, 0.0) / downside.takeIf { it > 0.0 }!!
        var position = 50
        position += when {
            rsi in 45.0..68.0 -> 18
            rsi in 35.0..75.0 -> 6
            rsi >= 80.0 || rsi <= 25.0 -> -22
            else -> -8
        }
        position += when {
            volatilityRatio <= 1.15 -> 12
            volatilityRatio <= 1.5 -> 2
            volatilityRatio >= 2.0 -> -20
            else -> -8
        }
        position += when {
            rewardRisk >= 2.0 -> 15
            rewardRisk >= 1.2 -> 6
            rewardRisk < 0.6 -> -15
            else -> -5
        }
        position += if (momentum20 > 0.0 && momentum60 > 0.0) 8 else if (momentum20 < 0.0 && momentum60 < 0.0) -8 else 0
        position = position.coerceIn(0, 100)

        val weighted = trend * 0.35 + volume * 0.20 + position * 0.25 + if (capitalAvailable) capital * 0.20 else 0.0
        val total = (weighted / if (capitalAvailable) 1.0 else 0.80).roundToInt().coerceIn(0, 100)
        // V4.2仅增加版本、权重和证据元数据，评分公式与V4.1保持一致。
        val factors = listOf(
            StrategyFactorScore("TREND", "趋势", trend, 35, true, "收盘/均线、MACD与均线斜率"),
            StrategyFactorScore("VOLUME", "量能", volume, 20, true, "量比、方向量与VWAP"),
            StrategyFactorScore("CAPITAL", "资金", capital, 20, capitalAvailable, if (capitalAvailable) "${capitalHorizonDays}日主力净流" else "资金数据缺失或过期"),
            StrategyFactorScore("POSITION", "风险收益", position, 25, true, "RSI、ATR、动量与潜在盈亏比")
        )
        val score = StrategyScore(
            trend, volume, capital, position, total, capitalAvailable,
            strategyVersion = StrategyVersions.CURRENT,
            dataCompletenessPct = if (capitalAvailable) 100 else 80,
            factors = factors
        )
        val reasons = listOf(
            "多因子趋势-波动模型 ${StrategyVersions.CURRENT}：趋势 $trend（收盘/MA20、MA20/MA60、MACD、均线斜率）",
            "20/60周期动量 ${pct(momentum20)} / ${pct(momentum60)}，RSI ${"%.1f".format(rsi)}",
            "量比 ${"%.2f".format(volumeRatio)}，10周期方向量 ${pct(signedVolumeBalance)}，量能 $volume",
            "ATR ${pct(atrPercent)}，相对常态波动 ${"%.2f".format(volatilityRatio)} 倍，潜在盈亏比 ${"%.2f".format(rewardRisk)}",
            if (capitalAvailable) "${capitalHorizonDays}日新鲜主力净流评分 $capital" else "资金数据缺失或过期：仅给技术评分，不产生实时买卖信号"
        )
        val bullishGate = latest.close > ma20 && ma20 > ma60 && momentum20 > 0.0 && macd.histogram > 0.0
        val bearishGate = latest.close < ma20 && ma20 < ma60 && momentum20 < 0.0 && macd.histogram < 0.0
        val signal = when {
            rsi >= 82.0 || (volatilityRatio >= 2.0 && latest.close < ma20) ->
                ChartSignal(latest.timestamp, latest.high, ChartSignalAction.RISK, total, "超买或波动率异常放大")
            !capitalAvailable -> null
            total >= 72 && trend >= 65 && volume >= 48 && position >= 50 && bullishGate ->
                ChartSignal(latest.timestamp, latest.low, ChartSignalAction.BUY, total, reasons.joinToString("；"))
            total <= 35 && trend <= 35 && bearishGate ->
                ChartSignal(latest.timestamp, latest.high, ChartSignalAction.SELL, total, reasons.joinToString("；"))
            else -> null
        }
        return StrategyScoreResult(score, reasons, signal)
    }

    private fun returnOver(values: List<Double>, period: Int): Double {
        val base = values[(values.lastIndex - period).coerceAtLeast(0)]
        return if (base == 0.0) 0.0 else values.last() / base - 1.0
    }

    private fun pct(value: Double): String = "%+.2f%%".format(value * 100.0)

    private const val MIN_CANDLES = 60
}
