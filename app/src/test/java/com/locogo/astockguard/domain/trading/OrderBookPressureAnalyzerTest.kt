package com.locogo.astockguard.domain.trading

import com.locogo.astockguard.data.level2.Level2Level
import com.locogo.astockguard.data.level2.Level2Snapshot
import com.locogo.astockguard.data.level2.Level2Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBookPressureAnalyzerTest {
    private val now = 1_800_000_000_000L

    @Test
    fun realFreshBidBookWithActiveBuyingIsBidDominant() {
        val result = OrderBookPressureAnalyzer.analyze(
            snapshot(bids = 3000L, asks = 1000L, buyTrades = 800L, sellTrades = 200L),
            "000001.SZ",
            now
        )

        assertEquals(OrderBookPressure.BID_DOMINANT, result.status)
        assertTrue((result.imbalance ?: 0.0) > 0.18)
        assertTrue((result.activeBuyRatio ?: 0.0) > 0.52)
    }

    @Test
    fun realFreshAskBookWithActiveSellingIsAskDominant() {
        val result = OrderBookPressureAnalyzer.analyze(
            snapshot(bids = 1000L, asks = 3000L, buyTrades = 100L, sellTrades = 900L),
            "SZ000001",
            now
        )

        assertEquals(OrderBookPressure.ASK_DOMINANT, result.status)
    }

    @Test
    fun simulatedCachedAndExpiredBooksAreNeverActionableEvidence() {
        val base = snapshot(3000L, 1000L, 800L, 200L)

        assertEquals(OrderBookPressure.UNAVAILABLE, OrderBookPressureAnalyzer.analyze(base.copy(simulated = true), "000001.SZ", now).status)
        assertEquals(OrderBookPressure.UNAVAILABLE, OrderBookPressureAnalyzer.analyze(base.copy(stale = true), "000001.SZ", now).status)
        assertEquals(OrderBookPressure.UNAVAILABLE, OrderBookPressureAnalyzer.analyze(base.copy(updatedAt = now - 20_001L), "000001.SZ", now).status)
    }

    private fun snapshot(bids: Long, asks: Long, buyTrades: Long, sellTrades: Long) = Level2Snapshot(
        code = "000001.SZ",
        bids = List(5) { Level2Level(10.0 - it * 0.01, bids) },
        asks = List(5) { Level2Level(10.01 + it * 0.01, asks) },
        trades = listOf(
            Level2Trade("10:00:00", 10.01, buyTrades, "BUY"),
            Level2Trade("10:00:01", 10.00, sellTrades, "SELL")
        ),
        source = "LICENSED_HTTP",
        simulated = false,
        stale = false,
        updatedAt = now
    )
}
