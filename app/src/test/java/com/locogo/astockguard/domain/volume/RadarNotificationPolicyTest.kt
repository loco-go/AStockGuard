package com.locogo.astockguard.domain.volume

import com.locogo.astockguard.domain.trading.DynamicTAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* 文件职责：验证量能雷达重要状态迁移和十分钟提醒冷却，避免后台轮询造成重复通知。 */

class RadarNotificationPolicyTest {
    /** 同一诱多分类和SELL_T动作在十分钟内不得重复通知。 */
    @Test
    fun sameStateInsideCooldownIsSuppressed() {
        val previous = RadarNotificationState(VolumeSignalType.BULL_TRAP, DynamicTAction.SELL_T, 1_000L)

        val notify = RadarNotificationPolicy.shouldNotify(
            previous, VolumeSignalType.BULL_TRAP, DynamicTAction.SELL_T, 1_000L + 9 * 60_000L
        )

        assertFalse(notify)
    }

    /** REAL_BREAKOUT转为BULL_TRAP属于重要状态变化，应绕过普通冷却立即提醒。 */
    @Test
    fun importantStateTransitionBypassesCooldown() {
        val previous = RadarNotificationState(VolumeSignalType.REAL_BREAKOUT, DynamicTAction.HOLD, 1_000L)

        val notify = RadarNotificationPolicy.shouldNotify(
            previous, VolumeSignalType.BULL_TRAP, DynamicTAction.SELL_T, 2_000L
        )

        assertTrue(notify)
    }

    /** NORMAL和WAIT不是重要操作，无论是否存在历史状态都不应发送系统通知。 */
    @Test
    fun normalWaitNeverNotifies() {
        assertFalse(RadarNotificationPolicy.shouldNotify(null, VolumeSignalType.NORMAL, DynamicTAction.WAIT, 1_000L))
    }
}
