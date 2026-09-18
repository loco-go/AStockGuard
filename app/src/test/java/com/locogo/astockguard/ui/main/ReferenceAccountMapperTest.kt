package com.locogo.astockguard.ui.main

import com.locogo.astockguard.*
import com.locogo.astockguard.data.local.ImportedAccountMetricEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** 防止新账户大号资产与环图把缺失数据展示成完整资产或零仓位。 */
class ReferenceAccountMapperTest {
    private val now = Instant.parse("2026-09-17T06:27:00Z").toEpochMilli()

    /** 任一持仓缺报价时不展示部分市值冒充总资产。 */
    @Test fun missingPositionQuoteHidesLocalTotalAndExposure() {
        val state = state().copy(positions = state().positions + Position("000002.SZ", "万科A", 100, 10.0, "CORE"))
        val summary = ReferenceAccountMapper.map(state, now)
        assertNull(summary.total)
        assertNull(summary.marketValue)
        assertNull(summary.positionPct)
        assertTrue(summary.source.contains("不完整"))
    }

    /** 有效核对快照优先展示，但不能据此补齐本地缺失的持仓市值。 */
    @Test fun confirmedSnapshotOverridesTotalWithoutInventingPositionValue() {
        val state = state().copy(snapshot = null, importedAccountMetrics = listOf(metric("TOTAL_ASSETS", 518320.26), metric("POSITION_PCT", 90.7)))
        val summary = ReferenceAccountMapper.map(state, now)
        assertEquals(518320.26, summary.total!!, 0.001)
        assertEquals(90.7, summary.positionPct!!, 0.001)
        assertNull(summary.marketValue)
        assertTrue(summary.source.contains("手工核对"))
    }

    /** 跨日导入收益沿用历史标题，避免新界面把快照当成今日收益。 */
    @Test fun previousDayImportedPnlKeepsHistoricalLabel() {
        val value = metric("TODAY_PNL", 128.0).copy(observedAt = now - 86_400_000)
        val summary = ReferenceAccountMapper.map(state().copy(importedAccountMetrics = listOf(value)), now)
        assertEquals("历史当日收益", summary.todayLabel)
        assertEquals(128.0, summary.today!!, 0.001)
    }

    /** 无现金配置时不能从默认零值推算完整资产或仓位。 */
    @Test fun unknownCashKeepsTotalAndPositionUnknown() {
        val summary = ReferenceAccountMapper.map(state().copy(cashBalance = null), now)
        assertNull(summary.total)
        assertNull(summary.positionPct)
        assertEquals(1200.0, summary.marketValue!!, 0.001)
    }

    /** 无效导入值不覆盖可核验的本地账户估值。 */
    @Test fun invalidImportFallsBackToCompleteLocalValuation() {
        val summary = ReferenceAccountMapper.map(state().copy(importedAccountMetrics = listOf(metric("TOTAL_ASSETS", Double.NaN))), now)
        assertEquals(2200.0, summary.total!!, 0.001)
        assertEquals(200.0, summary.holdingPnl!!, 0.001)
        assertEquals(20.0, summary.holdingReturn!!, 0.001)
    }

    /** 创建单只完整持仓的确定性样本。 */
    private fun state() = MainUiState(
        positions = listOf(Position("000001.SZ", "平安银行", 100, 10.0, "CORE", null)), cashBalance = 1000.0,
        snapshot = MonitorSnapshot(now, listOf(Quote("000001.SZ", latest = 12.0, previousClose = 11.0)), assessment = MarketAssessment("E0", "M2", 0.7, 0.0, 0.0, 0.0, "", emptyList()), positionRatio = 0.0)
    )

    /** 创建不触碰数据库的核对导入样本。 */
    private fun metric(key: String, value: Double) = ImportedAccountMetricEntity(key, value, now, now, "THS_MANUAL")
}
