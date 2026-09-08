package com.locogo.astockguard.ui.dashboard

import com.locogo.astockguard.data.local.ImportedAccountMetricEntity
import com.locogo.astockguard.integration.ths.ThsAccountMetric
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ImportedAccountPresenterTest {
    @Test fun `首页金额只显示两行且跨日标明历史`() {
        val observed = Instant.parse("2026-09-07T07:00:00Z").toEpochMilli()
        val values = listOf(ImportedAccountMetricEntity("TODAY_PNL", -25.0, observed, observed, "THS_CONFIRMED"))
        val text = ImportedAccountPresenter.text(ThsAccountMetric.TODAY_PNL, values, observed + 86400000)!!
        assertTrue(text.startsWith("历史当日收益"))
        assertEquals("历史当日收益\n-25.00", text)
        assertEquals(2, text.lines().size)
        assertFalse(text.contains("同花顺导入"))
        assertFalse(text.contains("快照"))
        assertTrue(ImportedAccountPresenter.text(ThsAccountMetric.TODAY_PNL, values, observed)!!.startsWith("今日收益"))
        assertNull(ImportedAccountPresenter.text(ThsAccountMetric.TOTAL_ASSETS, values, observed))
        val position = values.single().copy(metric = "POSITION_PCT", value = 65.8)
        assertEquals("当前仓位\n65.80%", ImportedAccountPresenter.text(ThsAccountMetric.POSITION_PCT, listOf(position), observed))
    }
}
