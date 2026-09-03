package com.locogo.astockguard

/*
 * 文件职责：向前台界面广播监控服务的最新快照；仅保存进程内状态，不代替 Room 持久化。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object MonitorBus {
    private val _snapshot = MutableStateFlow<MonitorSnapshot?>(null)
    val snapshot: StateFlow<MonitorSnapshot?> = _snapshot
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun update(value: MonitorSnapshot) {
        _snapshot.value = value
    }

    /** 同步后台服务的真实运行状态，供启动/停止按钮即时切换视觉状态。 */
    fun updateRunning(value: Boolean) {
        _running.value = value
    }
}
