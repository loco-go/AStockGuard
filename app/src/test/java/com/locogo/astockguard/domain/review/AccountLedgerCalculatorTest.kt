package com.locogo.astockguard.domain.review

/*
 * 文件职责：验证 AccountLedgerCalculator 的正常、边界与回归场景；测试名称描述业务行为，断言保护确定性输出和安全门禁。
 * 架构边界：测试数据应固定时间、代码和单位，避免依赖系统当天行情；新增分支时同步增加成功与拒绝路径。
 * 维护说明：测试用于锁定业务语义而非实现细节；策略阈值或版本有意变化时，应同时更新预期并说明原因。
 */

import com.locogo.astockguard.Position
import com.locogo.astockguard.Quote
import com.locogo.astockguard.data.local.AccountLedgerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountLedgerCalculatorTest {
    private val position = Position("000001.SZ", "平安银行", 100, 10.0, "CORE")
    private val quote = Quote("000001.SZ", latest = 12.0)

    @Test
    fun `按期初资产和外部资金流计算真实累计收益`() {
        val entries = listOf(
            ledger(AccountLedgerType.OPENING_BALANCE, 1_000.0),
            ledger(AccountLedgerType.DEPOSIT, 500.0),
            ledger(AccountLedgerType.WITHDRAWAL, 200.0),
            ledger(AccountLedgerType.DIVIDEND, 30.0),
            ledger(AccountLedgerType.FEE, 5.0)
        )

        val summary = AccountLedgerCalculator.calculate(entries, listOf(position), listOf(quote), cashBalance = 300.0)

        assertEquals(1_500.0, summary.totalAssets ?: 0.0, 0.001)
        assertEquals(1_300.0, summary.netInvestedCapital ?: 0.0, 0.001)
        assertEquals(200.0, summary.cumulativePnl ?: 0.0, 0.001)
        assertEquals(200.0, summary.unrealizedPnl, 0.001)
        assertEquals(30.0, summary.investmentIncome, 0.001)
        assertEquals(5.0, summary.explicitCosts, 0.001)
    }

    @Test
    fun `没有期初资产时不得展示伪累计收益`() {
        val summary = AccountLedgerCalculator.calculate(
            entries = listOf(ledger(AccountLedgerType.DEPOSIT, 500.0)),
            positions = listOf(position),
            quotes = listOf(quote),
            cashBalance = 300.0
        )

        assertNull(summary.netInvestedCapital)
        assertNull(summary.cumulativePnl)
    }

    private fun ledger(type: AccountLedgerType, amount: Double) = AccountLedgerEntity(
        occurredAt = 1L,
        type = type.value,
        amount = amount
    )
}
