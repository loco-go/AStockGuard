package com.locogo.astockguard.domain.plan

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExposureBacktestEngineTest {
    private val position = Position("000001.SZ", "测试", 1000, 10.0, "CORE")

    @Test
    fun insufficientHistoryDoesNotInventPerformance() {
        val report = ExposureBacktestEngine.evaluate(listOf(position), mapOf(position.code to bars(List(10) { 10.0 })))

        assertEquals("NO_DATA", report.status)
        assertEquals(0, report.sampleDays)
    }

    @Test
    fun fallingMarketProducesReductionSignalsAndSmallerDrawdown() {
        val prices = buildList {
            addAll(List(6) { 10.0 })
            repeat(30) { index -> add(10.0 * Math.pow(0.98, (index + 1).toDouble())) }
        }
        val report = ExposureBacktestEngine.evaluate(listOf(position), mapOf(position.code to bars(prices)))

        assertEquals("READY", report.status)
        assertTrue(report.reductionSignals > 0)
        assertTrue(report.strategyMaxDrawdownPct < report.benchmarkMaxDrawdownPct)
        assertTrue((report.reductionSuccessRatePct ?: 0.0) > 50.0)
    }

    @Test
    fun turnoverCostIsIncludedInReportedStrategyReturn() {
        val prices = (0 until 30).map { index -> if (index % 2 == 0) 10.0 else 9.75 }
        val noCost = ExposureBacktestEngine.evaluate(listOf(position), mapOf(position.code to bars(prices)), 0.0)
        val withCost = ExposureBacktestEngine.evaluate(listOf(position), mapOf(position.code to bars(prices)), 0.01)

        assertTrue(withCost.strategyReturnPct < noCost.strategyReturnPct)
    }

    private fun bars(prices: List<Double>): List<DailyBar> = prices.mapIndexed { index, close ->
        DailyBar(
            date = LocalDate.of(2026, 1, 1).plusDays(index.toLong()).toString(),
            open = close,
            close = close,
            high = close * 1.01,
            low = close * 0.99,
            volume = 1_000_000.0
        )
    }
}
