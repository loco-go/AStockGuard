package com.locogo.astockguard.ui.dashboard

import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import com.locogo.astockguard.ui.main.MainViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 分阶段迁移期间沿用 V3.5 业务实现，并以 V4 名称对外暴露。 */
typealias DashboardViewModel = MainViewModel

data class MarketIndexUi(
    val name: String,
    val value: Double?,
    val changePct: Double?
)

data class DashboardMarketUi(
    val indices: List<MarketIndexUi>,
    val turnover: Double?,
    val risingCount: Int,
    val fallingCount: Int,
    val flatCount: Int,
    val breadthScope: String,
    val stale: Boolean
)

data class DashboardAccountUi(
    val totalAssets: Double?,
    val marketValue: Double,
    val todayPnl: Double?,
    val cumulativePnl: Double?,
    val positionPct: Double,
    /** PREOPEN表示尚未产生连续竞价成交，INCOMPLETE表示部分持仓缺少有效现价。 */
    val todayPnlState: String = "READY"
)

object DashboardSummaryMapper {
    fun market(snapshot: MonitorSnapshot?): DashboardMarketUi {
        val indexByCode = snapshot?.marketIndices.orEmpty().associateBy(Quote::code)
        val tracked = snapshot?.quotes.orEmpty()
        return DashboardMarketUi(
            indices = listOf(
                index("上证指数", indexByCode["000001.SH"]),
                index("深证成指", indexByCode["399001.SZ"]),
                index("创业板指", indexByCode["399006.SZ"])
            ),
            turnover = listOfNotNull(indexByCode["000001.SH"]?.amount, indexByCode["399001.SZ"]?.amount)
                .takeIf { it.isNotEmpty() }?.sumOf(::normalizeIndexTurnover),
            risingCount = tracked.count { (it.changeRatio ?: 0.0) > 0.0 },
            fallingCount = tracked.count { (it.changeRatio ?: 0.0) < 0.0 },
            flatCount = tracked.count { it.changeRatio == 0.0 },
            breadthScope = "当前监控池",
            stale = snapshot?.dataHealth?.isStale == true
        )
    }

    fun account(
        snapshot: MonitorSnapshot?,
        positions: List<Position>,
        cashBalance: Double?,
        now: Long = System.currentTimeMillis()
    ): DashboardAccountUi {
        val quotes = snapshot?.quotes.orEmpty().associateBy(Quote::code)
        val preOpen = isBeforeContinuousTrading(now)
        val activePositions = positions.filter { it.shares > 0 }
        // 盘前集合竞价价格可能为0或短暂清空，市值统一以昨收作为稳定锚点。
        val priced = activePositions.mapNotNull { position ->
            val quote = quotes[position.code] ?: return@mapNotNull null
            val latest = quote.latest.validPrice()
            val previous = quote.previousClose.validPrice()
            val valuationPrice = if (preOpen) previous ?: latest else latest ?: previous
            valuationPrice?.let { position to it }
        }
        val marketValue = priced.sumOf { (position, price) -> position.shares * price }
        val cumulative = priced.takeIf { it.isNotEmpty() }?.sumOf { (position, price) -> position.shares * (price - position.cost) }
        val todayParts = if (preOpen) emptyList() else activePositions.mapNotNull { position ->
            val quote = quotes[position.code] ?: return@mapNotNull null
            val latest = quote.latest.validPrice() ?: return@mapNotNull null
            val previous = quote.previousClose.validPrice() ?: return@mapNotNull null
            position.shares * (latest - previous)
        }
        // 部分持仓缺价时不展示一个看似精确但实际少算的今日收益。
        val today = todayParts.takeIf { !preOpen && it.size == activePositions.size && it.isNotEmpty() }?.sum()
        val todayState = when {
            preOpen -> "PREOPEN"
            today == null && activePositions.isNotEmpty() -> "INCOMPLETE"
            else -> "READY"
        }
        val total = cashBalance?.let { it + marketValue }
        val ratio = if (total != null && total > 0.0) marketValue / total * 100.0 else snapshot?.positionRatio ?: 0.0
        return DashboardAccountUi(total, marketValue, today, cumulative, ratio, todayState)
    }

    private fun index(name: String, quote: Quote?) = MarketIndexUi(name, quote?.latest, quote?.changeRatio)

    /** 当前行情统一使用“元”；旧版 Room 缓存仍可能保存腾讯接口的“万元”字段。 */
    private fun normalizeIndexTurnover(value: Double): Double =
        if (value > 0.0 && value < 10_000_000_000.0) value * 10_000.0 else value

    private fun Double?.validPrice(): Double? = this?.takeIf { it.isFinite() && it > 0.0 }

    private fun isBeforeContinuousTrading(epochMs: Long): Boolean {
        val dateTime = Instant.ofEpochMilli(epochMs).atZone(CHINA_ZONE)
        if (dateTime.dayOfWeek == DayOfWeek.SATURDAY || dateTime.dayOfWeek == DayOfWeek.SUNDAY) return false
        return dateTime.toLocalTime().isBefore(LocalTime.of(9, 30))
    }

    private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
}
