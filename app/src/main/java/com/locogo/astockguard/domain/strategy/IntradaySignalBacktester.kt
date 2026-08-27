package com.locogo.astockguard.domain.strategy

import com.locogo.astockguard.chart.MinuteCandle

data class IntradayBacktestStats(
    val signals: Int = 0,
    val evaluated: Int = 0,
    val wins: Int = 0,
    val winRatePct: Double = 0.0,
    val averageEdgePct: Double = 0.0,
    val targetPct: Double = 0.5,
    val stopPct: Double = 0.35,
    val horizonBars: Int = 6
)

/** 对已确认提醒进行事件回测；推荐价位仅供参考，不纳入胜率统计。 */
object IntradaySignalBacktester {
    fun run(candles: List<MinuteCandle>, signals: List<IntradayChartSignal>): IntradayBacktestStats {
        val actionable = signals.filterNot { it.recommended }
        val outcomes = actionable.mapNotNull { signal ->
            val signalIndex = candles.indexOfFirst { it.time == signal.time }
            if (signalIndex < 0 || signalIndex >= candles.lastIndex) return@mapNotNull null
            val future = candles.drop(signalIndex + 1).take(HORIZON_BARS)
            if (future.isEmpty() || signal.price <= 0.0) return@mapNotNull null
            evaluate(signal, future)
        }
        val wins = outcomes.count { it.first }
        return IntradayBacktestStats(
            signals = actionable.size,
            evaluated = outcomes.size,
            wins = wins,
            winRatePct = if (outcomes.isEmpty()) 0.0 else wins * 100.0 / outcomes.size,
            averageEdgePct = if (outcomes.isEmpty()) 0.0 else outcomes.map { it.second }.average()
        )
    }

    private fun evaluate(signal: IntradayChartSignal, future: List<MinuteCandle>): Pair<Boolean, Double> {
        val direction = if (signal.action == ChartSignalAction.BUY) 1.0 else -1.0
        var success: Boolean? = null
        for (bar in future) {
            val favorable = if (direction > 0) bar.high / signal.price - 1.0 else signal.price / bar.low - 1.0
            val adverse = if (direction > 0) signal.price / bar.low - 1.0 else bar.high / signal.price - 1.0
            // 同一根K线同时触及止盈和止损时按失败处理，避免高估策略表现。
            if (adverse >= STOP_RATIO) { success = false; break }
            if (favorable >= TARGET_RATIO) { success = true; break }
        }
        val edge = direction * (future.last().close / signal.price - 1.0) * 100.0 - ROUND_TRIP_COST_PCT
        return (success ?: (edge > 0.0)) to edge
    }

    private const val HORIZON_BARS = 6
    private const val TARGET_RATIO = 0.005
    private const val STOP_RATIO = 0.0035
    private const val ROUND_TRIP_COST_PCT = 0.10
}
