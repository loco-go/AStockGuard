package com.locogo.astockguard.ui.main

import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Position
import com.locogo.astockguard.data.ai.AiStrategy

data class StockStrategyUiModel(
    val code: String,
    val name: String,
    val role: String,
    val positionPct: Double?,
    val price: Double?,
    val changeRatio: Double?,
    val r2Score: Int,
    val r2Grade: String,
    val localAction: String,
    val localReason: String,
    val aiAction: String,
    val aiConfidence: Int,
    val aiTargetPositionPct: Int,
    val aiReason: String,
    val conflict: Boolean,
    val stale: Boolean
)

object StrategyUiMapper {
    fun map(snapshot: MonitorSnapshot?, positions: List<Position>, ai: AiStrategy?): List<StockStrategyUiModel> {
        if (snapshot == null) return emptyList()
        val quoteByCode = snapshot.quotes.associateBy { it.code }
        val marketValue = positions.sumOf { position ->
            val latest = quoteByCode[position.code]?.latest ?: 0.0
            latest * position.shares
        }
        // 首页是“真实持仓”列表：自选监控代码即使有行情，也不能伪装成仍在持有的股票。
        return positions.map { position ->
            val quote = quoteByCode[position.code] ?: com.locogo.astockguard.Quote(
                code = position.code,
                name = position.name
            )
            val local = snapshot.assessment.signals.firstOrNull { it.code == quote.code }
            val aiStock = ai?.stocks?.firstOrNull { normalize(it.symbol) == normalize(quote.code) }
            val localAction = local?.action ?: "HOLD"
            val aiAction = aiStock?.action ?: "-"
            StockStrategyUiModel(
                code = quote.code,
                name = quote.name.ifBlank { position.name },
                role = position.role,
                positionPct = quote.latest?.takeIf { marketValue > 0.0 }?.let {
                    it * position.shares / marketValue * 100.0
                },
                price = quote.latest,
                changeRatio = quote.changeRatio,
                r2Score = quote.r2Score,
                r2Grade = quote.r2Grade,
                localAction = localAction,
                localReason = local?.reason.orEmpty(),
                aiAction = aiAction,
                aiConfidence = aiStock?.confidence ?: 0,
                aiTargetPositionPct = aiStock?.targetPositionPct ?: 0,
                aiReason = aiStock?.reason.orEmpty(),
                conflict = aiStock != null && normalizeAction(localAction) != normalizeAction(aiAction),
                stale = snapshot.dataHealth.isStale
            )
        }
    }

    private fun normalize(value: String) = value.uppercase().replace(".", "").replace("SH", "").replace("SZ", "")
    private fun normalizeAction(value: String): String = when (value.uppercase()) {
        "REDUCE", "REVIEW", "SELL", "STRONG_SELL" -> "SELL"
        "R2", "BUY", "STRONG_BUY" -> "BUY"
        "WATCH" -> "WATCH"
        else -> "HOLD"
    }
}
