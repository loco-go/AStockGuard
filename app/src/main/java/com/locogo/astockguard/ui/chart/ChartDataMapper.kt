package com.locogo.astockguard.ui.chart

import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.KLineAggregator
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
}
