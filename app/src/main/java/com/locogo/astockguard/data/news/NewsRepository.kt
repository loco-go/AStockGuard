package com.locogo.astockguard.data.news

/*
 * 文件职责：负责新闻刷新节流、缓存和风险评估输入；新闻只作为风险覆盖层，不能独立生成买卖动作。
 * 架构边界：解析失败、超时和字段缺失要显式返回失败或不可用状态，不能用零值伪造有效行情。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.NewsItemEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class NewsRepository(
    private val settings: SettingsRepository,
    private val cacheDao: CacheDao,
    private val client: NewsFeedClient = NewsFeedClient()
) {
    suspend fun refresh(): NewsRiskAssessment {
        val now = System.currentTimeMillis()
        if (!settings.newsEnabled) return NewsRiskAssessment(updatedAt = now)
        val sources = settings.newsSources()
        if (sources.isEmpty()) return fromCache(now, stale = true)

        val remote = coroutineScope {
            sources.map { source -> async { runCatching { client.fetch(source) }.getOrDefault(emptyList()) } }
                .flatMap { it.await() }
        }.distinctBy { it.id }

        if (remote.isNotEmpty()) {
            cacheDao.upsertNews(remote.map { it.toEntity() })
            cacheDao.deleteOldNews(now - 14L * 24L * 60L * 60L * 1000L)
            val recent = cacheDao.getNewsSince(now - 48L * 60L * 60L * 1000L).map { it.toModel() }
            return NewsRiskEngine.assess(recent, now, stale = false)
        }
        return fromCache(now, stale = true)
    }

    suspend fun cached(): NewsRiskAssessment = fromCache(System.currentTimeMillis(), stale = true)

    private suspend fun fromCache(now: Long, stale: Boolean): NewsRiskAssessment {
        val recent = cacheDao.getNewsSince(now - 48L * 60L * 60L * 1000L).map { it.toModel() }
        return NewsRiskEngine.assess(recent, now, stale = stale)
    }

    private fun NewsItem.toEntity() = NewsItemEntity(id, source, title, url, publishedAt, fetchedAt)
    private fun NewsItemEntity.toModel() = NewsItem(id, source, title, url, publishedAt, fetchedAt)
}
