package com.locogo.astockguard.domain.replay

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.domain.trading.TradingRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 文件职责：验证量能策略Replay的四组可比性、真实交易成本和数据覆盖披露。
 * 测试原则：只构造确定的分钟序列，不依赖网络、系统时间或真实账户，确保回归结果稳定可重复。
 */

class VolumeStrategyReplayEngineTest {
    /** 四组报告必须始终齐全且名称固定，避免UI或后续导出把不同策略结果错位。 */
    @Test
    fun compareAlwaysReturnsFourNamedStrategies() {
        val result = VolumeStrategyReplayEngine.compare("000001.SZ", baselineBars())

        assertEquals("A 不操作", result.buyAndHold.strategy)
        assertEquals("B 固定宽度T", result.fixedWidthT.strategy)
        assertEquals("C 动态量能T", result.dynamicVolumeT.strategy)
        assertEquals("D 风控+动态量能T", result.riskControlledDynamicT.strategy)
        assertTrue(result.dataCoverage.contains("历史板块、资金流、Level2未随本次输入提供"))
    }

    /** 固定宽度策略完成一次反T后，必须记录双边成交、佣金、印花税和滑点，净贡献不得使用毛价差冒充。 */
    @Test
    fun fixedWidthRoundTripDeductsAllTradingCosts() {
        val bars = listOf(
            bar(0, 10.00),
            bar(1, 10.20),
            bar(2, 10.00)
        )

        val metrics = VolumeStrategyReplayEngine.compare(
            code = "000001.SZ",
            bars = bars,
            rules = ReplayTradingRules(initialPosition = 100, tradeQuantity = 100)
        ).fixedWidthT

        assertEquals(2, metrics.transactionCount)
        assertTrue(metrics.fees >= 10.0)
        assertTrue(metrics.taxes > 0.0)
        assertTrue(metrics.slippage > 0.0)
        assertTrue(metrics.tNetContribution < (10.20 - 10.00) * 100)
    }

    /** 未提供风险时间线时D组必须明确披露降级，不能暗示已经验证账户风险覆盖效果。 */
    @Test
    fun missingRiskTimelineIsDisclosed() {
        val result = VolumeStrategyReplayEngine.compare("000001.SZ", baselineBars())

        assertTrue(result.dataCoverage.contains("D组本次等同NORMAL风险基线"))
    }

    /** 显式风险序列存在时D组保持独立标签，并移除“风险序列缺失”提示。 */
    @Test
    fun suppliedRiskTimelineRemovesMissingRiskWarning() {
        val bars = baselineBars()
        val result = VolumeStrategyReplayEngine.compare(
            code = "000001.SZ",
            bars = bars,
            riskTimeline = List(bars.size) { TradingRiskLevel.RISK_LOCKED }
        )

        assertEquals("D 风控+动态量能T", result.riskControlledDynamicT.strategy)
        assertTrue(!result.dataCoverage.contains("历史账户风险序列缺失"))
    }

    /** 生成足够长的平稳样本，让动态特征提取器拥有完整滚动窗口且不触发虚假异动。 */
    private fun baselineBars(): List<MinuteBar> = (0 until 30).map { index ->
        bar(index, 10.0 + (index % 2) * 0.01)
    }

    /** 构造字段口径一致的分钟K；成交额严格等于价格乘成交量，避免测试样本自身引入量价异常。 */
    private fun bar(index: Int, price: Double): MinuteBar = MinuteBar(
        time = "10:${index.toString().padStart(2, '0')}",
        price = price,
        avgPrice = price,
        high = price,
        low = price,
        volume = 1_000.0,
        amount = price * 1_000.0
    )
}
