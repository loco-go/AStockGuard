package com.locogo.astockguard.data.local

/*
 * 文件职责：保存账户现金和资产流水；每条记录必须有明确时间与来源，以支持收益归因。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "account_ledger", indices = [Index("occurredAt")])
data class AccountLedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val type: String,
    val amount: Double,
    val code: String = "",
    val source: String = "USER",
    val note: String = ""
)
