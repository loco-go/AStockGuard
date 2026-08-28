package com.locogo.astockguard.integration.ths

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 在辅助服务与前台页面之间传递一次性同步事件，避免持仓更新后仍显示旧数据。 */
object ThsSyncBus {
    private val mutableEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()

    fun notifyDataChanged() {
        mutableEvents.tryEmit(Unit)
    }
}
