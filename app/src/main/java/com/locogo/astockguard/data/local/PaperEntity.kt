package com.locogo.astockguard.data.local

/*
 * 文件职责：定义模拟账户、模拟持仓、订单和净值实体；表名与真实交易表分离是安全边界。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paper_account")
data class PaperAccountEntity(
    @PrimaryKey val id: Int = 1,
    val initialCash: Double,
    val cash: Double,
    val updatedAt: Long
)

@Entity(tableName = "paper_position")
data class PaperPositionEntity(
    @PrimaryKey val code: String,
    val quantity: Int,
    val avgCost: Double,
    val updatedAt: Long
)

@Entity(tableName = "paper_order")
data class PaperOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val code: String,
    val side: String,
    val quantity: Int,
    val price: Double,
    val fee: Double,
    val status: String,
    val source: String,
    val note: String
)

@Entity(tableName = "paper_equity")
data class PaperEquityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordedAt: Long,
    val equity: Double,
    val cash: Double,
    val marketValue: Double
)
