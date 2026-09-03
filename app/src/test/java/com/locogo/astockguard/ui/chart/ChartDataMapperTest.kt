package com.locogo.astockguard.ui.chart

/*
 * 文件职责：验证 ChartDataMapper 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.chart.ChartPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDataMapperTest {
    private val daily = listOf(
        DailyBar("2026-08-03", 10.0, 11.0, 11.5, 9.8, 100.0),
        DailyBar("2026-08-04", 11.0, 12.0, 12.5, 10.8, 120.0),
        DailyBar("2026-08-10", 12.0, 11.5, 12.2, 11.0, 130.0)
    )

    @Test
    fun mapsDailyOhlcvWithoutChangingValues() {
        val mapped = ChartDataMapper.aggregate(daily, ChartPeriod.DAY)
        assertEquals(3, mapped.size)
        assertEquals(10.0, mapped.first().open, 0.0)
        assertEquals(11.5, mapped.first().high, 0.0)
        assertEquals(9.8, mapped.first().low, 0.0)
        assertEquals(11.0, mapped.first().close, 0.0)
    }

    @Test
    fun aggregatesWeekWithCorrectOhlcvSemantics() {
        val mapped = ChartDataMapper.aggregate(daily, ChartPeriod.WEEK)
        assertEquals(2, mapped.size)
        assertEquals(10.0, mapped.first().open, 0.0)
        assertEquals(12.5, mapped.first().high, 0.0)
        assertEquals(9.8, mapped.first().low, 0.0)
        assertEquals(12.0, mapped.first().close, 0.0)
        assertEquals(220.0, mapped.first().volume, 0.0)
    }

    @Test
    fun minuteUsesDedicatedSeriesInsteadOfCandles() {
        assertTrue(ChartDataMapper.aggregate(daily, ChartPeriod.MINUTE).isEmpty())
    }

    @Test
    fun aggregatesMinuteQuotesIntoFiveMinuteOhlcvCandles() {
        val source = listOf(
            minute("09:30", 10.0, 100.0),
            minute("09:31", 10.2, 130.0),
            minute("09:32", 9.9, 180.0),
            minute("09:33", 10.1, 210.0),
            minute("09:34", 10.3, 250.0),
            minute("09:35", 10.4, 280.0)
        )

        val candles = ChartDataMapper.aggregateMinutes(source)

        assertEquals(2, candles.size)
        assertEquals(10.0, candles.first().open, 0.0)
        assertEquals(10.3, candles.first().close, 0.0)
        assertEquals(10.3, candles.first().high, 0.0)
        assertEquals(9.9, candles.first().low, 0.0)
        assertEquals(250.0, candles.first().volume, 0.0)
        assertEquals(30.0, candles.last().volume, 0.0)
    }

    private fun minute(time: String, price: Double, volume: Double) =
        MinuteBar(time, price, 10.0, price, price, volume, price * volume)
}
