package com.locogo.astockguard.domain.trading

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Position
import com.locogo.astockguard.domain.plan.PositionCategory
import com.locogo.astockguard.domain.volume.StockTradingStyle
import com.locogo.astockguard.domain.volume.VolumeFeatures
import com.locogo.astockguard.domain.volume.VolumeSignal
import com.locogo.astockguard.domain.volume.VolumeSignalType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * 文件职责：把量价分类转换为服从账户风险、持仓边界和全成本约束的动态做T辅助计划。
 * 安全边界：本类不下单、不猜测可卖数量、不产生无条件买入；所有输出均需由用户人工确认。
 */

/** 动态T对外动作集合；WAIT_BUYBACK只是回补观察状态，不代表系统发出买入委托。 */
enum class DynamicTAction { HOLD, SELL_T, REDUCE, WAIT, WAIT_BUYBACK, NO_T }

/** 高层风险状态，由现有RiskEngine和仓位控制计划在接入阶段适配，不改变原引擎确定性输出。 */
enum class TradingRiskLevel { NORMAL, SHOCK_DOWN, TREND_DOWN, RISK_LOCKED, REDUCE_EXPOSURE }

/**
 * 动态T风险上下文。
 * @property dataFresh 行情与分钟数据是否满足实时动作标准。
 * @property marketLevel 账户和市场合并后的最高风险等级。
 * @property allowBuyback 是否允许进入“回补条件改善”状态；风险锁定时必须为false。
 */
data class TradingRiskContext(
    val dataFresh: Boolean,
    val marketLevel: TradingRiskLevel = TradingRiskLevel.NORMAL,
    val allowBuyback: Boolean = true,
    val reason: String = ""
)

/** 已确认执行过的反T卖出上下文；只有真实成交或用户手工记录后才能创建。 */
data class BuybackContext(
    val soldPrice: Double,
    val quantity: Int,
    val expectedBuybackLow: Double,
    val expectedBuybackHigh: Double,
    val soldAt: Long
)

/** 动态T成本和宽度参数，费率使用小数比例，安全边际使用百分点。 */
data class DynamicTConfig(
    val atrWindow: Int = 20,
    val volatilityWindow: Int = 20,
    val amplitudeWindow: Int = 60,
    val commissionRate: Double = 0.0003,
    val minimumCommission: Double = 5.0,
    val stampTaxRate: Double = 0.0005,
    val slippageRate: Double = 0.0002,
    val safetyMarginPct: Double = 0.35,
    val maximumTradePositionPct: Double = 0.35,
    val buybackMinimumConditions: Int = 3
)

/** 动态做T计划；价格区间和收益字段均基于referencePrice，百分比字段使用百分点口径。 */
data class DynamicTPlan(
    val code: String,
    val action: DynamicTAction,
    val signalType: VolumeSignalType,
    val referencePrice: Double,
    val expectedPullbackPercent: Double,
    val expectedPullbackPrice: Double,
    val suggestedSellPrice: Double,
    val suggestedBuybackLow: Double,
    val suggestedBuybackHigh: Double,
    val suggestedQuantity: Int,
    val estimatedGrossProfit: Double,
    val estimatedCosts: Double,
    val estimatedNetProfit: Double,
    val buybackConditionsMet: Int = 0,
    val buybackEligible: Boolean = false,
    val reasons: List<String> = emptyList()
)

object DynamicTPlanner {
    /**
     * 生成当前时点的动态T计划。
     *
     * 处理顺序固定为：数据新鲜度→已卖出等待回补→量价分类→风险覆盖→可卖交易仓→成本收益。
     * 这一顺序保证高分局部信号不能覆盖账户风险，也保证未知可卖数量永远不会产生SELL_T数量。
     */
    fun plan(
        position: Position?,
        bars: List<MinuteBar>,
        features: VolumeFeatures,
        signal: VolumeSignal,
        risk: TradingRiskContext,
        buyback: BuybackContext? = null,
        config: DynamicTConfig = DynamicTConfig()
    ): DynamicTPlan {
        val latest = bars.lastOrNull()?.price?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val width = expectedPullbackPercent(bars, features, signal.type, config)
        val target = if (latest > 0.0) latest * (1.0 - width / 100.0) else 0.0
        val zoneHalfWidth = width * 0.12
        val base = DynamicTPlan(
            code = features.code,
            action = DynamicTAction.NO_T,
            signalType = signal.type,
            referencePrice = latest,
            expectedPullbackPercent = width,
            expectedPullbackPrice = target,
            suggestedSellPrice = latest,
            suggestedBuybackLow = if (latest > 0.0) latest * (1.0 - (width + zoneHalfWidth) / 100.0) else 0.0,
            suggestedBuybackHigh = if (latest > 0.0) latest * (1.0 - (width - zoneHalfWidth) / 100.0) else 0.0,
            suggestedQuantity = 0,
            estimatedGrossProfit = 0.0,
            estimatedCosts = 0.0,
            estimatedNetProfit = 0.0
        )
        if (!risk.dataFresh || !features.dataFresh || latest <= 0.0) {
            return base.copy(reasons = listOf("行情或分钟数据不是实时可用状态，禁止生成动态T动作"))
        }
        if (buyback != null) return evaluateBuyback(base, bars, features, risk, buyback, config)
        val riskRestricted = risk.marketLevel != TradingRiskLevel.NORMAL
        if (signal.type == VolumeSignalType.REAL_BREAKOUT) {
            return if (riskRestricted) {
                base.copy(reasons = listOf("账户或市场处于风险限制状态，局部真实突破不能覆盖风险控制"))
            } else {
                base.copy(action = DynamicTAction.HOLD, reasons = listOf("真实突破结构成立，暂缓按固定价差过早卖出"))
            }
        }
        if (signal.type == VolumeSignalType.WEAK_BREAKOUT || signal.type == VolumeSignalType.NORMAL) {
            return if (riskRestricted) {
                base.copy(reasons = listOf("风险限制状态下不接受局部弱突破，仅保留NO_T观察"))
            } else {
                base.copy(action = DynamicTAction.WAIT, reasons = listOf("当前证据不足以支持反T卖出，保持观察"))
            }
        }
        if (signal.type == VolumeSignalType.NO_SIGNAL) {
            return base.copy(reasons = listOf("没有可用于实时决策的量价信号"))
        }

        val quantity = sellableTradeQuantity(position, config)
        if (quantity <= 0) {
            val reason = when {
                position == null -> "没有匹配的真实持仓"
                position.availableShares == null -> "券商可卖数量未知，禁止把总持仓假定为可卖"
                else -> "可卖交易仓不足100股，核心仓保持保护"
            }
            return base.copy(reasons = listOf(reason))
        }
        val economics = estimateEconomics(latest, target, quantity, config)
        if (economics.netProfit <= 0.0) {
            return base.copy(
                suggestedQuantity = quantity,
                estimatedGrossProfit = economics.grossProfit,
                estimatedCosts = economics.costs,
                estimatedNetProfit = economics.netProfit,
                reasons = listOf("预计回落收益不足以覆盖佣金、最低佣金、印花税、滑点和安全边际")
            )
        }
        val forcedReduce = riskRestricted
        val action = if (forcedReduce || signal.type == VolumeSignalType.EXHAUSTION) DynamicTAction.REDUCE else DynamicTAction.SELL_T
        return base.copy(
            action = action,
            suggestedQuantity = quantity,
            estimatedGrossProfit = economics.grossProfit,
            estimatedCosts = economics.costs,
            estimatedNetProfit = economics.netProfit,
            reasons = buildList {
                add(if (signal.type == VolumeSignalType.BULL_TRAP) "诱多风险达到阈值，允许只处理交易仓" else "放量滞涨，优先降低机动仓风险")
                if (forcedReduce) add("账户或市场风险覆盖普通做T，计划降级为REDUCE且不承诺回补")
                add("建议数量已限制在真实可卖数量、交易仓和持仓角色上限以内")
            }
        )
    }

    /**
     * 计算动态预期回落幅度：ATR、20分钟真实波动和60分钟振幅取较强者，再按趋势与信号类型调整。
     * 强趋势真实突破虽然计算参考宽度，但主流程输出HOLD；弱趋势和诱多会采用更保守的回落预期。
     */
    private fun expectedPullbackPercent(
        bars: List<MinuteBar>,
        features: VolumeFeatures,
        signalType: VolumeSignalType,
        config: DynamicTConfig
    ): Double {
        if (bars.size < 2) return 0.0
        val atrPct = averageTrueRangePct(bars.takeLast(config.atrWindow + 1))
        val realized = bars.takeLast(config.volatilityWindow + 1).zipWithNext().mapNotNull { (before, after) ->
            before.price.takeIf { it > 0.0 }?.let { abs(after.price / it - 1.0) * 100.0 }
        }.averageOrZero()
        val amplitudeBars = bars.takeLast(config.amplitudeWindow)
        val low = amplitudeBars.minOfOrNull { it.low } ?: bars.last().price
        val high = amplitudeBars.maxOfOrNull { it.high } ?: bars.last().price
        val amplitude = if (low > 0.0) (high / low - 1.0) * 100.0 else 0.0
        var width = max(atrPct * 1.8, max(realized * 3.0, amplitude * 0.28))
        width += abs(features.priceVsVwapPct) * 0.25
        width *= when (signalType) {
            VolumeSignalType.BULL_TRAP -> 1.15
            VolumeSignalType.EXHAUSTION -> 1.05
            VolumeSignalType.REAL_BREAKOUT -> 1.35
            else -> 1.0
        }
        return width.coerceIn(0.60, 5.00)
    }

    /** 使用真实高低和前收计算平均真实波幅占价格比例，首根K线不参与以避免缺少前收。 */
    private fun averageTrueRangePct(bars: List<MinuteBar>): Double {
        if (bars.size < 2) return 0.0
        return bars.zipWithNext().mapNotNull { (previous, current) ->
            val reference = previous.price.takeIf { it > 0.0 } ?: return@mapNotNull null
            val trueRange = max(current.high - current.low, max(abs(current.high - reference), abs(current.low - reference)))
            trueRange / reference * 100.0
        }.averageOrZero()
    }

    /**
     * 计算可处理交易仓，依次限制券商可卖数量、核心仓保护、持仓角色上限和全局T仓上限。
     * 所有上限取最小值并向下取整到100股，任何未知可卖数量均返回0。
     */
    private fun sellableTradeQuantity(position: Position?, config: DynamicTConfig): Int {
        position ?: return 0
        val available = position.availableShares?.coerceIn(0, position.shares.coerceAtLeast(0)) ?: return 0
        val style = parseStyle(position.tradingStyle)
        val defaultCoreRatio = when (style) {
            StockTradingStyle.CORE_TREND -> 0.67
            StockTradingStyle.TREND -> 0.50
            StockTradingStyle.RECOVERY -> 0.35
            StockTradingStyle.ARBITRAGE -> 0.15
            StockTradingStyle.EMOTION -> 0.20
        }
        val protectedCore = if (position.coreShares > 0) position.coreShares else (position.shares * defaultCoreRatio).toInt()
        val tradeCapacity = (position.shares - protectedCore.coerceIn(0, position.shares)).coerceAtLeast(0)
        val roleCap = position.shares * PositionCategory.from(position.role).maxTSharePct / 100
        val globalCap = (position.shares * config.maximumTradePositionPct).toInt()
        return (min(available, min(tradeCapacity, min(roleCap, globalCap))) / 100) * 100
    }

    /** 未知交易风格安全降级为普通TREND，使历史配置仍获得50%默认核心仓保护。 */
    private fun parseStyle(value: String): StockTradingStyle =
        runCatching { StockTradingStyle.valueOf(value.trim().uppercase()) }.getOrDefault(StockTradingStyle.TREND)

    /**
     * 估算先卖旧仓、后按目标价买回的完整经济性。
     * 成本包含双边滑点、双边佣金最低收费和卖出印花税，另把安全边际作为必须覆盖的机会成本。
     */
    private fun estimateEconomics(
        sellReference: Double,
        buyReference: Double,
        quantity: Int,
        config: DynamicTConfig
    ): Economics {
        val sellPrice = sellReference * (1.0 - config.slippageRate)
        val buyPrice = buyReference * (1.0 + config.slippageRate)
        val sellAmount = sellPrice * quantity
        val buyAmount = buyPrice * quantity
        val commission = max(sellAmount * config.commissionRate, config.minimumCommission) +
            max(buyAmount * config.commissionRate, config.minimumCommission)
        val tax = sellAmount * config.stampTaxRate
        val slippage = (sellReference - sellPrice + buyPrice - buyReference) * quantity
        val safety = sellReference * quantity * config.safetyMarginPct / 100.0
        val costs = commission + tax + slippage + safety
        return Economics(
            grossProfit = (sellReference - buyReference) * quantity,
            costs = costs,
            netProfit = (sellReference - buyReference) * quantity - costs
        )
    }

    /**
     * 评价反T卖出后的回补观察状态，但绝不生成BUY动作。
     * 达到目标区、形成更高的第二低点、重新站上VWAP、板块改善和卖压衰减各计一项；风险锁定会强制eligible=false。
     */
    private fun evaluateBuyback(
        base: DynamicTPlan,
        bars: List<MinuteBar>,
        features: VolumeFeatures,
        risk: TradingRiskContext,
        context: BuybackContext,
        config: DynamicTConfig
    ): DynamicTPlan {
        val latest = bars.last().price
        val inTarget = latest in context.expectedBuybackLow..context.expectedBuybackHigh
        val recent = bars.takeLast(8)
        val secondLowHigher = recent.size >= 6 && recent.takeLast(3).minOf { it.low } > recent.dropLast(3).takeLast(3).minOf { it.low }
        val reclaimedVwap = features.priceVsVwapPct > 0.0
        val boardStabilized = (features.sector.boardSyncScore ?: 0) >= 50
        val sellPressureFaded = features.buyPressureScore >= 48
        val conditions = listOf(inTarget, secondLowHigher, reclaimedVwap, boardStabilized, sellPressureFaded).count { it }
        val riskAllows = risk.allowBuyback && risk.marketLevel == TradingRiskLevel.NORMAL
        val eligible = riskAllows && conditions >= config.buybackMinimumConditions
        return base.copy(
            action = DynamicTAction.WAIT_BUYBACK,
            suggestedQuantity = context.quantity,
            suggestedBuybackLow = context.expectedBuybackLow,
            suggestedBuybackHigh = context.expectedBuybackHigh,
            buybackConditionsMet = conditions,
            buybackEligible = eligible,
            reasons = buildList {
                add("已确认卖出${context.quantity}股，保持WAIT_BUYBACK观察状态，不因继续上涨立即追回")
                add("五项回补观察条件已满足${conditions}项")
                if (!riskAllows) add("账户或市场风险未解除，禁止回补许可")
                else if (!eligible) add("回补条件不足，继续持有现金")
                else add("回补观察条件改善，仍需用户人工确认，不生成无条件买入")
            }
        )
    }

    /** 空列表的平均值统一返回0，防止NaN进入动态宽度和价格计算。 */
    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    /** 内部经济性聚合值，确保毛收益、总成本和净收益采用同一数量与价格假设。 */
    private data class Economics(val grossProfit: Double, val costs: Double, val netProfit: Double)
}
