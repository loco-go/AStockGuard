package com.locogo.astockguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class EastMoneyMinuteHistoryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetch5Minute(code: String, date: LocalDate): List<MinuteBar> = withContext(Dispatchers.IO) {
        val compactDate = date.toString().replace("-", "")
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("secid", secid(code))
            .addQueryParameter("klt", "5")
            .addQueryParameter("fqt", "1")
            .addQueryParameter("beg", compactDate)
            .addQueryParameter("end", compactDate)
            .addQueryParameter("lmt", "1000")
            .addQueryParameter("fields1", "f1,f2,f3,f4,f5,f6")
            .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 Android AStockGuard")
            .header("Referer", "https://quote.eastmoney.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("历史分时 HTTP ${response.code}")
            parse(response.body?.string().orEmpty(), date)
        }
    }

    internal fun parse(text: String, requestedDate: LocalDate): List<MinuteBar> {
        val rows = runCatching { JSONObject(text) }.getOrNull()
            ?.optJSONObject("data")?.optJSONArray("klines") ?: return emptyList()
        return parseRows((0 until rows.length()).map { rows.optString(it) }, requestedDate)
    }

    internal fun parseRows(rows: List<String>, requestedDate: LocalDate): List<MinuteBar> {
        val result = mutableListOf<MinuteBar>()
        var cumulativeVolume = 0.0
        var cumulativeAmount = 0.0
        for (row in rows) {
            val parts = row.split(',')
            if (parts.size < 7) continue
            val dateTime = parts[0].trim()
            if (!dateTime.startsWith(requestedDate.toString())) continue
            val open = parts[1].toDoubleOrNull() ?: continue
            val close = parts[2].toDoubleOrNull() ?: continue
            val high = parts[3].toDoubleOrNull() ?: continue
            val low = parts[4].toDoubleOrNull() ?: continue
            val volume = parts[5].toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val amount = parts[6].toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            cumulativeVolume += volume
            cumulativeAmount += amount
            val average = if (cumulativeVolume > 0.0 && cumulativeAmount > 0.0) {
                cumulativeAmount / cumulativeVolume / 100.0
            } else close
            result += MinuteBar(
                time = dateTime.substringAfter(' ', dateTime).take(5),
                price = close,
                avgPrice = average,
                high = maxOf(high, open, close),
                low = minOf(low, open, close),
                volume = volume,
                amount = amount
            )
        }
        return result
    }

    private fun secid(code: String): String {
        val upper = code.uppercase()
        val digits = upper.substringBefore('.')
        val market = if (upper.endsWith(".SH") || digits.startsWith("6")) "1" else "0"
        return "$market.$digits"
    }

    private companion object {
        const val BASE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
    }
}
