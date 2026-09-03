package com.locogo.astockguard.chart

/*
 * 文件职责：向图表层提供周期行情并隔离具体数据来源；图表只消费规范化序列。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
            ChartPeriod.MINUTE -> error("Minute data uses the dedicated minute-series repository path")
            ChartPeriod.DAY -> daily
            ChartPeriod.WEEK -> KLineAggregator.toWeek(daily)
            ChartPeriod.MONTH -> KLineAggregator.toMonth(daily)
        }
    }
}

interface ChartDataSource {
    suspend fun loadDaily(code: String): List<StockKLine>
}
