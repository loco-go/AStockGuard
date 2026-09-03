package com.locogo.astockguard.domain.strategy

/*
 * 文件职责：验证 IntradaySignalBacktester 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

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
        assertTrue(result.totalCosts > 0.0)
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

    @Test
    fun minimumCommissionCanTurnSmallPriceMoveIntoNetLoss() {
        val candles = listOf(
            candle("10:00", 10.00, 10.01, 9.99),
            candle("10:05", 10.00, 10.01, 9.99),
            candle("10:10", 10.01, 10.02, 9.99)
        )
        val signal = IntradayChartSignal("10:05", 10.00, ChartSignalAction.BUY, 80, "test")

        val result = IntradaySignalBacktester.run(
            candles,
            listOf(signal),
            IntradayBacktestRules(quantity = 100)
        )

        assertEquals(0, result.wins)
        assertTrue(result.averageEdgePct < 0.0)
    }

    private fun candle(time: String, close: Double, high: Double, low: Double) = MinuteCandle(
        time = time, open = close, high = high, low = low, close = close,
        average = 10.0, volume = 100.0
    )
}
