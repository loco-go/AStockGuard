package com.locogo.astockguard.domain.review

import com.locogo.astockguard.data.local.AlertRecordEntity
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.fundflow.SectorFundFlowResult
import com.locogo.astockguard.domain.volume.IntradayRadarSnapshot
import com.locogo.astockguard.domain.trading.DynamicTAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 消息复用提醒事实表及既有备份，通知栏清除不删除历史；不同类型独立冷却。 */
class MonitorMessageRepository(private val dao: CacheDao) {
    private val mutex = Mutex()
    fun observe(type: String, limit: Int) = dao.observeMessages(type, limit)

    suspend fun recordDynamicT(name: String, radar: IntradayRadarSnapshot, now: Long): AlertRecordEntity? = mutex.withLock {
        if (!MonitorMessagePolicy.fresh(radar)) return@withLock null
        val plan = radar.plan
        val previous = dao.latestTypedMessage(plan.code, "DYNAMIC_T")
        if (!MonitorMessagePolicy.due(previous, plan.action.name, now, 10 * 60_000L)) return@withLock null
        val body = buildString {
            append("${MonitorMessagePolicy.actionLabel(plan.action)}\n参考价 ${money(plan.referencePrice)} 元")
            if (plan.action in setOf(DynamicTAction.SELL_T, DynamicTAction.REDUCE)) {
                append(" · 建议 ${plan.suggestedQuantity} 股\n卖出参考 ${money(plan.suggestedSellPrice)} 元")
                if (plan.action == DynamicTAction.SELL_T) append(" · 回补观察区 ${money(plan.suggestedBuybackLow)}–${money(plan.suggestedBuybackHigh)} 元")
                append("\n预计成本 ${money(plan.estimatedCosts)} 元 · 预计净收益 ${money(plan.estimatedNetProfit)} 元")
            } else if (plan.action == DynamicTAction.WAIT_BUYBACK) {
                append("\n回补观察区 ${money(plan.suggestedBuybackLow)}–${money(plan.suggestedBuybackHigh)} 元")
                append(if (plan.buybackEligible) " · 条件改善，仍需人工核对" else " · 等待条件满足")
            }
            append("\n${plan.reasons.joinToString("；")}")
            append("\n仅为执行辅助，提醒不代表成交。")
        }
        val evidence = JSONObject().apply {
            put("quantity", plan.suggestedQuantity); put("sellPrice", plan.suggestedSellPrice)
            put("buybackLow", plan.suggestedBuybackLow); put("buybackHigh", plan.suggestedBuybackHigh)
            put("estimatedCosts", plan.estimatedCosts); put("estimatedNetProfit", plan.estimatedNetProfit)
            put("volumeScore", radar.analysis.signal.volumeScore)
            put("dataFresh", true)
        }
        insert("DYNAMIC_T", plan.code, name, plan.action.name, plan.referencePrice,
            "$name · 做T · ${MonitorMessagePolicy.actionLabel(plan.action)}", body, radar.dataSource, now, evidence)
    }

    suspend fun recordSectorFlow(flow: SectorFundFlowResult, now: Long): AlertRecordEntity? = mutex.withLock {
        val code = "SECTOR_${flow.type}"
        val previous = dao.latestTypedMessage(code, "SECTOR_FLOW")
        if (!MonitorMessagePolicy.due(previous, "INFO", now, 5 * 60_000L)) return@withLock null
        val rows = flow.rows.filter { it.mainNet.isFinite() }.distinctBy { it.code }
        val fresh = !flow.stale && rows.isNotEmpty()
        val leaders = rows.filter { it.mainNet > 0 }.sortedByDescending { it.mainNet }.take(5)
        val body = buildString {
            append(if (fresh) "行业主力净流入排行（数据源返回范围）" else "板块资金缓存/不可用，仅供查看")
            if (leaders.isEmpty()) append("\n返回数据中暂无净流入板块")
            leaders.forEach { append("\n${it.name}：净流入 ${money(it.mainNet / 10000.0)} 万元") }
            append("\n市场板块概览，不代表持仓所属板块；不作为买卖触发条件。")
        }
        insert("SECTOR_FLOW", code, "行业板块", "INFO", 0.0, "板块资金 · 行业净流入", body,
            flow.source, now, JSONObject().put("dataFresh", fresh).put("rowCount", rows.size))
    }

    suspend fun latestVolume(code: String): AlertRecordEntity? = dao.latestTypedMessage(code, "VOLUME_RADAR")

    /** 保存通知文案后再调用系统；失败/权限关闭也保留历史，供消息页查看。 */
    suspend fun prepare(record: AlertRecordEntity, title: String, body: String): AlertRecordEntity {
        val evidence = JSONObject(record.evidenceJson).put("messageTitle", title).put("messageBody", body).put("delivery", "PENDING")
        dao.updateMessageEvidence(record.id, evidence.toString())
        return record.copy(evidenceJson = evidence.toString())
    }

    suspend fun delivery(record: AlertRecordEntity, posted: Boolean) {
        dao.updateMessageEvidence(record.id, JSONObject(record.evidenceJson)
            .put("delivery", if (posted) "POSTED" else "SAVED_ONLY").toString())
    }

    private suspend fun insert(type: String, code: String, name: String, action: String, price: Double,
        title: String, body: String, source: String, now: Long, evidence: JSONObject): AlertRecordEntity? {
        val point = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai"))
        val record = AlertRecordEntity(alertKey = "$type|$code|${now / 60_000}|$action", code = code, name = name,
            signalAt = now, signalDate = point.toLocalDate().toString(), signalTime = point.toLocalTime().toString(),
            action = action, price = price, score = 0, strategyVersion = "MONITOR_MESSAGES_V1",
            source = "${type}_MESSAGE", dataSource = source, reason = body, alertType = type, status = "RECORDED",
            evidenceJson = evidence.put("messageTitle", title).put("messageBody", body).put("delivery", "PENDING").toString())
        val id = dao.insertAlertRecord(record)
        return if (id == -1L) null else record.copy(id = id)
    }

    private fun money(value: Double) = String.format(Locale.CHINA, "%.2f", value)
}

object MonitorMessagePolicy {
    fun due(previous: AlertRecordEntity?, action: String, now: Long, cooldown: Long): Boolean =
        previous == null || previous.action != action || now - previous.signalAt >= cooldown

    fun fresh(radar: IntradayRadarSnapshot): Boolean = !radar.minuteSeries.fromCache && !radar.minuteSeries.isHistorical &&
        radar.analysis.features.dataFresh && radar.plan.referencePrice.isFinite() && radar.plan.referencePrice > 0

    fun notificationTag(type: String, code: String) = "astock.$type.$code"

    fun actionLabel(action: DynamicTAction) = when (action) {
        DynamicTAction.SELL_T -> "卖出交易仓"
        DynamicTAction.REDUCE -> "减仓优先"
        DynamicTAction.HOLD -> "持有，暂缓T出"
        DynamicTAction.WAIT_BUYBACK -> "等待回补"
        DynamicTAction.WAIT -> "观察等待"
        DynamicTAction.NO_T -> "暂不做T"
    }
}
