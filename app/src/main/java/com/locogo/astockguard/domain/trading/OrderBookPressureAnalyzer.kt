package com.locogo.astockguard.domain.trading

/*
 * 文件职责：分析真实盘口委托失衡、主动成交与跨帧持续性；模拟、缓存、过期及错码快照全部拒绝。
 * 架构边界：领域层不直接访问 Android View；需要网络或 Room 的输入由 Repository 在调用前准备。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

import com.locogo.astockguard.data.level2.Level2Snapshot
import kotlin.math.abs

/**
 * 真实盘口压力分析结果。
 *
 * 盘口只作为分时计划的确认层，不能单独生成买卖动作。模拟、缓存、超时或证券代码不匹配的
 * 快照统一返回 [UNAVAILABLE]，防止开发数据和旧盘口误触发实时提醒。
 */
data class OrderBookPressure(
    val status: String = UNAVAILABLE,
    val imbalance: Double? = null,
    val activeBuyRatio: Double? = null,
    val persistence: String = "UNAVAILABLE",
    val sampleCount: Int = 0,
    /** FULL可独立参与确认，SUPPORTING必须与资金流或量价信号同向。 */
    val evidenceGrade: String = "NONE",
    val reason: String = "无可用真实盘口"
) {
    companion object {
        const val UNAVAILABLE = "UNAVAILABLE"
        const val BID_DOMINANT = "BID_DOMINANT"
        const val ASK_DOMINANT = "ASK_DOMINANT"
        const val NEUTRAL = "NEUTRAL"
    }
}

object OrderBookPressureAnalyzer {
    /**
     * 使用买卖五档委托量失衡和最近主动成交方向交叉确认盘口强弱。
     * 委托量容易撤单，因此没有主动成交确认时采用更严格的失衡阈值。
     */
    fun analyze(
        snapshot: Level2Snapshot?,
        expectedCode: String,
        now: Long = System.currentTimeMillis(),
        history: List<Level2Snapshot> = emptyList()
    ): OrderBookPressure {
        val current = analyzeSingle(snapshot, expectedCode, now, MAX_AGE_MS)
        if (current.status == OrderBookPressure.UNAVAILABLE) return current
        if (snapshot?.source == "IFIND_HTTP_DEPTH_LIMITED") {
            return current.copy(
                persistence = "LIMITED_5",
                sampleCount = 1,
                evidenceGrade = "SUPPORTING",
                reason = "${current.reason}；仅五档，必须与分钟资金流同向才参与做T确认"
            )
        }

        // 收集期不足时沿用单帧结论；达到两个跨20秒样本后，方向必须连续才保留强信号。
        val sequence = (history + listOfNotNull(snapshot))
            .distinctBy { it.receivedAt.takeIf { time -> time > 0L } ?: it.updatedAt }
            .filter {
                !it.simulated && !it.stale && sameSecurity(it.code, expectedCode) &&
                    it.updatedAt > 0L && now - it.updatedAt in -MAX_FUTURE_SKEW_MS..SEQUENCE_MAX_AGE_MS &&
                    it.receivedAt > 0L && now - it.receivedAt in -MAX_FUTURE_SKEW_MS..SEQUENCE_MAX_AGE_MS
            }
            .sortedBy { it.receivedAt }
            .takeLast(MAX_SEQUENCE_SAMPLES)
        val span = (sequence.lastOrNull()?.receivedAt ?: 0L) - (sequence.firstOrNull()?.receivedAt ?: 0L)
        if (sequence.size < MIN_SEQUENCE_SAMPLES || span < MIN_SEQUENCE_SPAN_MS) {
            return current.copy(persistence = "COLLECTING", sampleCount = sequence.size)
        }

        val pressures = sequence.map { analyzeSingle(it, expectedCode, now, SEQUENCE_MAX_AGE_MS) }
            .filter { it.status != OrderBookPressure.UNAVAILABLE }
        val bidCount = pressures.count { it.status == OrderBookPressure.BID_DOMINANT }
        val askCount = pressures.count { it.status == OrderBookPressure.ASK_DOMINANT }
        val persistentStatus = when {
            current.status == OrderBookPressure.BID_DOMINANT && bidCount >= 2 && askCount == 0 -> OrderBookPressure.BID_DOMINANT
            current.status == OrderBookPressure.ASK_DOMINANT && askCount >= 2 && bidCount == 0 -> OrderBookPressure.ASK_DOMINANT
            else -> OrderBookPressure.NEUTRAL
        }
        val persistence = when (persistentStatus) {
            OrderBookPressure.BID_DOMINANT -> "PERSISTENT_BID"
            OrderBookPressure.ASK_DOMINANT -> "PERSISTENT_ASK"
            else -> if (bidCount > 0 && askCount > 0) "REVERSING" else "UNCONFIRMED"
        }
        val persistenceText = when (persistence) {
            "PERSISTENT_BID" -> "连续买压确认"
            "PERSISTENT_ASK" -> "连续卖压确认"
            "REVERSING" -> "盘口方向反复，强信号降级"
            else -> "盘口尚未连续，强信号降级"
        }
        return current.copy(
            status = persistentStatus,
            persistence = persistence,
            sampleCount = pressures.size,
            reason = "${current.reason}；$persistenceText（${pressures.size}帧）"
        )
    }

    private fun analyzeSingle(
        snapshot: Level2Snapshot?,
        expectedCode: String,
        now: Long,
        maxAgeMs: Long
    ): OrderBookPressure {
        if (snapshot == null) return unavailable("未获取盘口")
        if (snapshot.simulated || snapshot.source.equals("MOCK", ignoreCase = true)) {
            return unavailable("模拟盘口不参与策略")
        }
        if (snapshot.stale) return unavailable("缓存盘口不参与策略")
        if (!sameSecurity(snapshot.code, expectedCode)) return unavailable("盘口证券代码不匹配")
        if (snapshot.updatedAt <= 0L || now - snapshot.updatedAt !in -MAX_FUTURE_SKEW_MS..maxAgeMs) {
            return unavailable("盘口已超时")
        }

        val bidVolume = snapshot.bids.take(5).sumOf { it.volume.coerceAtLeast(0L) }.toDouble()
        val askVolume = snapshot.asks.take(5).sumOf { it.volume.coerceAtLeast(0L) }.toDouble()
        val totalBook = bidVolume + askVolume
        if (totalBook <= 0.0) return unavailable("买卖五档委托量为空")
        val imbalance = (bidVolume - askVolume) / totalBook

        val buyVolume = snapshot.trades.filter { it.side.equals("BUY", true) }
            .sumOf { it.volume.coerceAtLeast(0L) }.toDouble()
        val sellVolume = snapshot.trades.filter { it.side.equals("SELL", true) }
            .sumOf { it.volume.coerceAtLeast(0L) }.toDouble()
        val activeTotal = buyVolume + sellVolume
        val activeBuyRatio = if (activeTotal > 0.0) buyVolume / activeTotal else null

        val hasTradeConfirmation = activeBuyRatio != null
        val status = when {
            imbalance >= STRONG_BOOK_THRESHOLD && !hasTradeConfirmation -> OrderBookPressure.BID_DOMINANT
            imbalance <= -STRONG_BOOK_THRESHOLD && !hasTradeConfirmation -> OrderBookPressure.ASK_DOMINANT
            imbalance >= BOOK_THRESHOLD && (activeBuyRatio ?: 0.0) >= BUY_CONFIRM_RATIO -> OrderBookPressure.BID_DOMINANT
            imbalance <= -BOOK_THRESHOLD && (activeBuyRatio ?: 1.0) <= SELL_CONFIRM_RATIO -> OrderBookPressure.ASK_DOMINANT
            else -> OrderBookPressure.NEUTRAL
        }
        val direction = when (status) {
            OrderBookPressure.BID_DOMINANT -> "买盘占优"
            OrderBookPressure.ASK_DOMINANT -> "卖盘占优"
            else -> "盘口中性"
        }
        val tradeText = activeBuyRatio?.let { "，主动买入占比${percent(it)}" }.orEmpty()
        return OrderBookPressure(
            status = status,
            imbalance = imbalance,
            activeBuyRatio = activeBuyRatio,
            persistence = "SINGLE",
            sampleCount = 1,
            evidenceGrade = "FULL",
            reason = "$direction，五档失衡${signedPercent(imbalance)}$tradeText"
        )
    }

    private fun sameSecurity(left: String, right: String): Boolean {
        val leftDigits = left.filter(Char::isDigit).takeLast(6)
        val rightDigits = right.filter(Char::isDigit).takeLast(6)
        return leftDigits.length == 6 && leftDigits == rightDigits
    }

    private fun unavailable(reason: String) = OrderBookPressure(reason = reason)

    private fun percent(value: Double) = "%.1f%%".format(value * 100.0)

    private fun signedPercent(value: Double): String {
        val sign = if (value >= 0.0) "+" else "-"
        return "$sign${"%.1f".format(abs(value) * 100.0)}%"
    }

    private const val MAX_AGE_MS = 20_000L
    private const val SEQUENCE_MAX_AGE_MS = 120_000L
    private const val MIN_SEQUENCE_SPAN_MS = 20_000L
    private const val MIN_SEQUENCE_SAMPLES = 2
    private const val MAX_SEQUENCE_SAMPLES = 5
    private const val MAX_FUTURE_SKEW_MS = 5_000L
    private const val BOOK_THRESHOLD = 0.18
    private const val STRONG_BOOK_THRESHOLD = 0.30
    private const val BUY_CONFIRM_RATIO = 0.52
    private const val SELL_CONFIRM_RATIO = 0.48
}
