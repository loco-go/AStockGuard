package com.locogo.astockguard.domain.strategy

import com.locogo.astockguard.chart.StockKLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V4StrategyScorerTest {
    @Test
    fun `requires sixty candles`() {
        assertNull(V4StrategyScorer.evaluate(candles(59), 1.0, 2.0, true))
    }

    @Test
    fun `stale capital is excluded and never produces buy or sell`() {
        val result = V4StrategyScorer.evaluate(candles(80), 10_000.0, 20_000.0, false)

        assertNotNull(result)
        assertFalse(result!!.score.capitalAvailable)
        assertTrue(result.score.totalScore in 0..100)
        assertTrue(result.reasons.first().contains("V4.2.0"))
        assertTrue(result.reasons.any { it.contains("ATR") })
        assertEquals("V4.2.0", result.score.strategyVersion)
        assertEquals(80, result.score.dataCompletenessPct)
        assertEquals(listOf("TREND", "VOLUME", "CAPITAL", "POSITION"), result.score.factors.map { it.key })
        val signal = result.signal
        assertTrue(signal == null || signal.action == ChartSignalAction.RISK)
    }

    private fun candles(count: Int) = (0 until count).map { index ->
        val close = 10.0 + index * 0.02 + if (index % 5 == 0) -0.06 else 0.0
        StockKLine(
            timestamp = index.toLong(), date = index.toString(), open = close - 0.01,
            high = close + 0.05, low = close - 0.05, close = close,
            volume = 1000.0 + (index % 7) * 20.0
        )
    }
}
