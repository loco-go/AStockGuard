package com.locogo.astockguard.chart

/*
 * 文件职责：验证 StrategyEngine 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {
    private fun candles(count: Int = 40): List<StockKLine> = (1..count).map { i ->
        val p = 10.0 + i * 0.1
        StockKLine(
            i.toLong(),
            "2026-01-${(i % 28 + 1).toString().padStart(2, '0')}",
            p - 0.05,
            p + 0.1,
            p - 0.1,
            p,
            1000.0 + i * 20.0
        )
    }

    @Test fun macdHasOnePointPerClose() {
        val closes = candles().map { it.close }
        assertEquals(closes.size, StrategyEngine.macd(closes).size)
    }

    @Test fun rsiIsBounded() {
        StrategyEngine.rsi(candles().map { it.close }).forEach { assertTrue(it in 0.0..100.0) }
    }

    @Test fun bullishSeriesProducesValidDecision() {
        val result = StrategyEngine.evaluate(candles())
        assertTrue(result.score in 0..100)
        assertTrue(result.action in setOf("BUY", "HOLD", "SELL"))
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test fun replayProducesMetrics() {
        val result = ReplayEngine.run(candles(80))
        assertTrue(result.trades >= 0)
        assertTrue(result.maxDrawdown in 0.0..1.0)
        assertTrue(result.winRate in 0.0..1.0)
        assertTrue(result.returnRatio.isFinite())
    }
}
