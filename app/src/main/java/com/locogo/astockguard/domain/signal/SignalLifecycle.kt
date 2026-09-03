package com.locogo.astockguard.domain.signal

/*
 * 文件职责：定义并推进信号生命周期；合法迁移、冷却和终态规则用于避免重复提醒及旧信号复活。
 * 架构边界：领域层不直接访问 Android View；需要网络或 Room 的输入由 Repository 在调用前准备。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Quote
import com.locogo.astockguard.StockSignal
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.SignalEventEntity
import com.locogo.astockguard.data.local.SignalStateEntity

enum class SignalStage { IDLE, WATCH, READY, TRIGGERED, CONFIRMED, INVALIDATED }

data class SignalTransition(val code: String, val from: SignalStage, val to: SignalStage, val action: String, val reason: String, val at: Long) {
    val important: Boolean get() = to in setOf(SignalStage.TRIGGERED, SignalStage.CONFIRMED, SignalStage.INVALIDATED)
}

class SignalLifecycleManager(private val dao: CacheDao) {
    suspend fun evaluate(snapshot: MonitorSnapshot): List<SignalTransition> {
        if (snapshot.dataHealth.isStale) return emptyList()
        val quotes = snapshot.quotes.associateBy { it.code }
        return snapshot.assessment.signals.mapNotNull { evaluateOne(it, quotes[it.code], snapshot.updatedAt) }
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
            lastTransitionAt = if (changed) now else (old?.lastTransitionAt ?: now), lastNotifiedAt = old?.lastNotifiedAt ?: 0L,
            reason = signal.reason
        ))
        if (!changed) return null
        dao.insertSignalEvent(SignalEventEntity(
            code = signal.code, eventAt = now, fromStage = oldStage.name, toStage = next.name,
            action = signal.action, price = quote?.latest ?: 0.0, reason = signal.reason
        ))
        return SignalTransition(signal.code, oldStage, next, signal.action, signal.reason, now)
    }

    suspend fun markNotified(code: String, at: Long) {
        val state = dao.getSignalState(code) ?: return
        dao.upsertSignalState(state.copy(lastNotifiedAt = at))
    }
    suspend fun canNotify(code: String, now: Long, cooldownMs: Long = 10 * 60 * 1000L): Boolean {
        val state = dao.getSignalState(code) ?: return true
        return now - state.lastNotifiedAt >= cooldownMs
    }

    private fun desiredStage(signal: StockSignal, quote: Quote?, old: SignalStage): SignalStage = when (signal.action.uppercase()) {
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
    private fun promoteWithConfirmation(old: SignalStage, desired: SignalStage, consecutive: Int): SignalStage {
        if (desired == SignalStage.TRIGGERED && old == SignalStage.TRIGGERED && consecutive >= 2) return SignalStage.CONFIRMED
        if (desired == SignalStage.TRIGGERED && old == SignalStage.READY && consecutive < 2) return SignalStage.READY
        return desired
    }
}
