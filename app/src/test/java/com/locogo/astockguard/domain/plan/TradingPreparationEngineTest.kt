package com.locogo.astockguard.domain.plan

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.data.ifind.IFindAuctionTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TradingPreparationEngineTest {
    @Test
    fun `短期套利仓在防守阶段给出降仓并限制T仓`() {
        val plans = PositionPlanEngine.evaluate(
            positions = listOf(Position("000001.SZ", "测试", 1000, 10.0, "ATTACK")),
            quotes = listOf(Quote("000001.SZ", latest = 9.5, ma5 = 9.8, ma10 = 9.9, r2Score = 40)),
            marketPhase = "M1",
            dataStale = false
        )

        assertEquals("短期套利", plans.single().category.label)
        assertEquals("反弹降仓", plans.single().action)
        assertEquals(100, plans.single().maxTQuantity)
    }

    @Test
    fun `强竞价仍要求开盘确认而不是直接买入`() {
        val daily = (1..10).map { DailyBar("2026-08-${it.toString().padStart(2, '0')}", 10.0, 10.0, 10.2, 9.8, 10_000.0) }
        val series = MarketRepository.AuctionSeries(
            LocalDate.of(2026, 9, 1),
            listOf(
                IFindAuctionTick("09:15", 10.10, 100.0, 1010.0),
                IFindAuctionTick("09:25", 10.35, 400.0, 4140.0)
            ),
            "IFIND_SNAPSHOT", true, "正常"
        )

        val plan = AuctionPlanEngine.evaluate("000001.SZ", "CORE", 10.0, daily, series)

        assertEquals("强竞价开盘确认", plan.status)
        assertTrue(plan.score >= 70)
        assertTrue(plan.reason.contains("禁止仅凭竞价直接追入"))
    }

    @Test
    fun `无真实竞价数据时不产生操作计划`() {
        val plan = AuctionPlanEngine.evaluate(
            "000001.SZ", "CORE", 10.0, emptyList(),
            MarketRepository.AuctionSeries(LocalDate.of(2026, 9, 1), emptyList(), "NONE", false, "无权限")
        )

        assertEquals("NO_DATA", plan.status)
        assertEquals("无权限", plan.reason)
    }

    @Test
    fun `仓位超限时短期套利仓优先减而保护趋势利润仓`() {
        val positions = listOf(
            Position("000001.SZ", "短线", 1000, 9.0, "ATTACK"),
            Position("000002.SZ", "利润仓", 1000, 8.0, "PROFIT")
        )
        val quotes = listOf(
            Quote("000001.SZ", latest = 10.0, changeRatio = -3.0, ma5 = 10.2, ma10 = 10.3),
            Quote("000002.SZ", latest = 10.0, changeRatio = 2.0, ma5 = 9.8, ma10 = 9.5)
        )
        val assessment = MarketAssessment("E2", "M1", 0.5, -3.0, 0.8, 0.2, "防守", emptyList())

        val plan = PortfolioExposureEngine.evaluate(positions, quotes, assessment, 90.0, false)

        assertEquals("REDUCE_EXPOSURE", plan.status)
        assertEquals("000001.SZ", plan.reductions.first().code)
        assertTrue(plan.actionable)
    }

    @Test
    fun `主升且仓位有空间时只给分批利润扩展计划`() {
        val assessment = MarketAssessment("E0", "M3", 0.85, 2.5, 0.2, 0.0, "主升", emptyList())
        val plan = PortfolioExposureEngine.evaluate(
            listOf(Position("000001.SZ", "趋势", 1000, 8.0, "PROFIT")),
            listOf(Quote("000001.SZ", latest = 10.0, ma5 = 9.8, ma10 = 9.5)),
            assessment, 60.0, false
        )

        assertEquals("PROFIT_EXPANSION", plan.status)
        assertTrue(plan.reductions.isEmpty())
        assertTrue(!plan.actionable)
    }
}
