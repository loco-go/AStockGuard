package com.locogo.astockguard.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEngineTest {
    @Test
    fun insufficientHistoryReturnsEmptyStats() {
        val candles = (1..20).map { i ->
            StockKLine(
                timestamp = i.toLong(),
                date = "2026-01-${i.toString().padStart(2, '0')}",
                open = 10.0,
                high = 10.2,
                low = 9.8,
                close = 10.0,
                volume = 1_000.0
            )
        }

        assertEquals(ReplayStats(0.0, 0.0, 0.0, 0), ReplayEngine.run(candles, warmup = 30))
    }

    @Test
    fun replayProducesFiniteBoundedStatistics() {
        val candles = (0 until 90).map { i ->
            val trend = 10.0 + i * 0.05
            val wave = if (i % 12 < 6) 0.25 else -0.15
            val close = trend + wave
            StockKLine(
                timestamp = i.toLong(),
                date = "2026-${((i / 28) + 1).toString().padStart(2, '0')}-${((i % 28) + 1).toString().padStart(2, '0')}",
                open = close - 0.05,
                high = close + 0.2,
                low = close - 0.2,
                close = close,
                volume = 10_000.0 + i * 100.0
            )
        }

        val stats = ReplayEngine.run(candles)

        assertTrue(stats.returnRatio.isFinite())
        assertTrue(stats.maxDrawdown in 0.0..1.0)
        assertTrue(stats.winRate in 0.0..1.0)
        assertTrue(stats.trades >= 0)
    }
}
