package com.locogo.astockguard.integration.ths

import com.locogo.astockguard.Position
import org.junit.Assert.*
import org.junit.Test

class ThsPositionSelectionTest {
    @Test fun `已有股票默认选中新股不选且再次扫描保留取消选择`() {
        val candidates = listOf(ThsPositionCandidate("平安银行", "000001.SZ"), ThsPositionCandidate("贵州茅台"))
        val existing = listOf(Position("000001.SZ", "平安银行", 300, 10.0, "CORE"))
        val initial = reconcileThsImportRows(candidates, emptyList(), existing)
        assertTrue(initial.first().selected)
        assertFalse(initial.last().selected)
        val rescanned = reconcileThsImportRows(candidates, initial.map { it.copy(selected = false) }, existing)
        assertFalse(rescanned.first().selected)
        assertTrue(rescanned.first().existing)
    }

    @Test fun `只有名称也能标记已有持仓并保留人工补全`() {
        val candidate = ThsPositionCandidate("平安银行")
        val existing = listOf(Position("000001.SZ", "平安银行", 300, 10.0, "CORE"))
        assertTrue(reconcileThsImportRows(listOf(candidate), emptyList(), existing).single().selected)
        val edited = ThsImportRow(candidate.copy(code = "000001.SZ", position =
            ParsedThsPosition("000001.SZ", "平安银行", 500, 12.0)), true, true, true)
        assertEquals(edited, reconcileThsImportRows(listOf(candidate), listOf(edited), existing).single())
    }

    @Test fun `名称候选不依赖完整数量成本或盈亏百分比`() {
        val names = ThsPositionParser.findVisiblePositionNames(listOf(
            "持仓", "持仓/可用", "成本/现价", "平安银行", "--", "贵州茅台", "--",
            "持仓资讯", "万科A"
        ))
        assertEquals(listOf("平安银行", "贵州茅台"), names)
    }

    @Test fun `合并节点可以提取名称而不把股票代码当名称`() {
        assertEquals(listOf("平安银行", "贵州茅台"), ThsPositionParser.findVisiblePositionNames(
            listOf("持仓 成本/现价 平安银行 000001 -- 贵州茅台600519 --")
        ))
    }

    @Test fun `只更新所选股票并保留未识别持仓及角色`() {
        val bank = Position("000001.SZ", "平安银行", 300, 10.0, "ATTACK", coreShares = 100)
        val other = Position("600519.SH", "贵州茅台", 100, 1400.0, "CORE")
        val updated = ThsPositionMerger.mergeSelected(listOf(bank, other), listOf(
            ParsedThsPosition(bank.code, bank.name, 200, 11.0)
        ))
        assertEquals(other, updated.last())
        assertEquals(200, updated.first().shares)
        assertEquals("ATTACK", updated.first().role)
        assertEquals(100, updated.first().coreShares)
        assertNull(updated.first().availableShares)
    }

    @Test fun `重复确认同一股票不会重复创建持仓`() {
        val incoming = ParsedThsPosition("000001.SZ", "平安银行", 200, 10.0)
        val once = ThsPositionMerger.mergeSelected(emptyList(), listOf(incoming, incoming))
        assertEquals(1, once.size)
        assertEquals(once, ThsPositionMerger.mergeSelected(once, listOf(incoming)))
    }

    @Test fun `空选择不删除原持仓`() {
        val existing = listOf(Position("000001.SZ", "平安银行", 300, 10.0, "CORE"))
        assertEquals(existing, ThsPositionMerger.mergeSelected(existing, emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝可卖数量大于持仓的人工录入`() {
        ThsPositionMerger.mergeSelected(emptyList(), listOf(
            ParsedThsPosition("000001.SZ", "平安银行", 100, 10.0, availableShares = 200)
        ))
    }
}
