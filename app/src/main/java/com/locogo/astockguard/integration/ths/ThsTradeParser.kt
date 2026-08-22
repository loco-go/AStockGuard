package com.locogo.astockguard.integration.ths

import com.locogo.astockguard.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ParsedThsTrade(
    val code: String,
    val side: String,
    val quantity: Int,
    val price: Double,
    val tradeAt: Long
)

/**
 * Heuristic parser for visible text from Tonghuashun trade result pages.
 * Raw accessibility text must not be persisted or logged by callers.
 */
object ThsTradeParser {
    private val codeRegex = Regex("(?<!\\d)([0368]\\d{5})(?!\\d)")
    private val timeRegex = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?")
    private val dateRegex = Regex("(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})")
    private val numberRegex = Regex("-?\\d+(?:\\.\\d+)?")

    fun parse(texts: List<String>, today: LocalDate = LocalDate.now(CHINA_ZONE)): List<ParsedThsTrade> {
        val clean = texts.map { it.trim() }.filter { it.isNotBlank() }.take(500)
        if (clean.none { it.contains("成交") || it.contains("交割") || it == "买入" || it == "卖出" }) return emptyList()

        val results = linkedMapOf<String, ParsedThsTrade>()
        clean.forEachIndexed { index, token ->
            val side = normalizeSide(token) ?: return@forEachIndexed
            val window = clean.subList((index - 8).coerceAtLeast(0), (index + 9).coerceAtMost(clean.size))
            val code = window.asSequence().mapNotNull { codeRegex.find(it)?.groupValues?.getOrNull(1) }.firstOrNull() ?: return@forEachIndexed
            val timeToken = window.firstOrNull { timeRegex.containsMatchIn(it) }
            val dateToken = window.firstOrNull { dateRegex.containsMatchIn(it) }
            val tradeAt = parseTimestamp(dateToken, timeToken, today)

            val numeric = window.mapNotNull { tokenNumbers(it) }
                .flatten()
                .filter { it > 0.0 }
            val price = pickPrice(window, code) ?: numeric.firstOrNull { it in 0.01..9999.0 && it % 1.0 != 0.0 } ?: return@forEachIndexed
            val quantity = pickQuantity(window, price) ?: return@forEachIndexed
            if (quantity <= 0 || price <= 0.0) return@forEachIndexed

            val normalized = SettingsRepository.normalizeCode(code)
            val key = "$normalized|$side|$quantity|${"%.4f".format(price)}|$tradeAt"
            results[key] = ParsedThsTrade(normalized, side, quantity, price, tradeAt)
        }
        return results.values.toList()
    }

    private fun normalizeSide(value: String): String? = when {
        value == "买入" || value.contains("买入成交") || value == "证券买入" -> "BUY"
        value == "卖出" || value.contains("卖出成交") || value == "证券卖出" -> "SELL"
        else -> null
    }

    private fun pickPrice(window: List<String>, code: String): Double? {
        val labels = listOf("成交价", "成交价格", "价格")
        labels.forEach { label ->
            val i = window.indexOfFirst { it == label || it.startsWith(label) }
            if (i >= 0) {
                tokenNumbers(window[i]).firstOrNull { it > 0.0 && it.toLong().toString() != code }?.let { return it }
                window.getOrNull(i + 1)?.let { next -> tokenNumbers(next).firstOrNull { it > 0.0 }?.let { return it } }
            }
        }
        return window.asSequence()
            .flatMap { tokenNumbers(it).asSequence() }
            .firstOrNull { it in 0.1..5000.0 && kotlin.math.abs(it - it.toInt()) > 1e-6 }
    }

    private fun pickQuantity(window: List<String>, price: Double): Int? {
        val labels = listOf("成交量", "成交数量", "数量", "股数")
        labels.forEach { label ->
            val i = window.indexOfFirst { it == label || it.startsWith(label) }
            if (i >= 0) {
                tokenNumbers(window[i]).firstOrNull { isPlausibleQuantity(it, price) }?.toInt()?.let { return it }
                window.getOrNull(i + 1)?.let { next -> tokenNumbers(next).firstOrNull { isPlausibleQuantity(it, price) }?.toInt()?.let { return it } }
            }
        }
        return window.asSequence()
            .flatMap { tokenNumbers(it).asSequence() }
            .filter { isPlausibleQuantity(it, price) }
            .map { it.toInt() }
            .firstOrNull()
    }

    private fun isPlausibleQuantity(value: Double, price: Double): Boolean {
        if (kotlin.math.abs(value - price) < 1e-6) return false
        val intValue = value.toInt()
        return value == intValue.toDouble() && intValue in 1..10_000_000
    }

    private fun tokenNumbers(value: String): List<Double> = numberRegex.findAll(value)
        .mapNotNull { it.value.toDoubleOrNull() }
        .toList()

    private fun parseTimestamp(dateToken: String?, timeToken: String?, fallbackDate: LocalDate): Long {
        val date = dateToken?.let { token ->
            dateRegex.find(token)?.let { m ->
                runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
            }
        } ?: fallbackDate
        val time = timeToken?.let { token ->
            val matched = timeRegex.find(token)?.value ?: return@let null
            runCatching {
                if (matched.length == 5) LocalTime.parse(matched, DateTimeFormatter.ofPattern("HH:mm"))
                else LocalTime.parse(matched, DateTimeFormatter.ofPattern("HH:mm:ss"))
            }.getOrNull()
        } ?: LocalTime.NOON
        return LocalDateTime.of(date, time).atZone(CHINA_ZONE).toInstant().toEpochMilli()
    }

    private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
}
