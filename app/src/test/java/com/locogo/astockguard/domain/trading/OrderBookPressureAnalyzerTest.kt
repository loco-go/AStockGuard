package com.locogo.astockguard.domain.trading

/*
 * 文件职责：验证 OrderBookPressureAnalyzer 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import com.locogo.astockguard.data.level2.Level2Level
import com.locogo.astockguard.data.level2.Level2Snapshot
import com.locogo.astockguard.data.level2.Level2Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBookPressureAnalyzerTest {
    private val now = 1_800_000_000_000L

    @Test
    fun realFreshBidBookWithActiveBuyingIsBidDominant() {
        val result = OrderBookPressureAnalyzer.analyze(
            snapshot(bids = 3000L, asks = 1000L, buyTrades = 800L, sellTrades = 200L),
            "000001.SZ",
            now
        )

        assertEquals(OrderBookPressure.BID_DOMINANT, result.status)
        assertTrue((result.imbalance ?: 0.0) > 0.18)
        assertTrue((result.activeBuyRatio ?: 0.0) > 0.52)
    }

    @Test
    fun realFreshAskBookWithActiveSellingIsAskDominant() {
        val result = OrderBookPressureAnalyzer.analyze(
            snapshot(bids = 1000L, asks = 3000L, buyTrades = 100L, sellTrades = 900L),
            "SZ000001",
            now
        )

        assertEquals(OrderBookPressure.ASK_DOMINANT, result.status)
    }

    @Test
    fun simulatedCachedAndExpiredBooksAreNeverActionableEvidence() {
        val base = snapshot(3000L, 1000L, 800L, 200L)

        assertEquals(OrderBookPressure.UNAVAILABLE, OrderBookPressureAnalyzer.analyze(base.copy(simulated = true), "000001.SZ", now).status)
        assertEquals(OrderBookPressure.UNAVAILABLE, OrderBookPressureAnalyzer.analyze(base.copy(stale = true), "000001.SZ", now).status)
        assertEquals(OrderBookPressure.UNAVAILABLE, OrderBookPressureAnalyzer.analyze(base.copy(updatedAt = now - 20_001L), "000001.SZ", now).status)
        val limited = OrderBookPressureAnalyzer.analyze(
            base.copy(source = "IFIND_HTTP_DEPTH_LIMITED"), "000001.SZ", now
        )
        assertEquals(OrderBookPressure.BID_DOMINANT, limited.status)
        assertEquals("SUPPORTING", limited.evidenceGrade)
        assertEquals("LIMITED_5", limited.persistence)
    }

    @Test
    fun twoConsecutiveBidSnapshotsConfirmPersistentBuyingPressure() {
        val previous = snapshot(2800L, 1000L, 700L, 300L).copy(
            updatedAt = now - 30_000L,
            receivedAt = now - 30_000L
        )
        val current = snapshot(3200L, 1000L, 800L, 200L).copy(receivedAt = now)

        val result = OrderBookPressureAnalyzer.analyze(current, "000001.SZ", now, listOf(previous))

        assertEquals(OrderBookPressure.BID_DOMINANT, result.status)
        assertEquals("PERSISTENT_BID", result.persistence)
        assertEquals(2, result.sampleCount)
    }

    @Test
    fun directionReversalDowngradesSingleFrameDominance() {
        val previousAsk = snapshot(1000L, 3200L, 200L, 800L).copy(
            updatedAt = now - 30_000L,
            receivedAt = now - 30_000L
        )
        val currentBid = snapshot(3200L, 1000L, 800L, 200L).copy(receivedAt = now)

        val result = OrderBookPressureAnalyzer.analyze(currentBid, "000001.SZ", now, listOf(previousAsk))

        assertEquals(OrderBookPressure.NEUTRAL, result.status)
        assertEquals("REVERSING", result.persistence)
    }

    private fun snapshot(bids: Long, asks: Long, buyTrades: Long, sellTrades: Long) = Level2Snapshot(
        code = "000001.SZ",
        bids = List(5) { Level2Level(10.0 - it * 0.01, bids) },
        asks = List(5) { Level2Level(10.01 + it * 0.01, asks) },
        trades = listOf(
            Level2Trade("10:00:00", 10.01, buyTrades, "BUY"),
            Level2Trade("10:00:01", 10.00, sellTrades, "SELL")
        ),
        source = "LICENSED_HTTP",
        simulated = false,
        stale = false,
        updatedAt = now,
        receivedAt = now
    )
}
