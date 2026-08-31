package com.locogo.astockguard.ui.main

import com.locogo.astockguard.DataHealth
import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrategyUiMapperTest {
    private val assessment = MarketAssessment("E0", "M2", 0.7, 0.0, 0.0, 0.0, "", emptyList())

    @Test
    fun `首页只映射真实持仓并排除自选行情`() {
        val snapshot = snapshotOf(
            Quote("600667.SH", "太极实业", latest = 7.0),
            Quote("000001.SZ", "平安银行", latest = 12.0)
        )
        val positions = listOf(Position("000001.SZ", "平安银行", 100, 10.0, "CORE"))

        val rows = StrategyUiMapper.map(snapshot, positions, null)

        assertEquals(listOf("000001.SZ"), rows.map { it.code })
    }

    @Test
    fun `持仓暂时缺少行情时仍保留持仓行`() {
        val positions = listOf(Position("000001.SZ", "平安银行", 100, 10.0, "CORE"))

        val rows = StrategyUiMapper.map(snapshotOf(), positions, null)

        assertEquals(1, rows.size)
        assertEquals("平安银行", rows.single().name)
        assertNull(rows.single().price)
    }

    private fun snapshotOf(vararg quotes: Quote) = MonitorSnapshot(
        updatedAt = 1L,
        quotes = quotes.toList(),
        assessment = assessment,
        positionRatio = 0.0,
        dataHealth = DataHealth()
    )
}
