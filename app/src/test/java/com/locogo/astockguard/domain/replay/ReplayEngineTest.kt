package com.locogo.astockguard.domain.replay

import com.locogo.astockguard.MinuteBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEngineTest {
    private val engine = ReplayEngine()

    @Test
    fun flatRoundTripIsLossAfterRealTradingCosts() {
        val report = engine.runVwapReclaim(
            bars = listOf(bar("10:00", 10.0, 9.9), bar("10:01", 10.0, 10.1), bar("10:02", 10.0, 10.0)),
            rules = ReplayTradingRules(initialPosition = 100, tradeQuantity = 100),
            confirmBars = 1
        )

        assertEquals(1, report.closedTrades)
        assertEquals(0, report.wins)
        assertTrue(report.excessPnl < 0.0)
        assertTrue(report.totalFees >= 10.0)
        assertTrue(report.totalTax > 0.0)
        assertTrue(report.slippageCost > 0.0)
    }

    @Test
    fun keepsSameDayBuyOpenAtEndInsteadOfForcingIllegalSell() {
        val report = engine.runVwapReclaim(
            bars = listOf(bar("10:00", 10.0, 9.9), bar("10:01", 10.1, 10.0), bar("10:02", 10.2, 10.1)),
            rules = ReplayTradingRules(initialPosition = 100, tradeQuantity = 100),
            confirmBars = 1
        )

        assertEquals(0, report.closedTrades)
        assertEquals(100, report.unclosedQuantity)
        assertEquals(listOf("BUY"), report.trades.map { it.side })
    }

    @Test
    fun rejectsSellWhenThereIsNoOpeningSellablePosition() {
        val report = engine.runVwapReclaim(
            bars = listOf(bar("10:00", 10.0, 9.9), bar("10:01", 10.0, 10.1), bar("10:02", 10.0, 10.0)),
            rules = ReplayTradingRules(initialPosition = 0, tradeQuantity = 100),
            confirmBars = 1
        )

        assertEquals(0, report.closedTrades)
        assertEquals(100, report.unclosedQuantity)
        assertEquals(1, report.rejectedTrades)
    }

    private fun bar(time: String, price: Double, average: Double) = MinuteBar(
        time = time,
        price = price,
        avgPrice = average,
        high = price,
        low = price,
        volume = 1_000.0,
        amount = price * 1_000.0
    )
}
