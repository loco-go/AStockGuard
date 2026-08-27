package com.locogo.astockguard.domain.strategy

import com.locogo.astockguard.chart.MinuteCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntradaySignalBacktesterTest {
    @Test
    fun countsTargetReachedAfterBuyAlertAsWin() {
        val candles = listOf(
            candle("10:00", 10.00, 10.01, 9.99),
            candle("10:05", 10.00, 10.03, 9.98),
            candle("10:10", 10.06, 10.07, 9.99),
            candle("10:15", 10.08, 10.09, 10.04)
        )
        val signal = IntradayChartSignal("10:05", 10.00, ChartSignalAction.BUY, 80, "test")

        val result = IntradaySignalBacktester.run(candles, listOf(signal))

        assertEquals(1, result.evaluated)
        assertEquals(1, result.wins)
        assertEquals(100.0, result.winRatePct, 0.001)
        assertTrue(result.averageEdgePct > 0.0)
    }

    @Test
    fun treatsSameBarStopAndTargetAsLossAndIgnoresRecommendations() {
        val candles = listOf(
            candle("10:00", 10.00, 10.01, 9.99),
            candle("10:05", 10.00, 10.01, 9.99),
            candle("10:10", 10.00, 10.06, 9.96)
        )
        val signal = IntradayChartSignal("10:05", 10.00, ChartSignalAction.BUY, 80, "test")
        val recommendation = signal.copy(recommended = true)

        val result = IntradaySignalBacktester.run(candles, listOf(signal, recommendation))

        assertEquals(1, result.signals)
        assertEquals(0, result.wins)
    }

    private fun candle(time: String, close: Double, high: Double, low: Double) = MinuteCandle(
        time = time, open = close, high = high, low = low, close = close,
        average = 10.0, volume = 100.0
    )
}
