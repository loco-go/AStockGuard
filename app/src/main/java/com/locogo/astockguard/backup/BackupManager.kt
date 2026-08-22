package com.locogo.astockguard.backup

import androidx.room.withTransaction
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.local.AStockDatabase
import com.locogo.astockguard.data.local.AiAnalysisEntity
import com.locogo.astockguard.data.local.SignalEventEntity
import com.locogo.astockguard.data.local.SignalStateEntity
import com.locogo.astockguard.data.local.TradeRecordEntity
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(
    private val settings: SettingsRepository,
    private val database: AStockDatabase
) {
    suspend fun exportJson(): String {
        val dao = database.cacheDao()
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("settings", JSONObject().apply {
            put("watchCodes", settings.watchCodes)
            put("positionsText", settings.positionsText)
            put("positionRatio", settings.positionRatio)
            put("refreshSeconds", settings.refreshSeconds)
            put("newsEnabled", settings.newsEnabled)
            put("newsRefreshMinutes", settings.newsRefreshMinutes)
            put("newsSourcesText", settings.newsSourcesText)
            put("level2ProviderType", settings.level2ProviderType)
            put("level2BaseUrl", settings.level2BaseUrl)
            put("primaryType", settings.primaryType)
            put("primaryBaseUrl", settings.primaryBaseUrl)
            put("primaryModel", settings.primaryModel)
            put("backupType", settings.backupType)
            put("backupBaseUrl", settings.backupBaseUrl)
            put("backupModel", settings.backupModel)
            put("extraHeadersJson", settings.extraHeadersJson)
        })
        root.put("signalStates", JSONArray().apply { dao.getSignalStates().forEach { put(it.toJson()) } })
        root.put("signalEvents", JSONArray().apply { dao.getSignalEvents(10_000).forEach { put(it.toJson()) } })
        root.put("tradeRecords", JSONArray().apply { dao.getTradeRecords().forEach { put(it.toJson()) } })
        root.put("aiAnalysis", JSONArray().apply { dao.latestAiAnalysis(5_000).forEach { put(it.toJson()) } })
        root.put("security", JSONObject().apply {
            put("containsApiKeys", false)
            put("containsCookies", false)
            put("containsSessionTokens", false)
            put("containsLevel2Tokens", false)
        })
        return root.toString(2)
    }

    suspend fun importJson(text: String) {
        require(text.length <= MAX_IMPORT_CHARS) { "备份文件过大" }
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "不是 AStockGuard 备份" }
        require(root.optInt("version") in 1..VERSION) { "不支持的备份版本" }
        val s = root.optJSONObject("settings") ?: error("备份缺少 settings")

        val states = root.optJSONArray("signalStates").toSignalStates()
        val events = root.optJSONArray("signalEvents").toSignalEvents()
        val trades = root.optJSONArray("tradeRecords").toTradeRecords()
        val ai = root.optJSONArray("aiAnalysis").toAiAnalysis()

        database.withTransaction {
            val dao = database.cacheDao()
            dao.clearSignalStates(); dao.clearSignalEvents(); dao.clearTradeRecords(); dao.clearAiAnalysis()
            if (states.isNotEmpty()) dao.upsertSignalStates(states)
            if (events.isNotEmpty()) dao.insertSignalEvents(events)
            if (trades.isNotEmpty()) dao.insertTradeRecords(trades)
            if (ai.isNotEmpty()) dao.insertAiAnalyses(ai)
        }

        settings.watchCodes = s.optString("watchCodes", settings.watchCodes)
        settings.positionsText = s.optString("positionsText", settings.positionsText)
        settings.positionRatio = s.optDouble("positionRatio", settings.positionRatio)
        settings.refreshSeconds = s.optInt("refreshSeconds", settings.refreshSeconds)
        settings.newsEnabled = s.optBoolean("newsEnabled", settings.newsEnabled)
        settings.newsRefreshMinutes = s.optInt("newsRefreshMinutes", settings.newsRefreshMinutes)
        settings.newsSourcesText = s.optString("newsSourcesText", settings.newsSourcesText)
        settings.level2ProviderType = s.optString("level2ProviderType", settings.level2ProviderType)
        settings.level2BaseUrl = s.optString("level2BaseUrl", settings.level2BaseUrl)
        settings.primaryType = s.optString("primaryType", settings.primaryType)
        settings.primaryBaseUrl = s.optString("primaryBaseUrl", settings.primaryBaseUrl)
        settings.primaryModel = s.optString("primaryModel", settings.primaryModel)
        settings.backupType = s.optString("backupType", settings.backupType)
        settings.backupBaseUrl = s.optString("backupBaseUrl", settings.backupBaseUrl)
        settings.backupModel = s.optString("backupModel", settings.backupModel)
        settings.extraHeadersJson = s.optString("extraHeadersJson", settings.extraHeadersJson)
    }

    private fun SignalStateEntity.toJson() = JSONObject().apply {
        put("code", code); put("stage", stage); put("lastAction", lastAction); put("consecutiveCount", consecutiveCount)
        put("lastTransitionAt", lastTransitionAt); put("lastNotifiedAt", lastNotifiedAt); put("reason", reason)
    }
    private fun SignalEventEntity.toJson() = JSONObject().apply {
        put("code", code); put("eventAt", eventAt); put("fromStage", fromStage); put("toStage", toStage)
        put("action", action); put("price", price); put("reason", reason)
    }
    private fun TradeRecordEntity.toJson() = JSONObject().apply {
        put("tradeAt", tradeAt); put("code", code); put("side", side); put("quantity", quantity); put("price", price)
        put("fee", fee); put("source", source); put("note", note)
    }
    private fun AiAnalysisEntity.toJson() = JSONObject().apply {
        put("createdAt", createdAt); put("prompt", prompt); put("rawAnswer", rawAnswer); put("marketAction", marketAction)
        put("confidence", confidence); put("targetPositionPct", targetPositionPct); put("strategyJson", strategyJson)
    }

    private fun JSONArray?.toSignalStates() = objects().map { o -> SignalStateEntity(
        code = o.optString("code"), stage = o.optString("stage"), lastAction = o.optString("lastAction"),
        consecutiveCount = o.optInt("consecutiveCount"), lastTransitionAt = o.optLong("lastTransitionAt"),
        lastNotifiedAt = o.optLong("lastNotifiedAt"), reason = o.optString("reason")) }.filter { it.code.isNotBlank() }
    private fun JSONArray?.toSignalEvents() = objects().map { o -> SignalEventEntity(
        code = o.optString("code"), eventAt = o.optLong("eventAt"), fromStage = o.optString("fromStage"),
        toStage = o.optString("toStage"), action = o.optString("action"), price = o.optDouble("price"), reason = o.optString("reason")) }.filter { it.code.isNotBlank() }
    private fun JSONArray?.toTradeRecords() = objects().map { o -> TradeRecordEntity(
        tradeAt = o.optLong("tradeAt"), code = o.optString("code"), side = o.optString("side"), quantity = o.optInt("quantity"),
        price = o.optDouble("price"), fee = o.optDouble("fee"), source = o.optString("source", "RESTORE"), note = o.optString("note")) }.filter { it.code.isNotBlank() && it.quantity > 0 }
    private fun JSONArray?.toAiAnalysis() = objects().map { o -> AiAnalysisEntity(
        createdAt = o.optLong("createdAt"), prompt = o.optString("prompt"), rawAnswer = o.optString("rawAnswer"),
        marketAction = o.optString("marketAction"), confidence = o.optInt("confidence"), targetPositionPct = o.optInt("targetPositionPct"),
        strategyJson = o.optString("strategyJson")) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optJSONObject(i)?.let(::add)
    }

    companion object {
        private const val FORMAT = "ASTOCK_GUARD_BACKUP"
        private const val VERSION = 1
        private const val MAX_IMPORT_CHARS = 20_000_000
    }
}
