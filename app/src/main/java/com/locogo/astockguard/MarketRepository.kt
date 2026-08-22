package com.locogo.astockguard

class MarketRepository(
    private val settings: SettingsRepository,
    private val tencent: TencentMarketClient = TencentMarketClient(),
    private val history: TencentHistoryClient = TencentHistoryClient()
) {
    private data class Cache(val at: Long, val bars: List<DailyBar>)
    private val historyCache = mutableMapOf<String, Cache>()

    suspend fun refresh(): MonitorSnapshot {
        val codes = settings.allCodes()
        val rawQuotes = tencent.fetchQuotes(codes)
        val preliminary = RiskEngine.assess(rawQuotes, settings.positions(), settings.positionRatio)
        val enriched = rawQuotes.map { q ->
            val bars = getHistory(q.code)
            val ma5 = bars.takeLast(5).takeIf { it.size == 5 }?.map { it.close }?.average()
            val ma10 = bars.takeLast(10).takeIf { it.size == 10 }?.map { it.close }?.average()
            val r2 = R2Engine.evaluate(bars, preliminary.marketPhase)
            q.copy(ma5 = ma5, ma10 = ma10, r2Score = r2.score, r2Grade = r2.grade, r2Reason = r2.reason)
        }
        val assessment = RiskEngine.assess(enriched, settings.positions(), settings.positionRatio)
        return MonitorSnapshot(System.currentTimeMillis(), enriched, assessment, settings.positionRatio)
    }

    private suspend fun getHistory(code: String): List<DailyBar> {
        val now = System.currentTimeMillis()
        historyCache[code]?.takeIf { now - it.at < 30 * 60 * 1000L }?.let { return it.bars }
        val bars = runCatching { history.fetchDaily(code, 30) }.getOrDefault(emptyList())
        historyCache[code] = Cache(now, bars)
        return bars
    }
}
