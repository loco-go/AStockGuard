package com.locogo.astockguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketRepositoryDailyBarTest {
    private val today = LocalDate.of(2026, 9, 1)

    @Test
    fun appendsTodayGreenCandleWhenHistoryOnlyEndsYesterday() {
        val history = listOf(DailyBar("2026-08-31", 53.50, 55.88, 55.88, 52.22, 1_279_165.0))
        val quote = Quote(
            code = "000636.SZ",
            open = 55.59,
            latest = 52.15,
            high = 56.09,
            low = 52.12,
            volume = 861_204.0
        )

        val result = MarketRepository.mergeRealtimeDailyBar(history, quote, today)

        assertEquals(2, result.size)
        assertEquals("2026-09-01", result.last().date)
        assertEquals(55.59, result.last().open, 0.0001)
        assertEquals(52.15, result.last().close, 0.0001)
        assertTrue(result.last().close < result.last().open)
    }

    @Test
    fun replacesStaleTodayCandleInsteadOfCreatingDuplicate() {
        val history = listOf(DailyBar("2026-09-01", 55.59, 56.00, 56.00, 55.50, 100.0))
        val quote = Quote("000636.SZ", open = 55.59, latest = 52.15, high = 56.09, low = 52.12, volume = 861_204.0)

        val result = MarketRepository.mergeRealtimeDailyBar(history, quote, today)

        assertEquals(1, result.size)
        assertEquals(52.15, result.single().close, 0.0001)
        assertTrue(result.single().close < result.single().open)
    }

    @Test
    fun missingRealtimeOpenKeepsVerifiedHistoryUntouched() {
        val history = listOf(DailyBar("2026-08-31", 53.50, 55.88, 55.88, 52.22, 1_279_165.0))

        val result = MarketRepository.mergeRealtimeDailyBar(history, Quote("000636.SZ", latest = 52.15), today)

        assertEquals(history, result)
    }
}
