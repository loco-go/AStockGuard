package com.locogo.astockguard.integration.ths

import com.locogo.astockguard.data.local.ImportedAccountMetricEntity
import org.junit.Assert.*
import org.junit.Test

class ThsAccountImportStateTest {
    @Test fun `无候选时清空后仍有四个手动补全入口`() {
        val rows = reconcileThsAccountRows(emptyList(), emptyList(), emptyList())
        assertEquals(ThsAccountMetric.entries.toList(), rows.map { it.metric })
        assertTrue(rows.none { it.selected || it.candidate != null })
    }

    @Test fun `重新进入时旧扫描不能替换较新的手工导入`() {
        val metric = ThsAccountMetric.TOTAL_ASSETS
        val saved = ImportedAccountMetricEntity(metric.name, 20000.0, 2000, 2001, "THS_MANUAL")
        val rows = reconcileThsAccountRows(listOf(ThsAccountCandidate(metric, 10000.0, 1000)), emptyList(), listOf(saved))
        val row = rows.first { it.metric == metric }
        assertEquals(20000.0, row.candidate!!.value, 0.001)
        assertEquals("THS_MANUAL", row.candidate!!.source)
        assertTrue(row.selected)
    }

    @Test fun `更新扫描保留取消勾选和未保存的人工修改`() {
        val metric = ThsAccountMetric.TODAY_PNL
        val old = ThsAccountImportRow(metric, ThsAccountCandidate(metric, -30.0, 1000, "THS_MANUAL"), false, false, true)
        val result = reconcileThsAccountRows(listOf(ThsAccountCandidate(metric, 50.0, 2000)), listOf(old), emptyList())
        assertEquals(old, result.first { it.metric == metric })
    }

    @Test fun `大额及小数回填数字输入框不使用科学计数法`() {
        assertEquals("10000000", accountAmountInput(10000000.0))
        assertEquals("-12345678.25", accountAmountInput(-12345678.25))
        assertEquals("0.0001", accountAmountInput(0.0001))
        assertEquals("", accountAmountInput(null))
    }
}
