package com.locogo.astockguard.domain.review

import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.data.local.AlertRecordEntity
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.domain.strategy.ChartSignalAction
import com.locogo.astockguard.domain.strategy.IntradayChartSignal
import com.locogo.astockguard.domain.strategy.StrategyVersions
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max

data class AlertHistoryStats(
    val total: Int = 0,
    val pending: Int = 0,
    val evaluated: Int = 0,
    val wins: Int = 0,
    val winRatePct: Double = 0.0,
    val averageNetEdgePct: Double = 0.0,
    val recent: List<AlertRecordEntity> = emptyList()
)

data class AlertEvaluation(
    val status: String,
    val exitPrice: Double,
    val netEdgePct: Double,
    val maxFavorablePct: Double,
    val maxAdversePct: Double
)

/**
 * 提醒历史只记录真实发出的通知，并在未来六根五分钟K完成后自动评价。
 * 唯一键由股票、交易日、信号时间、方向和策略版本组成，服务重启不会重复计数。
 */
class AlertHistoryRepository(private val dao: CacheDao) {
    suspend fun recordLiveAlert(
        code: String,
        name: String,
        date: LocalDate,
        signal: IntradayChartSignal,
        dataSource: String
    ): Boolean {
        if (signal.recommended || signal.price <= 0.0) return false
        val version = StrategyVersions.CURRENT
        val alertKey = listOf(code, date, signal.time, signal.action.name, version).joinToString("|")
        val signalAt = date.atTime(parseTime(signal.time) ?: return false)
            .atZone(CHINA_ZONE).toInstant().toEpochMilli()
        return dao.insertAlertRecord(
            AlertRecordEntity(
                alertKey = alertKey,
                code = code,
                name = name.ifBlank { code },
                signalAt = signalAt,
                signalDate = date.toString(),
                signalTime = signal.time,
                action = signal.action.name,
                price = signal.price,
                score = signal.score,
                strategyVersion = version,
                source = "REALTIME_NOTIFICATION",
                dataSource = dataSource,
                reason = signal.reason
            )
        ) != -1L
    }

    /** 数据不足时保留PENDING；止盈/止损已触发时允许提前定案。 */
    suspend fun evaluatePending(code: String, date: LocalDate, candles: List<MinuteCandle>, now: Long) {
        dao.getPendingAlertRecords(code)
            .filter { it.signalDate == date.toString() }
            .forEach { record ->
                val result = AlertOutcomeEvaluator.evaluate(record, candles) ?: return@forEach
                dao.evaluateAlertRecord(
                    id = record.id,
                    status = result.status,
                    evaluatedAt = now,
                    exitPrice = result.exitPrice,
                    netEdgePct = result.netEdgePct,
                    maxFavorablePct = result.maxFavorablePct,
                    maxAdversePct = result.maxAdversePct
                )
            }
    }

    suspend fun stats(limit: Int = 100): AlertHistoryStats {
        val records = dao.getAlertRecords(limit)
        val evaluated = records.filter { it.status == "WIN" || it.status == "LOSS" }
        val wins = evaluated.count { it.status == "WIN" }
        return AlertHistoryStats(
            total = records.size,
            pending = records.count { it.status == "PENDING" },
            evaluated = evaluated.size,
            wins = wins,
            winRatePct = if (evaluated.isEmpty()) 0.0 else wins * 100.0 / evaluated.size,
            averageNetEdgePct = if (evaluated.isEmpty()) 0.0 else evaluated.map { it.netEdgePct }.average(),
            recent = records.take(20)
        )
    }

    private fun parseTime(value: String): LocalTime? = TIME_REGEX.find(value)?.let { match ->
        runCatching { LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
    }

    private companion object {
        val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val TIME_REGEX = Regex("(\\d{2}):(\\d{2})")
    }
}

/** 纯函数评价器，方便对统计口径做固定样本测试。 */
object AlertOutcomeEvaluator {
    fun evaluate(record: AlertRecordEntity, candles: List<MinuteCandle>): AlertEvaluation? {
        val index = candles.indexOfFirst { it.time == record.signalTime }
        if (index < 0 || index >= candles.lastIndex || record.price <= 0.0) return null
        val future = candles.drop(index + 1).take(record.horizonBars)
        if (future.isEmpty()) return null
        val isBuy = record.action == ChartSignalAction.BUY.name
        var exitPrice: Double? = null
        var status: String? = null
        var maxFavorable = 0.0
        var maxAdverse = 0.0
        future.forEach { candle ->
            val favorable = if (isBuy) candle.high / record.price - 1.0 else record.price / candle.low - 1.0
            val adverse = if (isBuy) record.price / candle.low - 1.0 else candle.high / record.price - 1.0
            maxFavorable = max(maxFavorable, favorable)
            maxAdverse = max(maxAdverse, adverse)
            if (status == null && adverse >= STOP_RATIO) {
                status = "LOSS"
                exitPrice = record.price * if (isBuy) 1.0 - STOP_RATIO else 1.0 + STOP_RATIO
            } else if (status == null && favorable >= TARGET_RATIO) {
                status = "WIN"
                exitPrice = record.price * if (isBuy) 1.0 + TARGET_RATIO else 1.0 - TARGET_RATIO
            }
        }
        // 尚未走完观察窗口且没有触发止盈止损，不提前用不完整数据评分。
        if (status == null && future.size < record.horizonBars) return null
        val actualExit = exitPrice ?: future.last().close
        val edge = netEdgePct(record.price, actualExit, isBuy)
        val finalStatus = status ?: if (edge > 0.0) "WIN" else "LOSS"
        return AlertEvaluation(
            status = if (finalStatus == "WIN" && edge <= 0.0) "LOSS" else finalStatus,
            exitPrice = actualExit,
            netEdgePct = edge,
            maxFavorablePct = maxFavorable * 100.0,
            maxAdversePct = maxAdverse * 100.0
        )
    }

    /** 与做T回测保持相同的1000股、最低佣金、印花税和双边滑点口径。 */
    private fun netEdgePct(entry: Double, exit: Double, isBuy: Boolean): Double {
        val buyReference = if (isBuy) entry else exit
        val sellReference = if (isBuy) exit else entry
        val buyPrice = buyReference * (1.0 + SLIPPAGE)
        val sellPrice = sellReference * (1.0 - SLIPPAGE)
        val buyAmount = buyPrice * QUANTITY
        val sellAmount = sellPrice * QUANTITY
        val fees = max(buyAmount * COMMISSION_RATE, MIN_COMMISSION) +
            max(sellAmount * COMMISSION_RATE, MIN_COMMISSION)
        val pnl = sellAmount - buyAmount - fees - sellAmount * STAMP_TAX_RATE
        return pnl / (entry * QUANTITY) * 100.0
    }

    private const val TARGET_RATIO = 0.005
    private const val STOP_RATIO = 0.0035
    private const val QUANTITY = 1_000
    private const val COMMISSION_RATE = 0.0003
    private const val MIN_COMMISSION = 5.0
    private const val STAMP_TAX_RATE = 0.0005
    private const val SLIPPAGE = 0.0002
}
