package com.locogo.astockguard.backup

/*
 * 文件职责：导出和恢复允许备份的本地结构化数据；API Key、Cookie、Session 和 Level2 Token 永不进入备份。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.withTransaction
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.local.AStockDatabase
import com.locogo.astockguard.data.local.AccountLedgerEntity
import com.locogo.astockguard.data.local.AlertRecordEntity
import com.locogo.astockguard.data.local.AiAnalysisEntity
import com.locogo.astockguard.data.local.PaperAccountEntity
import com.locogo.astockguard.data.local.PaperOrderEntity
import com.locogo.astockguard.data.local.PaperPositionEntity
import com.locogo.astockguard.data.local.SignalEventEntity
import com.locogo.astockguard.data.local.SignalStateEntity
import com.locogo.astockguard.data.local.TradeRecordEntity
import com.locogo.astockguard.data.local.VolumeRadarStateEntity
import com.locogo.astockguard.data.local.VolumeSignalOutcomeEntity
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
            put("marketDataSource", settings.marketDataSource)
            put("positionsText", settings.positionsText)
            put("positionRatio", settings.positionRatio)
            put("cashBalance", settings.cashBalance ?: JSONObject.NULL)
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
        root.put("accountLedgers", JSONArray().apply { dao.getAccountLedgers().forEach { put(it.toJson()) } })
        root.put("alertRecords", JSONArray().apply { dao.getAlertRecords(10_000).forEach { put(it.toJson()) } })
        root.put("volumeRadarStates", JSONArray().apply { dao.getAllVolumeRadarStates().forEach { put(it.toJson()) } })
        root.put("volumeSignalOutcomes", JSONArray().apply { dao.getAllVolumeSignalOutcomes().forEach { put(it.toJson()) } })
        root.put("aiAnalysis", JSONArray().apply { dao.latestAiAnalysis(5_000).forEach { put(it.toJson()) } })
        root.put("paperAccount", dao.getPaperAccount()?.toJson() ?: JSONObject.NULL)
        root.put("paperPositions", JSONArray().apply { dao.getPaperPositions().forEach { put(it.toJson()) } })
        root.put("paperOrders", JSONArray().apply { dao.getPaperOrders(10_000).forEach { put(it.toJson()) } })
        root.put("security", JSONObject().apply {
            put("containsApiKeys", false)
            put("containsCookies", false)
            put("containsSessionTokens", false)
            put("containsLevel2Tokens", false)
            put("containsIFindTokens", false)
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
        val ledgers = root.optJSONArray("accountLedgers").toAccountLedgers()
        val alerts = root.optJSONArray("alertRecords").toAlertRecords()
        val radarStates = root.optJSONArray("volumeRadarStates").toVolumeRadarStates()
        val radarOutcomes = root.optJSONArray("volumeSignalOutcomes").toVolumeSignalOutcomes()
        val ai = root.optJSONArray("aiAnalysis").toAiAnalysis()
        val paperAccount = root.optJSONObject("paperAccount")?.toPaperAccount()
        val paperPositions = root.optJSONArray("paperPositions").toPaperPositions()
        val paperOrders = root.optJSONArray("paperOrders").toPaperOrders()

        database.withTransaction {
            val dao = database.cacheDao()
            dao.clearSignalStates(); dao.clearSignalEvents(); dao.clearTradeRecords(); dao.clearAiAnalysis()
            if (states.isNotEmpty()) dao.upsertSignalStates(states)
            if (events.isNotEmpty()) dao.insertSignalEvents(events)
            if (trades.isNotEmpty()) dao.insertTradeRecords(trades)
            // 旧版备份没有账户流水字段，导入时保留设备上已有流水，避免意外抹掉收益基准。
            if (root.has("accountLedgers")) {
                dao.clearAccountLedgers()
                if (ledgers.isNotEmpty()) dao.insertAccountLedgers(ledgers)
            }
            // 与账户流水相同，导入旧备份时保留本机提醒历史，避免无提示的数据丢失。
            if (root.has("alertRecords")) {
                dao.clearAlertRecords()
                if (alerts.isNotEmpty()) dao.insertAlertRecords(alerts)
            }
            // 雷达状态与结果引用提醒ID，只有新格式备份同时包含相关数组时才整体替换。
            if (root.has("volumeRadarStates") || root.has("volumeSignalOutcomes")) {
                dao.clearVolumeRadarStates(); dao.clearVolumeSignalOutcomes()
                radarStates.forEach { dao.upsertVolumeRadarState(it) }
                radarOutcomes.forEach { dao.upsertVolumeSignalOutcome(it) }
            }
            if (ai.isNotEmpty()) dao.insertAiAnalyses(ai)
            if (root.has("paperAccount") || root.has("paperPositions") || root.has("paperOrders")) {
                dao.clearPaperOrders(); dao.clearPaperPositions(); dao.clearPaperEquity()
                paperAccount?.let { dao.upsertPaperAccount(it) }
                paperPositions.forEach { dao.upsertPaperPosition(it) }
                paperOrders.sortedBy { it.createdAt }.forEach { dao.insertPaperOrder(it.copy(id = 0)) }
            }
        }

        settings.watchCodes = s.optString("watchCodes", settings.watchCodes)
        settings.marketDataSource = s.optString("marketDataSource", settings.marketDataSource)
        settings.positionsText = s.optString("positionsText", settings.positionsText)
        settings.positionRatio = s.optDouble("positionRatio", settings.positionRatio)
        settings.cashBalance = when {
            !s.has("cashBalance") -> settings.cashBalance
            s.isNull("cashBalance") -> null
            else -> s.optDouble("cashBalance")
        }
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
    private fun AccountLedgerEntity.toJson() = JSONObject().apply {
        put("occurredAt", occurredAt); put("type", type); put("amount", amount); put("code", code)
        put("source", source); put("note", note)
    }
    private fun AlertRecordEntity.toJson() = JSONObject().apply {
        put("id", id); put("alertKey", alertKey); put("code", code); put("name", name); put("signalAt", signalAt)
        put("signalDate", signalDate); put("signalTime", signalTime); put("action", action); put("price", price)
        put("score", score); put("strategyVersion", strategyVersion); put("source", source); put("dataSource", dataSource)
        put("reason", reason); put("status", status); put("evaluatedAt", evaluatedAt); put("exitPrice", exitPrice)
        put("netEdgePct", netEdgePct); put("maxFavorablePct", maxFavorablePct); put("maxAdversePct", maxAdversePct)
        put("horizonBars", horizonBars); put("alertType", alertType); put("targetPrice", targetPrice)
        put("stopPrice", stopPrice); put("evidenceJson", evidenceJson)
        put("signalType", signalType); put("confidence", confidence)
    }
    /** 将跨进程雷达冷却状态转换为不含敏感信息的结构化JSON。 */
    private fun VolumeRadarStateEntity.toJson() = JSONObject().apply {
        put("code", code); put("signalType", signalType); put("action", action)
        put("lastNotifiedAt", lastNotifiedAt); put("referencePrice", referencePrice); put("strategyVersion", strategyVersion)
    }
    /** 将一个观察周期的价格表现和有效性转换为备份JSON。 */
    private fun VolumeSignalOutcomeEntity.toJson() = JSONObject().apply {
        put("alertId", alertId); put("horizonMinutes", horizonMinutes); put("evaluatedAt", evaluatedAt)
        put("futurePrice", futurePrice); put("returnPct", returnPct); put("maxFavorablePct", maxFavorablePct)
        put("maxAdversePct", maxAdversePct); put("effective", effective)
    }
    private fun AiAnalysisEntity.toJson() = JSONObject().apply {
        put("createdAt", createdAt); put("prompt", prompt); put("rawAnswer", rawAnswer); put("marketAction", marketAction)
        put("confidence", confidence); put("targetPositionPct", targetPositionPct); put("strategyJson", strategyJson)
    }
    private fun PaperAccountEntity.toJson() = JSONObject().apply {
        put("initialCash", initialCash); put("cash", cash); put("updatedAt", updatedAt)
    }
    private fun PaperPositionEntity.toJson() = JSONObject().apply {
        put("code", code); put("quantity", quantity); put("avgCost", avgCost); put("updatedAt", updatedAt)
    }
    private fun PaperOrderEntity.toJson() = JSONObject().apply {
        put("createdAt", createdAt); put("code", code); put("side", side); put("quantity", quantity)
        put("price", price); put("fee", fee); put("status", status); put("source", source); put("note", note)
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
    private fun JSONArray?.toAccountLedgers() = objects().map { o -> AccountLedgerEntity(
        occurredAt = o.optLong("occurredAt"), type = o.optString("type"), amount = o.optDouble("amount"),
        code = o.optString("code"), source = o.optString("source", "RESTORE"), note = o.optString("note")
    ) }.filter { it.type.isNotBlank() && it.amount > 0.0 }
    private fun JSONArray?.toAlertRecords() = objects().map { o -> AlertRecordEntity(
        id = o.optLong("id"), alertKey = o.optString("alertKey"), code = o.optString("code"), name = o.optString("name"),
        signalAt = o.optLong("signalAt"), signalDate = o.optString("signalDate"), signalTime = o.optString("signalTime"),
        action = o.optString("action"), price = o.optDouble("price"), score = o.optInt("score"),
        strategyVersion = o.optString("strategyVersion", "LEGACY"), source = o.optString("source", "RESTORE"),
        dataSource = o.optString("dataSource", "UNKNOWN"), reason = o.optString("reason"),
        alertType = o.optString("alertType", "INTRADAY_SIGNAL"), targetPrice = o.optDouble("targetPrice"),
        stopPrice = o.optDouble("stopPrice"), evidenceJson = o.optString("evidenceJson", "{}"),
        status = o.optString("status", "PENDING"), evaluatedAt = o.optLong("evaluatedAt"),
        exitPrice = o.optDouble("exitPrice"), netEdgePct = o.optDouble("netEdgePct"),
        maxFavorablePct = o.optDouble("maxFavorablePct"), maxAdversePct = o.optDouble("maxAdversePct"),
        horizonBars = o.optInt("horizonBars", 6), signalType = o.optString("signalType"),
        confidence = o.optInt("confidence")
    ) }.filter { it.alertKey.isNotBlank() && it.code.isNotBlank() && it.price > 0.0 }
    /** 解析雷达状态备份；枚举有效性在领域层读取时再次校验，导入层只拒绝空代码。 */
    private fun JSONArray?.toVolumeRadarStates() = objects().map { o -> VolumeRadarStateEntity(
        code = o.optString("code"), signalType = o.optString("signalType"), action = o.optString("action"),
        lastNotifiedAt = o.optLong("lastNotifiedAt"), referencePrice = o.optDouble("referencePrice"),
        strategyVersion = o.optString("strategyVersion", "VOLUME_RADAR_V1")
    ) }.filter { it.code.isNotBlank() }
    /** 解析多周期评价备份；只接收合法提醒ID和正观察周期，避免无效行污染统计。 */
    private fun JSONArray?.toVolumeSignalOutcomes() = objects().map { o -> VolumeSignalOutcomeEntity(
        alertId = o.optLong("alertId"), horizonMinutes = o.optInt("horizonMinutes"),
        evaluatedAt = o.optLong("evaluatedAt"), futurePrice = o.optDouble("futurePrice"),
        returnPct = o.optDouble("returnPct"), maxFavorablePct = o.optDouble("maxFavorablePct"),
        maxAdversePct = o.optDouble("maxAdversePct"), effective = o.optBoolean("effective")
    ) }.filter { it.alertId > 0L && it.horizonMinutes > 0 }
    private fun JSONArray?.toAiAnalysis() = objects().map { o -> AiAnalysisEntity(
        createdAt = o.optLong("createdAt"), prompt = o.optString("prompt"), rawAnswer = o.optString("rawAnswer"),
        marketAction = o.optString("marketAction"), confidence = o.optInt("confidence"), targetPositionPct = o.optInt("targetPositionPct"),
        strategyJson = o.optString("strategyJson")) }
    private fun JSONObject.toPaperAccount() = PaperAccountEntity(
        initialCash = optDouble("initialCash", 100_000.0), cash = optDouble("cash", 100_000.0), updatedAt = optLong("updatedAt"))
    private fun JSONArray?.toPaperPositions() = objects().map { o -> PaperPositionEntity(
        code = o.optString("code"), quantity = o.optInt("quantity"), avgCost = o.optDouble("avgCost"), updatedAt = o.optLong("updatedAt"))
    }.filter { it.code.isNotBlank() && it.quantity > 0 }
    private fun JSONArray?.toPaperOrders() = objects().map { o -> PaperOrderEntity(
        createdAt = o.optLong("createdAt"), code = o.optString("code"), side = o.optString("side"), quantity = o.optInt("quantity"),
        price = o.optDouble("price"), fee = o.optDouble("fee"), status = o.optString("status", "FILLED"),
        source = o.optString("source", "RESTORE"), note = o.optString("note"))
    }.filter { it.code.isNotBlank() && it.quantity > 0 }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else buildList {
        for (i in 0 until length()) optJSONObject(i)?.let(::add)
    }

    companion object {
        private const val FORMAT = "ASTOCK_GUARD_BACKUP"
        private const val VERSION = 3
        private const val MAX_IMPORT_CHARS = 20_000_000
    }
}
