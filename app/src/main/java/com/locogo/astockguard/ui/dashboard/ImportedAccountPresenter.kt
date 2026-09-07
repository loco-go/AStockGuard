package com.locogo.astockguard.ui.dashboard

import com.locogo.astockguard.data.local.ImportedAccountMetricEntity
import com.locogo.astockguard.integration.ths.ThsAccountMetric
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 账户导入值只覆盖展示；始终注明采集时间，跨日收益明确显示为历史值。 */
object ImportedAccountPresenter {
    fun text(metric: ThsAccountMetric, values: List<ImportedAccountMetricEntity>, now: Long): String? {
        val value = values.find { it.metric == metric.name } ?: return null
        if (!metric.valid(value.value)) return null
        val zone = ZoneId.of("Asia/Shanghai")
        val observed = Instant.ofEpochMilli(value.observedAt).atZone(zone)
        val historicalToday = metric == ThsAccountMetric.TODAY_PNL &&
            observed.toLocalDate() != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val label = if (historicalToday) "历史当日收益" else metric.label
        val formatted = String.format(Locale.CHINA, "%.2f", value.value)
        val source = if (value.source == "THS_MANUAL") "手工核对" else "同花顺导入"
        return "$label\n$formatted ${metric.unit}\n$source · ${observed.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} 快照"
    }
}
