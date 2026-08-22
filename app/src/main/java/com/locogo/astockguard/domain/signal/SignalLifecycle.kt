package com.locogo.astockguard.domain.signal

import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Quote
import com.locogo.astockguard.StockSignal
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.SignalStateEntity

enum class SignalStage { IDLE, WATCH, READY, TRIGGERED, CONFIRMED, INVALIDATED }

data class SignalTransition(
    val code: String,
    val from: SignalStage,
    val to: SignalStage,
    val action: String,
    val reason: String,
    val at: Long
) {
    val important: Boolean get() = to in setOf(SignalStage.TRIGGERED, SignalStage.CONFIRMED, SignalStage.INVALIDATED)
}

class SignalLifecycleManager(private val dao: CacheDao) {
    suspend fun evaluate(snapshot: MonitorSnapshot): List<SignalTransition> {
        if (snapshot.dataHealth.isStale) return emptyList()
        val quotes = snapshot.quotes.associateBy { it.code }
        val now = snapshot.updatedAt
        return snapshot.assessment.signals.mapNotNull { signal -> evaluateOne(signal, quotes[signal.code], now) }
    }

    private suspend fun evaluateOne(signal: StockSignal, quote: Quote?, now: Long): SignalTransition? {
        val old = dao.getSignalState(signal.code)
        val oldStage = old?.stage?.let { runCatching { SignalStage.valueOf(it) }.getOrNull() } ?: SignalStage.IDLE
        val desired = desiredStage(signal, quote, oldStage)
        val consecutive = if (old?.lastAction == signal.action) (old.consecutiveCount + 1).coerceAtMost(10) else 1
        val next = promoteWithConfirmation(oldStage, desired, consecutive)
        val changed = next != oldStage
        dao.upsertSignalState(SignalStateEntity(
            code = signal.code, stage = next.name, lastAction = signal.action, consecutiveCount = consecutive,
            lastTransitionAt = if (changed) now else (old?.lastTransitionAt ?: now),
            lastNotifiedAt = old?.lastNotifiedAt ?: 0L, reason = signal.reason
        ))
        return if (changed) SignalTransition(signal.code, oldStage, next, signal.action, signal.reason, now) else null
    }

    suspend fun markNotified(code: String, at: Long) {
        val state = dao.getSignalState(code) ?: return
        dao.upsertSignalState(state.copy(lastNotifiedAt = at))
    }

    suspend fun canNotify(code: String, now: Long, cooldownMs: Long = 10 * 60 * 1000L): Boolean {
        val state = dao.getSignalState(code) ?: return true
        return now - state.lastNotifiedAt >= cooldownMs
    }

    private fun desiredStage(signal: StockSignal, quote: Quote?, old: SignalStage): SignalStage {
        return when (signal.action.uppercase()) {
            "REDUCE", "REVIEW", "SELL" -> SignalStage.TRIGGERED
            "R2", "BUY" -> {
                val aboveVwap = quote?.latest != null && quote.vwap != null && quote.latest >= quote.vwap * 0.998
                if ((quote?.r2Score ?: 0) >= 85 && aboveVwap) SignalStage.TRIGGERED else SignalStage.READY
            }
            "WATCH" -> if (old in setOf(SignalStage.TRIGGERED, SignalStage.CONFIRMED)) SignalStage.INVALIDATED else SignalStage.WATCH
            else -> when (old) {
                SignalStage.TRIGGERED -> SignalStage.CONFIRMED
                SignalStage.CONFIRMED -> SignalStage.CONFIRMED
                SignalStage.READY, SignalStage.WATCH -> SignalStage.INVALIDATED
                else -> SignalStage.IDLE
            }
        }
    }

    private fun promoteWithConfirmation(old: SignalStage, desired: SignalStage, consecutive: Int): SignalStage {
        if (desired == SignalStage.TRIGGERED && old == SignalStage.TRIGGERED && consecutive >= 2) return SignalStage.CONFIRMED
        if (desired == SignalStage.TRIGGERED && old == SignalStage.READY && consecutive < 2) return SignalStage.READY
        return desired
    }
}
