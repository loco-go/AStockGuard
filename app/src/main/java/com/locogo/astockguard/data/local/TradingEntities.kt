package com.locogo.astockguard.data.local

/*
 * 文件职责：定义真实成交及交易相关持久化结构；来源和唯一键用于去重，不得与模拟订单混用。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.locogo.astockguard.data.fundflow.FundFlowPoint
import com.locogo.astockguard.domain.strategy.ChartSignal
import com.locogo.astockguard.domain.strategy.StrategyVersions

@Entity(
    tableName = "strategy_signal",
    indices = [Index(value = ["symbol", "time", "action"], unique = true)]
)
data class StrategySignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val time: Long,
    val price: Double,
    val action: String,
    val score: Int,
    val reason: String,
    val strategyVersion: String = StrategyVersions.LEGACY
) {
    companion object {
        fun from(symbol: String, signal: ChartSignal) = StrategySignalEntity(
            symbol = symbol,
            time = signal.timestamp,
            price = signal.price,
            action = signal.action.name,
            score = signal.score,
            reason = signal.reason,
            strategyVersion = signal.strategyVersion
        )
    }
}

@Entity(tableName = "fund_flow", primaryKeys = ["symbol", "date"])
data class FundFlowEntity(
    val symbol: String,
    val date: String,
    @ColumnInfo(name = "main_in") val mainIn: Double?,
    @ColumnInfo(name = "main_out") val mainOut: Double?,
    @ColumnInfo(name = "net_flow") val netFlow: Double,
    val source: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long
) {
    companion object {
        fun from(symbol: String, point: FundFlowPoint, now: Long = System.currentTimeMillis()) = FundFlowEntity(
            symbol = symbol,
            date = point.time,
            mainIn = null,
            mainOut = null,
            netFlow = point.mainNet,
            source = "EASTMONEY",
            cachedAt = now
        )
    }
}
