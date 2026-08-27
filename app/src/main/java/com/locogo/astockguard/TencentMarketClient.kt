package com.locogo.astockguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val latest = a.getOrNull(3)?.toDoubleOrNull()
        val volume = a.getOrNull(6)?.toDoubleOrNull()
        val amountWan = a.getOrNull(37)?.toDoubleOrNull()
        val amountYuan = a.getOrNull(35)?.split('/')?.getOrNull(2)?.toDoubleOrNull()
            ?: amountWan?.times(10_000.0)
        Quote(
            code = code,
            name = a.getOrNull(1).orEmpty(),
            time = a.getOrNull(30).orEmpty(),
            open = a.getOrNull(5)?.toDoubleOrNull(),
            high = a.getOrNull(33)?.toDoubleOrNull(),
            low = a.getOrNull(34)?.toDoubleOrNull(),
            latest = latest,
            previousClose = a.getOrNull(4)?.toDoubleOrNull(),
            changeRatio = a.getOrNull(32)?.toDoubleOrNull(),
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
        fun toTencentCode(code: String): String {
            val normalized = SettingsRepository.normalizeCode(code)
            val digits = normalized.take(6)
            return if (normalized.endsWith(".SH")) "sh$digits" else "sz$digits"
        }
    }
}
