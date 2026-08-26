package com.locogo.astockguard.ui.chart

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
