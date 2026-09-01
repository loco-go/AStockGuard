package com.locogo.astockguard.data.ifind

import com.locogo.astockguard.Quote
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.time.LocalDate

data class IFindConnectionStatus(
    val ready: Boolean,
    val message: String,
    val quoteCount: Int = 0
)

class IFindApiException(
    val errorCode: Int,
    message: String
) : IllegalStateException(message) {
    val authenticationFailure: Boolean
        get() = errorCode in AUTH_ERROR_CODES

    companion object {
        private val AUTH_ERROR_CODES = setOf(-1010, -1003, -1005, -1300, -1301, -1302, -1303, -1305)
    }
}

/**
 * iFinD 官方 HTTP 行情客户端。
 * refresh token 与 access token 均由 SettingsRepository 通过 Android Keystore 加密保存，日志中不输出凭据。
 */
class IFindHttpClient(
    private val settings: SettingsRepository,
    private val client: OkHttpClient = defaultClient()
) {
    private val tokenMutex = Mutex()

    suspend fun fetchQuotes(codes: List<String>): List<Quote> {
        if (codes.isEmpty()) return emptyList()
        require(settings.ifindRefreshToken.isNotBlank()) { "尚未填写 iFinD refresh token" }
        return codes.distinct().chunked(MAX_CODES_PER_REQUEST).flatMap { batch ->
            requestWithTokenRetry(REALTIME_PATH, JSONObject().apply {
                put("codes", batch.joinToString(","))
                put("indicators", QUOTE_INDICATORS)
            }).let(IFindResponseParser::parseQuotes)
        }
    }

    suspend fun fetchMinuteBars(code: String, date: LocalDate, intervalMinutes: Int): List<MinuteBar> {
        require(settings.ifindRefreshToken.isNotBlank()) { "尚未填写 iFinD refresh token" }
        val root = requestWithTokenRetry(HIGH_FREQUENCY_PATH, JSONObject().apply {
            put("codes", code)
            put("indicators", "open,high,low,close,volume,amount")
            put("starttime", "$date 09:15:00")
            put("endtime", "$date 15:15:00")
            put("functionpara", JSONObject().apply {
                put("Interval", intervalMinutes.coerceIn(1, 60).toString())
                put("Fill", "Original")
                put("Timeformat", "LocalTime")
                put("Limitstart", "09:15:00")
                put("Limitend", "15:15:00")
                put("CPS", "no")
            })
        })
        return IFindResponseParser.parseMinuteBars(root)
    }

    suspend fun fetchDailyBars(code: String, start: LocalDate, end: LocalDate): List<DailyBar> {
        require(settings.ifindRefreshToken.isNotBlank()) { "尚未填写 iFinD refresh token" }
        val root = requestWithTokenRetry(HISTORY_PATH, JSONObject().apply {
            put("codes", code)
            put("indicators", "open,high,low,close,volume")
            put("startdate", start.toString())
            put("enddate", end.toString())
            put("functionpara", JSONObject().apply {
                put("Interval", "D")
                put("CPS", "forward1")
                put("Fill", "Omit")
            })
        })
        return IFindResponseParser.parseDailyBars(root)
    }

    suspend fun test(codes: List<String>): IFindConnectionStatus = runCatching {
        val quotes = fetchQuotes(codes.take(3))
        if (quotes.isEmpty()) IFindConnectionStatus(false, "iFinD鉴权成功，但实时行情没有返回数据")
        else IFindConnectionStatus(true, "iFinD实时行情正常", quotes.size)
    }.getOrElse { error ->
        IFindConnectionStatus(false, safeErrorMessage(error))
    }

    private suspend fun requestWithTokenRetry(path: String, payload: JSONObject): JSONObject {
        var token = accessToken()
        repeat(2) { attempt ->
            try {
                return post(path, payload, "access_token" to token)
            } catch (error: IFindApiException) {
                if (!error.authenticationFailure || attempt > 0) throw error
                // access token过期时清除本地短期Token，再用长期refresh token获取当前有效Token。
                settings.clearIFindAccessToken()
                token = accessToken()
            }
        }
        error("iFinD请求重试失败")
    }

    private suspend fun accessToken(): String {
        val cached = settings.ifindAccessToken
        val age = System.currentTimeMillis() - settings.ifindAccessTokenFetchedAt
        if (cached.isNotBlank() && age in 0 until ACCESS_TOKEN_REUSE_MS) return cached
        return tokenMutex.withLock {
            val rechecked = settings.ifindAccessToken
            val recheckedAge = System.currentTimeMillis() - settings.ifindAccessTokenFetchedAt
            if (rechecked.isNotBlank() && recheckedAge in 0 until ACCESS_TOKEN_REUSE_MS) return@withLock rechecked
            val refreshToken = settings.ifindRefreshToken
            require(refreshToken.isNotBlank()) { "尚未填写 iFinD refresh token" }
            val root = post(GET_TOKEN_PATH, JSONObject(), "refresh_token" to refreshToken)
            val accessToken = root.optJSONObject("data")?.optString("access_token").orEmpty()
                .ifBlank { root.optString("access_token") }
            if (accessToken.isBlank()) error("iFinD未返回access token")
            settings.saveIFindAccessToken(accessToken, System.currentTimeMillis())
            accessToken
        }
    }

    private suspend fun post(path: String, payload: JSONObject, authHeader: Pair<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL/$path")
                .header("Content-Type", "application/json")
                .header("ifindlang", "cn")
                .header(authHeader.first, authHeader.second)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("iFinD HTTP ${response.code}")
                val root = runCatching { JSONObject(text) }.getOrElse { error("iFinD返回内容不是有效JSON") }
                val errorCode = root.optInt("errorcode", root.optInt("errorCode", 0))
                if (errorCode != 0) {
                    val message = root.optString("errmsg", root.optString("message", "iFinD请求失败"))
                    throw IFindApiException(errorCode, "iFinD错误$errorCode：$message")
                }
                root
            }
        }

    private fun safeErrorMessage(error: Throwable): String = when (error) {
        is IFindApiException -> error.message ?: "iFinD接口错误"
        else -> error.message?.take(180) ?: "iFinD连接失败"
    }

    companion object {
        private const val BASE_URL = "https://quantapi.51ifind.com/api/v1"
        private const val GET_TOKEN_PATH = "get_access_token"
        private const val REALTIME_PATH = "real_time_quotation"
        private const val HIGH_FREQUENCY_PATH = "high_frequency"
        private const val HISTORY_PATH = "cmd_history_quotation"
        private const val MAX_CODES_PER_REQUEST = 40
        // 官方access token有效期为7天；第6天主动重新获取，给设备时钟偏差留出余量。
        private const val ACCESS_TOKEN_REUSE_MS = 6L * 24 * 60 * 60 * 1_000
        private const val QUOTE_INDICATORS =
            "tradeTime,preClose,open,high,low,latest,avgPrice,changeRatio,volume,amount"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** 兼容HTTP文档中的 tables[].table.{indicator:[value]} 返回结构。 */
internal object IFindResponseParser {
    fun parseQuotes(root: JSONObject): List<Quote> {
        val tables = root.optJSONArray("tables") ?: root.optJSONObject("data")?.optJSONArray("tables") ?: JSONArray()
        return buildList {
            for (index in 0 until tables.length()) {
                val row = tables.optJSONObject(index) ?: continue
                val table = row.optJSONObject("table") ?: row
                val code = row.optString("thscode", row.optString("code")).trim()
                if (code.isBlank()) continue
                add(
                    Quote(
                        code = SettingsRepository.normalizeCode(code),
                        time = table.firstString("tradeTime"),
                        open = table.firstDouble("open"),
                        high = table.firstDouble("high"),
                        low = table.firstDouble("low"),
                        latest = table.firstDouble("latest"),
                        previousClose = table.firstDouble("preClose"),
                        changeRatio = table.firstDouble("changeRatio"),
                        volume = table.firstDouble("volume"),
                        amount = table.firstDouble("amount"),
                        vwap = table.firstDouble("avgPrice")
                    )
                )
            }
        }
    }

    fun parseMinuteBars(root: JSONObject): List<MinuteBar> {
        val row = firstTable(root) ?: return emptyList()
        val table = row.optJSONObject("table") ?: return emptyList()
        val times = row.optJSONArray("time") ?: table.optJSONArray("time") ?: return emptyList()
        var weightedAmount = 0.0
        var weightedVolume = 0.0
        return buildList {
            for (index in 0 until times.length()) {
                val close = table.doubleAt("close", index) ?: continue
                val volume = table.doubleAt("volume", index) ?: 0.0
                weightedAmount += close * volume
                weightedVolume += volume
                add(
                    MinuteBar(
                        time = times.optString(index).toMarketTime(),
                        price = close,
                        avgPrice = if (weightedVolume > 0.0) weightedAmount / weightedVolume else close,
                        high = table.doubleAt("high", index) ?: close,
                        low = table.doubleAt("low", index) ?: close,
                        volume = volume,
                        amount = table.doubleAt("amount", index) ?: 0.0
                    )
                )
            }
        }
    }

    fun parseDailyBars(root: JSONObject): List<DailyBar> {
        val row = firstTable(root) ?: return emptyList()
        val table = row.optJSONObject("table") ?: return emptyList()
        val times = row.optJSONArray("time") ?: table.optJSONArray("time") ?: return emptyList()
        return buildList {
            for (index in 0 until times.length()) {
                val close = table.doubleAt("close", index) ?: continue
                add(
                    DailyBar(
                        date = times.optString(index).take(10),
                        open = table.doubleAt("open", index) ?: close,
                        close = close,
                        high = table.doubleAt("high", index) ?: close,
                        low = table.doubleAt("low", index) ?: close,
                        volume = table.doubleAt("volume", index) ?: 0.0
                    )
                )
            }
        }.sortedBy { it.date }
    }

    private fun firstTable(root: JSONObject): JSONObject? {
        val tables = root.optJSONArray("tables") ?: root.optJSONObject("data")?.optJSONArray("tables")
        return tables?.optJSONObject(0)
    }

    private fun JSONObject.firstString(key: String): String {
        val value = opt(key) ?: return ""
        return when (value) {
            is JSONArray -> value.opt(0)?.toString().orEmpty()
            JSONObject.NULL -> ""
            else -> value.toString()
        }
    }

    private fun JSONObject.firstDouble(key: String): Double? =
        firstString(key).takeUnless { it.isBlank() || it.equals("null", true) || it == "--" }?.toDoubleOrNull()

    private fun JSONObject.doubleAt(key: String, index: Int): Double? {
        val value = opt(key) ?: return null
        val raw = if (value is JSONArray) value.opt(index) else value
        return raw?.toString()?.takeUnless { it.isBlank() || it.equals("null", true) || it == "--" }?.toDoubleOrNull()
    }

    private fun String.toMarketTime(): String {
        val match = Regex("(\\d{2}:\\d{2})(?::\\d{2})?").find(this)
        return match?.groupValues?.get(1) ?: this
    }
}
