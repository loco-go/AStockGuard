package com.locogo.astockguard.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {
    private fun candles(count: Int = 40): List<StockKLine> = (1..count).map { i ->
        val p = 10.0 + i * 0.1
        StockKLine(i.toLong(), "2026-01-${(i % 28 + 1).toString().padStart(2, '0')}", p - 0.05, p + 0.1, p - 0.1, p, 1000L + i * 20)
    }

    @Test fun macdHasOnePointPerClose() {
        val closes = candles().map { it.close }
        assertEquals(closes.size, StrategyEngine.macd(closes).size)
    }

    @Test fun rsiIsBounded() {
        StrategyEngine.rsi(candles().map { it.close }).forEach { assertTrue(it in 0.0..100.0) }
    }

    @Test fun bullishSeriesProducesValidDecision() {
        val result = StrategyEngine.evaluate(candles())
        assertTrue(result.score in 0..100)
        assertTrue(result.action in setOf("BUY", "HOLD", "SELL"))
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test fun replayProducesMetrics() {
        val result = ReplayEngine.run(candles(80))
        assertTrue(result.tradeCount >= 0)
        assertTrue(result.maxDrawdown <= 0.0)
        assertTrue(result.winRate in 0.0..1.0)
    }
}
