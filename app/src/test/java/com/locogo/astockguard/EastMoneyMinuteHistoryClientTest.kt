package com.locogo.astockguard

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class EastMoneyMinuteHistoryClientTest {
    @Test
    fun parsesRequestedDateAndBuildsRunningAveragePrice() {
        val rows = listOf(
            "2026-08-26 15:00,9.9,10.0,10.1,9.8,20,20000,0,0,0,0",
            "2026-08-27 09:35,10.0,10.2,10.3,9.9,100,101000,0,0,0,0",
            "2026-08-27 09:40,10.2,10.4,10.5,10.1,50,52000,0,0,0,0"
        )

        val bars = EastMoneyMinuteHistoryClient().parseRows(rows, LocalDate.parse("2026-08-27"))

        assertEquals(2, bars.size)
        assertEquals("09:35", bars.first().time)
        assertEquals(10.3, bars.first().high, 0.0)
        assertEquals(10.1, bars.first().avgPrice, 0.0001)
    }
}
