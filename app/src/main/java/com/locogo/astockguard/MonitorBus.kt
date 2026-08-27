package com.locogo.astockguard

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
