package com.locogo.astockguard.data.sync

import androidx.room.withTransaction
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.local.AStockDatabase
import com.locogo.astockguard.data.local.PaperAccountEntity
import com.locogo.astockguard.data.local.PaperOrderEntity
import com.locogo.astockguard.data.local.PaperPositionEntity
import com.locogo.astockguard.data.local.SignalEventEntity
import com.locogo.astockguard.data.local.SyncCursorEntity
import com.locogo.astockguard.data.local.SyncRecordMapEntity
import com.locogo.astockguard.data.local.TradeRecordEntity
import org.json.JSONArray
import org.json.JSONObject

class SyncRepository(
    private val settings: SettingsRepository,
    private val database: AStockDatabase,
    private val deviceIdentity: DeviceIdentity,
    private val client: SyncClient = SyncClient()
) {
    private val dao get() = database.cacheDao()

    suspend fun health(): String = client.health(settings.syncBaseUrl, settings.syncToken)

    suspend fun syncOnce(): SyncResult {
        require(settings.syncEnabled) { "同步未启用" }
        require(settings.syncBaseUrl.startsWith("https://")) { "同步地址必须是 HTTPS" }
        require(settings.syncToken.isNotBlank()) { "同步 Token 未配置" }
        val deviceId = deviceIdentity.id
        val state = dao.getSyncCursor() ?: SyncCursorEntity()
        val now = System.currentTimeMillis()
        if (settings.syncProfileUpdatedAt <= 0L) settings.syncProfileUpdatedAt = now
        val outgoing = buildOutgoing(deviceId, state.lastPushAt)

        var cursor = state.remoteCursor
        var pulled = 0
        var first = true
        var loops = 0
        do {
            val response = client.exchange(
                settings.syncBaseUrl,
                settings.syncToken,
                SyncRequest(deviceId = deviceId, cursor = cursor, records = if (first) outgoing else emptyList())
            )
            pulled += applyRemote(deviceId, response.records)
            cursor = response.cursor
            first = false
            loops++
            if (!response.hasMore || loops >= 10) break
        } while (true)

        dao.upsertSyncCursor(SyncCursorEntity(remoteCursor = cursor, lastPushAt = now, lastSyncAt = System.currentTimeMillis()))
        return SyncResult(pushed = outgoing.size, pulled = pulled, cursor = cursor, message = "同步完成")
    }

    private suspend fun buildOutgoing(deviceId: String, lastPushAt: Long): List<SyncRecord> {
        val threshold = (lastPushAt - 5 * 60_000L).coerceAtLeast(0L)
        val result = mutableListOf<SyncRecord>()
        result += SyncRecord(
            id = "PROFILE",
            deviceId = deviceId,
            type = TYPE_PROFILE,
            updatedAt = settings.syncProfileUpdatedAt,
            payload = profilePayload().toString()
        )

        val importedSignals = dao.getImportedLocalIds(TYPE_SIGNAL).toHashSet()
        dao.getSignalEvents(10_000)
            .asSequence()
            .filter { it.eventAt >= threshold && it.id !in importedSignals }
            .forEach { e ->
                result += SyncRecord(
                    id = "$deviceId:$TYPE_SIGNAL:${e.id}", deviceId = deviceId, type = TYPE_SIGNAL,
                    updatedAt = e.eventAt, payload = JSONObject().apply {
                        put("code", e.code); put("eventAt", e.eventAt); put("fromStage", e.fromStage); put("toStage", e.toStage)
                        put("action", e.action); put("price", e.price); put("reason", e.reason)
                    }.toString()
                )
            }

        val importedTrades = dao.getImportedLocalIds(TYPE_TRADE).toHashSet()
        dao.getTradeRecords()
            .asSequence()
            .filter { it.tradeAt >= threshold && it.id !in importedTrades }
            .forEach { t ->
                result += SyncRecord(
                    id = "$deviceId:$TYPE_TRADE:${t.id}", deviceId = deviceId, type = TYPE_TRADE,
                    updatedAt = t.tradeAt, payload = JSONObject().apply {
                        put("tradeAt", t.tradeAt); put("code", t.code); put("side", t.side); put("quantity", t.quantity)
                        put("price", t.price); put("fee", t.fee); put("source", t.source); put("note", t.note)
                    }.toString()
                )
            }

        dao.getPaperAccount()?.let { account ->
            result += SyncRecord(
                id = "PAPER_STATE",
                deviceId = deviceId,
                type = TYPE_PAPER,
                updatedAt = account.updatedAt,
                payload = paperPayload(account, dao.getPaperPositions(), dao.getPaperOrders(2_000)).toString()
            )
        }
        return result.take(MAX_PUSH_RECORDS)
    }

    private suspend fun applyRemote(thisDeviceId: String, records: List<SyncRecord>): Int {
        var applied = 0
        records.sortedBy { it.updatedAt }.forEach { record ->
            if (record.deviceId == thisDeviceId || record.deleted) return@forEach
            when (record.type) {
                TYPE_PROFILE -> if (record.updatedAt > settings.syncProfileUpdatedAt) {
                    applyProfile(JSONObject(record.payload), record.updatedAt); applied++
                }
                TYPE_SIGNAL -> if (dao.hasSyncRecordMap(record.id) == 0) {
                    val o = JSONObject(record.payload)
                    val localId = dao.insertSignalEvent(SignalEventEntity(
                        code = o.optString("code"), eventAt = o.optLong("eventAt", record.updatedAt),
                        fromStage = o.optString("fromStage"), toStage = o.optString("toStage"),
                        action = o.optString("action"), price = o.optDouble("price"), reason = o.optString("reason")
                    ))
                    dao.upsertSyncRecordMap(SyncRecordMapEntity(record.id, TYPE_SIGNAL, localId, System.currentTimeMillis()))
                    applied++
                }
                TYPE_TRADE -> if (dao.hasSyncRecordMap(record.id) == 0) {
                    val o = JSONObject(record.payload)
                    val localId = dao.insertTradeRecord(TradeRecordEntity(
                        tradeAt = o.optLong("tradeAt", record.updatedAt), code = o.optString("code"),
                        side = o.optString("side"), quantity = o.optInt("quantity"), price = o.optDouble("price"),
                        fee = o.optDouble("fee"), source = o.optString("source", "SYNC"), note = o.optString("note")
                    ))
                    dao.upsertSyncRecordMap(SyncRecordMapEntity(record.id, TYPE_TRADE, localId, System.currentTimeMillis()))
                    applied++
                }
                TYPE_PAPER -> {
                    val current = dao.getPaperAccount()
                    if (current == null || record.updatedAt > current.updatedAt) {
                        applyPaper(JSONObject(record.payload)); applied++
                    }
                }
            }
        }
        return applied
    }

    private fun profilePayload() = JSONObject().apply {
        put("watchCodes", settings.watchCodes)
        put("positionsText", settings.positionsText)
        put("positionRatio", settings.positionRatio)
        put("refreshSeconds", settings.refreshSeconds)
        put("newsEnabled", settings.newsEnabled)
        put("newsRefreshMinutes", settings.newsRefreshMinutes)
        put("newsSourcesText", settings.newsSourcesText)
        put("level2ProviderType", settings.level2ProviderType)
        put("level2BaseUrl", settings.level2BaseUrl)
    }

    private fun applyProfile(o: JSONObject, updatedAt: Long) {
        settings.watchCodes = o.optString("watchCodes", settings.watchCodes)
        settings.positionsText = o.optString("positionsText", settings.positionsText)
        settings.positionRatio = o.optDouble("positionRatio", settings.positionRatio)
        settings.refreshSeconds = o.optInt("refreshSeconds", settings.refreshSeconds)
        settings.newsEnabled = o.optBoolean("newsEnabled", settings.newsEnabled)
        settings.newsRefreshMinutes = o.optInt("newsRefreshMinutes", settings.newsRefreshMinutes)
        settings.newsSourcesText = o.optString("newsSourcesText", settings.newsSourcesText)
        settings.level2ProviderType = o.optString("level2ProviderType", settings.level2ProviderType)
        settings.level2BaseUrl = o.optString("level2BaseUrl", settings.level2BaseUrl)
        settings.syncProfileUpdatedAt = updatedAt
    }

    private fun paperPayload(account: PaperAccountEntity, positions: List<PaperPositionEntity>, orders: List<PaperOrderEntity>) = JSONObject().apply {
        put("account", JSONObject().apply { put("initialCash", account.initialCash); put("cash", account.cash); put("updatedAt", account.updatedAt) })
        put("positions", JSONArray().apply { positions.forEach { p -> put(JSONObject().apply { put("code", p.code); put("quantity", p.quantity); put("avgCost", p.avgCost); put("updatedAt", p.updatedAt) }) } })
        put("orders", JSONArray().apply { orders.sortedBy { it.createdAt }.forEach { t -> put(JSONObject().apply {
            put("createdAt", t.createdAt); put("code", t.code); put("side", t.side); put("quantity", t.quantity); put("price", t.price)
            put("fee", t.fee); put("status", t.status); put("source", t.source); put("note", t.note)
        }) } })
    }

    private suspend fun applyPaper(o: JSONObject) = database.withTransaction {
        val accountJson = o.optJSONObject("account") ?: return@withTransaction
        dao.clearPaperOrders(); dao.clearPaperPositions(); dao.clearPaperEquity()
        dao.upsertPaperAccount(PaperAccountEntity(
            initialCash = accountJson.optDouble("initialCash", 100_000.0),
            cash = accountJson.optDouble("cash", 100_000.0),
            updatedAt = accountJson.optLong("updatedAt")
        ))
        val positions = o.optJSONArray("positions") ?: JSONArray()
        for (i in 0 until positions.length()) positions.optJSONObject(i)?.let { p ->
            if (p.optString("code").isNotBlank() && p.optInt("quantity") > 0) dao.upsertPaperPosition(PaperPositionEntity(
                code = p.optString("code"), quantity = p.optInt("quantity"), avgCost = p.optDouble("avgCost"), updatedAt = p.optLong("updatedAt")
            ))
        }
        val orders = o.optJSONArray("orders") ?: JSONArray()
        for (i in 0 until orders.length()) orders.optJSONObject(i)?.let { t ->
            if (t.optString("code").isNotBlank() && t.optInt("quantity") > 0) dao.insertPaperOrder(PaperOrderEntity(
                createdAt = t.optLong("createdAt"), code = t.optString("code"), side = t.optString("side"), quantity = t.optInt("quantity"),
                price = t.optDouble("price"), fee = t.optDouble("fee"), status = t.optString("status", "FILLED"),
                source = t.optString("source", "SYNC"), note = t.optString("note")
            ))
        }
    }

    companion object {
        const val TYPE_PROFILE = "PROFILE"
        const val TYPE_SIGNAL = "SIGNAL_EVENT"
        const val TYPE_TRADE = "TRADE_RECORD"
        const val TYPE_PAPER = "PAPER_STATE"
        private const val MAX_PUSH_RECORDS = 2_000
    }
}
