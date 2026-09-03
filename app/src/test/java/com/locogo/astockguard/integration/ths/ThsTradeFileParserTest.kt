package com.locogo.astockguard.integration.ths

/*
 * 文件职责：验证 ThsTradeFileParser 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThsTradeFileParserTest {
    @Test
    fun parsesChineseCsvDeliveryNote() {
        val csv = """
            证券代码,买卖方向,成交价格,成交数量,成交日期,成交时间
            000001,买入,10.25,300,2026-08-22,10:15:30
            600000,卖出,12.80,100,2026-08-22,14:05:00
        """.trimIndent()

        val rows = ThsTradeFileParser.parse(csv)
        assertEquals(2, rows.size)
        assertEquals("000001.SZ", rows[0].code)
        assertEquals("BUY", rows[0].side)
        assertEquals(300, rows[0].quantity)
        assertEquals(10.25, rows[0].price, 0.0001)
        assertEquals("600000.SH", rows[1].code)
        assertEquals("SELL", rows[1].side)
    }

    @Test
    fun parsesTabSeparatedEnglishHeaders() {
        val tsv = "symbol\tside\tprice\tqty\tdate\ttime\n000938\tB\t51.20\t200\t20260822\t101530"
        val rows = ThsTradeFileParser.parse(tsv)
        assertEquals(1, rows.size)
        assertEquals("000938.SZ", rows.single().code)
        assertEquals("BUY", rows.single().side)
        assertEquals(200, rows.single().quantity)
    }

    @Test
    fun rejectsFilesWithoutRequiredColumns() {
        val text = "股票,金额\n000001,1000"
        assertTrue(ThsTradeFileParser.parse(text).isEmpty())
    }
}
