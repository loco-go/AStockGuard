package com.locogo.astockguard.data.local

/*
 * 文件职责：将盘口快照序列化保存到 Room；反序列化失败应降级为不可用而非构造盘口。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "level2_snapshot")
data class Level2SnapshotEntity(
    @PrimaryKey val code: String,
    val payloadJson: String,
    val source: String,
    val simulated: Boolean,
    val cachedAt: Long
)

/**
 * 真实盘口短时序快照。最新快照仍由 [Level2SnapshotEntity] 负责快速读取，本表只保存近期样本，
 * 用于判断买卖压力是否连续，避免单帧挂单变化直接升级为做T证据。
 */
@Entity(
    tableName = "level2_snapshot_history",
    primaryKeys = ["code", "capturedAt"],
    indices = [Index("capturedAt")]
)
data class Level2SnapshotHistoryEntity(
    val code: String,
    val capturedAt: Long,
    val payloadJson: String,
    val source: String,
    val providerUpdatedAt: Long
)
