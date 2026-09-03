package com.locogo.astockguard.data.local

/*
 * 文件职责：定义复盘所需的信号结果与交易统计持久化模型；评价结论不得覆盖最初触发证据。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_event")
data class SignalEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val eventAt: Long,
    val fromStage: String,
    val toStage: String,
    val action: String,
    val price: Double,
    val reason: String
)

@Entity(tableName = "trade_record")
data class TradeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tradeAt: Long,
    val code: String,
    val side: String,
    val quantity: Int,
    val price: Double,
    val fee: Double = 0.0,
    val source: String = "USER",
    val note: String = ""
)
