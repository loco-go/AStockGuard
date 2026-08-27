package com.locogo.astockguard.ui.chart

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.KLineAggregator
import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.chart.StockKLine
import java.time.LocalDate
import java.time.ZoneId

object ChartDataMapper {
    fun mapDaily(source: List<DailyBar>): List<StockKLine> = source.mapIndexed { index, bar ->
        StockKLine(
            timestamp = runCatching {
                LocalDate.parse(bar.date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrElse { index.toLong() },
            date = bar.date,
            open = bar.open,
            high = bar.high,
            low = bar.low,
            close = bar.close,
            volume = bar.volume
        )
    }

    fun aggregate(source: List<DailyBar>, period: ChartPeriod): List<StockKLine> {
        val daily = mapDaily(source)
        return when (period) {
            ChartPeriod.MINUTE -> emptyList()
            ChartPeriod.DAY -> daily
            ChartPeriod.WEEK -> KLineAggregator.toWeek(daily)
            ChartPeriod.MONTH -> KLineAggregator.toMonth(daily)
        }
    }

    /** 将行情序列聚合为标准的五分钟 OHLC K线。 */
    fun aggregateMinutes(
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
