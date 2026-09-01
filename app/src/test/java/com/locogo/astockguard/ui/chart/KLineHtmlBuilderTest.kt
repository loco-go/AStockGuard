package com.locogo.astockguard.ui.chart

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.chart.MinuteCandle
import org.junit.Assert.assertTrue
import org.junit.Test

class KLineHtmlBuilderTest {
    @Test
    fun dailyCandlesExplicitlyCompareCloseWithCurrentOpen() {
        val html = KLineHtmlBuilder.build(
            listOf(DailyBar("2026-09-01", 10.0, 10.5, 10.6, 9.9, 1000.0))
        )

        assertTrue(html.contains("compareRule:'current_open'"))
        assertTrue(html.contains("upColor:'#d92d20'"))
        assertTrue(html.contains("downColor:'#039855'"))
        assertTrue(html.contains("noChangeColor:'#8a8f98'"))
    }

    @Test
    fun minuteCandlesUseNeutralColorWhenCloseEqualsOpen() {
        val html = KLineHtmlBuilder.buildMinute(
            listOf(MinuteCandle("09:30", 10.0, 10.1, 9.9, 10.0, 10.0, 100.0))
        )

        assertTrue(html.contains("r.close>r.open?'#d92d20'"))
        assertTrue(html.contains("r.close<r.open?'#039855':'#8a8f98'"))
    }
}
