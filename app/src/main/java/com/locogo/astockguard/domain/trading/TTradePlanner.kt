package com.locogo.astockguard.domain.trading

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import com.locogo.astockguard.data.fundflow.StockFundFlow
import com.locogo.astockguard.domain.plan.PositionCategory
import kotlin.math.max
import kotlin.math.min

/**
 * Intraday T-trade planner for an existing A-share position.
 *
 * This is a decision-support model, not an execution engine. It deliberately requires an
 * existing >= 100-share position because newly purchased A shares are T+1. The suggested
 * quantity is capped to roughly one third of the existing base position and rounded to lots.
 */
data class TTradePlan(
    val code: String = "",
    val status: String = "NO_DATA",
    val referencePrice: Double = 0.0,
    val buyZoneLow: Double = 0.0,
    val buyZoneHigh: Double = 0.0,
    val sellZoneLow: Double = 0.0,
    val sellZoneHigh: Double = 0.0,
    val invalidPrice: Double = 0.0,
    val expectedEdgePct: Double = 0.0,
    val suggestedQuantity: Int = 0,
    val manualAnchorPrice: Double? = null,
    val reason: String = "",
    val generatedAt: Long = 0L
) {
    val actionable: Boolean get() = status == "BUY_ZONE" || status == "SELL_ZONE"
}

object TTradePlanner {
    fun plan(
        quote: Quote?,
        position: Position?,
        minuteBars: List<MinuteBar>,
        fundFlow: StockFundFlow?,
        marketPhase: String?,
        dataStale: Boolean,
        manualAnchorPrice: Double? = null,
        now: Long = System.currentTimeMillis()
    ): TTradePlan {
        val code = quote?.code ?: position?.code.orEmpty()
        if (dataStale) return TTradePlan(code = code, status = "BLOCKED_STALE", reason = "行情为缓存数据，禁止生成盘中T交易动作。", generatedAt = now)
        if (position == null || position.shares < 100) return TTradePlan(code = code, status = "NO_BASE_POSITION", reason = "没有至少100股可作为T仓基础；A股新买入股票受T+1限制。", generatedAt = now)
        if (minuteBars.size < 8) return TTradePlan(code = code, status = "NO_DATA", reason = "分钟数据不足，暂不计算T区间。", generatedAt = now)

        val latest = quote?.latest ?: minuteBars.last().price
        if (!latest.isFinite() || latest <= 0.0) return TTradePlan(code = code, status = "NO_DATA", reason = "最新价无效。", generatedAt = now)

        val bars = minuteBars.takeLast(min(60, minuteBars.size))
        val sessionHigh = bars.maxOf { max(it.high, it.price) }
        val sessionLow = bars.minOf { min(it.low, it.price) }
        val vwap = bars.lastOrNull()?.avgPrice?.takeIf { it > 0.0 } ?: quote?.vwap?.takeIf { it > 0.0 } ?: latest
        val rangePct = ((sessionHigh - sessionLow) / latest).coerceAtLeast(0.0)

        // Too little intraday amplitude usually cannot cover fees/slippage/decision error.
        if (rangePct < 0.009) {
            return TTradePlan(
                code = code, status = "NO_T", referencePrice = latest,
                suggestedQuantity = lotQuantity(position),
                reason = "近60分钟振幅不足0.9%，T交易空间偏小，优先减少无效交易。", generatedAt = now
            )
        }

        val dynamicBand = (rangePct * 0.24).coerceIn(0.0035, 0.012)
        val anchor = manualAnchorPrice?.takeIf { it.isFinite() && it > 0.0 }
        val buyCenter = anchor ?: max(sessionLow * 1.002, vwap * (1.0 - dynamicBand))
        val minEdge = max(0.0065, dynamicBand * 1.45)
        val sellCenter = min(sessionHigh * 0.998, buyCenter * (1.0 + max(minEdge, rangePct * 0.38)))

        if (sellCenter <= buyCenter * 1.0045) {
            return TTradePlan(
                code = code, status = "NO_T", referencePrice = latest,
                suggestedQuantity = lotQuantity(position), manualAnchorPrice = anchor,
                reason = "当前上方空间不足，计划买卖价差无法形成有效安全垫。", generatedAt = now
            )
        }

        val zoneHalfWidth = (dynamicBand * 0.22).coerceIn(0.0012, 0.0028)
        val buyLow = buyCenter * (1.0 - zoneHalfWidth)
        val buyHigh = buyCenter * (1.0 + zoneHalfWidth)
        val sellLow = sellCenter * (1.0 - zoneHalfWidth)
        val sellHigh = sellCenter * (1.0 + zoneHalfWidth)
        val invalid = min(sessionLow * 0.994, buyLow * 0.994)

        val flowImproving = fundFlow?.minute?.takeLast(2)?.let { points ->
            points.size >= 2 && points.last().mainNet >= points.first().mainNet
        }
        val flowWeakening = fundFlow?.minute?.takeLast(2)?.let { points ->
            points.size >= 2 && points.last().mainNet < points.first().mainNet
        }

        val riskPhase = marketPhase?.uppercase() in setOf("M0", "M1")
        val status = when {
            latest <= invalid -> "INVALIDATED"
            latest in buyLow..buyHigh && flowWeakening != true && !riskPhase -> "BUY_ZONE"
            latest in sellLow..sellHigh || latest > sellHigh -> "SELL_ZONE"
            latest < buyLow -> "WAIT_RECLAIM"
            else -> "WAIT"
        }

        val qty = lotQuantity(position).let { if (riskPhase) min(it, 100) else it }
        val expectedEdge = (sellCenter / buyCenter - 1.0) * 100.0
        val flowText = when {
            flowImproving == true -> "主力分钟流向改善"
            flowWeakening == true -> "主力分钟流向转弱"
            else -> "资金流暂无明确加速度"
        }
        val reason = buildString {
            append("近60分钟振幅${"%.2f".format(rangePct * 100)}%，VWAP=${"%.2f".format(vwap)}，$flowText。")
            if (anchor != null) append("买点锚定于用户选择价${"%.2f".format(anchor)}。")
            append("计划价差约${"%.2f".format(expectedEdge)}%；失效位${"%.2f".format(invalid)}。")
            if (riskPhase) append("当前市场阶段偏防守，T仓压缩到最多100股。")
        }

        return TTradePlan(
            code = code,
            status = status,
            referencePrice = latest,
            buyZoneLow = buyLow,
            buyZoneHigh = buyHigh,
            sellZoneLow = sellLow,
            sellZoneHigh = sellHigh,
            invalidPrice = invalid,
            expectedEdgePct = expectedEdge,
            suggestedQuantity = qty,
            manualAnchorPrice = anchor,
            reason = reason,
            generatedAt = now
        )
    }

    private fun lotQuantity(position: Position): Int {
        val categoryPct = PositionCategory.from(position.role).maxTSharePct
        val maxT = (position.shares * categoryPct / 100 / 100) * 100
        return maxT.coerceAtLeast(100).coerceAtMost(position.shares - (position.shares % 100))
    }
}
