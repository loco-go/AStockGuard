package com.locogo.astockguard.domain.review

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.data.local.AlertRecordEntity
import com.locogo.astockguard.data.local.VolumeSignalOutcomeEntity
import kotlin.math.max

/*
 * 文件职责：在5/15/30/60分钟观察窗口结束后评价量能雷达提醒，不提前制造胜负结论。
 * 评价边界：只使用信号之后已经完成的分钟K；样本不足返回null并等待下一轮监控。
 */

object IntradayVolumeSignalEvaluator {
    /**
     * 评价单条雷达提醒在指定观察周期的方向有效性。
     * REAL_BREAKOUT期望继续上涨，BULL_TRAP/EXHAUSTION/SELL_T/REDUCE期望回落；其他类型不做方向评价。
     */
    fun evaluate(
        record: AlertRecordEntity,
        bars: List<MinuteBar>,
        intervalMinutes: Int,
        horizonMinutes: Int,
        evaluatedAt: Long
    ): VolumeSignalOutcomeEntity? {
        if (record.alertType != "VOLUME_RADAR" || record.price <= 0.0 || intervalMinutes <= 0) return null
        val signalIndex = bars.indexOfLast { normalizeTime(it.time) == normalizeTime(record.signalTime) }
        if (signalIndex < 0) return null
        val requiredBars = (horizonMinutes + intervalMinutes - 1) / intervalMinutes
        val future = bars.drop(signalIndex + 1).take(requiredBars)
        if (future.size < requiredBars) return null
        val expectUp = when {
            record.signalType == "REAL_BREAKOUT" -> true
            record.signalType in setOf("BULL_TRAP", "EXHAUSTION") -> false
            record.action in setOf("SELL_T", "REDUCE") -> false
            else -> return null
        }
        val exit = future.last().price
        val rawReturn = (exit / record.price - 1.0) * 100.0
        val directionalReturn = if (expectUp) rawReturn else -rawReturn
        val favorable = future.maxOf { bar ->
            if (expectUp) (bar.high / record.price - 1.0) * 100.0 else (record.price / bar.low - 1.0) * 100.0
        }
        val adverse = future.maxOf { bar ->
            if (expectUp) (record.price / bar.low - 1.0) * 100.0 else (bar.high / record.price - 1.0) * 100.0
        }
        return VolumeSignalOutcomeEntity(
            alertId = record.id,
            horizonMinutes = horizonMinutes,
            evaluatedAt = evaluatedAt,
            futurePrice = exit,
            returnPct = rawReturn,
            maxFavorablePct = max(0.0, favorable),
            maxAdversePct = max(0.0, adverse),
            effective = directionalReturn >= effectiveThreshold(horizonMinutes)
        )
    }

    /** 将供应商可能带秒的时间统一截取为HH:mm，保证提醒时点与分钟K能够稳定匹配。 */
    private fun normalizeTime(value: String): String = TIME_REGEX.find(value)?.value ?: value.trim()

    /** 不同观察周期采用递增有效阈值；30分钟2%与需求示例保持一致。 */
    private fun effectiveThreshold(horizonMinutes: Int): Double = when (horizonMinutes) {
        5 -> 0.5
        15 -> 1.0
        30 -> 2.0
        else -> 2.5
    }

    private val TIME_REGEX = Regex("\\d{2}:\\d{2}")
}
