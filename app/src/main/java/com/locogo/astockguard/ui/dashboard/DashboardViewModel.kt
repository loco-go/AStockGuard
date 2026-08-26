package com.locogo.astockguard.ui.dashboard

import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import com.locogo.astockguard.ui.main.MainViewModel

/** V4 name for the retained V3.5 business ViewModel during the staged migration. */
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
    val positionPct: Double
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
                .takeIf { it.isNotEmpty() }?.sum()?.times(10_000.0),
            risingCount = tracked.count { (it.changeRatio ?: 0.0) > 0.0 },
            fallingCount = tracked.count { (it.changeRatio ?: 0.0) < 0.0 },
            flatCount = tracked.count { it.changeRatio == 0.0 },
            breadthScope = "当前监控池",
            stale = snapshot?.dataHealth?.isStale == true
        )
    }

    fun account(snapshot: MonitorSnapshot?, positions: List<Position>, cashBalance: Double?): DashboardAccountUi {
        val quotes = snapshot?.quotes.orEmpty().associateBy(Quote::code)
        val priced = positions.mapNotNull { position -> quotes[position.code]?.latest?.let { position to it } }
        val marketValue = priced.sumOf { (position, price) -> position.shares * price }
        val cumulative = priced.takeIf { it.isNotEmpty() }?.sumOf { (position, price) -> position.shares * (price - position.cost) }
        val today = positions.mapNotNull { position ->
            val quote = quotes[position.code] ?: return@mapNotNull null
            val latest = quote.latest ?: return@mapNotNull null
            val previous = quote.previousClose ?: return@mapNotNull null
            position.shares * (latest - previous)
        }.takeIf { it.isNotEmpty() }?.sum()
        val total = cashBalance?.let { it + marketValue }
        val ratio = if (total != null && total > 0.0) marketValue / total * 100.0 else snapshot?.positionRatio ?: 0.0
        return DashboardAccountUi(total, marketValue, today, cumulative, ratio)
    }

    private fun index(name: String, quote: Quote?) = MarketIndexUi(name, quote?.latest, quote?.changeRatio)
}
