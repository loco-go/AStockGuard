package com.locogo.astockguard.domain.replay

import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Position
import com.locogo.astockguard.domain.trading.BuybackContext
import com.locogo.astockguard.domain.trading.DynamicTAction
import com.locogo.astockguard.domain.trading.DynamicTConfig
import com.locogo.astockguard.domain.trading.DynamicTPlanner
import com.locogo.astockguard.domain.trading.TradingRiskContext
import com.locogo.astockguard.domain.trading.TradingRiskLevel
import com.locogo.astockguard.domain.volume.SectorSyncFeatures
import com.locogo.astockguard.domain.volume.VolumeFeatureExtractor
import com.locogo.astockguard.domain.volume.VolumeSignalClassifier
import kotlin.math.max

/*
 * 文件职责：在相同分钟行情、初始仓位和费用口径下比较不操作、固定宽度T、动态量能T和风险控制动态T。
 * 回放纪律：每个索引只向策略传入0..index的历史切片，禁止读取未来K线；缺失板块/Level2会披露覆盖率而非补造。
 */

/** 单个策略的完整Replay指标，收益和回撤使用百分点，金额字段使用元。 */
data class VolumeReplayMetrics(
    val strategy: String,
    val totalReturnPct: Double,
    val tNetContribution: Double,
    val winRatePct: Double,
    val profitFactor: Double,
    val maxDrawdownPct: Double,
    val averageTProfit: Double,
    val averageHoldingMinutes: Double,
    val soldAwayLoss: Double,
    val wrongBuybackLoss: Double,
    val transactionCount: Int,
    val fees: Double,
    val taxes: Double,
    val slippage: Double,
    val returnDrawdownRatio: Double
)

/** A/B/C/D四组结果及历史增强数据覆盖说明。 */
data class VolumeReplayComparison(
    val buyAndHold: VolumeReplayMetrics,
    val fixedWidthT: VolumeReplayMetrics,
    val dynamicVolumeT: VolumeReplayMetrics,
    val riskControlledDynamicT: VolumeReplayMetrics,
    val dataCoverage: String
)

object VolumeStrategyReplayEngine {
    /**
     * 运行四策略对照。
     * riskTimeline为空时D组按NORMAL运行并明确披露“历史风险缺失”；长度不足的尾部同样安全降级为NORMAL。
     */
    fun compare(
        code: String,
        bars: List<MinuteBar>,
        rules: ReplayTradingRules = ReplayTradingRules(),
        riskTimeline: List<TradingRiskLevel> = emptyList()
    ): VolumeReplayComparison {
        val clean = bars.filter { it.price.isFinite() && it.price > 0.0 }
        val hold = simulateHold(clean, rules)
        val fixed = simulateFixed(clean, rules, widthPct = 1.5)
        val dynamic = simulateDynamic("C 动态量能T", code, clean, rules) { TradingRiskLevel.NORMAL }
        val risk = simulateDynamic("D 风控+动态量能T", code, clean, rules) { index ->
            riskTimeline.getOrNull(index) ?: TradingRiskLevel.NORMAL
        }
        return VolumeReplayComparison(
            buyAndHold = hold,
            fixedWidthT = fixed,
            dynamicVolumeT = dynamic,
            riskControlledDynamicT = risk,
            dataCoverage = buildString {
                append("分钟价量100%；历史板块、资金流、Level2未随本次输入提供，动态策略按缺失证据降置信度运行")
                if (riskTimeline.isEmpty()) append("；历史账户风险序列缺失，D组本次等同NORMAL风险基线")
            }
        )
    }

    /** 不操作策略仅持有日初底仓，用于提供行情本身的收益和回撤基准。 */
    private fun simulateHold(bars: List<MinuteBar>, rules: ReplayTradingRules): VolumeReplayMetrics {
        if (bars.isEmpty()) return emptyMetrics("A 不操作")
        val initial = rules.initialPosition * bars.first().price + rules.initialCash
        val equities = bars.map { rules.initialCash + rules.initialPosition * it.price }
        return metrics("A 不操作", initial, equities, emptyList())
    }

    /**
     * 固定宽度基线在价格较最近锚点上涨1.5%时卖出旧仓，随后回落1.5%买回。
     * 该基线用于回答“固定价差是否优于动态量价”，不调用新分类器，避免B组被新逻辑污染。
     */
    private fun simulateFixed(bars: List<MinuteBar>, rules: ReplayTradingRules, widthPct: Double): VolumeReplayMetrics {
        if (bars.isEmpty()) return emptyMetrics("B 固定宽度T")
        var anchor = bars.first().price
        var open: OpenReverseT? = null
        val trades = mutableListOf<ReplayCycle>()
        return simulate("B 固定宽度T", bars, rules) { index, bar, account ->
            if (open == null && bar.price >= anchor * (1.0 + widthPct / 100.0) && account.sellable >= rules.tradeQuantity) {
                val sell = account.sell(index, bar, rules.tradeQuantity, rules)
                open = OpenReverseT(index, sell.fillPrice, rules.tradeQuantity, sell.totalCost, bar.price)
            } else if (open != null && bar.price <= open!!.sellPrice * (1.0 - widthPct / 100.0)) {
                val closed = account.buyback(index, bar, open!!, rules)
                trades += closed
                anchor = bar.price
                open = null
            }
            trades.toList()
        }
    }

    /**
     * 动态策略逐根提取特征、分类并调用生产DynamicTPlanner；没有历史板块时不会产生伪造REAL_BREAKOUT。
     * 已卖出后仅当WAIT_BUYBACK观察条件满足时在Replay中模拟人工确认买回。
     */
    private fun simulateDynamic(
        strategyName: String,
        code: String,
        bars: List<MinuteBar>,
        rules: ReplayTradingRules,
        riskAt: (Int) -> TradingRiskLevel
    ): VolumeReplayMetrics {
        if (bars.isEmpty()) return emptyMetrics(strategyName)
        var open: OpenReverseT? = null
        val cycles = mutableListOf<ReplayCycle>()
        return simulate(strategyName, bars, rules) { index, bar, account ->
            if (index < 21) return@simulate cycles.toList()
            val prefix = bars.take(index + 1)
            val features = VolumeFeatureExtractor.extract(
                code = code,
                bars = prefix,
                sector = SectorSyncFeatures(available = false, fresh = false),
                dataFresh = true
            )
            val signal = VolumeSignalClassifier.classify(features)
            val level = riskAt(index)
            val risk = TradingRiskContext(true, level, allowBuyback = level == TradingRiskLevel.NORMAL)
            val position = Position(code, code, account.shares, bar.price, "ATTACK", account.sellable, coreShares = 0, tradingStyle = "ARBITRAGE")
            val buyback = open?.let {
                BuybackContext(it.sellPrice, it.quantity, it.buybackLow, it.buybackHigh, it.sellIndex.toLong())
            }
            val plan = DynamicTPlanner.plan(position, prefix, features, signal, risk, buyback, DynamicTConfig())
            if (open == null && plan.action in setOf(DynamicTAction.SELL_T, DynamicTAction.REDUCE) && plan.suggestedQuantity > 0) {
                val sell = account.sell(index, bar, plan.suggestedQuantity, rules)
                open = OpenReverseT(index, sell.fillPrice, plan.suggestedQuantity, sell.totalCost, bar.price,
                    plan.suggestedBuybackLow, plan.suggestedBuybackHigh)
            } else if (open != null && plan.action == DynamicTAction.WAIT_BUYBACK && plan.buybackEligible) {
                cycles += account.buyback(index, bar, open!!, rules)
                open = null
            }
            cycles.toList()
        }
    }

    /**
     * 统一执行账户权益、费用和回撤记账；策略回调只能操作当前bar，不能访问未来序列。
     * 回调返回已闭合周期快照，最终指标由同一函数计算，确保四组成本口径一致。
     */
    private fun simulate(
        strategy: String,
        bars: List<MinuteBar>,
        rules: ReplayTradingRules,
        decide: (Int, MinuteBar, ReplayAccount) -> List<ReplayCycle>
    ): VolumeReplayMetrics {
        val account = ReplayAccount(rules.initialCash, rules.initialPosition, rules.initialPosition)
        val initial = account.cash + account.shares * bars.first().price
        val equity = mutableListOf<Double>()
        var cycles = emptyList<ReplayCycle>()
        bars.forEachIndexed { index, bar ->
            cycles = decide(index, bar, account)
            equity += account.cash + account.shares * bar.price
        }
        return metrics(strategy, initial, equity, evaluateOpportunityLoss(cycles, bars), account)
    }

    /** 汇总收益、PF、最大回撤、持有时间、卖飞和错误回补损失，避免只用胜率评价策略。 */
    private fun metrics(
        strategy: String,
        initialEquity: Double,
        equity: List<Double>,
        cycles: List<ReplayCycle>,
        account: ReplayAccount? = null
    ): VolumeReplayMetrics {
        val final = equity.lastOrNull() ?: initialEquity
        val wins = cycles.filter { it.netProfit > 0.0 }
        val losses = cycles.filter { it.netProfit < 0.0 }
        val grossProfit = wins.sumOf { it.netProfit }
        val grossLoss = -losses.sumOf { it.netProfit }
        val drawdown = maxDrawdown(equity)
        val returnPct = if (initialEquity > 0.0) (final / initialEquity - 1.0) * 100.0 else 0.0
        return VolumeReplayMetrics(
            strategy = strategy,
            totalReturnPct = returnPct,
            tNetContribution = cycles.sumOf { it.netProfit },
            winRatePct = if (cycles.isEmpty()) 0.0 else wins.size * 100.0 / cycles.size,
            profitFactor = when { grossLoss > 0.0 -> grossProfit / grossLoss; grossProfit > 0.0 -> Double.POSITIVE_INFINITY; else -> 0.0 },
            maxDrawdownPct = drawdown,
            averageTProfit = cycles.map { it.netProfit }.averageOrZero(),
            averageHoldingMinutes = cycles.map { (it.buyIndex - it.sellIndex).toDouble() }.averageOrZero(),
            soldAwayLoss = cycles.sumOf { it.soldAwayLoss },
            wrongBuybackLoss = cycles.sumOf { it.wrongBuybackLoss },
            transactionCount = account?.transactionCount ?: cycles.size * 2,
            fees = account?.fees ?: 0.0,
            taxes = account?.taxes ?: 0.0,
            slippage = account?.slippage ?: 0.0,
            returnDrawdownRatio = if (drawdown > 0.0) returnPct / drawdown else 0.0
        )
    }

    /** 按权益峰值计算最大回撤百分点，空序列和非正权益安全返回0。 */
    private fun maxDrawdown(equity: List<Double>): Double {
        var peak = 0.0
        var result = 0.0
        equity.forEach { value ->
            peak = max(peak, value)
            if (peak > 0.0) result = max(result, (peak - value) / peak * 100.0)
        }
        return result
    }

    /**
     * 在交易决策全部结束后，使用后续价格评价“卖飞”和“过早回补”的机会损失。
     * 这里的未来价格只用于事后统计，不会回流到任何卖出或回补决策，因此不会造成回放前视偏差。
     */
    private fun evaluateOpportunityLoss(cycles: List<ReplayCycle>, bars: List<MinuteBar>): List<ReplayCycle> =
        cycles.map { cycle ->
            val whileWaiting = bars.subList(
                (cycle.sellIndex + 1).coerceAtMost(bars.size),
                (cycle.buyIndex + 1).coerceAtMost(bars.size)
            )
            val afterBuyback = bars.drop((cycle.buyIndex + 1).coerceAtMost(bars.size))
            val soldAway = whileWaiting.maxOfOrNull { it.price }
                ?.let { highest -> max(0.0, highest - cycle.sellReference) * cycle.quantity }
                ?: 0.0
            val wrongBuyback = afterBuyback.minOfOrNull { it.price }
                ?.let { lowest -> max(0.0, cycle.buyReference - lowest) * cycle.quantity }
                ?: 0.0
            cycle.copy(soldAwayLoss = soldAway, wrongBuybackLoss = wrongBuyback)
        }

    /** 为无行情输入生成全零报告，策略名仍保留以便UI显示数据不足。 */
    private fun emptyMetrics(name: String) = VolumeReplayMetrics(name, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0)

    /** 空周期不产生NaN，统一以0表达尚无可评价闭环。 */
    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    /** Replay内部账户只支持卖旧仓和等量买回，不允许创建超出本任务范围的新多头仓位。 */
    private class ReplayAccount(var cash: Double, var shares: Int, var sellable: Int) {
        var fees: Double = 0.0
        var taxes: Double = 0.0
        var slippage: Double = 0.0
        var transactionCount: Int = 0

        /** 以卖方滑点成交并扣除佣金、印花税，同时减少总仓和可卖仓。 */
        fun sell(index: Int, bar: MinuteBar, quantity: Int, rules: ReplayTradingRules): SellFill {
            val safeQuantity = minOf(quantity, sellable).coerceAtLeast(0)
            val fill = bar.price * (1.0 - rules.slippageBps / 10_000.0)
            val amount = fill * safeQuantity
            val fee = max(amount * rules.commissionRate, rules.minimumCommission)
            val tax = amount * rules.stampTaxRate
            val slip = (bar.price - fill) * safeQuantity
            cash += amount - fee - tax
            shares -= safeQuantity
            sellable -= safeQuantity
            fees += fee; taxes += tax; slippage += slip
            if (safeQuantity > 0) transactionCount++
            return SellFill(index, fill, fee + tax)
        }

        /** 以买方滑点买回先前卖出的等量旧仓，并形成一个已闭合反T周期。 */
        fun buyback(index: Int, bar: MinuteBar, open: OpenReverseT, rules: ReplayTradingRules): ReplayCycle {
            val fill = bar.price * (1.0 + rules.slippageBps / 10_000.0)
            val amount = fill * open.quantity
            val fee = max(amount * rules.commissionRate, rules.minimumCommission)
            val slip = (fill - bar.price) * open.quantity
            cash -= amount + fee
            shares += open.quantity
            fees += fee; slippage += slip
            transactionCount++
            val net = (open.sellPrice - fill) * open.quantity - open.sellCosts - fee
            return ReplayCycle(
                sellIndex = open.sellIndex,
                buyIndex = index,
                quantity = open.quantity,
                sellReference = open.sellReference,
                buyReference = bar.price,
                netProfit = net
            )
        }
    }

    private data class SellFill(val index: Int, val fillPrice: Double, val totalCost: Double)
    private data class OpenReverseT(
        val sellIndex: Int,
        val sellPrice: Double,
        val quantity: Int,
        val sellCosts: Double,
        val sellReference: Double,
        val buybackLow: Double = sellPrice * 0.97,
        val buybackHigh: Double = sellPrice * 0.99
    )
    private data class ReplayCycle(
        val sellIndex: Int,
        val buyIndex: Int,
        val quantity: Int,
        val sellReference: Double,
        val buyReference: Double,
        val netProfit: Double,
        val soldAwayLoss: Double = 0.0,
        val wrongBuybackLoss: Double = 0.0
    )
}
