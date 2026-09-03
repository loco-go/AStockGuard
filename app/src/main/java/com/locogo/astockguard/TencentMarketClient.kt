package com.locogo.astockguard

/*
 * 文件职责：解析腾讯实时行情文本并规范价格与成交额单位；盘前零价等占位符转换为空值，供 Repository 安全降级使用。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class TencentMarketClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchQuotes(codes: List<String>): List<Quote> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext emptyList()
        val symbols = codes.joinToString(",") { toTencentCode(it) }
        val request = Request.Builder().url("https://qt.gtimg.cn/q=$symbols").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("腾讯行情 HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: return@use emptyList()
            parse(String(bytes, Charset.forName("GBK")))
        }
    }

    /** 按证券名称查询腾讯候选代码；最终名称一致性由 Repository 拉取实时行情后二次校验。 */
    suspend fun searchCodes(name: String): List<String> = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext emptyList()
        val url = "https://smartbox.gtimg.cn/s3/".toHttpUrl().newBuilder()
            .addQueryParameter("q", name.trim())
            .addQueryParameter("t", "all")
            .build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("腾讯证券搜索 HTTP ${response.code}")
            parseSearchCodes(response.body?.string().orEmpty())
        }
    }

    fun parse(text: String): List<Quote> = text.split(';', '\n').mapNotNull { line ->
        val body = line.substringAfter('"', "").substringBeforeLast('"', "")
        if (body.isBlank()) return@mapNotNull null
        val a = body.split('~')
        if (a.size < 35) return@mapNotNull null
        val wireSymbol = line.substringBefore('=').substringAfter("v_").lowercase()
        val digits = a.getOrNull(2).orEmpty().filter(Char::isDigit).takeLast(6)
        val code = when {
            wireSymbol.startsWith("sh") -> "$digits.SH"
            wireSymbol.startsWith("sz") -> "$digits.SZ"
            else -> SettingsRepository.normalizeCode(digits)
        }
        // 腾讯盘前可能以0占位；统一转为null，避免市值和今日收益被瞬间清零。
        val latest = a.getOrNull(3)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val volume = a.getOrNull(6)?.toDoubleOrNull()
        val amountWan = a.getOrNull(37)?.toDoubleOrNull()
        val amountYuan = a.getOrNull(35)?.split('/')?.getOrNull(2)?.toDoubleOrNull()
            ?: amountWan?.times(10_000.0)
        Quote(
            code = code,
            name = a.getOrNull(1).orEmpty(),
            time = a.getOrNull(30).orEmpty(),
            open = a.getOrNull(5)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
            high = a.getOrNull(33)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
            low = a.getOrNull(34)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
            latest = latest,
            previousClose = a.getOrNull(4)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
            changeRatio = a.getOrNull(32)?.toDoubleOrNull()?.takeIf { latest != null && it.isFinite() },
            volume = volume,
            amount = amountYuan,
            vwap = inferVwap(latest, volume, amountYuan)
        )
    }

    private fun inferVwap(price: Double?, volume: Double?, amount: Double?): Double? {
        if (price == null || price <= 0 || volume == null || volume <= 0 || amount == null || amount <= 0) return null
        val candidates = listOf(amount / volume, amount * 100.0 / volume, amount * 10_000.0 / volume)
            .filter { it in price * 0.3..price * 3.0 }
        return candidates.minByOrNull { abs(it - price) }
    }

    companion object {
        /** 解析 smartbox 的 sh~600522~名称 格式，只接受当前应用支持的沪深 A 股代码。 */
        fun parseSearchCodes(text: String): List<String> = Regex("(?:^|[\\\"^])(sh|sz)~(\\d{6})~", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { match ->
                val suffix = if (match.groupValues[1].equals("sh", true)) "SH" else "SZ"
                "${match.groupValues[2]}.$suffix"
            }
            .distinct()
            .toList()

        fun toTencentCode(code: String): String {
            val normalized = SettingsRepository.normalizeCode(code)
            val digits = normalized.take(6)
            return if (normalized.endsWith(".SH")) "sh$digits" else "sz$digits"
        }
    }
}
