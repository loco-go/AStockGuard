package com.locogo.astockguard.domain.quality

import com.locogo.astockguard.DataHealth
import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Quote
import com.locogo.astockguard.data.level2.Level2Snapshot
import com.locogo.astockguard.data.news.NewsRiskAssessment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataQualityEvaluatorTest {
    @Test
    fun `实时行情和远端分时同时可用时允许实时策略`() {
        val report = evaluate(minuteFromCache = false)

        assertTrue(report.realtimeReady)
    }

    @Test
    fun `缓存分时必须阻止实时策略`() {
        val report = evaluate(minuteFromCache = true)

        assertFalse(report.realtimeReady)
        assertTrue(report.blockingReasons.any { it.contains("分时") })
    }

    @Test
    fun `模拟Level2不能标记为实时可用但不阻断基础行情`() {
        val report = evaluate(
            minuteFromCache = false,
            level2 = Level2Snapshot(code = "000001.SZ", source = "MOCK", simulated = true)
        )

        assertTrue(report.realtimeReady)
        assertFalse(report.items.first { it.name.contains("Level-2") }.usableForRealtime)
    }

    private fun evaluate(
        minuteFromCache: Boolean,
        level2: Level2Snapshot? = null
    ) = DataQualityEvaluator.evaluate(
        snapshot = snapshot(stale = false),
        minuteCount = 120,
        minuteFromCache = minuteFromCache,
        minuteHistorical = false,
        stockFundFlow = null,
        level2 = level2,
        news = NewsRiskAssessment()
    )

    private fun snapshot(stale: Boolean) = MonitorSnapshot(
        updatedAt = 1L,
        quotes = listOf(Quote("000001.SZ", latest = 10.0)),
        assessment = MarketAssessment("E0", "M2", 0.7, 0.0, 0.0, 0.0, "", emptyList()),
        positionRatio = 0.0,
        dataHealth = DataHealth(if (stale) "ROOM_CACHE" else "TENCENT", stale, "test")
    )
}
