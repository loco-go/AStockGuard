package com.locogo.astockguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class IfindClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var cachedAccessToken: String = ""
    @Volatile private var tokenFetchedAt: Long = 0L

    suspend fun ensureAccessToken(refreshToken: String): String = withContext(Dispatchers.IO) {
        require(refreshToken.isNotBlank()) { "请先配置 iFinD refresh_token" }
        if (cachedAccessToken.isNotBlank() && System.currentTimeMillis() - tokenFetchedAt < 6 * 60 * 60 * 1000L) {
            return@withContext cachedAccessToken
        }
        val request = Request.Builder()
            .url("https://quantapi.51ifind.com/api/v1/get_access_token")
            .header("Content-Type", "application/json")
            .header("refresh_token", refreshToken)
            .post("{}".toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("iFinD token HTTP ${response.code}: $text")
            val root = JSONObject(text)
            val token = root.optJSONObject("data")?.optString("access_token").orEmpty()
            if (token.isBlank()) error("iFinD access_token 解析失败: $text")
            cachedAccessToken = token
            tokenFetchedAt = System.currentTimeMillis()
            token
        }
    }

    suspend fun fetchQuotes(accessToken: String, codes: List<String>, previousCloses: Map<String, Double>): List<Quote> =
        withContext(Dispatchers.IO) {
            if (codes.isEmpty()) return@withContext emptyList()
            val body = JSONObject()
                .put("codes", codes.joinToString(","))
                .put("indicators", "open,high,low,latest")
                .toString()
                .toRequestBody(jsonType)
            val request = Request.Builder()
                .url("https://quantapi.51ifind.com/api/v1/real_time_quotation")
                .header("Content-Type", "application/json")
                .header("access_token", accessToken)
                .header("ifindlang", "cn")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("iFinD quote HTTP ${response.code}: $text")
                parseRealtime(text, previousCloses)
            }
        }

    suspend fun fetchPreviousCloses(accessToken: String, codes: List<String>): Map<String, Double> =
        withContext(Dispatchers.IO) {
            if (codes.isEmpty()) return@withContext emptyMap()
            val today = LocalDate.now()
            val start = today.minusDays(14)
            val body = JSONObject()
                .put("codes", codes.joinToString(","))
                .put("indicators", "close")
                .put("startdate", start.toString())
                .put("enddate", today.toString())
                .put("functionpara", JSONObject().put("Fill", "Omit"))
                .toString()
                .toRequestBody(jsonType)
            val request = Request.Builder()
                .url("https://quantapi.51ifind.com/api/v1/cmd_history_quotation")
                .header("Content-Type", "application/json")
                .header("access_token", accessToken)
                .header("ifindlang", "cn")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("iFinD history HTTP ${response.code}: $text")
                parsePreviousCloses(text)
            }
        }

    private fun parseRealtime(text: String, previousCloses: Map<String, Double>): List<Quote> {
        val root = JSONObject(text)
        val errorCode = root.optInt("errorcode", 0)
        if (errorCode != 0) error("iFinD error=$errorCode ${root.optString("errmsg")}")
        val tables = root.optJSONArray("tables") ?: JSONArray()
        val result = mutableListOf<Quote>()
        for (i in 0 until tables.length()) {
            val item = tables.optJSONObject(i) ?: continue
            val code = item.optString("thscode").ifBlank { item.optString("code") }
            val table = item.optJSONObject("table") ?: item
            val time = scalarString(item.opt("time"))
            val open = scalarDouble(table.opt("open"))
            val high = scalarDouble(table.opt("high"))
            val low = scalarDouble(table.opt("low"))
            val latest = scalarDouble(table.opt("latest")) ?: scalarDouble(table.opt("new"))
            val prev = previousCloses[code]
            val change = if (latest != null && prev != null && prev != 0.0) (latest / prev - 1.0) * 100.0 else null
            result += Quote(code, time, open, high, low, latest, prev, change)
        }
        return result
    }

    private fun parsePreviousCloses(text: String): Map<String, Double> {
        val root = JSONObject(text)
        val errorCode = root.optInt("errorcode", 0)
        if (errorCode != 0) error("iFinD error=$errorCode ${root.optString("errmsg")}")
        val tables = root.optJSONArray("tables") ?: JSONArray()
        val today = LocalDate.now().toString()
        val result = mutableMapOf<String, Double>()
        for (i in 0 until tables.length()) {
            val item = tables.optJSONObject(i) ?: continue
            val code = item.optString("thscode")
            val table = item.optJSONObject("table") ?: continue
            val closes = toDoubleList(table.opt("close"))
            val times = toStringList(item.opt("time"))
            if (closes.isEmpty()) continue
            var idx = closes.lastIndex
            if (times.size == closes.size && times.lastOrNull()?.startsWith(today) == true && closes.size >= 2) idx = closes.lastIndex - 1
            if (idx >= 0) result[code] = closes[idx]
        }
        return result
    }

    private fun scalarDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is JSONArray -> if (v.length() > 0) scalarDouble(v.opt(0)) else null
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun scalarString(v: Any?): String = when (v) {
        is JSONArray -> if (v.length() > 0) v.opt(0)?.toString().orEmpty() else ""
        null -> ""
        else -> v.toString()
    }

    private fun toDoubleList(v: Any?): List<Double> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { scalarDouble(v.opt(it)) }
        else -> listOfNotNull(scalarDouble(v))
    }

    private fun toStringList(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).map { v.opt(it)?.toString().orEmpty() }
        null -> emptyList()
        else -> listOf(v.toString())
    }
}
