package com.locogo.astockguard.data.local

/*
 * 文件职责：持久化信号生命周期与冷却时间；重启应用后仍应保持通知去重状态。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_state")
data class SignalStateEntity(
    @PrimaryKey val code: String,
    val stage: String,
    val lastAction: String,
    val consecutiveCount: Int,
    val lastTransitionAt: Long,
    val lastNotifiedAt: Long,
    val reason: String
)
