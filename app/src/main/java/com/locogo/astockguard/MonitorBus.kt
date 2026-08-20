package com.locogo.astockguard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object MonitorBus {
    private val _snapshot = MutableStateFlow<MonitorSnapshot?>(null)
    val snapshot: StateFlow<MonitorSnapshot?> = _snapshot

    fun update(value: MonitorSnapshot) {
        _snapshot.value = value
    }
}
