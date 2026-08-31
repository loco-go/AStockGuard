package com.locogo.astockguard.data.local

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
