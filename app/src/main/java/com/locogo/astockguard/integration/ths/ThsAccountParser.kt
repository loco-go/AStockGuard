package com.locogo.astockguard.integration.ths

enum class ThsAccountMetric(val label: String, val unit: String, val aliases: List<String>) {
    TODAY_PNL("今日收益", "元", listOf("当日参考盈亏", "今日收益", "当日收益", "今日盈亏", "当日盈亏")),
    CUMULATIVE_PNL("累计收益", "元", listOf("浮动盈亏", "累计收益", "累计盈亏", "总盈亏")),
    POSITION_PCT("当前仓位", "%", listOf("当前仓位", "仓位比例", "仓位")),
    TOTAL_ASSETS("总资产", "元", listOf("总资产", "总资产(元)", "总资产（元）"));

    fun valid(value: Double): Boolean = value.isFinite() && when (this) {
        POSITION_PCT -> value in 0.0..100.0
        TOTAL_ASSETS -> value >= 0.0
        else -> true
    }
}

data class ThsAccountCandidate(
    val metric: ThsAccountMetric, val value: Double, val observedAt: Long,
    val source: String = "THS_CONFIRMED"
)

/** 只读取账户区带标签的值；不把持仓浮盈当累计收益，也不根据市值反推资产。 */
object ThsAccountParser {
    private val amount = Regex("^[￥¥]?([+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)(万|亿)?(?:元)?(%)?(?=$|\\s|[（(])")
    private val aliases = ThsAccountMetric.entries.flatMap { metric ->
        metric.aliases.filterNot { it.contains('(') || it.contains('（') }.map { it to metric }
    }.toMap()
    private val labels = Regex("(?<![\\p{IsHan}A-Za-z])(" +
        aliases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
        ")(?![\\p{IsHan}A-Za-z])(?:\\s*[（(](元|万元|亿元|%|百分比)[）)])?")
    private val tableBoundary = Regex("持仓/可用|市值/盈亏|证券名称|股票名称|持仓管理|持仓资讯")

    fun parse(texts: List<String>, observedAt: Long): List<ThsAccountCandidate> {
        // 先按账户区边界截断，再按标签切分；兼容一节点多个字段及表头与值分离。
        val page = texts.take(800).joinToString("\n") { it.trim() }
            .replace('−', '-').replace('，', ',').replace('％', '%')
        val account = page.take(tableBoundary.find(page)?.range?.first ?: page.length)
        val matches = labels.findAll(account).toList()
        val recognized = matches.mapIndexedNotNull { index, label ->
            val metric = aliases.getValue(label.groupValues[1])
            val end = matches.getOrNull(index + 1)?.range?.first ?: account.length
            val raw = account.substring(label.range.last + 1, end).trim().trimStart(':', '：').trim()
            val headerUnit = label.groupValues[2]
            if (metric != ThsAccountMetric.POSITION_PCT && headerUnit in listOf("%", "百分比")) return@mapIndexedNotNull null
            if (metric == ThsAccountMetric.POSITION_PCT && headerUnit in listOf("元", "万元", "亿元")) return@mapIndexedNotNull null
            val value = parseValue(metric, raw) ?: return@mapIndexedNotNull null
            val explicitUnit = amount.find(normalizeValue(raw))?.groupValues?.get(2).orEmpty()
            val scale = if (explicitUnit.isNotEmpty()) 1.0 else when (headerUnit) {
                "万元" -> 10_000.0
                "亿元" -> 100_000_000.0
                else -> 1.0
            }
            (value * scale).takeIf(metric::valid)?.let { ThsAccountCandidate(metric, it, observedAt) }
        }
        return ThsAccountMetric.entries.mapNotNull { metric ->
            // 同屏同标签不同值时拒绝猜测，由用户手工补全。
            recognized.filter { it.metric == metric }.distinctBy { it.value }.singleOrNull()
        }
    }

    fun parseValue(metric: ThsAccountMetric, raw: String): Double? {
        val normalized = normalizeValue(raw)
        val match = amount.find(normalized) ?: return null
        val percentage = match.groupValues[3].isNotEmpty()
        if (metric != ThsAccountMetric.POSITION_PCT && percentage) return null
        if (metric == ThsAccountMetric.POSITION_PCT && match.groupValues[2].isNotEmpty()) return null
        val scale = when (match.groupValues[2]) { "万" -> 10_000.0; "亿" -> 100_000_000.0; else -> 1.0 }
        return match.groupValues[1].replace(",", "").toDoubleOrNull()?.times(scale)?.takeIf(metric::valid)
    }

    private fun normalizeValue(raw: String) = raw.trim().replace('−', '-').replace('，', ',').replace('％', '%')
        .replace(Regex("\\s+(?=[%万亿元])"), "")
}
