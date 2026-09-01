package com.locogo.astockguard.domain.review

import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.data.local.AlertRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertOutcomeEvaluatorTest {
    @Test
    fun keepsAlertPendingUntilEnoughFutureBarsExist() {
        val candles = listOf(candle("10:00", 10.0), candle("10:05", 10.01))

        assertNull(AlertOutcomeEvaluator.evaluate(record(), candles))
    }

    @Test
    fun treatsSameBarStopAndTargetAsLossConservatively() {
        val candles = listOf(
            candle("10:00", 10.0),
            candle("10:05", 10.0, high = 10.06, low = 9.96)
        )

        val result = AlertOutcomeEvaluator.evaluate(record(), candles)

        assertEquals("LOSS", result?.status)
        assertTrue(result!!.netEdgePct < 0.0)
    }

    @Test
    fun evaluatesCompletedHorizonUsingNetEdgeAfterCosts() {
        val candles = buildList {
            add(candle("10:00", 10.0))
            repeat(6) { index -> add(candle("10:${(index + 1) * 5}", 10.01)) }
        }

        val result = AlertOutcomeEvaluator.evaluate(record(), candles)

        assertEquals("LOSS", result?.status)
        assertTrue(result!!.netEdgePct < 0.0)
    }

    private fun record() = AlertRecordEntity(
        alertKey = "key",
        code = "000001.SZ",
        name = "测试",
        signalAt = 1,
        signalDate = "2026-09-01",
        signalTime = "10:00",
        action = "BUY",
        price = 10.0,
        score = 80,
        strategyVersion = "V4.2.0",
        source = "REALTIME_NOTIFICATION",
        dataSource = "TENCENT_MINUTE",
        reason = "test"
    )

    private fun candle(time: String, close: Double, high: Double = close, low: Double = close) = MinuteCandle(
        time = time,
        open = close,
        high = high,
        low = low,
        close = close,
        average = close,
        volume = 1_000.0
    )
}
