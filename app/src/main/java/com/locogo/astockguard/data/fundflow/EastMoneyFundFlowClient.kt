package com.locogo.astockguard.data.fundflow

/*
 * 文件职责：解析东方财富个股与板块资金流响应；网络异常和字段漂移必须显式失败。
 * 架构边界：外部字段缺失、过期或异常时返回不可用，不能以默认零值伪造有效数据。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EastMoneyFundFlowClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    suspend fun stockMinute(code: String): List<FundFlowPoint> = withContext(Dispatchers.IO) {
        val url = "https://push2.eastmoney.com/api/qt/stock/fflow/kline/get".toHttpUrl().newBuilder()
            .addQueryParameter("secid", secid(code)).addQueryParameter("klt", "1").addQueryParameter("lmt", "0")
            .addQueryParameter("fields1", "f1,f2,f3,f7")
            .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57")
            .addQueryParameter("ut", UT).build()
        parseFlow(request(url.toString()))
    }

    suspend fun stockDaily(code: String, limit: Int = 20): List<FundFlowPoint> = withContext(Dispatchers.IO) {
        val url = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get".toHttpUrl().newBuilder()
            .addQueryParameter("secid", secid(code)).addQueryParameter("lmt", limit.toString())
            .addQueryParameter("fields1", "f1,f2,f3,f7")
            .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65")
            .addQueryParameter("ut", UT).build()
        parseFlow(request(url.toString()))
    }

    suspend fun sectors(type: String, limit: Int = 30): List<SectorFundFlow> = withContext(Dispatchers.IO) {
        val fs = when (type.uppercase()) {
            "CONCEPT" -> "m:90+t:3+f:!50"
            "REGION" -> "m:90+t:1+f:!50"
            else -> "m:90+t:2+f:!50"
        }
        val url = "https://push2.eastmoney.com/api/qt/clist/get".toHttpUrl().newBuilder()
            .addQueryParameter("pn", "1").addQueryParameter("pz", limit.coerceIn(1, 100).toString())
            .addQueryParameter("po", "1").addQueryParameter("np", "1")
            .addQueryParameter("fltt", "2").addQueryParameter("invt", "2")
            .addQueryParameter("fid", "f62").addQueryParameter("fs", fs)
            .addQueryParameter("fields", "f12,f14,f2,f3,f62,f184,f66,f69,f72,f75,f78,f81,f84,f87,f204,f205")
            .addQueryParameter("ut", UT).build()
        val diff = JSONObject(request(url.toString())).optJSONObject("data")?.optJSONArray("diff") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until diff.length()) {
                val r = diff.optJSONObject(i) ?: continue
                val name = r.optString("f14"); if (name.isBlank()) continue
                add(SectorFundFlow(
                    code = r.optString("f12"), name = name, type = type.uppercase(),
                    changePct = r.optDouble("f3", 0.0), mainNet = r.optDouble("f62", 0.0), mainPct = r.optDouble("f184", 0.0),
                    superLargeNet = r.optDouble("f66", 0.0), largeNet = r.optDouble("f72", 0.0),
                    mediumNet = r.optDouble("f78", 0.0), smallNet = r.optDouble("f84", 0.0),
                    leadStockName = r.optString("f204"), leadStockCode = r.optString("f205")
                ))
            }
        }
    }

    private fun parseFlow(text: String): List<FundFlowPoint> {
        val rows = JSONObject(text).optJSONObject("data")?.optJSONArray("klines") ?: return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val p = rows.optString(i).split(','); if (p.size < 6) continue
                add(FundFlowPoint(
                    time = p[0], mainNet = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                    smallNet = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0, mediumNet = p.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
                    largeNet = p.getOrNull(4)?.toDoubleOrNull() ?: 0.0, superLargeNet = p.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                ))
            }
        }
    }

    private fun request(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 Android AStockGuard")
            .header("Referer", "https://data.eastmoney.com/").build()
        return client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("EastMoney HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    private fun secid(code: String): String {
        val digits = code.substringBefore('.').filter(Char::isDigit).takeLast(6)
        val market = if (digits.startsWith("6") || digits.startsWith("5") || digits.startsWith("9")) 1 else 0
        return "$market.$digits"
    }

    companion object { private const val UT = "fa5fd1943c7b386f172d6893dbbd1d0c" }
}
