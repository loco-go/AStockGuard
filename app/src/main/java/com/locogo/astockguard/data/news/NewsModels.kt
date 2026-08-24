package com.locogo.astockguard.data.news

data class NewsSource(val name: String, val url: String)

data class NewsItem(
    val id: String,
    val source: String,
    val title: String,
    val url: String,
    val publishedAt: Long,
    val fetchedAt: Long
)

data class NewsRiskAssessment(
    val level: String = "E0",
    val score: Int = 0,
    val evidence: List<NewsItem> = emptyList(),
    val sourceCount: Int = 0,
    val stale: Boolean = false,
    val updatedAt: Long = 0L
)

object NewsRiskEngine {
    private val severe = mapOf(
        "战争" to 5, "开战" to 5, "空袭" to 5, "导弹" to 4, "封锁" to 4,
        "制裁" to 4, "断供" to 4, "出口管制" to 4, "禁运" to 4, "退市" to 4,
        "暴雷" to 4, "违约" to 4, "崩盘" to 5, "熔断" to 5, "紧急停牌" to 4,
        "war" to 5, "airstrike" to 5, "missile" to 4, "sanction" to 4, "embargo" to 4,
        "default" to 4, "crash" to 5, "circuit breaker" to 5
    )
    private val medium = mapOf(
        "调查" to 2, "立案" to 3, "减持" to 2, "关税" to 2, "加息" to 2,
        "监管" to 2, "处罚" to 2, "召回" to 2, "停产" to 3, "下调评级" to 2,
        "investigation" to 2, "tariff" to 2, "rate hike" to 2, "downgrade" to 2
    )
    private val relief = listOf(
        "解除制裁", "取消制裁", "停火协议", "达成停火", "恢复供应", "恢复生产", "解除封锁",
        "撤销关税", "取消关税", "制裁解除", "ceasefire agreement", "sanctions lifted", "supply restored"
    )

    fun assess(items: List<NewsItem>, now: Long = System.currentTimeMillis(), stale: Boolean = false): NewsRiskAssessment {
        val cutoff = now - 24L * 60L * 60L * 1000L
        val scored = items.asSequence()
            .filter { it.publishedAt <= 0L || it.publishedAt >= cutoff }
            .map { item -> item to scoreTitle(item.title) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .toList()
        val top = scored.take(5)
        val total = top.sumOf { it.second }.coerceAtMost(20)
        val level = when {
            top.any { it.second >= 5 } || total >= 9 -> "E2"
            total >= 3 -> "E1"
            else -> "E0"
        }
        return NewsRiskAssessment(
            level = level,
            score = total,
            evidence = top.map { it.first },
            sourceCount = items.map { it.source }.distinct().size,
            stale = stale,
            updatedAt = now
        )
    }

    private fun scoreTitle(raw: String): Int {
        val title = raw.lowercase()
        if (relief.any { title.contains(it.lowercase()) }) return 0
        var score = 0
        severe.forEach { (word, weight) -> if (title.contains(word.lowercase())) score += weight }
        medium.forEach { (word, weight) -> if (title.contains(word.lowercase())) score += weight }
        return score.coerceAtMost(8)
    }
}
