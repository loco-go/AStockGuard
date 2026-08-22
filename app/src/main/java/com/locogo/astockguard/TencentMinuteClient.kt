package com.locogo.astockguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TencentMinuteClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetch(code: String): List<MinuteBar> = withContext(Dispatchers.IO) {
        val qCode = toTencentCode(code)
        val request = Request.Builder()
            .url("https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$qCode")
            .header("User-Agent", "Mozilla/5.0 Android AStockGuard")
            .build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("腾讯分时 HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        parse(text, qCode)
    }

    internal fun parse(text: String, qCode: String): List<MinuteBar> {
        val jsonText = text.substringAfter('=', text).trim().trimEnd(';')
        val root = JSONObject(jsonText)
        val data = root.optJSONObject("data")?.optJSONObject(qCode) ?: return emptyList()
        val rows = data.optJSONObject("data")?.optJSONArray("data")
            ?: data.optJSONArray("data")
            ?: return emptyList()
        val result = ArrayList<MinuteBar>(rows.length())
        for (i in 0 until rows.length()) {
            val parts = rows.optString(i).trim().split(' ').filter { it.isNotBlank() }
            if (parts.size < 4) continue
            val time = parts[0]
            val price = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
            val volume = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val amount = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            val avg = if (volume > 0 && amount > 0) amount / volume / 100.0 else price
            result += MinuteBar(time, price, avg, price, price, volume, amount)
        }
        return result
    }

    private fun toTencentCode(code: String): String {
        val c = code.uppercase()
        val digits = c.substringBefore('.')
        return if (c.endsWith(".SH") || digits.startsWith("6")) "sh$digits" else "sz$digits"
    }
}
