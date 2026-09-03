package com.locogo.astockguard.data.local

/*
 * 文件职责：定义行情、持仓及通用缓存实体和映射；持久化单位、主键和时间戳必须保持向后兼容。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.Quote

@Entity(tableName = "quote_cache")
data class QuoteCacheEntity(
    @PrimaryKey val code: String,
    val name: String,
    val time: String,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val latest: Double?,
    val previousClose: Double?,
    val changeRatio: Double?,
    val volume: Double?,
    val amount: Double?,
    val vwap: Double?,
    val ma5: Double?,
    val ma10: Double?,
    val r2Score: Int,
    val r2Grade: String,
    val r2Reason: String,
    val cachedAt: Long
)

@Entity(tableName = "daily_bar_cache", primaryKeys = ["code", "date"])
data class DailyBarCacheEntity(
    val code: String,
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val cachedAt: Long
)

@Entity(tableName = "ai_analysis")
data class AiAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val prompt: String,
    val rawAnswer: String,
    val marketAction: String,
    val confidence: Int,
    val targetPositionPct: Int,
    val strategyJson: String
)

fun Quote.toCacheEntity(now: Long = System.currentTimeMillis()) = QuoteCacheEntity(
    code, name, time, open, high, low, latest, previousClose, changeRatio, volume, amount,
    vwap, ma5, ma10, r2Score, r2Grade, r2Reason, now
)

fun QuoteCacheEntity.toModel() = Quote(
    code, name, time, open, high, low, latest, previousClose, changeRatio, volume, amount,
    vwap, ma5, ma10, r2Score, r2Grade, r2Reason
)

fun DailyBar.toCacheEntity(code: String, now: Long = System.currentTimeMillis()) =
    DailyBarCacheEntity(code, date, open, close, high, low, volume, now)

fun DailyBarCacheEntity.toModel() = DailyBar(date, open, close, high, low, volume)

@Entity(tableName = "minute_bar_cache", primaryKeys = ["code", "date", "time"])
data class MinuteBarCacheEntity(
    val code: String,
    val date: String,
    val time: String,
    val intervalMinutes: Int,
    val price: Double,
    val avgPrice: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double,
    val cachedAt: Long
)

fun MinuteBar.toCacheEntity(
    code: String,
    date: String,
    intervalMinutes: Int = 1,
    now: Long = System.currentTimeMillis()
) = MinuteBarCacheEntity(code, date, time, intervalMinutes, price, avgPrice, high, low, volume, amount, now)

fun MinuteBarCacheEntity.toModel() = MinuteBar(time, price, avgPrice, high, low, volume, amount)

@Entity(tableName = "fund_flow_cache", primaryKeys = ["code", "period", "time"])
data class FundFlowCacheEntity(
    val code: String,
    val period: String,
    val time: String,
    val mainNet: Double,
    val smallNet: Double,
    val mediumNet: Double,
    val largeNet: Double,
    val superLargeNet: Double,
    val cachedAt: Long
) {
    fun toModel() = com.locogo.astockguard.data.fundflow.FundFlowPoint(time, mainNet, smallNet, mediumNet, largeNet, superLargeNet)

    companion object {
        fun from(code: String, period: String, model: com.locogo.astockguard.data.fundflow.FundFlowPoint) = FundFlowCacheEntity(
            code, period, model.time, model.mainNet, model.smallNet, model.mediumNet, model.largeNet, model.superLargeNet, System.currentTimeMillis()
        )
    }
}

@Entity(tableName = "sector_fund_flow_cache", primaryKeys = ["type", "code"])
data class SectorFundFlowCacheEntity(
    val type: String,
    val code: String,
    val name: String,
    val changePct: Double,
    val mainNet: Double,
    val mainPct: Double,
    val superLargeNet: Double,
    val largeNet: Double,
    val mediumNet: Double,
    val smallNet: Double,
    val leadStockName: String,
    val leadStockCode: String,
    val cachedAt: Long
) {
    fun toModel() = com.locogo.astockguard.data.fundflow.SectorFundFlow(
        code, name, type, changePct, mainNet, mainPct, superLargeNet, largeNet, mediumNet, smallNet, leadStockName, leadStockCode
    )

    companion object {
        fun from(model: com.locogo.astockguard.data.fundflow.SectorFundFlow) = SectorFundFlowCacheEntity(
            model.type, model.code, model.name, model.changePct, model.mainNet, model.mainPct,
            model.superLargeNet, model.largeNet, model.mediumNet, model.smallNet,
            model.leadStockName, model.leadStockCode, System.currentTimeMillis()
        )
    }
}
