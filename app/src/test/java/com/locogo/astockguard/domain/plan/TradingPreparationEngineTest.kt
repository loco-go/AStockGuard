package com.locogo.astockguard.domain.plan

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
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
}
