package com.locogo.astockguard

/*
 * 文件职责：根据市场宽度、平均涨跌和极端下跌比例生成确定性风险阶段与仓位上限；规则调整必须保持可回测。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

object RiskEngine {
    fun assess(quotes: List<Quote>, positions: List<Position>, currentPositionRatioPercent: Double): MarketAssessment {
        val changes = quotes.mapNotNull { it.changeRatio }
        if (changes.isEmpty()) return MarketAssessment("E?", "M?", 0.60, 0.0, 0.0, 0.0, "实时行情不足，禁止自动升仓。", emptyList())

        val avg = changes.average()
        val redRatio = changes.count { it < 0 }.toDouble() / changes.size
        val severeRatio = changes.count { it <= -7.0 }.toDouble() / changes.size
        val eventRisk = when {
            avg <= -4.0 || severeRatio >= 0.35 -> "E2"
            avg <= -2.0 || redRatio >= 0.70 -> "E1"
            else -> "E0"
        }
        val marketPhase = when {
            eventRisk == "E2" -> "M1"
            avg >= 2.0 && redRatio <= 0.35 -> "M3"
            avg > 0 && redRatio < 0.55 -> "M2"
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
            current > maxPosition + 0.03 -> "当前仓位${pct(current)}高于建议上限${pct(maxPosition)}，优先撤交易/攻击仓。"
            current < maxPosition - 0.15 && eventRisk == "E0" && marketPhase == "M3" -> "主升确认且风险低，可分批提高趋势仓。"
            else -> "当前仓位${pct(current)}接近允许区间，按个股强弱竞争处理。"
        }

        val pos = positions.associateBy { it.code }
        val signals = quotes.mapNotNull { q ->
            val p = pos[q.code] ?: return@mapNotNull null
            val chg = q.changeRatio ?: return@mapNotNull null
            val role = p.role.uppercase()
            val belowVwap = q.latest != null && q.vwap != null && q.latest < q.vwap * 0.995
            when {
                (role == "TRADE" || role == "ATTACK") && chg <= -5 -> StockSignal(q.code, "RED", "REDUCE", "${p.name}为$role 仓，跌幅${fmt(chg)}%，优先验收。")
                chg <= -8 -> StockSignal(q.code, "RED", "REVIEW", "${p.name}接近跌停级风险，禁止继续摊低。")
                belowVwap && chg < -2 -> StockSignal(q.code, "YELLOW", "WATCH", "${p.name}位于VWAP下方且偏弱，等待反抽确认。")
                q.r2Score >= 75 && eventRisk != "E2" -> StockSignal(q.code, "GREEN", "R2", "${p.name} R2 ${q.r2Grade}级(${q.r2Score})，只在分时承接确认后考虑。")
                chg >= 5 -> StockSignal(q.code, "GREEN", "HOLD", "${p.name}相对强，盈利仓优先让利润奔跑。")
                else -> StockSignal(q.code, "GRAY", "HOLD", "${p.name}暂无极端信号。")
            }
        }
        return MarketAssessment(eventRisk, marketPhase, maxPosition, avg, redRatio, severeRatio, advice, signals)
    }
    private fun pct(v: Double) = "%.0f%%".format(v * 100)
    private fun fmt(v: Double) = "%+.2f".format(v)
}
