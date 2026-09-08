package com.locogo.astockguard.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locogo.astockguard.data.local.AlertRecordEntity
import com.locogo.astockguard.domain.review.MonitorMessageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class MessageRow(val id: Long, val title: String, val summary: String, val detail: String)

/** 消息列表来自 Room；筛选和分页不清除历史记录。 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageCenterViewModel(repository: MonitorMessageRepository) : ViewModel() {
    val filter = MutableStateFlow("")
    private val limit = MutableStateFlow(100)
    val rows = combine(filter, limit) { type, count -> type to count }
        .flatMapLatest { (type, count) -> repository.observe(type, count) }
        .map { records -> records.map(::messageRow) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun select(type: String) { filter.value = type; limit.value = 100 }
    fun more() { limit.value += 100 }
}

internal fun messageRow(record: AlertRecordEntity): MessageRow {
    val evidence = runCatching { JSONObject(record.evidenceJson) }.getOrDefault(JSONObject())
    val type = when (record.alertType) {
        "DYNAMIC_T", "T_PLAN" -> "做T"
        "VOLUME_RADAR" -> "量能"
        "SECTOR_FLOW" -> "板块资金"
        else -> "交易信号"
    }
    val title = evidence.optString("messageTitle").ifBlank { "${record.name} · $type · ${record.action}" }
    val body = evidence.optString("messageBody").ifBlank {
        buildString {
            if (record.price > 0) append("触发价格 ${record.price} 元\n")
            if (record.targetPrice > 0) append("目标参考 ${record.targetPrice} 元\n")
            if (record.stopPrice > 0) append("失效参考 ${record.stopPrice} 元\n")
            append(record.reason)
        }
    }
    val delivery = when (evidence.optString("delivery")) {
        "POSTED" -> "已提交通知"
        "SAVED_ONLY" -> "已保存，未推送"
        "PENDING" -> "已记录，推送状态待确认"
        else -> "历史记录"
    }
    val time = "${record.signalDate} ${record.signalTime.take(8)}"
    return MessageRow(record.id, title, "$time · $delivery\n$body",
        "$title\n$time · $delivery\n\n$body\n\n数据来源：${record.dataSource}\n策略版本：${record.strategyVersion}")
}
