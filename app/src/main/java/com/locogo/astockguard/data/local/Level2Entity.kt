package com.locogo.astockguard.data.local

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
