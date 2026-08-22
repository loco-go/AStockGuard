package com.locogo.astockguard

import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.toCacheEntity
import com.locogo.astockguard.data.local.toModel

class MarketRepository(
    private val settings: SettingsRepository,
    private val tencent: TencentMarketClient = TencentMarketClient(),
    private val history: TencentHistoryClient = TencentHistoryClient(),
    private val cacheDao: CacheDao? = null
) {
    private data class Cache(val at: Long, val bars: List<DailyBar>)
    private val historyCache = mutableMapOf<String, Cache>()

    suspend fun refresh(): MonitorSnapshot {
        val codes = settings.allCodes()
        val remote = runCatching { tencent.fetchQuotes(codes) }.getOrNull().orEmpty()
        val usingCache = remote.isEmpty()
        val rawQuotes = if (remote.isNotEmpty()) {
            cacheDao?.let { dao -> runCatching { dao.upsertQuotes(remote.map { it.toCacheEntity() }) } }
            remote
        } else {
            cacheDao?.getQuotes(codes).orEmpty().map { it.toModel() }
        }
        if (rawQuotes.isEmpty()) error("实时行情和本地缓存均不可用")

        val preliminary = RiskEngine.assess(rawQuotes, settings.positions(), settings.positionRatio)
        val enriched = rawQuotes.map { q ->
            val bars = getHistory(q.code)
            val ma5 = bars.takeLast(5).takeIf { it.size == 5 }?.map { it.close }?.average()
            val ma10 = bars.takeLast(10).takeIf { it.size == 10 }?.map { it.close }?.average()
            val r2 = R2Engine.evaluate(bars, preliminary.marketPhase)
            q.copy(ma5 = ma5, ma10 = ma10, r2Score = r2.score, r2Grade = r2.grade, r2Reason = r2.reason)
        }
        cacheDao?.let { dao -> runCatching { dao.upsertQuotes(enriched.map { it.toCacheEntity() }) } }

        val liveAssessment = RiskEngine.assess(enriched, settings.positions(), settings.positionRatio)
        val assessment = if (usingCache) {
            liveAssessment.copy(
                advice = "⚠ 当前使用本地缓存行情，不执行实时 BUY/SELL。${liveAssessment.advice}",
                signals = liveAssessment.signals.map { signal ->
                    if (signal.action == "HOLD") signal
                    else signal.copy(action = "WATCH", reason = "缓存行情：仅观察，不执行。${signal.reason}")
                }
            )
        } else liveAssessment

        return MonitorSnapshot(
            updatedAt = System.currentTimeMillis(),
            quotes = enriched,
            assessment = assessment,
            positionRatio = settings.positionRatio,
            dataHealth = DataHealth(
                source = if (usingCache) "ROOM_CACHE" else "TENCENT",
                isStale = usingCache,
                message = if (usingCache) "腾讯请求失败，已降级到本地缓存" else "实时行情正常"
            )
        )
    }

    private suspend fun getHistory(code: String): List<DailyBar> {
        val now = System.currentTimeMillis()
        historyCache[code]?.takeIf { now - it.at < 30 * 60 * 1000L }?.let { return it.bars }

        val remote = runCatching { history.fetchDaily(code, 30) }.getOrDefault(emptyList())
        val bars = if (remote.isNotEmpty()) {
            cacheDao?.let { dao -> runCatching { dao.upsertDailyBars(remote.map { it.toCacheEntity(code, now) }) } }
            remote
        } else {
            cacheDao?.getDailyBars(code, 30).orEmpty().map { it.toModel() }.reversed()
        }
        historyCache[code] = Cache(now, bars)
        return bars
    }
}
