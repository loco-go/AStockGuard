package com.locogo.astockguard.chart

object KLineAggregator {

    fun toWeek(source: List<StockKLine>): List<StockKLine> {
        return source.groupBy { weekKey(it.date) }
            .values
            .map { merge(it) }
    }

    fun toMonth(source: List<StockKLine>): List<StockKLine> {
        return source.groupBy { monthKey(it.date) }
            .values
            .map { merge(it) }
    }

    private fun merge(items: List<StockKLine>): StockKLine {
        val sorted = items.sortedBy { it.date }
        return StockKLine(
            timestamp = sorted.last().timestamp,
            date = sorted.last().date,
            open = sorted.first().open,
            high = items.maxOf { it.high },
            low = items.minOf { it.low },
            close = sorted.last().close,
            volume = items.sumOf { it.volume }
        )
    }

    private fun weekKey(date: String): String = date.take(7) + "-" + date
    private fun monthKey(date: String): String = date.take(7)
}
