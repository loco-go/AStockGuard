package com.locogo.astockguard.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class KLineAggregatorTest {

    @Test
    fun `aggregates daily candles into ISO weeks`() {
        val source = listOf(
            bar("2026-08-17", 10.0, 12.0, 9.0, 11.0, 100),
            bar("2026-08-18", 11.0, 13.0, 10.0, 12.0, 200),
            bar("2026-08-21", 12.0, 14.0, 11.0, 13.0, 300),
            bar("2026-08-24", 13.0, 15.0, 12.0, 14.0, 400)
        )

        val weeks = KLineAggregator.toWeek(source)

        assertEquals(2, weeks.size)
        assertEquals(10.0, weeks[0].open, 0.0)
        assertEquals(14.0, weeks[0].high, 0.0)
        assertEquals(9.0, weeks[0].low, 0.0)
        assertEquals(13.0, weeks[0].close, 0.0)
        assertEquals(600L, weeks[0].volume)
        assertEquals("2026-08-21", weeks[0].date)
    }

    @Test
    fun `aggregates daily candles into months`() {
        val source = listOf(
            bar("2026-07-31", 8.0, 9.0, 7.0, 8.5, 50),
            bar("2026-08-03", 10.0, 12.0, 9.0, 11.0, 100),
            bar("2026-08-31", 11.0, 13.0, 10.0, 12.0, 200)
        )

        val months = KLineAggregator.toMonth(source)

        assertEquals(2, months.size)
        assertEquals(10.0, months[1].open, 0.0)
        assertEquals(13.0, months[1].high, 0.0)
        assertEquals(9.0, months[1].low, 0.0)
        assertEquals(12.0, months[1].close, 0.0)
        assertEquals(300L, months[1].volume)
        assertEquals("2026-08-31", months[1].date)
    }

    private fun bar(
        date: String,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long
    ) = StockKLine(
        timestamp = 0L,
        date = date,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )
}
