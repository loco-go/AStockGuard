package com.locogo.astockguard.chart

/*
 * 文件职责：按时间顺序推进历史数据并模拟现实成交约束；禁止读取游标之后的数据，避免回放中的未来函数。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import kotlin.math.max

data class ReplayStats(
    val returnRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val trades: Int
)

object ReplayEngine {
    fun run(candles: List<StockKLine>, warmup: Int = 30): ReplayStats {
        if (candles.size <= warmup) return ReplayStats(0.0, 0.0, 0.0, 0)
        var cash = 1.0
        var entry: Double? = null
        var peak = 1.0
        var maxDrawdown = 0.0
        var wins = 0
        var trades = 0
        candles.indices.drop(warmup).forEach { i ->
            val result = StrategyEngine.evaluate(candles.subList(0, i + 1))
            val price = candles[i].close
            if (entry == null && result.action == "BUY") entry = price
            else if (entry != null && result.action == "SELL") {
                val ratio = price / entry!!
                cash *= ratio
                if (ratio > 1.0) wins++
                trades++
                entry = null
            }
            val equity = if (entry == null) cash else cash * price / entry!!
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, (peak - equity) / peak)
        }
        if (entry != null) {
            val ratio = candles.last().close / entry!!
            cash *= ratio
            if (ratio > 1.0) wins++
            trades++
        }
        return ReplayStats(cash - 1.0, maxDrawdown, if (trades == 0) 0.0 else wins.toDouble() / trades, trades)
    }
}
