package com.locogo.astockguard.domain.signal

import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.R2Engine

data class R2ScanRow(
    val code: String,
    val score: Int,
    val grade: String,
    val reason: String,
    val stageHint: SignalStage
)

class R2Scanner(private val marketRepository: MarketRepository) {
    suspend fun scan(codes: List<String>, marketPhase: String): List<R2ScanRow> = codes.mapNotNull { code ->
        val bars = runCatching { marketRepository.loadDailyBars(code, 30) }.getOrDefault(emptyList())
        if (bars.size < 12) return@mapNotNull null
        val r2 = R2Engine.evaluate(bars, marketPhase)
        if (r2.score < 50) return@mapNotNull null
        R2ScanRow(
            code = code, score = r2.score, grade = r2.grade, reason = r2.reason,
            stageHint = when { r2.score >= 85 -> SignalStage.READY; r2.score >= 75 -> SignalStage.WATCH; else -> SignalStage.IDLE }
        )
    }.sortedByDescending { it.score }
}
