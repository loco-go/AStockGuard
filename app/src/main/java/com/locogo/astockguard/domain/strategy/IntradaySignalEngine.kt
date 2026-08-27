package com.locogo.astockguard.domain.strategy

import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.data.fundflow.FundFlowPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class IntradayChartSignal(
    val time: String,
    val price: Double,
    val action: ChartSignalAction,
    val score: Int,
    val reason: String,
    val recommended: Boolean = false
)

/** 基于均线、VWAP、量能和资金流确认的确定性图表提示，不直接执行交易。 */
object IntradaySignalEngine {
    fun evaluate(
        candles: List<MinuteCandle>,
        dataIsFresh: Boolean,
        fundFlow: List<FundFlowPoint> = emptyList()
    ): List<IntradayChartSignal> {
        if (!dataIsFresh || candles.size < 2) return emptyList()
        val result = mutableListOf<IntradayChartSignal>()
        val flowDeltas = alignFlowDeltas(candles, fundFlow)
        var lastSignalIndex = -COOLDOWN_BARS

        for (index in SLOW_WINDOW until (candles.size - CONFIRM_BARS + 1).coerceAtLeast(SLOW_WINDOW)) {
            if (index - lastSignalIndex < COOLDOWN_BARS) continue
            val previous = candles[index - 1]
            val previousFast = averageClose(candles, index - 1, FAST_WINDOW)
            val previousSlow = averageClose(candles, index - 1, SLOW_WINDOW)
            val fast = averageClose(candles, index, FAST_WINDOW)
            val slow = averageClose(candles, index, SLOW_WINDOW)
            val confirmation = candles.subList(index, index + CONFIRM_BARS)
            val confirmedIndex = index + CONFIRM_BARS - 1
            val confirmed = candles[confirmedIndex]
            val volumeBase = candles.subList(max(0, index - VOLUME_WINDOW), index)
                .map { it.volume }.filter { it > 0.0 }.averageOrZero()
            val volumeRatio = if (volumeBase > 0.0) confirmed.volume / volumeBase else 1.0
            val flowDelta = flowDeltas.getOrNull(confirmedIndex)
            val previousFlowDelta = flowDeltas.getOrNull(confirmedIndex - 1)
            val hasFlow = flowDelta != null
            val buyFlowConfirmed = flowDelta?.let { it > 0.0 && (previousFlowDelta == null || it >= previousFlowDelta * 0.65) }
            val sellFlowConfirmed = flowDelta?.let { it < 0.0 || (previousFlowDelta != null && it < previousFlowDelta * 0.35) }
            val volumeConfirmed = volumeRatio >= MIN_VOLUME_RATIO
            val buy = previousFast <= previousSlow && fast > slow && previous.close <= previous.average &&
                confirmation.all { it.close >= it.average } && (buyFlowConfirmed == true || (!hasFlow && volumeConfirmed))
            val sell = previousFast >= previousSlow && fast < slow && previous.close >= previous.average &&
                confirmation.all { it.close <= it.average } && (sellFlowConfirmed == true || (!hasFlow && volumeConfirmed))
            if (!buy && !sell) continue

            val action = if (buy) ChartSignalAction.BUY else ChartSignalAction.SELL
            val direction = if (buy) 1.0 else -1.0
            val separation = if (confirmed.average == 0.0) 0.0 else direction * (fast - slow) / confirmed.average
            val vwapDistance = if (confirmed.average == 0.0) 0.0 else direction * (confirmed.close - confirmed.average) / confirmed.average
            val flowStrength = normalizedFlowStrength(flowDelta, flowDeltas, confirmedIndex) * direction
            val score = (58 + separation.coerceIn(0.0, 0.02) * 650 +
                vwapDistance.coerceIn(0.0, 0.02) * 400 +
                (volumeRatio - 1.0).coerceIn(0.0, 1.5) * 8 +
                flowStrength.coerceIn(0.0, 2.0) * 6).roundToInt().coerceIn(60, 92)
            result += IntradayChartSignal(
                time = confirmed.time,
                price = confirmed.close,
                action = action,
                score = score,
                reason = buildString {
                    append(if (buy) "短均线上穿并站稳VWAP" else "短均线下穿并跌破VWAP")
                    append("；量比%.2f".format(volumeRatio))
                    if (flowDelta != null) append("；主力净流增量%s%.0f".format(if (flowDelta >= 0) "+" else "", flowDelta))
                    else append("；无同日分钟资金流，以量能确认")
                }
            )
            lastSignalIndex = confirmedIndex
        }
        result += recommendedLevels(candles, flowDeltas)
        return result
    }

    private fun recommendedLevels(candles: List<MinuteCandle>, flowDeltas: List<Double?>): List<IntradayChartSignal> {
        val lastIndex = candles.lastIndex
        val anchorIndices = generateSequence(lastIndex) { previous ->
            (previous - RECOMMENDATION_SPACING).takeIf { it >= 0 }
        }.take(MAX_RECOMMENDATION_ZONES).toList().reversed()
        return anchorIndices.flatMap { anchorIndex -> recommendedLevelAt(candles, flowDeltas, anchorIndex) }
    }

    private fun recommendedLevelAt(
        candles: List<MinuteCandle>,
        flowDeltas: List<Double?>,
        anchorIndex: Int
    ): List<IntradayChartSignal> {
        val recent = candles.subList(max(0, anchorIndex - RECOMMENDATION_WINDOW + 1), anchorIndex + 1)
        val latest = recent.last()
        val average = latest.average.takeIf { it > 0.0 } ?: latest.close
        val averageRange = recent.map { (it.high - it.low).coerceAtLeast(0.0) }.average()
        val riskUnit = max(averageRange, latest.close * MIN_RECOMMENDATION_DISTANCE)
        var buyPrice = max(recent.minOf { it.low }, average - riskUnit)
        var sellPrice = min(recent.maxOf { it.high }, average + riskUnit)
        if (sellPrice <= buyPrice) {
            buyPrice = latest.close - riskUnit
            sellPrice = latest.close + riskUnit
        }
        val volumeBase = recent.dropLast(1).map { it.volume }.filter { it > 0.0 }.averageOrZero()
        val volumeRatio = if (volumeBase > 0.0) latest.volume / volumeBase else 1.0
        val anchorFlow = flowDeltas.getOrNull(anchorIndex)
        val flowStrength = normalizedFlowStrength(anchorFlow, flowDeltas, anchorIndex)
        val buyScore = (66 + ((average - latest.close) / latest.close * 400).roundToInt() +
            (flowStrength.coerceIn(0.0, 2.0) * 6).roundToInt() + ((volumeRatio - 1.0).coerceIn(0.0, 1.0) * 4).roundToInt()).coerceIn(60, 88)
        val sellScore = (66 + ((latest.close - average) / latest.close * 400).roundToInt() +
            ((-flowStrength).coerceIn(0.0, 2.0) * 6).roundToInt() + ((volumeRatio - 1.0).coerceIn(0.0, 1.0) * 4).roundToInt()).coerceIn(60, 88)
        val context = anchorFlow?.let { "主力净流增量%s%.0f、量比%.2f".format(if (it >= 0) "+" else "", it, volumeRatio) }
            ?: "无同日分钟资金流、量比%.2f".format(volumeRatio)
        return listOf(
            IntradayChartSignal(
                time = latest.time,
                price = buyPrice,
                action = ChartSignalAction.BUY,
                score = buyScore,
                reason = "支撑/VWAP承接位；$context",
                recommended = true
            ),
            IntradayChartSignal(
                time = latest.time,
                price = sellPrice,
                action = ChartSignalAction.SELL,
                score = sellScore,
                reason = "压力/VWAP止盈位；$context",
                recommended = true
            )
        )
    }

    private fun averageClose(candles: List<MinuteCandle>, end: Int, size: Int): Double =
        candles.subList(end - size + 1, end + 1).map { it.close }.average()

    private fun alignFlowDeltas(candles: List<MinuteCandle>, flow: List<FundFlowPoint>): List<Double?> {
        if (flow.size < 2) return List(candles.size) { null }
        val timed = flow.mapNotNull { point -> timeMinutes(point.time)?.let { it to point.mainNet } }.sortedBy { it.first }
        if (timed.size < 2) return List(candles.size) { null }
        var previousNet: Double? = null
        return candles.map { candle ->
            val start = timeMinutes(candle.time) ?: return@map null
            val end = start + 4
            val net = timed.lastOrNull { it.first in start..end }?.second ?: return@map null
            val delta = previousNet?.let { net - it }
            previousNet = net
            delta
        }
    }

    private fun timeMinutes(value: String): Int? {
        val match = TIME_REGEX.find(value) ?: return null
        return match.groupValues[1].toIntOrNull()?.times(60)?.plus(match.groupValues[2].toIntOrNull() ?: return null)
    }

    private fun normalizedFlowStrength(delta: Double?, all: List<Double?>, index: Int): Double {
        if (delta == null) return 0.0
        val base = all.subList(max(0, index - FLOW_WINDOW), index.coerceAtLeast(0))
            .mapNotNull { it }.map(::abs).sorted().let { values -> values.getOrNull(values.size / 2) }
        return if (base == null || base <= 0.0) 0.0 else delta / base
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private const val FAST_WINDOW = 3
    private const val SLOW_WINDOW = 6
    private const val CONFIRM_BARS = 2
    private const val COOLDOWN_BARS = 6
    private const val RECOMMENDATION_WINDOW = 12
    private const val RECOMMENDATION_SPACING = 10
    private const val MAX_RECOMMENDATION_ZONES = 5
    private const val MIN_RECOMMENDATION_DISTANCE = 0.003
    private const val VOLUME_WINDOW = 10
    private const val FLOW_WINDOW = 12
    private const val MIN_VOLUME_RATIO = 1.15
    private val TIME_REGEX = Regex("(\\d{2}):(\\d{2})")
}
