package com.locogo.astockguard.data.local

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
