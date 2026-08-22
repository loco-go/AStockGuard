package com.locogo.astockguard.data.sync

data class SyncRecord(
    val id: String,
    val deviceId: String,
    val type: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val payload: String
)

data class SyncRequest(
    val deviceId: String,
    val cursor: Long,
    val records: List<SyncRecord>
)

data class SyncResponse(
    val cursor: Long,
    val records: List<SyncRecord>,
    val hasMore: Boolean = false
)

data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val cursor: Long = 0,
    val message: String = ""
)
