package com.locogo.astockguard.chart

import java.time.LocalDate
import java.time.temporal.WeekFields

object KLineAggregator {

    fun toWeek(source: List<StockKLine>): List<StockKLine> {
        return source
            .groupBy { weekKey(it.date) }
            .toSortedMap()
            .values
            .map { merge(it) }
    }

    fun toMonth(source: List<StockKLine>): List<StockKLine> {
        return source
            .groupBy { monthKey(it.date) }
            .toSortedMap()
            .values
            .map { merge(it) }
    }

    private fun merge(items: List<StockKLine>): StockKLine {
        val sorted = items.sortedBy { it.date }
        return StockKLine(
            timestamp = sorted.last().timestamp,
            date = sorted.last().date,
            open = sorted.first().open,
            high = sorted.maxOf { it.high },
            low = sorted.minOf { it.low },
            close = sorted.last().close,
            volume = sorted.sumOf { it.volume }
        )
    }

    private fun weekKey(date: String): String {
        val localDate = LocalDate.parse(date)
        val weekFields = WeekFields.ISO
        val weekBasedYear = localDate.get(weekFields.weekBasedYear())
        val week = localDate.get(weekFields.weekOfWeekBasedYear())
        return "%04d-W%02d".format(weekBasedYear, week)
    }

    private fun monthKey(date: String): String = LocalDate.parse(date).withDayOfMonth(1).toString()
}
