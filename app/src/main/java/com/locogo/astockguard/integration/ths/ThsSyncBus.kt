package com.locogo.astockguard.integration.ths

/*
 * 文件职责：传递识别服务与前台之间的短生命周期结构化事件；Room 才是需要跨进程重启保留的数据事实来源。
 * 架构边界：集成层只读取用户授权的数据，不保存整页原文，不执行真实交易。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

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
