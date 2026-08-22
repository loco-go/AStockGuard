package com.locogo.astockguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "level2_snapshot")
data class Level2SnapshotEntity(
    @PrimaryKey val code: String,
    val payloadJson: String,
    val source: String,
    val simulated: Boolean,
    val cachedAt: Long
)
