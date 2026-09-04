package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.domain.trading.DynamicTAction

/*
 * 文件职责：集中判断量能雷达状态变化是否值得发送系统提醒，避免后台服务散落重复冷却条件。
 * Phase 4先使用进程内状态；Phase 5迁移到Room后仍复用相同纯函数规则。
 */

/** 上一次已发送提醒的最小状态，时间使用epoch毫秒，便于后续直接持久化。 */
data class RadarNotificationState(
    val signalType: VolumeSignalType,
    val action: DynamicTAction,
    val notifiedAt: Long
)

object RadarNotificationPolicy {
    /**
     * 判断当前状态是否应提醒。
     * 同分类且同动作在冷却期内禁止重复；分类或动作实质变化时立即提醒；普通、弱突破和无信号不通知。
     */
    fun shouldNotify(
        previous: RadarNotificationState?,
        currentType: VolumeSignalType,
        currentAction: DynamicTAction,
        now: Long,
        cooldownMs: Long = 10 * 60_000L
    ): Boolean {
        if (!isImportant(currentType, currentAction)) return false
        if (previous == null) return true
        val changed = previous.signalType != currentType || previous.action != currentAction
        return changed || now - previous.notifiedAt >= cooldownMs
    }

    /** 只把真实突破、诱多、衰竭以及实际减仓类动作视为重要变化，避免每分钟用WAIT打扰用户。 */
    private fun isImportant(type: VolumeSignalType, action: DynamicTAction): Boolean =
        type in setOf(VolumeSignalType.REAL_BREAKOUT, VolumeSignalType.BULL_TRAP, VolumeSignalType.EXHAUSTION) ||
            action in setOf(DynamicTAction.SELL_T, DynamicTAction.REDUCE)
}
