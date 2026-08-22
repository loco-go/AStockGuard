package com.locogo.astockguard.data.local

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
