package com.locogo.astockguard.domain.plan

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import kotlin.math.roundToInt

enum class PositionCategory(val label: String, val maxTSharePct: Int) {
    LONG_TERM("长期持仓", 10),
    CORE("趋势核心", 20),
    SHORT_ARBITRAGE("短期套利", 35),
    PROFIT_RUNNER("格局利润仓", 20);

    companion object {
        /** 兼容历史 CORE/ATTACK/LONG，同时接受设置页新增的中文或英文分类。 */
        fun from(role: String): PositionCategory = when (role.trim().uppercase()) {
            "LONG", "LONG_TERM", "长期", "长期持仓" -> LONG_TERM
            "ATTACK", "ARBITRAGE", "SHORT", "短线", "短期套利" -> SHORT_ARBITRAGE
            "PROFIT", "RUNNER", "TREND", "格局", "利润仓", "格局利润仓" -> PROFIT_RUNNER
            else -> CORE
        }
    }
}

data class PositionNextDayPlan(
    val code: String,
    val name: String,
    val category: PositionCategory,
    val action: String,
    val maxTQuantity: Int,
    val defensePrice: Double?,
    val reason: String
)

data class AuctionPlan(
    val code: String = "",
    val status: String = "NO_DATA",
    val score: Int = 0,
    val gapPct: Double? = null,
    val auctionVolumePct: Double? = null,
    val referencePrice: Double? = null,
    val source: String = "NONE",
    val reason: String = "暂无竞价数据"
)

/** 隔日持仓计划：分类决定底仓纪律，行情只负责触发减仓、持有或利润保护。 */
object PositionPlanEngine {
    fun evaluate(
        positions: List<Position>,
        quotes: List<Quote>,
        marketPhase: String?,
        dataStale: Boolean
    ): List<PositionNextDayPlan> {
        val quoteMap = quotes.associateBy { it.code }
        val riskPhase = marketPhase?.uppercase() in setOf("M0", "M1")
        return positions.map { position ->
            val category = PositionCategory.from(position.role)
            val quote = quoteMap[position.code]
            val latest = quote?.latest
            val pnlPct = latest?.takeIf { position.cost > 0.0 }?.let { (it / position.cost - 1.0) * 100.0 }
            val belowMa5 = latest != null && quote.ma5 != null && latest < quote.ma5
            val belowMa10 = latest != null && quote.ma10 != null && latest < quote.ma10
            val weak = belowMa5 && belowMa10
            val strong = quote?.r2Score?.let { it >= 80 } == true && !belowMa5
            val action = when {
                dataStale || latest == null -> "仅观察"
                category == PositionCategory.SHORT_ARBITRAGE && (riskPhase || weak || (pnlPct ?: 0.0) <= -4.0) -> "反弹降仓"
                category == PositionCategory.PROFIT_RUNNER && (weak || (pnlPct ?: 0.0) < 3.0) -> "收紧利润保护"
                category == PositionCategory.LONG_TERM && riskPhase -> "保留底仓，暂停加仓"
                riskPhase -> "降低机动仓"
                strong -> "持有并让利润奔跑"
                else -> "按计划持有"
            }
            val tPct = if (riskPhase) minOf(category.maxTSharePct, 10) else category.maxTSharePct
            val maxTQuantity = ((position.shares * tPct / 100) / 100 * 100).coerceAtLeast(0)
            val defense = when (category) {
                PositionCategory.LONG_TERM -> quote?.ma10 ?: position.cost * 0.92
                PositionCategory.CORE -> quote?.ma10 ?: position.cost * 0.96
                PositionCategory.SHORT_ARBITRAGE -> quote?.ma5 ?: position.cost * 0.97
                PositionCategory.PROFIT_RUNNER -> quote?.ma5 ?: latest?.times(0.96)
            }?.takeIf { it.isFinite() && it > 0.0 }
            PositionNextDayPlan(
                code = position.code,
                name = position.name,
                category = category,
                action = action,
                maxTQuantity = maxTQuantity,
                defensePrice = defense,
                reason = buildString {
                    append("角色上限T仓$tPct%")
                    pnlPct?.let { append("，持仓收益${signed(it)}%") }
                    append("，R2=${quote?.r2Score ?: 0}")
                    if (weak) append("，价格同时位于MA5/MA10下方")
                    if (riskPhase) append("，市场处于防守阶段")
                    if (dataStale) append("；当前行情为缓存，不生成操作指令")
                }
            )
        }
    }

    private fun signed(value: Double): String = "%+.2f".format(value)
}

/** 集合竞价计划：以缺口、竞价量占近期日均量比例和竞价价格漂移做确定性评分。 */
object AuctionPlanEngine {
    fun evaluate(
        code: String,
        positionRole: String?,
        previousClose: Double?,
        dailyBars: List<DailyBar>,
        series: MarketRepository.AuctionSeries
    ): AuctionPlan {
        if (!series.fresh || series.ticks.isEmpty()) {
            return AuctionPlan(code = code, source = series.source, reason = series.message)
        }
        val preClose = previousClose?.takeIf { it.isFinite() && it > 0.0 }
            ?: dailyBars.lastOrNull()?.close?.takeIf { it > 0.0 }
            ?: return AuctionPlan(code = code, source = series.source, reason = "缺少前收盘价，竞价计划不可用")
        val averageDailyVolume = dailyBars.takeLast(10).map { it.volume }.filter { it > 0.0 }.averageOrNull()
            ?: return AuctionPlan(code = code, source = series.source, reason = "缺少近期成交量，不能判断竞价量能")
        val first = series.ticks.first()
        val last = series.ticks.last()
        val auctionVolume = effectiveVolume(series.ticks.map { it.volume })
        val volumePct = auctionVolume / averageDailyVolume * 100.0
        val gapPct = (last.price / preClose - 1.0) * 100.0
        val driftPct = (last.price / first.price - 1.0) * 100.0
        val category = PositionCategory.from(positionRole.orEmpty())
        var score = 50.0 + gapPct.coerceIn(-5.0, 5.0) * 4.0 + driftPct.coerceIn(-3.0, 3.0) * 5.0
        score += when {
            volumePct >= 3.0 -> 16.0
            volumePct >= 1.5 -> 10.0
            volumePct >= 0.6 -> 4.0
            else -> -6.0
        }
        if (category == PositionCategory.SHORT_ARBITRAGE && gapPct < 0.0) score -= 5.0
        val normalizedScore = score.roundToInt().coerceIn(0, 100)
        val status = when {
            gapPct >= 7.0 -> "高开防追涨"
            gapPct <= -4.0 || normalizedScore <= 35 -> "弱竞价减仓预案"
            normalizedScore >= 70 && gapPct in -1.5..5.5 -> "强竞价开盘确认"
            else -> "等待开盘确认"
        }
        return AuctionPlan(
            code = code,
            status = status,
            score = normalizedScore,
            gapPct = gapPct,
            auctionVolumePct = volumePct,
            referencePrice = last.price,
            source = series.source,
            reason = buildString {
                append("竞价${if (gapPct >= 0) "高" else "低"}开${"%.2f".format(kotlin.math.abs(gapPct))}%")
                append("，竞价量约占10日日均量${"%.2f".format(volumePct)}%")
                append("，竞价价格漂移${"%+.2f".format(driftPct)}%。")
                append(when (status) {
                    "强竞价开盘确认" -> "开盘后仍需站稳竞价价和VWAP，禁止仅凭竞价直接追入。"
                    "弱竞价减仓预案" -> "若开盘反抽不能收复竞价价，优先降低短期和攻击仓。"
                    "高开防追涨" -> "高开透支预期，等待回踩承接，不追集合竞价价格。"
                    else -> "等待开盘量价与资金流给出方向。"
                })
            }
        )
    }

    /** 兼容累计量和分段量两种返回方式：单调递增按累计量，否则按各段求和。 */
    private fun effectiveVolume(values: List<Double>): Double {
        val valid = values.filter { it.isFinite() && it >= 0.0 }
        if (valid.isEmpty()) return 0.0
        val cumulative = valid.zipWithNext().all { (a, b) -> b >= a }
        return if (cumulative) valid.last() else valid.sum()
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
