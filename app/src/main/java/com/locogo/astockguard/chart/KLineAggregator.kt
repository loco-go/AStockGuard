package com.locogo.astockguard.chart

/*
 * 文件职责：把日线按周或月聚合，开高低收量必须遵守标准 OHLCV 语义。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
