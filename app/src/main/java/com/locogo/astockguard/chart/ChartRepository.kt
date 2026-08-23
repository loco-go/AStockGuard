package com.locogo.astockguard.chart

interface ChartRepository {
    suspend fun getChart(
        code: String,
        period: ChartPeriod
    ): List<StockKLine>
}

class DefaultChartRepository(
    private val source: ChartDataSource
) : ChartRepository {

    override suspend fun getChart(
        code: String,
        period: ChartPeriod
    ): List<StockKLine> {
        val daily = source.loadDaily(code)
        return when (period) {
            ChartPeriod.DAY -> daily
            ChartPeriod.WEEK -> KLineAggregator.toWeek(daily)
            ChartPeriod.MONTH -> KLineAggregator.toMonth(daily)
        }
    }
}

interface ChartDataSource {
    suspend fun loadDaily(code: String): List<StockKLine>
}
