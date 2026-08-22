package com.locogo.astockguard.data.news

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
