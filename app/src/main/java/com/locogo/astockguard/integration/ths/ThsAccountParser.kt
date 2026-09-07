package com.locogo.astockguard.integration.ths

enum class ThsAccountMetric(val label: String, val unit: String, val aliases: List<String>) {
    TODAY_PNL("今日收益", "元", listOf("今日收益", "当日收益", "今日盈亏", "当日盈亏")),
    CUMULATIVE_PNL("累计收益", "元", listOf("累计收益", "累计盈亏", "总盈亏")),
    POSITION_PCT("当前仓位", "%", listOf("当前仓位", "仓位比例", "仓位")),
    TOTAL_ASSETS("总资产", "元", listOf("总资产", "总资产(元)", "总资产（元）"));

    fun valid(value: Double): Boolean = value.isFinite() && when (this) {
        POSITION_PCT -> value in 0.0..100.0
        TOTAL_ASSETS -> value >= 0.0
        else -> true
    }
}

data class ThsAccountCandidate(val metric: ThsAccountMetric, val value: Double, val observedAt: Long)

/** 只读取账户区带标签的值；不把持仓浮盈当累计收益，也不根据市值反推资产。 */
object ThsAccountParser {
    private val amount = Regex("^[￥¥]?([+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)(万|亿)?(?:元)?(%)?(?=$|\\s|[（(])")

    fun parse(texts: List<String>, observedAt: Long): List<ThsAccountCandidate> {
        val tokens = texts.flatMap { it.split('\n') }.map { it.trim().replace('−', '-').replace('，', ',').replace('％', '%') }
        val end = tokens.indexOfFirst {
            it.contains("持仓/可用") || it.contains("市值/盈亏") || it == "证券名称" ||
                it == "股票名称" || it.contains("持仓管理") || it.contains("持仓资讯")
        }.takeIf { it >= 0 } ?: tokens.size
        val account = tokens.take(end)
        return ThsAccountMetric.entries.mapNotNull { metric ->
            val values = account.mapIndexedNotNull { index, token ->
                val alias = metric.aliases.sortedByDescending { it.length }.firstOrNull {
                    token == it || token.startsWith("$it ") || token.startsWith("$it:") ||
                        token.startsWith("$it：") || token.startsWith("$it(") || token.startsWith("$it（") ||
                        (token.startsWith(it) && token.removePrefix(it).firstOrNull()?.let { c -> c.isDigit() || c in "+-￥¥" } == true)
                } ?: return@mapIndexedNotNull null
                val suffix = token.removePrefix(alias).trim().removePrefix("(元)").removePrefix("（元）")
                    .trim().trimStart(':', '：').trim()
                val raw = if (suffix.isEmpty()) account.getOrNull(index + 1).orEmpty() else suffix
                parseValue(metric, raw)
            }.distinct()
            // 同屏同标签不同值时拒绝猜测，由用户手工补全。
            values.singleOrNull()?.let { ThsAccountCandidate(metric, it, observedAt) }
        }
    }

    fun parseValue(metric: ThsAccountMetric, raw: String): Double? {
        val normalized = raw.trim().replace('−', '-').replace('，', ',').replace('％', '%')
            .replace(Regex("\\s+(?=[%万亿元])"), "")
        val match = amount.find(normalized) ?: return null
        val percentage = match.groupValues[3].isNotEmpty()
        if (metric != ThsAccountMetric.POSITION_PCT && percentage) return null
        if (metric == ThsAccountMetric.POSITION_PCT && match.groupValues[2].isNotEmpty()) return null
        val scale = when (match.groupValues[2]) { "万" -> 10_000.0; "亿" -> 100_000_000.0; else -> 1.0 }
        return match.groupValues[1].replace(",", "").toDoubleOrNull()?.times(scale)?.takeIf(metric::valid)
    }
}
