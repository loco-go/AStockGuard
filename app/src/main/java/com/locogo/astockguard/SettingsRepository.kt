package com.locogo.astockguard

import android.content.Context
import com.locogo.astockguard.data.news.NewsSource
import java.util.Locale

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val crypto = CryptoStore(context.applicationContext)

    var watchCodes: String get() = prefs.getString("watch_codes", DEFAULT_WATCH_CODES) ?: DEFAULT_WATCH_CODES; set(v) = prefs.edit().putString("watch_codes", v).apply()
    var positionsText: String get() = prefs.getString("positions", DEFAULT_POSITIONS) ?: DEFAULT_POSITIONS; set(v) = prefs.edit().putString("positions", v).apply()
    var positionRatio: Double get() = prefs.getFloat("position_ratio", 65.8f).toDouble(); set(v) = prefs.edit().putFloat("position_ratio", v.toFloat()).apply()
    var cashBalance: Double?
        get() = if (prefs.contains("cash_balance")) prefs.getString("cash_balance", null)?.toDoubleOrNull() else null
        set(v) {
            val editor = prefs.edit()
            if (v == null) editor.remove("cash_balance") else editor.putString("cash_balance", v.coerceAtLeast(0.0).toString())
            editor.apply()
        }
    var refreshSeconds: Int get() = prefs.getInt("refresh_seconds", 5).coerceIn(3, 60); set(v) = prefs.edit().putInt("refresh_seconds", v.coerceIn(3, 60)).apply()

    /** 行情首选源：FREE始终使用免费接口；IFIND在失败时自动降级免费接口。 */
    var marketDataSource: String
        get() = prefs.getString("market_data_source", MARKET_SOURCE_FREE) ?: MARKET_SOURCE_FREE
        set(v) = prefs.edit().putString("market_data_source", if (v == MARKET_SOURCE_IFIND) v else MARKET_SOURCE_FREE).apply()
    var ifindRefreshToken: String
        get() = crypto.get("ifind_refresh_token")
        set(v) {
            val normalized = v.trim()
            if (normalized != crypto.get("ifind_refresh_token")) clearIFindAccessToken()
            crypto.put("ifind_refresh_token", normalized)
        }
    val ifindAccessToken: String get() = crypto.get("ifind_access_token")
    val ifindAccessTokenFetchedAt: Long get() = prefs.getLong("ifind_access_token_fetched_at", 0L)

    fun saveIFindAccessToken(token: String, fetchedAt: Long) {
        crypto.put("ifind_access_token", token)
        prefs.edit().putLong("ifind_access_token_fetched_at", fetchedAt).apply()
    }

    fun clearIFindAccessToken() {
        crypto.put("ifind_access_token", "")
        prefs.edit().remove("ifind_access_token_fetched_at").apply()
    }

    /** 同花顺辅助同步的最后诊断结果，用于区分“未收到事件”和“页面字段未识别”。 */
    var thsLastSyncAt: Long get() = prefs.getLong("ths_last_sync_at", 0L); set(v) = prefs.edit().putLong("ths_last_sync_at", v).apply()
    var thsLastSyncMessage: String get() = prefs.getString("ths_last_sync_message", "") ?: ""; set(v) = prefs.edit().putString("ths_last_sync_message", v).apply()
    var thsLastSuccessMessage: String get() = prefs.getString("ths_last_success_message", "") ?: ""; set(v) = prefs.edit().putString("ths_last_success_message", v).apply()
    var thsEstimatedTotalAssets: Double?
        get() = prefs.getString("ths_estimated_total_assets", null)?.toDoubleOrNull()
        set(v) {
            val editor = prefs.edit()
            if (v == null) editor.remove("ths_estimated_total_assets") else editor.putString("ths_estimated_total_assets", v.toString())
            editor.apply()
        }
    var thsStatusNotificationEnabled: Boolean
        get() = prefs.getBoolean("ths_status_notification_enabled", true)
        set(v) = prefs.edit().putBoolean("ths_status_notification_enabled", v).apply()

    var newsEnabled: Boolean get() = prefs.getBoolean("news_enabled", true); set(v) = prefs.edit().putBoolean("news_enabled", v).apply()
    var newsRefreshMinutes: Int get() = prefs.getInt("news_refresh_minutes", 15).coerceIn(5, 120); set(v) = prefs.edit().putInt("news_refresh_minutes", v.coerceIn(5, 120)).apply()
    var newsSourcesText: String get() = prefs.getString("news_sources", DEFAULT_NEWS_SOURCES) ?: DEFAULT_NEWS_SOURCES; set(v) = prefs.edit().putString("news_sources", v).apply()

    /** 新安装默认复用iFinD官方HTTP；无Token或无十档权限时仓位策略只会把盘口标记为不可用。 */
    var level2ProviderType: String get() = prefs.getString("level2_provider_type", "IFIND_HTTP") ?: "IFIND_HTTP"; set(v) = prefs.edit().putString("level2_provider_type", v).apply()
    var level2BaseUrl: String get() = prefs.getString("level2_base_url", "") ?: ""; set(v) = prefs.edit().putString("level2_base_url", v).apply()
    var level2ApiToken: String get() = crypto.get("level2_api_token"); set(v) = crypto.put("level2_api_token", v)

    var primaryType: String get() = prefs.getString("primary_type", "CHATGPT_WEB") ?: "CHATGPT_WEB"; set(v) = prefs.edit().putString("primary_type", v).apply()
    var chatGptConversationUrl: String get() = prefs.getString("chatgpt_conversation_url", "") ?: ""; set(v) = prefs.edit().putString("chatgpt_conversation_url", v).apply()
    var primaryBaseUrl: String get() = prefs.getString("primary_base_url", defaultEndpoint(primaryType)) ?: defaultEndpoint(primaryType); set(v) = prefs.edit().putString("primary_base_url", v).apply()
    var primaryModel: String get() = prefs.getString("primary_model", "auto") ?: "auto"; set(v) = prefs.edit().putString("primary_model", v).apply()
    var extraHeadersJson: String get() = prefs.getString("extra_headers", "{}") ?: "{}"; set(v) = prefs.edit().putString("extra_headers", v).apply()
    var primaryApiKey: String get() = crypto.get("primary_api_key"); set(v) = crypto.put("primary_api_key", v)
    var primarySessionToken: String get() = crypto.get("primary_session_token"); set(v) = crypto.put("primary_session_token", v)
    var primaryCookie: String get() = crypto.get("primary_cookie"); set(v) = crypto.put("primary_cookie", v)

    var backupType: String get() = prefs.getString("backup_type", "RESPONSES") ?: "RESPONSES"; set(v) = prefs.edit().putString("backup_type", v).apply()
    var backupBaseUrl: String get() = prefs.getString("backup_base_url", "") ?: ""; set(v) = prefs.edit().putString("backup_base_url", v).apply()
    var backupModel: String get() = prefs.getString("backup_model", "") ?: ""; set(v) = prefs.edit().putString("backup_model", v).apply()
    var backupApiKey: String get() = crypto.get("backup_api_key"); set(v) = crypto.put("backup_api_key", v)

    fun applyImported(c: AiProviderConfig) { primaryType = c.type; primaryBaseUrl = c.baseUrl; primaryApiKey = c.apiKey; primarySessionToken = c.sessionToken; primaryCookie = c.cookie; primaryModel = c.model.ifBlank { "auto" } }

    fun positions(): List<Position> = positionsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
        val p = line.split(',').map { it.trim() }; if (p.size < 5) return@mapNotNull null
        Position(normalizeCode(p[0]), p[1], p[2].toIntOrNull() ?: return@mapNotNull null, p[3].toDoubleOrNull() ?: return@mapNotNull null, p[4].uppercase())
    }.toList()

    /** 原子写入结构化持仓，统一使用应用原有的五列配置格式。 */
    fun savePositions(positions: List<Position>) {
        positionsText = positions.joinToString("\n") { position ->
            listOf(
                position.code,
                position.name.replace(',', ' '),
                position.shares.toString(),
                String.format(Locale.US, "%.4f", position.cost).trimEnd('0').trimEnd('.'),
                position.role
            ).joinToString(",")
        }
    }

    /** 记录一次辅助同步扫描；不保存同花顺页面的原始文本。 */
    fun recordThsSync(message: String, timestamp: Long = System.currentTimeMillis()) {
        // 实时行情会频繁刷新，相同诊断短时间内无需反复写入闪存。
        if (message == thsLastSyncMessage && timestamp - thsLastSyncAt < 5_000L) return
        prefs.edit()
            .putLong("ths_last_sync_at", timestamp)
            .putString("ths_last_sync_message", message)
            .apply()
    }

    fun allCodes(): List<String> = linkedSetOf<String>().apply {
        watchCodes.split(',', '\n', ';').map(::normalizeCode).filter(String::isNotBlank).forEach(::add)
        positions().map { it.code }.forEach(::add)
    }.toList()

    fun newsSources(): List<NewsSource> = newsSourcesText.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
        val p = line.split('|', limit = 2).map { it.trim() }; if (p.size != 2 || !p[1].startsWith("https://")) null else NewsSource(p[0].ifBlank { "RSS" }, p[1])
    }.distinctBy { it.url }.take(12).toList()

    fun primaryProvider() = AiProviderConfig(primaryType, "primary", primaryBaseUrl, primaryApiKey, primarySessionToken, primaryCookie, primaryModel, extraHeadersJson)
    fun backupProvider(): AiProviderConfig? = if (backupBaseUrl.isBlank() || backupApiKey.isBlank()) null else AiProviderConfig(backupType, "backup", backupBaseUrl, backupApiKey, "", "", backupModel.ifBlank { primaryModel }, extraHeadersJson)

    companion object {
        const val MARKET_SOURCE_FREE = "FREE"
        const val MARKET_SOURCE_IFIND = "IFIND"
        const val DEFAULT_WATCH_CODES = "000636.SZ,000938.SZ,600667.SH,002579.SZ"
        val DEFAULT_POSITIONS = """
000636.SZ,风华高科,500,45.679,CORE
000938.SZ,紫光股份,600,48.162,CORE
600667.SH,太极实业,800,23.492,ATTACK
002579.SZ,中京电子,600,15.317,LONG
        """.trimIndent()
        val DEFAULT_NEWS_SOURCES = """
GoogleNews-A股|https://news.google.com/rss/search?q=A%E8%82%A1+OR+%E8%82%A1%E5%B8%82+OR+%E5%8D%8A%E5%AF%BC%E4%BD%93&hl=zh-CN&gl=CN&ceid=CN:zh-Hans
GoogleNews-宏观风险|https://news.google.com/rss/search?q=%E5%88%B6%E8%A3%81+OR+%E5%85%B3%E7%A8%8E+OR+%E6%88%98%E4%BA%89+OR+%E8%8A%AF%E7%89%87&hl=zh-CN&gl=CN&ceid=CN:zh-Hans
        """.trimIndent()
        fun defaultEndpoint(type: String) = if (type.uppercase() == "CHATGPT_WEB") "https://chatgpt.com/" else "https://api.openai.com/v1"
        fun normalizeCode(raw: String): String { val v = raw.trim().uppercase().removePrefix("SH").removePrefix("SZ"); if (v.contains('.') || v.length != 6) return v; return if (v.startsWith("6")) "$v.SH" else "$v.SZ" }
    }
}
