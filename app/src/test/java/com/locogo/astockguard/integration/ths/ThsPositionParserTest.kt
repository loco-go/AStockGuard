package com.locogo.astockguard.integration.ths

import com.locogo.astockguard.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThsPositionParserTest {
    @Test
    fun `解析标签分离的持仓节点`() {
        val result = ThsPositionParser.parse(
            listOf(
                "我的持仓", "平安银行 000001", "持仓数量", "1200", "可用余额", "1000",
                "成本价", "10.25", "现价", "10.60"
            )
        )

        assertEquals(1, result.size)
        assertEquals("000001.SZ", result.single().code)
        assertEquals("平安银行", result.single().name)
        assertEquals(1200, result.single().shares)
        assertEquals(10.25, result.single().cost, 0.0001)
    }

    @Test
    fun `解析表格顺序的多只持仓`() {
        val result = ThsPositionParser.parse(
            listOf(
                "持仓", "证券名称", "证券代码", "股票余额", "可用余额", "成本价", "现价",
                "平安银行", "000001", "1200", "1000", "10.25", "10.60",
                "贵州茅台", "600519", "100", "0", "1600.50", "1598.00"
            )
        )

        assertEquals(2, result.size)
        assertEquals(1200, result.first { it.code == "000001.SZ" }.shares)
        assertEquals(1600.50, result.first { it.code == "600519.SH" }.cost, 0.0001)
    }

    @Test
    fun `解析合并为单节点的持仓描述`() {
        val result = ThsPositionParser.parse(
            listOf("持仓", "平安银行000001持仓数量1200成本价10.25现价10.60")
        )

        assertEquals(1, result.size)
        assertEquals("平安银行", result.single().name)
        assertEquals(1200, result.single().shares)
        assertEquals(10.25, result.single().cost, 0.0001)
    }

    @Test
    fun `合并持仓时保留角色与未显示记录`() {
        val existing = listOf(
            Position("000001.SZ", "旧名称", 100, 9.0, "ATTACK"),
            Position("000002.SZ", "万科A", 200, 8.0, "LONG")
        )
        val incoming = listOf(ParsedThsPosition("000001.SZ", "平安银行", 1200, 10.25))

        val merged = ThsPositionMerger.merge(existing, incoming)

        assertEquals("ATTACK", merged.first { it.code == "000001.SZ" }.role)
        assertEquals(1200, merged.first { it.code == "000001.SZ" }.shares)
        assertTrue(merged.any { it.code == "000002.SZ" && it.role == "LONG" })
    }
}
