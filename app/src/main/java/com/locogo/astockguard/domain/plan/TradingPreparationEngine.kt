package com.locogo.astockguard.domain.plan

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import kotlin.math.roundToInt
import kotlin.math.ceil

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

data class ExposureReduction(
    val code: String,
    val name: String,
    val category: PositionCategory,
    val quantity: Int,
    val reason: String
)

data class ExposureControlPlan(
    val status: String = "NO_DATA",
    val currentPositionPct: Double = 0.0,
    val targetPositionPct: Double = 0.0,
    val reductions: List<ExposureReduction> = emptyList(),
    val reason: String = "暂无仓位计划",
    val actionable: Boolean = false
)

/**
 * 将市场风险上限转换为逐只持仓的减仓顺序。
 * 短期套利仓优先释放，趋势仍完整的格局利润仓和长期仓最后处理，避免一刀切破坏底仓。
 */
object PortfolioExposureEngine {
    fun evaluate(
        positions: List<Position>,
        quotes: List<Quote>,
        assessment: MarketAssessment?,
        currentPositionPct: Double,
        dataStale: Boolean
    ): ExposureControlPlan {
        val targetPct = (assessment?.maxPositionRatio ?: 0.0) * 100.0
        if (dataStale || assessment == null) {
            return ExposureControlPlan(
                status = "BLOCKED_STALE",
                currentPositionPct = currentPositionPct,
                targetPositionPct = targetPct,
                reason = "行情为缓存或风险评估缺失，不生成实时减仓数量。"
            )
        }
        val quoteMap = quotes.associateBy { it.code }
        val marketValue = positions.sumOf { position ->
            (quoteMap[position.code]?.latest ?: 0.0).coerceAtLeast(0.0) * position.shares
        }
        if (positions.isEmpty() || marketValue <= 0.0 || currentPositionPct <= 0.0) {
            return ExposureControlPlan(reason = "持仓市值或仓位比例不足，无法换算减仓数量。")
        }
        val gapPct = currentPositionPct - targetPct
        if (gapPct <= 3.0) {
            val expansion = assessment.marketPhase == "M3" && gapPct <= -10.0 && assessment.eventRisk == "E0"
            return ExposureControlPlan(
                status = if (expansion) "PROFIT_EXPANSION" else "BALANCED",
                currentPositionPct = currentPositionPct,
                targetPositionPct = targetPct,
                reason = if (expansion) {
                    "主升阶段且仓位低于上限：只对站稳MA5/MA10的趋势仓分批扩大利润，单次不超过总资产5%，不追高。"
                } else {
                    "当前仓位位于风险上限附近，保持分类持仓纪律。"
                }
            )
        }

        val estimatedAssets = marketValue / (currentPositionPct / 100.0)
        var remainingValue = estimatedAssets * gapPct / 100.0
        val candidates = positions.mapNotNull { position ->
            val quote = quoteMap[position.code] ?: return@mapNotNull null
            val latest = quote.latest?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val category = PositionCategory.from(position.role)
            val weak = (quote.changeRatio ?: 0.0) <= -2.0 ||
                (quote.ma5 != null && quote.ma10 != null && latest < quote.ma5 && latest < quote.ma10)
            val gainPct = if (position.cost > 0.0) (latest / position.cost - 1.0) * 100.0 else 0.0
            val trendProtected = category == PositionCategory.PROFIT_RUNNER && gainPct >= 5.0 && !weak
            val priority = when (category) {
                PositionCategory.SHORT_ARBITRAGE -> 0
                PositionCategory.CORE -> 2
                PositionCategory.PROFIT_RUNNER -> if (trendProtected) 5 else 1
                PositionCategory.LONG_TERM -> 6
            } - if (weak) 1 else 0
            Candidate(position, quote, category, latest, weak, trendProtected, priority)
        }.sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.quote.changeRatio ?: 0.0 })

        val reductions = buildList {
            candidates.forEach { candidate ->
                if (remainingValue <= 0.0) return@forEach
                val availableLots = candidate.position.shares / 100
                if (availableLots <= 0) return@forEach
                val requiredLots = ceil(remainingValue / candidate.latest / 100.0).toInt().coerceAtLeast(1)
                val lots = minOf(requiredLots, availableLots)
                val quantity = lots * 100
                add(
                    ExposureReduction(
                        candidate.position.code,
                        candidate.position.name,
                        candidate.category,
                        quantity,
                        when {
                            candidate.weak && candidate.category == PositionCategory.SHORT_ARBITRAGE -> "短期仓且量价转弱，优先退出"
                            candidate.weak -> "价格/涨跌表现转弱，优先降低机动仓"
                            candidate.trendProtected -> "趋势利润仓最后处理，仅在其他仓位不足时减"
                            candidate.category == PositionCategory.LONG_TERM -> "长期底仓最后处理"
                            else -> "按分类优先级释放风险敞口"
                        }
                    )
                )
                remainingValue -= quantity * candidate.latest
            }
        }
        return ExposureControlPlan(
            status = "REDUCE_EXPOSURE",
            currentPositionPct = currentPositionPct,
            targetPositionPct = targetPct,
            reductions = reductions,
            reason = "当前仓位高出风险上限${"%.1f".format(gapPct)}个百分点；建议按顺序分批降仓，实际成交后重新计算。",
            actionable = reductions.isNotEmpty()
        )
    }

    private data class Candidate(
        val position: Position,
        val quote: Quote,
        val category: PositionCategory,
        val latest: Double,
        val weak: Boolean,
        val trendProtected: Boolean,
        val priority: Int
    )
}

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
            val strong = latest != null && quote.ma5 != null && quote.ma10 != null &&
                latest >= quote.ma5 && latest >= quote.ma10 && (quote.changeRatio ?: 0.0) >= 0.0
            val action = when {
                dataStale || latest == null -> "仅观察"
                category == PositionCategory.SHORT_ARBITRAGE && (riskPhase || weak || (pnlPct ?: 0.0) <= -4.0) -> "反弹降仓"
                category == PositionCategory.PROFIT_RUNNER && weak -> "收紧利润保护"
                category == PositionCategory.PROFIT_RUNNER && strong && (pnlPct ?: 0.0) >= 5.0 -> "移动防守，放大利润"
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
                PositionCategory.PROFIT_RUNNER -> listOfNotNull(quote?.ma5, latest?.times(0.96)).maxOrNull()
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
