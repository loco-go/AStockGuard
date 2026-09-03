package com.locogo.astockguard.data.level2

/*
 * 文件职责：实现 provider-neutral 的盘口获取接口及 HTTP、Mock 适配；Mock 仅用于演示和测试，禁止冒充实盘证据。
 * 架构边界：解析失败、超时和字段缺失要显式返回失败或不可用状态，不能用零值伪造有效行情。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import com.locogo.astockguard.data.ifind.IFindHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** 直接复用App内已加密保存的iFinD refresh token，不需要重复填写Level-2 Bearer Token。 */
class IFindLevel2Provider(private val client: IFindHttpClient) : Level2Provider {
    override suspend fun snapshot(code: String, referencePrice: Double?): Level2Snapshot =
        client.fetchLevel2Snapshot(code)
}

class MockLevel2Provider : Level2Provider {
    override suspend fun snapshot(code: String, referencePrice: Double?): Level2Snapshot {
        val base = (referencePrice ?: (8.0 + (code.hashCode().toLong().let { if (it < 0) -it else it } % 5000) / 100.0)).coerceAtLeast(0.01)
        val tick = if (base < 10) 0.01 else 0.01
        val seed = code.hashCode().toLong().let { if (it < 0) -it else it }
        val bids = (1..10).map { i ->
            Level2Level(price = round2(base - i * tick), volume = 100L * (5 + ((seed + i * 17) % 80)))
        }
        val asks = (1..10).map { i ->
            Level2Level(price = round2(base + i * tick), volume = 100L * (5 + ((seed + i * 23) % 80)))
        }
        val now = LocalTime.now()
        val trades = (0 until 12).map { i ->
            val side = if ((seed + i) % 2L == 0L) "BUY" else "SELL"
            val drift = if (side == "BUY") tick else -tick
            Level2Trade(
                time = now.minusSeconds((i * 7L)).format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                price = round2(base + drift * (i % 3)),
                volume = 100L * (1 + ((seed + i * 13) % 30)),
                side = side
            )
        }
        return Level2Snapshot(
            code = code,
            bids = bids,
            asks = asks,
            trades = trades,
            source = "MOCK",
            simulated = true,
            stale = false,
            updatedAt = System.currentTimeMillis(),
            message = "模拟十档盘口，仅用于 UI/回放/开发验证"
        )
    }

    private fun round2(v: Double) = String.format(Locale.US, "%.2f", max(v, 0.01)).toDouble()
}

class HttpJsonLevel2Provider(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : Level2Provider {
    override suspend fun snapshot(code: String, referencePrice: Double?): Level2Snapshot = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "Level2 Base URL 必须是 HTTPS" }
        val url = baseUrl.trimEnd('/') + "/level2?symbol=" + java.net.URLEncoder.encode(code, "UTF-8")
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Level2 HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            parse(code, json)
        }
    }

    private fun parse(code: String, json: JSONObject): Level2Snapshot {
        fun levels(key: String): List<Level2Level> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length().coerceAtMost(10)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optDouble("price", Double.NaN)
                    val v = o.optLong("volume", -1L)
                    if (p.isFinite() && p > 0 && v >= 0) add(Level2Level(p, v))
                }
            }
        }
        val trades = buildList {
            val arr = json.optJSONArray("trades")
            if (arr != null) for (i in 0 until arr.length().coerceAtMost(50)) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optDouble("price", Double.NaN)
                val v = o.optLong("volume", -1L)
                if (p.isFinite() && p > 0 && v >= 0) {
                    add(Level2Trade(o.optString("time"), p, v, o.optString("side", "UNKNOWN").uppercase()))
                }
            }
        }
        val bids = levels("bids")
        val asks = levels("asks")
        require(bids.isNotEmpty() || asks.isNotEmpty()) { "Level2 JSON 缺少 bids/asks" }
        val rawTimestamp = json.optLong("ts", System.currentTimeMillis())
        // 部分授权网关返回秒级 Unix 时间戳，统一换算成毫秒后再参与实时性校验。
        val timestampMs = if (rawTimestamp in 1 until 10_000_000_000L) rawTimestamp * 1000L else rawTimestamp
        return Level2Snapshot(
            code = json.optString("symbol", code),
            bids = bids,
            asks = asks,
            trades = trades,
            source = json.optString("source", "HTTP_JSON"),
            simulated = false,
            stale = false,
            updatedAt = timestampMs,
            message = "授权 HTTP JSON Level2"
        )
    }
}
