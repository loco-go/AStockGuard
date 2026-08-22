package com.locogo.astockguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_event")
data class SignalEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val eventAt: Long,
    val fromStage: String,
    val toStage: String,
    val action: String,
    val price: Double,
    val reason: String
)

@Entity(tableName = "trade_record")
data class TradeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tradeAt: Long,
    val code: String,
    val side: String,
    val quantity: Int,
    val price: Double,
    val fee: Double = 0.0,
    val source: String = "USER",
    val note: String = ""
)
