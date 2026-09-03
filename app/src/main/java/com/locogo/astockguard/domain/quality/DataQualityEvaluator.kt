package com.locogo.astockguard.domain.quality

/*
 * 文件职责：把行情、分时、资金流、盘口与新闻的实时性汇总为统一报告；必需数据非实时即阻断 actionable 状态。
 * 架构边界：领域层不直接访问 Android View；需要网络或 Room 的输入由 Repository 在调用前准备。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

import com.locogo.astockguard.MonitorSnapshot
import com.locogo.astockguard.data.fundflow.StockFundFlow
import com.locogo.astockguard.data.level2.Level2Snapshot
import com.locogo.astockguard.data.news.NewsRiskAssessment

enum class DataQualityStatus(val label: String) {
    LIVE("实时"), FRESH("最新"), CACHED("缓存"), SIMULATED("模拟"), MISSING("缺失")
}

data class DataQualityItem(
    val name: String,
    val source: String,
    val status: DataQualityStatus,
    val usableForRealtime: Boolean,
    val requiredForRealtime: Boolean,
    val updatedAt: Long? = null,
    val message: String = ""
)

data class DataQualityReport(
    val realtimeReady: Boolean,
    val items: List<DataQualityItem>,
    val blockingReasons: List<String>
)

/**
 * 将各数据源的缓存、模拟和缺失状态汇总成统一报告。
 * 只有核心行情和当日分时均来自本次远端请求时，才标记为可用于实时策略。
 */
object DataQualityEvaluator {
    fun evaluate(
        snapshot: MonitorSnapshot?,
        minuteCount: Int,
        minuteFromCache: Boolean,
        minuteHistorical: Boolean,
        minuteSource: String = "TENCENT",
        stockFundFlow: StockFundFlow?,
        level2: Level2Snapshot?,
        news: NewsRiskAssessment
    ): DataQualityReport {
        val market = when {
            snapshot == null -> missing("市场行情", required = true)
            snapshot.dataHealth.isStale -> DataQualityItem(
                "市场行情", snapshot.dataHealth.source, DataQualityStatus.CACHED,
                usableForRealtime = false, requiredForRealtime = true,
                updatedAt = snapshot.updatedAt, message = snapshot.dataHealth.message
            )
            else -> DataQualityItem(
                "市场行情", snapshot.dataHealth.source, DataQualityStatus.LIVE,
                usableForRealtime = true, requiredForRealtime = true,
                updatedAt = snapshot.updatedAt, message = snapshot.dataHealth.message
            )
        }
        val minute = when {
            minuteCount == 0 -> missing("当日分时", required = true)
            minuteHistorical -> DataQualityItem(
                "分时数据", "历史行情", DataQualityStatus.CACHED,
                usableForRealtime = false, requiredForRealtime = true,
                message = "历史分时仅用于查看和回放"
            )
            minuteFromCache -> DataQualityItem(
                "当日分时", "ROOM_CACHE", DataQualityStatus.CACHED,
                usableForRealtime = false, requiredForRealtime = true,
                message = "远端分时失败，缓存不得触发实时提醒"
            )
            else -> DataQualityItem(
                "当日分时", minuteSource, DataQualityStatus.LIVE,
                usableForRealtime = true, requiredForRealtime = true,
                message = "$minuteCount 条分钟数据"
            )
        }
        val flow = when {
            stockFundFlow == null || (stockFundFlow.minute.isEmpty() && stockFundFlow.periods.isEmpty()) ->
                missing("个股资金流")
            stockFundFlow.stale -> DataQualityItem(
                "个股资金流", stockFundFlow.source, DataQualityStatus.CACHED,
                usableForRealtime = false, requiredForRealtime = false,
                message = "缓存资金流不参与实时确认"
            )
            stockFundFlow.minuteStale -> DataQualityItem(
                "个股资金流", stockFundFlow.source, DataQualityStatus.FRESH,
                usableForRealtime = false, requiredForRealtime = false,
                message = "日级资金流已更新，但分钟资金流为缓存，不参与盘中买卖确认"
            )
            else -> DataQualityItem(
                "个股资金流", stockFundFlow.source, DataQualityStatus.LIVE,
                usableForRealtime = stockFundFlow.minute.isNotEmpty(), requiredForRealtime = false,
                message = if (stockFundFlow.minute.isEmpty()) "仅有日级资金流" else "分钟资金流可用"
            )
        }
        val orderBook = when {
            level2 == null -> missing("Level-2盘口")
            level2.simulated -> DataQualityItem(
                "Level-2盘口", level2.source, DataQualityStatus.SIMULATED,
                usableForRealtime = false, requiredForRealtime = false,
                updatedAt = level2.updatedAt, message = "模拟盘口仅用于界面和回放"
            )
            level2.source == "IFIND_HTTP_DEPTH_LIMITED" -> DataQualityItem(
                "Level-2盘口", level2.source, DataQualityStatus.FRESH,
                usableForRealtime = false, requiredForRealtime = false,
                updatedAt = level2.updatedAt, message = level2.message
            )
            level2.stale -> DataQualityItem(
                "Level-2盘口", level2.source, DataQualityStatus.CACHED,
                usableForRealtime = false, requiredForRealtime = false,
                updatedAt = level2.updatedAt, message = level2.message
            )
            else -> DataQualityItem(
                "Level-2盘口", level2.source, DataQualityStatus.LIVE,
                usableForRealtime = true, requiredForRealtime = false,
                updatedAt = level2.updatedAt, message = level2.message
            )
        }
        val newsItem = when {
            news.updatedAt <= 0L -> missing("新闻风险")
            news.stale -> DataQualityItem(
                "新闻风险", "NEWS_CACHE", DataQualityStatus.CACHED,
                usableForRealtime = false, requiredForRealtime = false,
                updatedAt = news.updatedAt, message = "缓存新闻仅作风险参考"
            )
            else -> DataQualityItem(
                "新闻风险", "${news.sourceCount}个来源", DataQualityStatus.FRESH,
                usableForRealtime = true, requiredForRealtime = false,
                updatedAt = news.updatedAt, message = "风险层级 ${news.level}"
            )
        }
        val items = listOf(market, minute, flow, orderBook, newsItem)
        val blockers = items.filter { it.requiredForRealtime && !it.usableForRealtime }
            .map { "${it.name}：${it.message.ifBlank { it.status.label }}" }
        return DataQualityReport(blockers.isEmpty(), items, blockers)
    }

    private fun missing(name: String, required: Boolean = false) = DataQualityItem(
        name = name,
        source = "NONE",
        status = DataQualityStatus.MISSING,
        usableForRealtime = false,
        requiredForRealtime = required,
        message = "暂无数据"
    )
}
