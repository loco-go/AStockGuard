package com.locogo.astockguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 用户确认的账户页面快照；不是成交流水，也不能作为实时策略输入。 */
@Entity(tableName = "imported_account_metric")
data class ImportedAccountMetricEntity(
    @PrimaryKey val metric: String,
    val value: Double,
    val observedAt: Long,
    val importedAt: Long,
    val source: String
)
