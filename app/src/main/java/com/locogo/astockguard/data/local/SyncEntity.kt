package com.locogo.astockguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val id: Int = 1,
    val remoteCursor: Long = 0L,
    val lastPushAt: Long = 0L,
    val lastSyncAt: Long = 0L
)

@Entity(tableName = "sync_record_map")
data class SyncRecordMapEntity(
    @PrimaryKey val recordId: String,
    val entityType: String,
    val localId: Long,
    val importedAt: Long
)
