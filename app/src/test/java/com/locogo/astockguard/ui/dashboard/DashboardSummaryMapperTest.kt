package com.locogo.astockguard.ui.dashboard

import com.locogo.astockguard.DataHealth
import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DashboardSummaryMapperTest {
    private val position = Position("000001.SZ", "平安银行", 100, 10.0, "CORE")
    private val stock = Quote("000001.SZ", latest = 12.0, previousClose = 11.0, changeRatio = 9.09)
    private val snapshot = MonitorSnapshot(
        updatedAt = 1L,
        quotes = listOf(stock),
        marketIndices = listOf(
            Quote("000001.SH", latest = 3200.0, changeRatio = 1.0, amount = 100.0),
            Quote("399001.SZ", latest = 11000.0, changeRatio = -0.5, amount = 200.0),
            Quote("399006.SZ", latest = 2300.0, changeRatio = 0.3, amount = 50.0)
        ),
        assessment = MarketAssessment("E0", "M2", 0.7, 0.0, 0.0, 0.0, "", emptyList()),
        positionRatio = 60.0,
        dataHealth = DataHealth()
    )

    @Test
    fun calculatesAccountOnlyFromConfiguredRealValues() {
        val configured = DashboardSummaryMapper.account(snapshot, listOf(position), cashBalance = 800.0, now = tradingTime())
        assertEquals(2000.0, configured.totalAssets ?: 0.0, 0.0)
        assertEquals(1200.0, configured.marketValue, 0.0)
        assertEquals(100.0, configured.todayPnl ?: 0.0, 0.0)
        assertEquals(200.0, configured.cumulativePnl ?: 0.0, 0.0)
        assertEquals(60.0, configured.positionPct, 0.0)

        assertNull(DashboardSummaryMapper.account(snapshot, listOf(position), cashBalance = null, now = tradingTime()).totalAssets)
    }

    @Test
    fun reportsTrackedBreadthScopeWithoutClaimingWholeMarket() {
        val market = DashboardSummaryMapper.market(snapshot)
        assertEquals("当前监控池", market.breadthScope)
        assertEquals(1, market.risingCount)
        assertEquals(0, market.fallingCount)
        assertEquals(3_000_000.0, market.turnover ?: 0.0, 0.0)
    }

    @Test
    fun preOpenZeroQuoteUsesPreviousCloseAndDoesNotFakeTodayPnl() {
        val preOpenSnapshot = snapshot.copy(quotes = listOf(stock.copy(latest = 0.0, changeRatio = 0.0)))

        val account = DashboardSummaryMapper.account(
            preOpenSnapshot,
            listOf(position),
            cashBalance = 900.0,
            now = LocalDate.of(2026, 9, 2).atTime(LocalTime.of(9, 10)).atZone(CHINA_ZONE).toInstant().toEpochMilli()
        )

        assertEquals(1100.0, account.marketValue, 0.0)
        assertEquals(2000.0, account.totalAssets ?: 0.0, 0.0)
        assertNull(account.todayPnl)
        assertEquals("PREOPEN", account.todayPnlState)
    }

    @Test
    fun invalidPriceDuringTradingDoesNotProducePartialTodayPnl() {
        val invalid = snapshot.copy(quotes = listOf(stock.copy(latest = 0.0)))

        val account = DashboardSummaryMapper.account(invalid, listOf(position), 900.0, tradingTime())

        assertNull(account.todayPnl)
        assertEquals("INCOMPLETE", account.todayPnlState)
    }

    private fun tradingTime() = LocalDate.of(2026, 9, 2).atTime(LocalTime.of(10, 0))
        .atZone(CHINA_ZONE).toInstant().toEpochMilli()

    private companion object {
        val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
