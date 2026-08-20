package com.locogo.astockguard

import android.content.Context

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val crypto = CryptoStore(appContext)

    var watchCodes: String
        get() = prefs.getString("watch_codes", DEFAULT_WATCH_CODES) ?: DEFAULT_WATCH_CODES
        set(v) = prefs.edit().putString("watch_codes", v).apply()

    var positionsText: String
        get() = prefs.getString("positions", DEFAULT_POSITIONS) ?: DEFAULT_POSITIONS
        set(v) = prefs.edit().putString("positions", v).apply()

    var positionRatio: Double
        get() = prefs.getFloat("position_ratio", 65.8f).toDouble()
        set(v) = prefs.edit().putFloat("position_ratio", v.toFloat()).apply()

    var primaryBaseUrl: String
        get() = prefs.getString("primary_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(v) = prefs.edit().putString("primary_base_url", v).apply()

    var primaryModel: String
        get() = prefs.getString("primary_model", "gpt-5.6") ?: "gpt-5.6"
        set(v) = prefs.edit().putString("primary_model", v).apply()

    var backupBaseUrl: String
        get() = prefs.getString("backup_base_url", "") ?: ""
        set(v) = prefs.edit().putString("backup_base_url", v).apply()

    var backupModel: String
        get() = prefs.getString("backup_model", "") ?: ""
        set(v) = prefs.edit().putString("backup_model", v).apply()

    var extraHeadersJson: String
        get() = prefs.getString("extra_headers", "{}") ?: "{}"
        set(v) = prefs.edit().putString("extra_headers", v).apply()

    var ifindRefreshToken: String
        get() = crypto.get("ifind_refresh_token")
        set(v) = crypto.put("ifind_refresh_token", v)

    var primaryApiKey: String
        get() = crypto.get("primary_api_key")
        set(v) = crypto.put("primary_api_key", v)

    var backupApiKey: String
        get() = crypto.get("backup_api_key")
        set(v) = crypto.put("backup_api_key", v)

    fun positions(): List<Position> = positionsText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split(',').map { it.trim() }
            if (p.size < 5) return@mapNotNull null
            Position(
                code = normalizeCode(p[0]),
                name = p[1],
                shares = p[2].toIntOrNull() ?: return@mapNotNull null,
                cost = p[3].toDoubleOrNull() ?: return@mapNotNull null,
                role = p[4].uppercase()
            )
        }.toList()

    fun allCodes(): List<String> {
        val set = linkedSetOf<String>()
        watchCodes.split(',', '\n', ';').map { normalizeCode(it) }.filter { it.isNotBlank() }.forEach(set::add)
        positions().map { it.code }.forEach(set::add)
        return set.toList()
    }

    fun primaryProvider() = AiProviderConfig(
        name = "primary",
        baseUrl = primaryBaseUrl,
        apiKey = primaryApiKey,
        model = primaryModel,
        extraHeadersJson = extraHeadersJson
    )

    fun backupProvider(): AiProviderConfig? {
        if (backupBaseUrl.isBlank() || backupApiKey.isBlank()) return null
        return AiProviderConfig(
            name = "backup",
            baseUrl = backupBaseUrl,
            apiKey = backupApiKey,
            model = backupModel.ifBlank { primaryModel },
            extraHeadersJson = extraHeadersJson
        )
    }

    companion object {
        val DEFAULT_WATCH_CODES = "000636.SZ,000938.SZ,600667.SH,002579.SZ"
        val DEFAULT_POSITIONS = """
000636.SZ,风华高科,500,45.679,CORE
000938.SZ,紫光股份,600,48.162,CORE
600667.SH,太极实业,800,23.492,ATTACK
002579.SZ,中京电子,600,15.317,LONG
        """.trimIndent()

        fun normalizeCode(raw: String): String {
            val v = raw.trim().uppercase()
            if (v.contains('.')) return v
            if (v.length != 6) return v
            return when {
                v.startsWith("6") -> "$v.SH"
                else -> "$v.SZ"
            }
        }
    }
}
