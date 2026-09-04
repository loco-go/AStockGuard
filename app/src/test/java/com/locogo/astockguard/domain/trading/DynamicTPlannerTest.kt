package com.locogo.astockguard.domain.trading

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Position
import com.locogo.astockguard.domain.volume.SectorSyncFeatures
import com.locogo.astockguard.domain.volume.VolumeFeatures
import com.locogo.astockguard.domain.volume.VolumeSignal
import com.locogo.astockguard.domain.volume.VolumeSignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 文件职责：验证动态T的可卖数量、核心仓、全成本和风险优先级。
 * 安全目标：任何测试都不允许用总持仓替代未知可卖数量，也不允许风险锁定产生回补许可。
 */

class DynamicTPlannerTest {
    /** 可卖数量只有100股时，即使交易仓上限更高，SELL_T建议也不得超过100股。 */
    @Test
    fun sellSuggestionNeverExceedsBrokerAvailableShares() {
        val plan = DynamicTPlanner.plan(
            position = Position("000001.SZ", "测试", 600, 90.0, "CORE", availableShares = 100, coreShares = 400),
            bars = volatileBars(100.0),
            features = features(VolumeSignalType.BULL_TRAP, price = 100.0),
            signal = signal(VolumeSignalType.BULL_TRAP),
            risk = TradingRiskContext(dataFresh = true)
        )

        assertEquals(DynamicTAction.SELL_T, plan.action)
        assertEquals(100, plan.suggestedQuantity)
    }

    /** 券商可卖数量未知时必须返回NO_T，不能沿用旧逻辑把总持仓当成全部可卖。 */
    @Test
    fun unknownAvailableSharesBlocksSellAction() {
        val plan = DynamicTPlanner.plan(
            position = Position("000001.SZ", "测试", 600, 90.0, "CORE"),
            bars = volatileBars(100.0),
            features = features(VolumeSignalType.BULL_TRAP, 100.0),
            signal = signal(VolumeSignalType.BULL_TRAP),
            risk = TradingRiskContext(dataFresh = true)
        )

        assertEquals(DynamicTAction.NO_T, plan.action)
        assertEquals(0, plan.suggestedQuantity)
        assertTrue(plan.reasons.any { it.contains("可卖数量未知") })
    }

    /** 低价小额交易的预期回落不足以覆盖最低佣金和全部成本时必须返回NO_T。 */
    @Test
    fun expectedProfitBelowAllCostsReturnsNoT() {
        val plan = DynamicTPlanner.plan(
            position = Position("000001.SZ", "低价股", 600, 1.0, "CORE", availableShares = 100, coreShares = 400),
            bars = flatBars(1.0),
            features = features(VolumeSignalType.BULL_TRAP, 1.0),
            signal = signal(VolumeSignalType.BULL_TRAP),
            risk = TradingRiskContext(dataFresh = true)
        )

        assertEquals(DynamicTAction.NO_T, plan.action)
        assertTrue(plan.estimatedNetProfit <= 0.0)
        assertTrue(plan.reasons.any { it.contains("不足以覆盖") })
    }

    /** RISK_LOCKED状态即使回补观察条件全部改善，也只能WAIT_BUYBACK且eligible必须为false。 */
    @Test
    fun riskLockedForbidsBuybackEligibility() {
        val bars = risingBars(96.0)
        val plan = DynamicTPlanner.plan(
            position = Position("000001.SZ", "测试", 600, 90.0, "CORE", availableShares = 500, coreShares = 400),
            bars = bars,
            features = features(VolumeSignalType.NORMAL, bars.last().price, boardScore = 80, buyPressure = 75, aboveVwap = true),
            signal = signal(VolumeSignalType.NORMAL),
            risk = TradingRiskContext(dataFresh = true, marketLevel = TradingRiskLevel.RISK_LOCKED, allowBuyback = false),
            buyback = BuybackContext(100.0, 100, 95.0, 98.0, 1L)
        )

        assertEquals(DynamicTAction.WAIT_BUYBACK, plan.action)
        assertFalse(plan.buybackEligible)
        assertTrue(plan.reasons.any { it.contains("禁止回补") })
    }

    /** 账户风险受限时局部真实突破不能输出普通HOLD或任何买入暗示，应降为NO_T。 */
    @Test
    fun riskStateOverridesLocalRealBreakout() {
        val plan = DynamicTPlanner.plan(
            position = Position("000001.SZ", "测试", 600, 90.0, "CORE", availableShares = 200),
            bars = volatileBars(100.0),
            features = features(VolumeSignalType.REAL_BREAKOUT, 100.0),
            signal = signal(VolumeSignalType.REAL_BREAKOUT),
            risk = TradingRiskContext(dataFresh = true, marketLevel = TradingRiskLevel.REDUCE_EXPOSURE)
        )

        assertEquals(DynamicTAction.NO_T, plan.action)
    }

    /** 相同价格下更高的分钟波动应产生更宽的预期回落，不再使用所有股票同一固定价差。 */
    @Test
    fun higherIntradayVolatilityProducesWiderPullback() {
        val position = Position("000001.SZ", "测试", 1_000, 90.0, "ATTACK", availableShares = 500, coreShares = 200)
        val quiet = DynamicTPlanner.plan(
            position, flatBars(100.0), features(VolumeSignalType.BULL_TRAP, 100.0),
            signal(VolumeSignalType.BULL_TRAP), TradingRiskContext(true)
        )
        val volatile = DynamicTPlanner.plan(
            position, volatileBars(100.0), features(VolumeSignalType.BULL_TRAP, 100.0),
            signal(VolumeSignalType.BULL_TRAP), TradingRiskContext(true)
        )

        assertTrue(volatile.expectedPullbackPercent > quiet.expectedPullbackPercent)
    }

    /** 构造用于规划器的量价特征，只改变测试关心的分类、板块、买压和VWAP状态。 */
    private fun features(
        type: VolumeSignalType,
        price: Double,
        boardScore: Int = 20,
        buyPressure: Int = 30,
        aboveVwap: Boolean = false
    ): VolumeFeatures = VolumeFeatures(
        code = "000001.SZ", sampleCount = 30, dataFresh = true, volumeRatio1m = 2.0,
        volumeRatio3m = 1.5, volumeRatio5m = 1.4, amountRatio = 2.0,
        volumeDecay = type == VolumeSignalType.BULL_TRAP, sustainedVolume = type == VolumeSignalType.REAL_BREAKOUT,
        priceChange1mPct = 0.2, priceChange5mPct = 1.0, priceEfficiency = 40,
        priceVsVwapPct = if (aboveVwap) 0.3 else -0.2, vwapSlopePct = if (aboveVwap) 0.1 else -0.1,
        minutesAboveVwap = if (aboveVwap) 15 else 2, pullbackToVwapCount = 0,
        failedVwapReclaim = type == VolumeSignalType.BULL_TRAP, newHigh = type == VolumeSignalType.EXHAUSTION,
        sector = SectorSyncFeatures(true, true, boardScore), volumeScore = 75,
        buyPressureScore = buyPressure, trendScore = 60, bullTrapRisk = 80, confidence = 85,
        evidence = listOf("测试特征，参考价$price")
    )

    /** 构造与特征类型一致的分类结果，分项数值仅用于满足稳定领域契约。 */
    private fun signal(type: VolumeSignalType): VolumeSignal = VolumeSignal(
        type, 75, 40, 40, 20, 60, 80, 85, listOf("TEST"), listOf("测试信号")
    )

    /** 构造高低波幅约4%的分钟序列，使预期回落有足够空间覆盖100股交易成本。 */
    private fun volatileBars(price: Double): List<MinuteBar> = (1..30).map { index ->
        val close = price + if (index % 2 == 0) 1.2 else -1.0
        bar(index, close, high = close + 0.8, low = close - 0.8)
    }.dropLast(1) + bar(30, price, high = price + 1.0, low = price - 1.0)

    /** 构造极低波动序列，动态宽度会落到安全下限，用于验证成本过滤和宽度差异。 */
    private fun flatBars(price: Double): List<MinuteBar> = (1..30).map { index ->
        bar(index, price, high = price * 1.0005, low = price * 0.9995)
    }

    /** 构造回补观察使用的抬高低点序列，最后价格位于预设回补区且站在VWAP之上。 */
    private fun risingBars(start: Double): List<MinuteBar> = (1..8).map { index ->
        val close = start + index * 0.2
        bar(index, close, high = close + 0.1, low = close - 0.1)
    }

    /** 创建字段完整的一分钟行情，成交量和成交额保持正值以避免被特征层过滤。 */
    private fun bar(index: Int, close: Double, high: Double, low: Double): MinuteBar = MinuteBar(
        time = "10:${index.toString().padStart(2, '0')}", price = close, avgPrice = close - 0.05,
        high = high, low = low, volume = 1_000.0, amount = close * 1_000.0
    )
}
