package com.locogo.astockguard.integration.ths

import com.locogo.astockguard.Position
import org.junit.Assert.assertEquals
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
    fun `解析同花顺卡片式斜杠字段`() {
        val result = ThsPositionParser.parse(
            listOf(
                "持仓", "市值/盈亏", "持仓/可用", "成本/现价",
                "平安银行 000001", "12720.00/-320.00", "1200/1000", "10.25/10.60"
            )
        )

        assertEquals(1, result.size)
        assertEquals(1200, result.single().shares)
        assertEquals(10.25, result.single().cost, 0.0001)
    }

    @Test
    fun `按本地名称映射解析不含证券代码的真实持仓表`() {
        val result = ThsPositionParser.parse(
            texts = listOf(
                "持仓股", "市值", "盈亏", "持仓/可用", "成本/现价",
                "平安银行", "12,720.00", "-320.00", "-2.45%", "1200", "1000", "10.25", "10.60"
            ),
            knownCodeByName = mapOf("平安银行" to "000001.SZ")
        )

        assertEquals(1, result.size)
        assertEquals("000001.SZ", result.single().code)
        assertEquals(1200, result.single().shares)
        assertEquals(10.25, result.single().cost, 0.0001)
        assertEquals(12_720.0, result.single().marketValue!!, 0.0001)
        assertEquals(10.60, result.single().latest!!, 0.0001)
    }

    @Test
    fun `整体替换持仓时保留现有角色并清理已卖出记录`() {
        val existing = listOf(
            Position("000001.SZ", "旧名称", 100, 9.0, "ATTACK"),
            Position("000002.SZ", "万科A", 200, 8.0, "LONG")
        )
        val incoming = listOf(ParsedThsPosition("000001.SZ", "平安银行", 1200, 10.25))

        val replacement = ThsPositionMerger.replace(existing, incoming)

        assertEquals(1, replacement.size)
        assertEquals("ATTACK", replacement.single().role)
        assertEquals(1200, replacement.single().shares)
    }

    @Test
    fun `解析当前仓位并反推总资产与现金`() {
        val ratio = ThsPositionParser.parsePositionRatio(listOf("总资产", "当前仓位 80.00%"))
        val estimate = ThsAccountCalculator.estimate(
            positions = listOf(
                ParsedThsPosition("000001.SZ", "平安银行", 1000, 10.0, marketValue = 12_000.0),
                ParsedThsPosition("600519.SH", "贵州茅台", 100, 1500.0, marketValue = 168_000.0)
            ),
            positionRatio = ratio
        )!!

        assertEquals(80.0, ratio!!, 0.0001)
        assertEquals(225_000.0, estimate.totalAssets, 0.0001)
        assertEquals(45_000.0, estimate.cashBalance, 0.0001)
    }

    @Test
    fun `持仓标签和值分离时也能解析仓位`() {
        assertEquals(65.8, ThsPositionParser.parsePositionRatio(listOf("仓位", "65.8%"))!!, 0.0001)
    }

    @Test
    fun `持仓资讯中的已卖股票不会进入权威快照`() {
        val texts = listOf(
            "持仓股", "市值", "盈亏", "持仓/可用", "成本/现价",
            "平安银行", "12,720.00", "-320.00", "-2.45%", "1200", "1000", "10.25", "10.60",
            "持仓管理", "持仓资讯", "万科A", "地产资讯", "-3.20%", "200", "200", "8.00", "7.50"
        )
        val mapping = mapOf("平安银行" to "000001.SZ", "万科A" to "000002.SZ")

        val parsed = ThsPositionParser.parse(texts, mapping)

        assertEquals(listOf("平安银行"), ThsPositionParser.findVisiblePositionNames(texts))
        assertEquals(listOf("000001.SZ"), parsed.map(ParsedThsPosition::code))
    }
}
