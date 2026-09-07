package com.locogo.astockguard.ui.dashboard

import com.locogo.astockguard.data.local.ImportedAccountMetricEntity
import com.locogo.astockguard.integration.ths.ThsAccountMetric
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ImportedAccountPresenterTest {
    @Test fun `隔日导入收益标记历史且保留来源时间`() {
        val observed = Instant.parse("2026-09-07T07:00:00Z").toEpochMilli()
        val values = listOf(ImportedAccountMetricEntity("TODAY_PNL", -25.0, observed, observed, "THS_CONFIRMED"))
        val text = ImportedAccountPresenter.text(ThsAccountMetric.TODAY_PNL, values, observed + 86400000)!!
        assertTrue(text.startsWith("历史当日收益"))
        assertTrue(text.contains("同花顺导入"))
        assertTrue(text.contains("09-07 15:00"))
        assertTrue(ImportedAccountPresenter.text(ThsAccountMetric.TODAY_PNL, values, observed)!!.startsWith("今日收益"))
        assertNull(ImportedAccountPresenter.text(ThsAccountMetric.TOTAL_ASSETS, values, observed))
    }
}
