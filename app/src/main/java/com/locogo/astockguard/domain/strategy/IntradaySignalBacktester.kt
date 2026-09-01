package com.locogo.astockguard.domain.strategy

import com.locogo.astockguard.chart.MinuteCandle
import kotlin.math.max

data class IntradayBacktestRules(
    val quantity: Int = 1_000,
    val commissionRate: Double = 0.0003,
    val minimumCommission: Double = 5.0,
    val stampTaxRate: Double = 0.0005,
    val slippageBps: Double = 2.0
)

data class IntradayBacktestStats(
    val signals: Int = 0,
    val evaluated: Int = 0,
    val wins: Int = 0,
    val winRatePct: Double = 0.0,
    val averageEdgePct: Double = 0.0,
    val totalCosts: Double = 0.0,
    val targetPct: Double = 0.5,
    val stopPct: Double = 0.35,
    val horizonBars: Int = 6,
    val rulesDescription: String = "按1000股，含佣金、印花税和双边滑点"
)

/** 对已确认提醒进行事件回测；推荐价位仅作参考，不纳入胜率统计。 */
object IntradaySignalBacktester {
    fun run(
        candles: List<MinuteCandle>,
        signals: List<IntradayChartSignal>,
        rules: IntradayBacktestRules = IntradayBacktestRules()
    ): IntradayBacktestStats {
        val actionable = signals.filterNot { it.recommended }
        val outcomes = actionable.mapNotNull { signal ->
            val signalIndex = candles.indexOfFirst { it.time == signal.time }
            if (signalIndex < 0 || signalIndex >= candles.lastIndex) return@mapNotNull null
            val future = candles.drop(signalIndex + 1).take(HORIZON_BARS)
            if (future.isEmpty() || signal.price <= 0.0) return@mapNotNull null
            evaluate(signal, future, rules)
        }
        val wins = outcomes.count { it.win }
        return IntradayBacktestStats(
            signals = actionable.size,
            evaluated = outcomes.size,
            wins = wins,
            winRatePct = if (outcomes.isEmpty()) 0.0 else wins * 100.0 / outcomes.size,
            averageEdgePct = if (outcomes.isEmpty()) 0.0 else outcomes.map { it.edgePct }.average(),
            totalCosts = outcomes.sumOf { it.cost },
            rulesDescription = "按${rules.quantity}股，含佣金、印花税和双边${rules.slippageBps}bp滑点"
        )
    }

    private fun evaluate(
        signal: IntradayChartSignal,
        future: List<MinuteCandle>,
        rules: IntradayBacktestRules
    ): Outcome {
        val buyFirst = signal.action == ChartSignalAction.BUY
        var exitReference = future.last().close
        var pathSuccess: Boolean? = null
        for (bar in future) {
            val favorable = if (buyFirst) bar.high / signal.price - 1.0 else signal.price / bar.low - 1.0
            val adverse = if (buyFirst) signal.price / bar.low - 1.0 else bar.high / signal.price - 1.0
            // 同一根K线同时触及止盈和止损时先判止损，避免使用未知的盘中先后顺序高估结果。
            if (adverse >= STOP_RATIO) {
                exitReference = if (buyFirst) signal.price * (1.0 - STOP_RATIO) else signal.price * (1.0 + STOP_RATIO)
                pathSuccess = false
                break
            }
            if (favorable >= TARGET_RATIO) {
                exitReference = if (buyFirst) signal.price * (1.0 + TARGET_RATIO) else signal.price * (1.0 - TARGET_RATIO)
                pathSuccess = true
                break
            }
        }

        val quantity = rules.quantity.coerceAtLeast(100)
        val slip = rules.slippageBps.coerceAtLeast(0.0) / 10_000.0
        val buyReference = if (buyFirst) signal.price else exitReference
        val sellReference = if (buyFirst) exitReference else signal.price
        val buyPrice = buyReference * (1.0 + slip)
        val sellPrice = sellReference * (1.0 - slip)
        val buyAmount = buyPrice * quantity
        val sellAmount = sellPrice * quantity
        val buyFee = max(buyAmount * rules.commissionRate.coerceAtLeast(0.0), rules.minimumCommission.coerceAtLeast(0.0))
        val sellFee = max(sellAmount * rules.commissionRate.coerceAtLeast(0.0), rules.minimumCommission.coerceAtLeast(0.0))
        val tax = sellAmount * rules.stampTaxRate.coerceAtLeast(0.0)
        val pnl = sellAmount - buyAmount - buyFee - sellFee - tax
        val notional = signal.price * quantity
        val edgePct = if (notional > 0) pnl / notional * 100.0 else 0.0
        val costs = buyFee + sellFee + tax + (buyPrice - buyReference + sellReference - sellPrice) * quantity
        return Outcome(win = pathSuccess == true && pnl > 0.0, edgePct = edgePct, cost = costs)
    }

    private data class Outcome(val win: Boolean, val edgePct: Double, val cost: Double)

    private const val HORIZON_BARS = 6
    private const val TARGET_RATIO = 0.005
    private const val STOP_RATIO = 0.0035
}
