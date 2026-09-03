package com.locogo.astockguard.domain.indicator

/*
 * 文件职责：验证 TechnicalIndicatorSet 的正常、拒绝、边界与历史回归场景。
 * 架构边界：固定时间、证券代码和单位，避免测试依赖运行当天；业务口径有意变化时同步说明预期。
 * 维护说明：断言优先保护公开业务语义，而不是私有实现步骤。
 */

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

    @Test
    fun `atr uses gaps and keeps warmup explicit`() {
        val candles = listOf(
            StockKLine(1, "1", 10.0, 10.5, 9.5, 10.0, 100.0),
            StockKLine(2, "2", 11.0, 11.5, 10.8, 11.2, 100.0),
            StockKLine(3, "3", 11.1, 11.4, 10.9, 11.0, 100.0)
        )

        val atr = TechnicalIndicatorSet.atr(candles, 2)

        assertNull(atr.first())
        assertEquals(1.25, atr[1]!!, 0.0001)
        assertEquals(1.0, atr[2]!!, 0.0001)
    }

    private fun candle(index: Int, close: Double, volume: Double) = StockKLine(
        timestamp = index.toLong(), date = index.toString(), open = close - 0.1,
        high = close + 0.2, low = close - 0.2, close = close, volume = volume
    )
}
