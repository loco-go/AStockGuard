package com.locogo.astockguard.ui.dashboard

import com.locogo.astockguard.data.local.ImportedAccountMetricEntity
import com.locogo.astockguard.integration.ths.ThsAccountMetric
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 首页只保留标题和数值两行；来源与时间在导入页核对，跨日收益仍标明历史。 */
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
        return "$label\n$formatted${if (metric == ThsAccountMetric.POSITION_PCT) "%" else ""}"
    }
}
