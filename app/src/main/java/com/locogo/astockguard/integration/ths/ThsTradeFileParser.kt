package com.locogo.astockguard.integration.ths

/*
 * 文件职责：解析 CSV、TSV、TXT 交割单并兼容常见中英文表头；输入文件只在用户授权的读取周期内处理。
 * 架构边界：集成层只读取用户授权的数据，不保存整页原文，不执行真实交易。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

import com.locogo.astockguard.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CSV/TSV/text delivery-note parser. XLS/XLSX will be added only if a broker export requires it. */
object ThsTradeFileParser {
    private val aliases = mapOf(
        "code" to listOf("证券代码", "股票代码", "代码", "securitycode", "symbol"),
        "side" to listOf("买卖标志", "买卖方向", "方向", "操作", "side"),
        "price" to listOf("成交价格", "成交价", "价格", "price"),
        "qty" to listOf("成交数量", "成交量", "数量", "quantity", "qty"),
        "date" to listOf("成交日期", "交易日期", "日期", "date"),
        "time" to listOf("成交时间", "交易时间", "时间", "time")
    )

    fun parse(text: String): List<ParsedThsTrade> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val lines = normalized.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(20_000).toList()
        if (lines.size < 2) return emptyList()
        val delimiter = detectDelimiter(lines.first())
        val header = split(lines.first(), delimiter).map { it.trim().lowercase() }
        val index = aliases.mapValues { (_, names) -> header.indexOfFirst { cell -> names.any { it.lowercase() == cell } } }
        if ((index["code"] ?: -1) < 0 || (index["side"] ?: -1) < 0 || (index["price"] ?: -1) < 0 || (index["qty"] ?: -1) < 0) return emptyList()

        val result = linkedMapOf<String, ParsedThsTrade>()
        lines.drop(1).forEach { line ->
            val cells = split(line, delimiter)
            fun cell(key: String): String = index[key]?.takeIf { it >= 0 }?.let { cells.getOrNull(it).orEmpty().trim() }.orEmpty()
            val code = cell("code").filter { it.isDigit() }.takeLast(6)
            if (code.length != 6) return@forEach
            val side = normalizeSide(cell("side")) ?: return@forEach
            val price = cell("price").replace(",", "").toDoubleOrNull() ?: return@forEach
            val qty = cell("qty").replace(",", "").filter { it.isDigit() }.toIntOrNull() ?: return@forEach
            if (price <= 0 || qty <= 0) return@forEach
            val tradeAt = parseTimestamp(cell("date"), cell("time"))
            val normalizedCode = SettingsRepository.normalizeCode(code)
            val key = "$normalizedCode|$side|$qty|${"%.4f".format(price)}|$tradeAt"
            result[key] = ParsedThsTrade(normalizedCode, side, qty, price, tradeAt)
        }
        return result.values.toList()
    }

    private fun detectDelimiter(header: String): Char = when {
        header.count { it == '\t' } >= 2 -> '\t'
        header.count { it == ',' } >= 2 -> ','
        header.count { it == ';' } >= 2 -> ';'
        else -> ','
    }

    private fun split(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { result += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result += current.toString()
        return result
    }

    private fun normalizeSide(value: String): String? {
        val v = value.trim().uppercase()
        return when {
            v.contains("买") || v == "BUY" || v == "B" -> "BUY"
            v.contains("卖") || v == "SELL" || v == "S" -> "SELL"
            else -> null
        }
    }

    private fun parseTimestamp(dateText: String, timeText: String): Long {
        val date = listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "yyyyMMdd")
            .asSequence().mapNotNull { pattern -> runCatching { LocalDate.parse(dateText.trim(), DateTimeFormatter.ofPattern(pattern)) }.getOrNull() }.firstOrNull()
            ?: LocalDate.now(CHINA_ZONE)
        val time = listOf("HH:mm:ss", "HH:mm", "HHmmss")
            .asSequence().mapNotNull { pattern -> runCatching { LocalTime.parse(timeText.trim(), DateTimeFormatter.ofPattern(pattern)) }.getOrNull() }.firstOrNull()
            ?: LocalTime.NOON
        return LocalDateTime.of(date, time).atZone(CHINA_ZONE).toInstant().toEpochMilli()
    }

    private val CHINA_ZONE = ZoneId.of("Asia/Shanghai")
}
