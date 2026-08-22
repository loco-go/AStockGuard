package com.locogo.astockguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TencentHistoryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchDaily(code: String, count: Int = 30): List<DailyBar> = withContext(Dispatchers.IO) {
        val tc = TencentMarketClient.toTencentCode(code)
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$tc,day,,,$count,qfq"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("腾讯K线 HTTP ${response.code}")
            parse(tc, text)
        }
    }

    fun parse(tencentCode: String, text: String): List<DailyBar> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val node = root.optJSONObject("data")?.optJSONObject(tencentCode) ?: return emptyList()
        val rows = node.optJSONArray("qfqday") ?: node.optJSONArray("day") ?: return emptyList()
        val out = mutableListOf<DailyBar>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            if (r.length() < 6) continue
            val bar = DailyBar(
                date = r.optString(0),
                open = r.num(1) ?: continue,
                close = r.num(2) ?: continue,
                high = r.num(3) ?: continue,
                low = r.num(4) ?: continue,
                volume = r.num(5) ?: 0.0
            )
            out += bar
        }
        return out
    }

    private fun JSONArray.num(index: Int): Double? = opt(index)?.toString()?.toDoubleOrNull()
}
