package com.locogo.astockguard.data.local

/*
 * 文件职责：保存提醒动作、触发价格、目标、止损、证据与评价；字段快照保证未来策略升级后仍可复现旧提醒。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

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
    /** 量能雷达分类；旧提醒为空字符串，以保持历史记录语义不变。 */
    val signalType: String = "",
    /** 信号置信度0..100；旧提醒默认0代表该版本尚未记录置信度。 */
    val confidence: Int = 0,
    val status: String = "PENDING",
    val evaluatedAt: Long = 0,
    val exitPrice: Double = 0.0,
    val netEdgePct: Double = 0.0,
    val maxFavorablePct: Double = 0.0,
    val maxAdversePct: Double = 0.0,
    val horizonBars: Int = 6
)
