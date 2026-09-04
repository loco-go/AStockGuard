package com.locogo.astockguard.data.local

/*
 * 文件职责：持久化量能雷达冷却状态和多观察周期评价，保证服务重启后不重复提醒且历史结论可审计。
 * 数据边界：表中只保存结构化策略证据，不保存接口Token、Cookie或供应商原始敏感响应。
 */

import androidx.room.Entity
import androidx.room.Index

/** 每只证券最后一次已发送雷达提醒，主键code保证冷却状态只有一份。 */
@Entity(tableName = "volume_radar_state", primaryKeys = ["code"])
data class VolumeRadarStateEntity(
    val code: String,
    val signalType: String,
    val action: String,
    val lastNotifiedAt: Long,
    val referencePrice: Double,
    val strategyVersion: String
)

/**
 * 单条雷达提醒在一个观察周期上的结果；同一alertId和horizonMinutes只能评价一次。
 */
@Entity(
    tableName = "volume_signal_outcome",
    primaryKeys = ["alertId", "horizonMinutes"],
    indices = [Index(value = ["evaluatedAt"])]
)
data class VolumeSignalOutcomeEntity(
    val alertId: Long,
    val horizonMinutes: Int,
    val evaluatedAt: Long,
    val futurePrice: Double,
    val returnPct: Double,
    val maxFavorablePct: Double,
    val maxAdversePct: Double,
    val effective: Boolean
)
