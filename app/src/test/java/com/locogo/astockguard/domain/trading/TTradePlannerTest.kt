package com.locogo.astockguard.domain.trading

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import com.locogo.astockguard.data.fundflow.FundFlowPoint
import com.locogo.astockguard.data.fundflow.StockFundFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TTradePlannerTest {
    private val position = Position("000001.SZ", "测试股票", 900, 98.0, "CORE")
    private val quote = Quote(code = "000001.SZ", name = "测试股票", latest = 100.0, vwap = 100.0)

    @Test
    fun staleDataNeverProducesActionablePlan() {
        val plan = TTradePlanner.plan(
            quote = quote,
            position = position,
            minuteBars = volatileBars(),
            fundFlow = null,
            marketPhase = "M2",
            dataStale = true
        )
        assertEquals("BLOCKED_STALE", plan.status)
        assertTrue(!plan.actionable)
    }

    @Test
    fun noBasePositionBlocksIntradayT() {
        val plan = TTradePlanner.plan(
            quote = quote,
            position = null,
            minuteBars = volatileBars(),
            fundFlow = null,
            marketPhase = "M2",
            dataStale = false
        )
        assertEquals("NO_BASE_POSITION", plan.status)
    }

    @Test
    fun insufficientAmplitudeReturnsNoT() {
        val flat = (0 until 12).map { index ->
            MinuteBar(
                time = "10:${index.toString().padStart(2, '0')}",
                price = 100.0 + if (index % 2 == 0) 0.05 else -0.05,
                avgPrice = 100.0,
                high = 100.10,
                low = 99.90,
                volume = 10_000.0,
                amount = 1_000_000.0
            )
        }
        val plan = TTradePlanner.plan(quote, position, flat, null, "M2", false)
        assertEquals("NO_T", plan.status)
    }

    @Test
    fun manualBuyAnchorProducesHigherSellZoneAndPositiveEdge() {
        val plan = TTradePlanner.plan(
            quote = quote,
            position = position,
            minuteBars = volatileBars(),
            fundFlow = null,
            marketPhase = "M2",
            dataStale = false,
            manualAnchorPrice = 99.0
        )
        assertTrue(plan.buyZoneLow > 0.0)
        assertTrue(plan.sellZoneLow > plan.buyZoneHigh)
        assertTrue(plan.expectedEdgePct > 0.0)
        assertEquals(99.0, plan.manualAnchorPrice ?: 0.0, 0.0001)
        assertTrue(plan.suggestedQuantity in 100..300)
    }

    @Test
    fun defensiveMarketCapsTQuantityAtOneLot() {
        val plan = TTradePlanner.plan(
            quote = quote.copy(latest = 100.0),
            position = position.copy(shares = 1800),
            minuteBars = volatileBars(),
            fundFlow = null,
            marketPhase = "M1",
            dataStale = false,
            manualAnchorPrice = 99.0
        )
        assertEquals(100, plan.suggestedQuantity)
    }

    @Test
    fun positionCategoryControlsMaximumTQuantity() {
        val longPlan = TTradePlanner.plan(
            quote, position.copy(shares = 3000, role = "LONG"), volatileBars(), null, "M2", false, 99.0
        )
        val attackPlan = TTradePlanner.plan(
            quote, position.copy(shares = 3000, role = "ATTACK"), volatileBars(), null, "M2", false, 99.0
        )

        assertEquals(300, longPlan.suggestedQuantity)
        assertEquals(1000, attackPlan.suggestedQuantity)
        assertTrue(attackPlan.suggestedQuantity > longPlan.suggestedQuantity)
    }

    @Test
    fun sustainedFreshInflowExtendsProfitTarget() {
        val now = LocalDate.of(2026, 9, 1).atTime(LocalTime.of(10, 20))
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val flow = StockFundFlow(
            code = position.code,
            minute = listOf(0.0, 100.0, 220.0, 360.0, 520.0).mapIndexed { index, net ->
                FundFlowPoint("10:${(16 + index).toString().padStart(2, '0')}", net, 0.0, 0.0, 0.0, 0.0)
            },
            periods = emptyList(), source = "EASTMONEY", stale = false,
            minuteStale = false, dailyStale = false
        )
        val baseline = TTradePlanner.plan(quote, position, volatileBars(), null, "M2", false, 99.0, now)
        val enhanced = TTradePlanner.plan(quote, position, volatileBars(), flow, "M2", false, 99.0, now)

        assertEquals("SUSTAINED_INFLOW", enhanced.fundFlowStatus)
        assertEquals("EXTEND_PROFIT", enhanced.profitMode)
        assertTrue(enhanced.expectedEdgePct > baseline.expectedEdgePct)
    }

    @Test
    fun expiredMinuteFlowNeverParticipatesInLiveTPlan() {
        val now = LocalDate.of(2026, 9, 1).atTime(LocalTime.of(14, 0))
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val flow = StockFundFlow(
            code = position.code,
            minute = listOf(0.0, 100.0, 200.0, 300.0).mapIndexed { index, net ->
                FundFlowPoint("10:0$index", net, 0.0, 0.0, 0.0, 0.0)
            },
            periods = emptyList(), source = "EASTMONEY", stale = false,
            minuteStale = false, dailyStale = false
        )

        val plan = TTradePlanner.plan(quote, position, volatileBars(), flow, "M2", false, 99.0, now)

        assertEquals("UNAVAILABLE", plan.fundFlowStatus)
    }

    private fun volatileBars(): List<MinuteBar> {
        val prices = listOf(99.2, 98.8, 98.5, 98.9, 99.4, 99.8, 100.1, 100.4, 100.8, 101.2, 101.5, 100.9)
        return prices.mapIndexed { index, price ->
            MinuteBar(
                time = "10:${(index + 10).toString().padStart(2, '0')}",
                price = price,
                avgPrice = 100.0,
                high = price + 0.12,
                low = price - 0.12,
                volume = 20_000.0 + index * 500,
                amount = 2_000_000.0 + index * 50_000
            )
        }
    }
}
