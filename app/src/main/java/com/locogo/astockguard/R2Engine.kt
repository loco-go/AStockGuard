package com.locogo.astockguard

import kotlin.math.abs
import kotlin.math.max

object R2Engine {
    fun evaluate(bars: List<DailyBar>, marketPhase: String): R2Result {
        if (bars.size < 12) return R2Result(0, "-", "历史K线不足")
        val d1 = bars[bars.lastIndex - 1]
        val d2 = bars.last()
        val d1Pct = d1.close / d1.open - 1.0
        val d2Pct = d2.close / d2.open - 1.0
        if (d1Pct >= -0.005 || d2Pct >= -0.005) return R2Result(0, "-", "未形成连续两根有效阴线")

        var score = 25 // 连续二阴结构
        val reasons = mutableListOf("D1/D2连续收阴")
        val volRatio = if (d1.volume > 0) d2.volume / d1.volume else 9.0
        when {
            volRatio in 0.50..0.80 -> { score += 20; reasons += "D2缩量至D1的${"%.0f".format(volRatio * 100)}%" }
            volRatio < 0.50 -> { score += 14; reasons += "D2极度缩量" }
            volRatio <= 0.95 -> { score += 10; reasons += "D2温和缩量" }
            volRatio > 1.10 -> { score -= 10; reasons += "D2放量，风险偏高" }
        }
        if (abs(d2Pct) < abs(d1Pct)) { score += 10; reasons += "D2跌幅收窄" }

        val ma5 = bars.takeLast(5).map { it.close }.average()
        val ma10 = bars.takeLast(10).map { it.close }.average()
        val nearMa = minOf(abs(d2.low / ma5 - 1), abs(d2.low / ma10 - 1)) <= 0.02
        if (nearMa) { score += 15; reasons += "低点靠近MA5/MA10" }

        val range = max(0.0001, d2.high - d2.low)
        val lowerShadow = (minOf(d2.open, d2.close) - d2.low).coerceAtLeast(0.0) / range
        if (lowerShadow >= 0.30) { score += 10; reasons += "D2有下影承接" }

        val high20 = bars.takeLast(20.coerceAtMost(bars.size)).maxOf { it.high }
        val pullback = 1 - d2.close / high20
        if (pullback <= 0.15) { score += 10; reasons += "距20日高点回撤≤15%" }
        else if (pullback > 0.25) { score -= 15; reasons += "高点回撤>25%，更像老龙/A杀" }

        val start10 = bars[bars.size - 11].close
        val momentum10 = d2.close / start10 - 1
        if (momentum10 >= 0.12) { score += 10; reasons += "前序10日动量较强" }

        if (marketPhase == "M2" || marketPhase == "M3") { score += 10; reasons += "市场处修复/主升" }
        if (marketPhase == "M1") { score -= 10; reasons += "市场仍在恐慌期" }

        score = score.coerceIn(0, 100)
        val grade = when {
            score >= 85 -> "S"
            score >= 75 -> "A"
            score >= 60 -> "B"
            else -> "C"
        }
        return R2Result(score, grade, reasons.joinToString("；"))
    }
}
