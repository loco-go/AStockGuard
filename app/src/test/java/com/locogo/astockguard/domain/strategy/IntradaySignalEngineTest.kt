package com.locogo.astockguard.domain.strategy

import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.data.fundflow.FundFlowPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntradaySignalEngineTest {
    @Test
    fun emitsConfirmedBuyAfterMaAndAveragePriceCross() {
        val closes = listOf(10.0, 10.0, 10.0, 9.9, 9.8, 9.7, 10.5, 10.4)
        val candles = closes.mapIndexed { index, close -> candle(index, close) }

        val signals = IntradaySignalEngine.evaluate(candles, dataIsFresh = true)

        val triggered = signals.filterNot { it.recommended }
        assertEquals(1, triggered.size)
        assertEquals(ChartSignalAction.BUY, triggered.single().action)
        assertEquals("10:05", triggered.single().time)
        assertEquals(setOf(ChartSignalAction.BUY, ChartSignalAction.SELL),
            signals.filter { it.recommended }.map { it.action }.toSet())
    }

    @Test
    fun neverEmitsActionableMarkersForStaleMinuteData() {
        val closes = listOf(10.0, 10.0, 10.0, 9.9, 9.8, 9.7, 10.5, 10.4)
        val candles = closes.mapIndexed { index, close -> candle(index, close) }

        assertTrue(IntradaySignalEngine.evaluate(candles, dataIsFresh = false).isEmpty())
    }

    @Test
    fun emitsConfirmedSellAfterMaAndAveragePriceBreak() {
        val closes = listOf(10.0, 10.0, 10.0, 10.1, 10.2, 10.3, 9.5, 9.6)
        val candles = closes.mapIndexed { index, close -> candle(index, close) }

        val signals = IntradaySignalEngine.evaluate(candles, dataIsFresh = true)

        val triggered = signals.filterNot { it.recommended }
        assertEquals(1, triggered.size)
        assertEquals(ChartSignalAction.SELL, triggered.single().action)
    }

    @Test
    fun positiveMainFlowConfirmsBuyWhenVolumeIsNotExpanded() {
        val closes = listOf(10.0, 10.0, 10.0, 9.9, 9.8, 9.7, 10.5, 10.4)
        val candles = closes.mapIndexed { index, close -> candle(index, close).copy(volume = 100.0) }
        val flow = candles.mapIndexed { index, candle ->
            FundFlowPoint(candle.time, index * 100.0, 0.0, 0.0, 0.0, 0.0)
        }

        val triggered = IntradaySignalEngine.evaluate(candles, dataIsFresh = true, fundFlow = flow)
            .filterNot { it.recommended }

        assertEquals(1, triggered.size)
        assertTrue(triggered.single().reason.contains("主力净流增量"))
    }

    @Test
    fun distributesSeveralRecommendationZonesAcrossTheSession() {
        val candles = List(48) { index -> candle(index, 10.0 + (index % 6) * 0.01) }

        val recommendations = IntradaySignalEngine.evaluate(candles, dataIsFresh = true)
            .filter { it.recommended }

        assertEquals(10, recommendations.size)
        assertEquals(5, recommendations.map { it.time }.distinct().size)
    }

    private fun candle(index: Int, close: Double) = MinuteCandle(
        time = "%02d:%02d".format(9 + (30 + index * 5) / 60, (30 + index * 5) % 60),
        open = close,
        high = close,
        low = close,
        close = close,
        average = 10.0,
        volume = if (index >= 6) 150.0 else 100.0
    )
}
