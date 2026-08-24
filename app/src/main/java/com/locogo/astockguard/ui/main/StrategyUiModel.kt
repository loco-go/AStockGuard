package com.locogo.astockguard.ui.main

import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Position
import com.locogo.astockguard.data.ai.AiStrategy

data class StockStrategyUiModel(
    val code: String,
    val name: String,
    val role: String,
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
        val posByCode = positions.associateBy { it.code }
        return snapshot.quotes.map { quote ->
            val position = posByCode[quote.code]
            val local = snapshot.assessment.signals.firstOrNull { it.code == quote.code }
            val aiStock = ai?.stocks?.firstOrNull { normalize(it.symbol) == normalize(quote.code) }
            val localAction = local?.action ?: "HOLD"
            val aiAction = aiStock?.action ?: "-"
            StockStrategyUiModel(
                code = quote.code,
                name = quote.name.ifBlank { position?.name.orEmpty() },
                role = position?.role ?: "WATCH",
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
