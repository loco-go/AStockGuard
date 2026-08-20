package com.locogo.astockguard

import kotlin.math.abs

object RiskEngine {
    fun assess(quotes: List<Quote>, positions: List<Position>, currentPositionRatioPercent: Double): MarketAssessment {
        val changes = quotes.mapNotNull { it.changeRatio }
        if (changes.isEmpty()) {
            return MarketAssessment(
                eventRisk = "E?",
                marketPhase = "M?",
                maxPositionRatio = 0.60,
                avgChange = 0.0,
                redRatio = 0.0,
                severeDropRatio = 0.0,
                advice = "缺少昨收或实时行情，暂不自动升仓。",
                signals = emptyList()
            )
        }

        val avg = changes.average()
        val redRatio = changes.count { it < 0 }.toDouble() / changes.size
        val severeRatio = changes.count { it <= -7.0 }.toDouble() / changes.size

        val eventRisk = when {
            avg <= -4.0 || severeRatio >= 0.40 -> "E2"
            avg <= -2.0 || redRatio >= 0.70 -> "E1"
            else -> "E0"
        }

        val marketPhase = when {
            eventRisk == "E2" -> "M1"
            avg >= 2.0 && redRatio <= 0.35 -> "M3"
            avg > 0.0 && redRatio < 0.50 -> "M2"
            avg <= -1.0 -> "M1"
            else -> "M2"
        }

        val maxPosition = when {
            eventRisk == "E2" -> 0.50
            eventRisk == "E1" -> 0.65
            marketPhase == "M3" -> 0.85
            else -> 0.70
        }

        val current = currentPositionRatioPercent / 100.0
        val advice = when {
            current > maxPosition + 0.03 -> "当前仓位${pct(current)}，高于建议上限${pct(maxPosition)}，优先撤交易/攻击仓。"
            current < maxPosition - 0.15 && eventRisk == "E0" && marketPhase == "M3" -> "风险低且主升确认，可分批提高趋势仓，但不要一次满仓。"
            else -> "当前仓位${pct(current)}接近本模型允许区间，先按个股强弱竞争处理。"
        }

        val positionByCode = positions.associateBy { it.code }
        val signals = quotes.mapNotNull { q ->
            val pos = positionByCode[q.code] ?: return@mapNotNull null
            val chg = q.changeRatio ?: return@mapNotNull null
            val role = pos.role.uppercase()
            val nearLow = if (q.latest != null && q.low != null && q.low > 0) (q.latest / q.low - 1) * 100 < 0.6 else false
            when {
                (role == "TRADE" || role == "ATTACK") && chg <= -5.0 -> StockSignal(q.code, "RED", "REDUCE", "${pos.name}为$role 仓且跌幅${fmt(chg)}%，新增/攻击逻辑优先验收。")
                chg <= -8.0 && nearLow -> StockSignal(q.code, "RED", "REVIEW", "${pos.name}接近跌停级风险且仍贴近日低，禁止继续摊低。")
                chg <= -5.0 -> StockSignal(q.code, "YELLOW", "WATCH", "${pos.name}跌幅${fmt(chg)}%，等待VWAP/低点抬高确认再决定。")
                chg >= 5.0 -> StockSignal(q.code, "GREEN", "HOLD", "${pos.name}相对强，盈利仓优先让利润奔跑。")
                else -> StockSignal(q.code, "GRAY", "HOLD", "${pos.name}暂无极端信号。")
            }
        }

        return MarketAssessment(eventRisk, marketPhase, maxPosition, avg, redRatio, severeRatio, advice, signals)
    }

    private fun pct(v: Double) = "%.0f%%".format(v * 100)
    private fun fmt(v: Double) = "%+.2f".format(v)
}
