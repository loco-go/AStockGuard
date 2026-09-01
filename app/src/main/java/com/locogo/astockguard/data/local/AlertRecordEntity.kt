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
        Index(value = ["status"]),
        Index(value = ["alertType"])
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
    /** INTRADAY_SIGNAL 为原分时信号，T_PLAN 为资金流/盘口做T提醒。 */
    val alertType: String = "INTRADAY_SIGNAL",
    /** 计划自带目标/止损价；旧信号为0时继续使用固定百分比评价。 */
    val targetPrice: Double = 0.0,
    val stopPrice: Double = 0.0,
    /** 仅保存结构化策略证据，不保存Token或接口原始响应。 */
    val evidenceJson: String = "{}",
    val status: String = "PENDING",
    val evaluatedAt: Long = 0,
    val exitPrice: Double = 0.0,
    val netEdgePct: Double = 0.0,
    val maxFavorablePct: Double = 0.0,
    val maxAdversePct: Double = 0.0,
    val horizonBars: Int = 6
)
