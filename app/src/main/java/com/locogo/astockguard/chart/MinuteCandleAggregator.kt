package com.locogo.astockguard.chart

/*
 * 文件职责：把逐笔或细粒度行情聚合为分钟蜡烛；同一分钟内正确维护开高低收与累计成交量。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import com.locogo.astockguard.MinuteBar

/**
 * 将不同粒度的分时行情聚合为 OHLC K线。
 *
 * 成交量接口既可能返回逐笔增量，也可能返回当日累计值，因此这里会先识别口径，
 * 再计算每根K线的真实增量，避免量能策略被累计值放大。
 */
object MinuteCandleAggregator {
    fun aggregate(
        source: List<MinuteBar>,
        sourceIntervalMinutes: Int = 1,
        targetIntervalMinutes: Int = 5
    ): List<MinuteCandle> {
        require(sourceIntervalMinutes > 0) { "sourceIntervalMinutes must be positive" }
        require(targetIntervalMinutes >= sourceIntervalMinutes) { "target interval cannot be smaller than source" }
        if (source.isEmpty()) return emptyList()
        val bucketSize = (targetIntervalMinutes / sourceIntervalMinutes).coerceAtLeast(1)
        val pairCount = source.size - 1
        val cumulativeVolume = pairCount == 0 ||
            source.zipWithNext().count { (a, b) -> b.volume >= a.volume } >= (pairCount * 3 + 3) / 4
        return source.chunked(bucketSize).mapIndexed { bucketIndex, bars ->
            val firstIndex = bucketIndex * bucketSize
            val volume = bars.indices.sumOf { offset ->
                val index = firstIndex + offset
                if (!cumulativeVolume) source[index].volume.coerceAtLeast(0.0)
                else if (index == 0) source[index].volume.coerceAtLeast(0.0)
                else (source[index].volume - source[index - 1].volume).coerceAtLeast(0.0)
            }
            MinuteCandle(
                time = bars.first().time,
                open = bars.first().price,
                high = bars.maxOf { maxOf(it.high, it.price) },
                low = bars.minOf { minOf(it.low, it.price) },
                close = bars.last().price,
                average = bars.last().avgPrice,
                volume = volume
            )
        }
    }
}
