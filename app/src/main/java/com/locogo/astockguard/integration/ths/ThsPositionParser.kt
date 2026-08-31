package com.locogo.astockguard.integration.ths

import com.locogo.astockguard.Position
import com.locogo.astockguard.SettingsRepository

data class ParsedThsPosition(
    val code: String,
    val name: String,
    val shares: Int,
    val cost: Double
)

/**
 * 解析同花顺持仓页暴露给辅助功能的可见文本。
 *
 * 不同同花顺版本会把一行持仓拆成多个节点，或合并为一段描述，因此这里同时支持
 * “标签 + 数值”和表格顺序两种结构。调用方只保存结构化结果，不保存整页原始文本。
 */
object ThsPositionParser {
    private val codeRegex = Regex("(?<!\\d)([0368]\\d{5})(?!\\d)")
    private val numberRegex = Regex("-?\\d+(?:\\.\\d+)?")
    private val pageMarkers = listOf("持仓", "股票余额", "股份余额", "持仓数量", "证券市值", "成本价")
    private val shareLabels = listOf("持仓数量", "持有数量", "股票余额", "股份余额", "证券数量", "实际数量", "总数量", "持仓")
    private val costLabels = listOf("持仓成本", "参考成本价", "成本价格", "成本价", "买入均价", "成本")
    private val sharePairRegex = Regex("(?<![\\d.])(\\d{1,9})\\s*/\\s*\\d{1,9}(?![\\d.])")
    private val pricePairRegex = Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*/\\s*\\d+(?:\\.\\d+)?(?![\\d.])")
    private val ignoredNames = (pageMarkers + shareLabels + costLabels + listOf(
        "证券代码", "股票代码", "证券名称", "股票名称", "可用余额", "可用数量", "现价", "市价",
        "市值", "盈亏", "盈亏比例", "当日盈亏", "更多", "操作", "买入", "卖出"
    )).toSet()

    /** 从当前可见节点中提取持仓；只返回股数和成本均有效的记录。 */
    fun parse(texts: List<String>, knownCodeByName: Map<String, String> = emptyMap()): List<ParsedThsPosition> {
        val clean = texts.asSequence()
            .flatMap { it.split(Regex("[\\s|｜]+")) .asSequence() }
            .map { it.trim().trim(',', '，', ':', '：') }
            .filter { it.isNotBlank() }
            .take(800)
            .toList()
        if (clean.none { token -> pageMarkers.any(token::contains) }) return emptyList()

        val codeIndexes = clean.mapIndexedNotNull { index, token ->
            codeRegex.find(token)?.groupValues?.getOrNull(1)?.let { index to it }
        }
        val results = linkedMapOf<String, ParsedThsPosition>()
        codeIndexes.forEachIndexed { order, (codeIndex, rawCode) ->
            val previousCodeIndex = codeIndexes.getOrNull(order - 1)?.first ?: -1
            val nextCodeIndex = codeIndexes.getOrNull(order + 1)?.first ?: clean.size
            val start = maxOf(previousCodeIndex + 1, codeIndex - 4)
            val end = minOf(nextCodeIndex, codeIndex + 14)
            val row = clean.subList(start, end)
            val localCodeIndex = codeIndex - start

            val shares = findLabeledNumber(row, shareLabels)?.takeIf(::isShareCount)?.toInt()
                ?: inferShares(row.drop(localCodeIndex + 1))
            val cost = findLabeledNumber(row, costLabels)?.takeIf(::isCost)
                ?: inferCost(row.drop(localCodeIndex + 1), shares)
            val name = inferName(row, localCodeIndex, rawCode)
            if (shares != null && cost != null && name != null) {
                val code = SettingsRepository.normalizeCode(rawCode)
                results[code] = ParsedThsPosition(code, name, shares, cost)
            }
        }
        parseNameOnlyRows(clean, knownCodeByName).forEach { parsed -> results.putIfAbsent(parsed.code, parsed) }
        return results.values.toList()
    }

    /** 提取真实持仓表中的股票名称，供 Repository 用本地缓存或远端接口补全证券代码。 */
    fun findVisiblePositionNames(texts: List<String>): List<String> {
        val clean = texts.map(String::trim).filter(String::isNotBlank).take(800)
        val headerEnd = clean.indexOfFirst { it.contains("成本/现价") || it.contains("成本价/现价") }
        if (headerEnd < 0) return emptyList()
        return clean.mapIndexedNotNull { index, token ->
            if (index <= headerEnd || token in ignoredNames || numberRegex.containsMatchIn(token)) return@mapIndexedNotNull null
            val following = clean.subList(index + 1, minOf(clean.size, index + 5))
            token.takeIf { following.any { value -> value.contains('%') } && token.length in 2..16 }
        }.distinct()
    }

    /**
     * 新版同花顺持仓表不向辅助节点暴露证券代码，只提供名称以及固定的七列数值。
     * 这里仅使用调用方提供的本地“名称→代码”映射，避免根据名称模糊猜测错误证券。
     */
    private fun parseNameOnlyRows(clean: List<String>, knownCodeByName: Map<String, String>): List<ParsedThsPosition> {
        if (knownCodeByName.isEmpty()) return emptyList()
        val normalizedCodes = knownCodeByName.entries.associate { normalizeName(it.key) to SettingsRepository.normalizeCode(it.value) }
        val headerEnd = clean.indexOfFirst { it.contains("成本/现价") || it.contains("成本价/现价") }
        if (headerEnd < 0) return emptyList()

        val nameIndexes = clean.mapIndexedNotNull { index, token ->
            normalizedCodes[normalizeName(token)]?.takeIf { index > headerEnd }?.let { index to it }
        }
        return nameIndexes.mapNotNull { (nameIndex, code) ->
            val nextNameIndex = nameIndexes.firstOrNull { it.first > nameIndex }?.first ?: clean.size
            val row = clean.subList(nameIndex + 1, minOf(nextNameIndex, nameIndex + 10))
            val percentIndex = row.indexOfFirst { it.contains('%') }
            if (percentIndex < 0) return@mapNotNull null
            val valuesAfterPercent = row.drop(percentIndex + 1).flatMap(::numbers)
            val shares = valuesAfterPercent.getOrNull(0)?.takeIf(::isShareCount)?.toInt() ?: return@mapNotNull null
            // 实际列顺序为持仓、可用、成本、现价，成本位于第三个数值。
            val cost = valuesAfterPercent.getOrNull(2)?.takeIf(::isCost) ?: return@mapNotNull null
            ParsedThsPosition(code, clean[nameIndex], shares, cost)
        }
    }

    /** 优先读取“持仓数量 1000”或标签后一个节点的明确值。 */
    private fun findLabeledNumber(row: List<String>, labels: List<String>): Double? {
        row.forEachIndexed { index, token ->
            val label = labels.asSequence().filter(token::contains).maxByOrNull(String::length) ?: return@forEachIndexed
            val suffix = token.substringAfter(label, "")
            firstNumber(suffix)?.let { return it }
            val next = row.getOrNull(index + 1)
            // 表头“持仓”后面可能直接是第一只股票，不能把六位证券代码误当成 1 股。
            if (next != null && !codeRegex.containsMatchIn(next)) firstNumber(next)?.let { return it }
        }
        return null
    }

    /** 表格页通常按“持仓/可用/成本/现价”排列，首个正整数可作为持仓数量。 */
    private fun inferShares(afterCode: List<String>): Int? {
        afterCode.asSequence().mapNotNull { token ->
            sharePairRegex.find(token)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }.firstOrNull(::isShareCount)?.toInt()?.let { return it }
        afterCode.asSequence().mapNotNull { token ->
            Regex("(?<!\\d)(\\d{1,9})\\s*股").find(token)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }.firstOrNull(::isShareCount)?.toInt()?.let { return it }
        return afterCode.asSequence()
            .flatMap { numbers(it).asSequence() }
            .firstOrNull(::isShareCount)
            ?.toInt()
    }

    /**
     * 无标签表格中，成本通常位于持仓数量、可用数量之后；若只有一个数量列，则取其后首个价格。
     */
    private fun inferCost(afterCode: List<String>, shares: Int?): Double? {
        if (shares == null) return null
        val shareTokenIndex = afterCode.indexOfFirst { token ->
            sharePairRegex.find(token)?.groupValues?.getOrNull(1)?.toIntOrNull() == shares ||
                Regex("(?<!\\d)$shares\\s*股").containsMatchIn(token)
        }
        if (shareTokenIndex >= 0) {
            afterCode.drop(shareTokenIndex + 1).asSequence()
                .mapNotNull { pricePairRegex.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
                .firstOrNull(::isCost)
                ?.let { return it }
        }
        val values = afterCode.flatMap(::numbers)
        val shareIndex = values.indexOfFirst { it.toInt() == shares && isShareCount(it) }
        if (shareIndex < 0) return null
        val following = values.drop(shareIndex + 1)
        val afterAvailable = if (following.firstOrNull()?.let(::isNonNegativeInteger) == true) following.drop(1) else following
        return afterAvailable.firstOrNull(::isCost)
    }

    private fun inferName(row: List<String>, codeIndex: Int, rawCode: String): String? {
        val candidates = buildList {
            // 部分版本把“股票名 + 代码 + 持仓字段”合成一个 contentDescription。
            row.getOrNull(codeIndex)?.substringBefore(rawCode)?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            for (distance in 1..4) {
                row.getOrNull(codeIndex - distance)?.let(::add)
                row.getOrNull(codeIndex + distance)?.let(::add)
            }
        }
        return candidates.firstOrNull { token ->
            token != rawCode && token !in ignoredNames && !codeRegex.containsMatchIn(token) &&
                numberRegex.find(token)?.value != token && token.length in 2..16 &&
                token.any { it.isLetter() || it.code > 127 }
        }?.replace(rawCode, "")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun firstNumber(value: String): Double? = numberRegex.find(value)?.value?.toDoubleOrNull()
    private fun numbers(value: String): List<Double> = numberRegex.findAll(value.replace(",", ""))
        .mapNotNull { it.value.toDoubleOrNull() }
        .toList()
    private fun normalizeName(value: String): String = value.trim().replace(" ", "").uppercase()
    private fun isShareCount(value: Double): Boolean = value > 0 && isNonNegativeInteger(value) && value <= 100_000_000
    private fun isNonNegativeInteger(value: Double): Boolean = value >= 0 && value == value.toLong().toDouble()
    private fun isCost(value: Double): Boolean = value in 0.001..100_000.0
}

/** 将本次可见持仓合并进用户配置，同时保留用户原先设置的仓位角色。 */
object ThsPositionMerger {
    fun merge(existing: List<Position>, incoming: List<ParsedThsPosition>): List<Position> {
        val merged = linkedMapOf<String, Position>()
        existing.forEach { merged[it.code] = it }
        incoming.forEach { parsed ->
            val old = merged[parsed.code]
            merged[parsed.code] = Position(
                code = parsed.code,
                name = parsed.name.ifBlank { old?.name.orEmpty() },
                shares = parsed.shares,
                cost = parsed.cost,
                role = old?.role ?: "CORE"
            )
        }
        return merged.values.toList()
    }
}
