package com.locogo.astockguard.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 真正发送到系统通知栏的分时提醒；推荐区间不会写入此表。 */
@Entity(
    tableName = "alert_record",
    indices = [
        Index(value = ["alertKey"], unique = true),
        Index(value = ["signalAt"]),
        Index(value = ["status"])
    ]
)
data class AlertRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alertKey: String,
    val code: String,
    val name: String,
    val signalAt: Long,
    val signalDate: String,
    val signalTime: String,
    val action: String,
    val price: Double,
    val score: Int,
    val strategyVersion: String,
    val source: String,
    val dataSource: String,
    val reason: String,
    val status: String = "PENDING",
    val evaluatedAt: Long = 0,
    val exitPrice: Double = 0.0,
    val netEdgePct: Double = 0.0,
    val maxFavorablePct: Double = 0.0,
    val maxAdversePct: Double = 0.0,
    val horizonBars: Int = 6
)
