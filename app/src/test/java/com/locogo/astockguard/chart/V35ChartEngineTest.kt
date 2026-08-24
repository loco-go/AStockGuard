package com.locogo.astockguard.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V35ChartEngineTest {

    @Test
    fun `moving average returns expected rolling values`() {
        val result = TechnicalIndicators.ma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), result)
    }

    @Test
    fun `volume ratio is safe for zero average`() {
        assertEquals(0.0, TechnicalIndicators.volumeRatio(100.0, 0.0), 0.0)
        assertEquals(2.0, TechnicalIndicators.volumeRatio(200.0, 100.0), 0.0001)
    }

    @Test
    fun `weekly aggregation preserves ohlcv semantics`() {
        val input = listOf(
            bar("2026-08-17", 10.0, 12.0, 9.0, 11.0, 100.0),
            bar("2026-08-18", 11.0, 13.0, 10.0, 12.0, 120.0),
            bar("2026-08-24", 12.0, 14.0, 11.0, 13.0, 130.0)
        )
        val weekly = KLineAggregator.weekly(input)
        assertEquals(2, weekly.size)
        assertEquals(10.0, weekly[0].open, 0.0)
        assertEquals(12.0, weekly[0].close, 0.0)
        assertEquals(13.0, weekly[0].high, 0.0)
        assertEquals(9.0, weekly[0].low, 0.0)
        assertEquals(220.0, weekly[0].volume, 0.0)
    }

    @Test
    fun `monthly aggregation splits calendar months`() {
        val input = listOf(
            bar("2026-07-31", 10.0, 11.0, 9.0, 10.5, 100.0),
            bar("2026-08-03", 10.5, 12.0, 10.0, 11.5, 150.0),
            bar("2026-08-04", 11.5, 13.0, 11.0, 12.5, 200.0)
        )
        val monthly = KLineAggregator.monthly(input)
        assertEquals(2, monthly.size)
        assertEquals(10.5, monthly[1].open, 0.0)
        assertEquals(12.5, monthly[1].close, 0.0)
        assertEquals(350.0, monthly[1].volume, 0.0)
    }

    @Test
    fun `strategy emits bounded score and actionable zones`() {
        val candles = (0 until 60).map { i ->
            val base = 10.0 + i * 0.2
            StockKLine(
                timestamp = i.toLong(),
                date = "2026-08-${(i % 28 + 1).toString().padStart(2, '0')}",
                open = base - 0.05,
                high = base + 0.2,
                low = base - 0.2,
                close = base,
                volume = 1_000.0 + i * 30
            )
        }
        val result = StrategyEngine.evaluate(candles)
        assertTrue(result.score in 0..100)
        assertTrue(result.action in setOf("BUY", "HOLD", "SELL"))
        assertTrue(result.reasons.isNotEmpty())
        if (result.action != "SELL") assertNotNull(result.buyZone)
    }

    @Test
    fun `replay returns finite normalized statistics`() {
        val candles = (0 until 90).map { i ->
            val wave = if ((i / 15) % 2 == 0) i % 15 else 15 - (i % 15)
            val close = 20.0 + wave * 0.4
            StockKLine(
                timestamp = i.toLong(),
                date = "D$i",
                open = close - 0.1,
                high = close + 0.2,
                low = close - 0.2,
                close = close,
                volume = 1_000.0 + (i % 10) * 100
            )
        }
        val stats = ReplayEngine.run(candles)
        assertTrue(stats.returnRatio.isFinite())
        assertTrue(stats.maxDrawdown in 0.0..1.0)
        assertTrue(stats.winRate in 0.0..1.0)
        assertTrue(stats.trades >= 0)
    }

    private fun bar(date: String, open: Double, high: Double, low: Double, close: Double, volume: Double) =
        StockKLine(
            timestamp = date.hashCode().toLong(),
            date = date,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )
}
