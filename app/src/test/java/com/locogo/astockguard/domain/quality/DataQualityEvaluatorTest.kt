package com.locogo.astockguard.domain.quality

import com.locogo.astockguard.DataHealth
import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Quote
import com.locogo.astockguard.data.level2.Level2Snapshot
import com.locogo.astockguard.data.news.NewsRiskAssessment
import com.locogo.astockguard.data.fundflow.FundFlowSummary
import com.locogo.astockguard.data.fundflow.StockFundFlow
import org.junit.Assert.assertEquals
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

    @Test
    fun `iFinD五档不能冒充Level2实盘证据`() {
        val report = evaluate(
            minuteFromCache = false,
            level2 = Level2Snapshot(
                code = "000001.SZ",
                source = "IFIND_HTTP_DEPTH_LIMITED",
                message = "iFinD仅返回买5档/卖5档"
            )
        )

        val item = report.items.first { it.name.contains("Level-2") }
        assertFalse(item.usableForRealtime)
        assertEquals(DataQualityStatus.FRESH, item.status)
    }

    @Test
    fun `数据质量中心展示分时实际来源`() {
        val report = evaluate(minuteFromCache = false, minuteSource = "TENCENT_FALLBACK")

        assertEquals("TENCENT_FALLBACK", report.items.first { it.name == "当日分时" }.source)
    }

    @Test
    fun `日级资金刷新不能把缓存分钟资金误标为实时`() {
        val flow = StockFundFlow(
            "000001.SZ", emptyList(), listOf(FundFlowSummary(1, 1.0, 0.0, 0.0, 0.0, 0.0)),
            "EASTMONEY_MIXED", stale = false, minuteStale = true, dailyStale = false
        )
        val report = evaluate(minuteFromCache = false, stockFundFlow = flow)

        val item = report.items.first { it.name == "个股资金流" }
        assertFalse(item.usableForRealtime)
        assertTrue(item.message.contains("分钟资金流为缓存"))
    }

    private fun evaluate(
        minuteFromCache: Boolean,
        minuteSource: String = "TENCENT",
        stockFundFlow: StockFundFlow? = null,
        level2: Level2Snapshot? = null
    ) = DataQualityEvaluator.evaluate(
        snapshot = snapshot(stale = false),
        minuteCount = 120,
        minuteFromCache = minuteFromCache,
        minuteHistorical = false,
        minuteSource = minuteSource,
        stockFundFlow = stockFundFlow,
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
