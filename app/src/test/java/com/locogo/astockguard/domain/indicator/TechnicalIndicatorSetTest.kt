package com.locogo.astockguard.domain.indicator

import com.locogo.astockguard.chart.StockKLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalIndicatorSetTest {
    @Test
    fun `ma and vwap keep unavailable leading values explicit`() {
        val candles = (1..5).map { index -> candle(index, index.toDouble(), index * 100.0) }

        val ma = TechnicalIndicatorSet.ma(candles.map { it.close }, 3)
        val vwap = TechnicalIndicatorSet.vwap(candles, 3)

        assertNull(ma[1])
        assertEquals(2.0, ma[2]!!, 0.0001)
        assertNull(vwap[1])
        assertTrue(vwap[4]!! in 4.0..5.0)
    }

    @Test
    fun `macd rsi and kdj return aligned series`() {
        val candles = (1..40).map { index -> candle(index, 10.0 + index * 0.1, 1000.0) }
        val closes = candles.map { it.close }

        assertEquals(candles.size, TechnicalIndicatorSet.macd(closes).size)
        assertEquals(candles.size, TechnicalIndicatorSet.rsi(closes).size)
        assertEquals(candles.size, TechnicalIndicatorSet.kdj(candles).size)
        assertTrue(TechnicalIndicatorSet.rsi(closes).last()!! >= 99.0)
    }

    private fun candle(index: Int, close: Double, volume: Double) = StockKLine(
        timestamp = index.toLong(), date = index.toString(), open = close - 0.1,
        high = close + 0.2, low = close - 0.2, close = close, volume = volume
    )
}
