package com.locogo.astockguard.integration.ths

import org.junit.Assert.*
import org.junit.Test

class ThsAccountParserTest {
    @Test fun `四项账户数据支持负收益千分位万元与百分比`() {
        val result = ThsAccountParser.parse(listOf("今日收益", "-1,234.56", "累计收益：+2.5万", "仓位", "65.80%", "总资产(元)", "200,000.00"), 123L).associateBy { it.metric }
        assertEquals(4, result.size)
        assertEquals(-1234.56, result.getValue(ThsAccountMetric.TODAY_PNL).value, 0.001)
        assertEquals(25000.0, result.getValue(ThsAccountMetric.CUMULATIVE_PNL).value, 0.001)
        assertEquals(65.8, result.getValue(ThsAccountMetric.POSITION_PCT).value, 0.001)
        assertEquals(200000.0, result.getValue(ThsAccountMetric.TOTAL_ASSETS).value, 0.001)
        assertEquals(123L, result.values.first().observedAt)
    }

    @Test fun `隐藏缺失字段不填零也不把百分比当收益金额`() {
        assertTrue(ThsAccountParser.parse(listOf("今日收益", "--", "累计收益", "***", "总资产", "****", "仓位", "101%"), 1).isEmpty())
        assertNull(ThsAccountParser.parseValue(ThsAccountMetric.TODAY_PNL, "12.5%"))
        assertNull(ThsAccountParser.parseValue(ThsAccountMetric.TODAY_PNL, "12.5 %"))
        assertNull(ThsAccountParser.parseValue(ThsAccountMetric.TOTAL_ASSETS, "-1"))
        assertNull(ThsAccountParser.parseValue(ThsAccountMetric.TOTAL_ASSETS, "1,23"))
    }

    @Test fun `只读取账户区不导入个股盈亏或用持仓浮盈替代累计收益`() {
        val result = ThsAccountParser.parse(listOf("总资产", "1000", "持仓盈亏", "200", "持仓/可用", "今日收益", "999"), 1)
        assertEquals(listOf(ThsAccountMetric.TOTAL_ASSETS), result.map { it.metric })
    }

    @Test fun `零值有效同字段冲突拒绝猜测`() {
        assertEquals(65.8, ThsAccountParser.parse(listOf("仓位65.8%"), 1).single().value, 0.001)
        assertEquals(0.0, ThsAccountParser.parseValue(ThsAccountMetric.TODAY_PNL, "0.00")!!, 0.0)
        assertTrue(ThsAccountParser.parse(listOf("今日收益", "10", "当日盈亏", "20"), 1).isEmpty())
    }
}
