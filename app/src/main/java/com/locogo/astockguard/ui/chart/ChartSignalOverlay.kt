package com.locogo.astockguard.ui.chart

/*
 * 文件职责：将真实成交点、计划区间和失效位转换为可视覆盖物，三者必须使用不同语义和样式。
 * 架构边界：生命周期内只收集可观察状态；耗时任务、持久化和网络请求交给 ViewModel/Repository。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import com.locogo.astockguard.domain.strategy.ChartSignal
import org.json.JSONArray
import org.json.JSONObject

object ChartSignalOverlay {
    fun toJson(signals: List<ChartSignal>): JSONArray = JSONArray().apply {
        signals.forEach { signal ->
            put(JSONObject().apply {
                put("timestamp", signal.timestamp)
                put("value", signal.price)
                put("action", signal.action.name)
                put("score", signal.score)
                put("reason", signal.reason)
            })
        }
    }
}
