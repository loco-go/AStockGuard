package com.locogo.astockguard.domain.plan

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.Position
import kotlin.math.abs
import kotlin.math.max

/** 动态仓位历史回放结果；收益字段均为百分数，不是未来收益承诺。 */
data class ExposureBacktestReport(
    val sampleDays: Int = 0,
    val strategyReturnPct: Double = 0.0,
    val benchmarkReturnPct: Double = 0.0,
    val strategyMaxDrawdownPct: Double = 0.0,
    val benchmarkMaxDrawdownPct: Double = 0.0,
    val reductionSignals: Int = 0,
    val reductionSuccessRatePct: Double? = null,
    val status: String = "NO_DATA",
    val note: String = "历史数据不足"
)

/**
 * 组合仓位策略的无前视回放。
 *
 * 每日目标仓位只读取前一日之前已经收盘的数据，并按仓位变化扣除摩擦成本。当前没有历史
 * 逐笔 Level-2，因此盘口增强不计入回放，避免生成不可复现的虚假胜率。
 */
object ExposureBacktestEngine {
    fun evaluate(
        positions: List<Position>,
        histories: Map<String, List<DailyBar>>,
        turnoverCostRate: Double = 0.001
    ): ExposureBacktestReport {
        val active = positions.filter { it.shares > 0 && histories[it.code].orEmpty().size >= MIN_BARS }
        if (active.isEmpty()) return ExposureBacktestReport()
        val commonDates = active.map { position -> histories[position.code].orEmpty().mapTo(hashSetOf()) { it.date } }
            .reduce { left, right -> left.apply { retainAll(right) } }.sorted()
        if (commonDates.size < MIN_BARS) return ExposureBacktestReport()

        val maps = active.associate { it.code to histories.getValue(it.code).associateBy(DailyBar::date) }
        val lastDate = commonDates.last()
        val values = active.associate { position ->
            position.code to position.shares * (maps.getValue(position.code)[lastDate]?.close ?: position.cost)
        }
        val totalValue = values.values.sum().takeIf { it > 0.0 } ?: return ExposureBacktestReport()
        val weights = values.mapValues { it.value / totalValue }
        val returns = commonDates.drop(1).mapIndexed { index, date ->
            val previousDate = commonDates[index]
            active.sumOf { position ->
                val previous = maps.getValue(position.code).getValue(previousDate).close
                val current = maps.getValue(position.code).getValue(date).close
                if (previous > 0.0) weights.getValue(position.code) * (current / previous - 1.0) else 0.0
            }
        }.filter(Double::isFinite)
        if (returns.size < MIN_RETURNS) return ExposureBacktestReport()

        var strategyNet = 1.0
        var benchmarkNet = 1.0
        var strategyPeak = 1.0
        var benchmarkPeak = 1.0
        var strategyDrawdown = 0.0
        var benchmarkDrawdown = 0.0
        var previousExposure = BASE_EXPOSURE
        val reductionIndexes = mutableListOf<Int>()
        returns.forEachIndexed { index, dailyReturn ->
            // index 日只能看到 index 之前的收益，禁止用当天跌幅决定当天盘前仓位。
            val exposure = targetExposure(returns.subList(0, index))
            if (exposure < previousExposure - 0.05) reductionIndexes += index
            strategyNet *= 1.0 + exposure * dailyReturn - abs(exposure - previousExposure) * turnoverCostRate
            benchmarkNet *= 1.0 + dailyReturn
            strategyPeak = max(strategyPeak, strategyNet)
            benchmarkPeak = max(benchmarkPeak, benchmarkNet)
            strategyDrawdown = max(strategyDrawdown, 1.0 - strategyNet / strategyPeak)
            benchmarkDrawdown = max(benchmarkDrawdown, 1.0 - benchmarkNet / benchmarkPeak)
            previousExposure = exposure
        }

        // 降仓后的三个交易日组合累计收益为负，视为这次回撤提醒有效。
        val evaluated = reductionIndexes.filter { it + 2 < returns.size }
        val successful = evaluated.count { start -> returns.subList(start, start + 3).sum() < 0.0 }
        return ExposureBacktestReport(
            sampleDays = returns.size,
            strategyReturnPct = (strategyNet - 1.0) * 100.0,
            benchmarkReturnPct = (benchmarkNet - 1.0) * 100.0,
            strategyMaxDrawdownPct = strategyDrawdown * 100.0,
            benchmarkMaxDrawdownPct = benchmarkDrawdown * 100.0,
            reductionSignals = evaluated.size,
            reductionSuccessRatePct = evaluated.takeIf { it.isNotEmpty() }?.let { successful * 100.0 / it.size },
            status = "READY",
            note = "仅回放日K动态仓位，已扣${"%.2f".format(turnoverCostRate * 100)}%调仓摩擦；Level-2等待实时前向样本"
        )
    }

    private fun targetExposure(knownReturns: List<Double>): Double {
        if (knownReturns.isEmpty()) return BASE_EXPOSURE
        val last = knownReturns.last()
        val fiveDay = knownReturns.takeLast(5).sum()
        return when {
            last <= -0.02 || fiveDay <= -0.04 -> 0.45
            last <= -0.01 || fiveDay <= -0.02 -> 0.60
            last >= 0.01 && fiveDay >= 0.02 -> 0.85
            else -> BASE_EXPOSURE
        }
    }

    private const val BASE_EXPOSURE = 0.70
    private const val MIN_BARS = 20
    private const val MIN_RETURNS = 19
}
