package com.locogo.astockguard

import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.toCacheEntity
import com.locogo.astockguard.data.local.toModel
import java.time.LocalDate
import java.time.ZoneId

class MarketRepository(
    private val settings: SettingsRepository,
    private val tencent: TencentMarketClient = TencentMarketClient(),
    private val history: TencentHistoryClient = TencentHistoryClient(),
    private val minute: TencentMinuteClient = TencentMinuteClient(),
    private val historicalMinute: EastMoneyMinuteHistoryClient = EastMoneyMinuteHistoryClient(),
    private val cacheDao: CacheDao? = null
) {
    data class MinuteSeries(
        val date: LocalDate,
        val bars: List<MinuteBar>,
        val intervalMinutes: Int,
        val fromCache: Boolean,
        val isHistorical: Boolean
    )

    private data class Cache(val at: Long, val bars: List<DailyBar>, val requestedLimit: Int)
    private val historyCache = mutableMapOf<String, Cache>()

    suspend fun refresh(): MonitorSnapshot {
        val codes = settings.allCodes()
        val requestCodes = (codes + MARKET_INDEX_CODES).distinct()
        val remote = runCatching { tencent.fetchQuotes(requestCodes) }.getOrNull().orEmpty()
        val usingCache = remote.isEmpty()
        val allQuotes = if (remote.isNotEmpty()) {
            cacheDao?.let { dao -> runCatching { dao.upsertQuotes(remote.map { it.toCacheEntity() }) } }
            remote
        } else {
            cacheDao?.getQuotes(requestCodes).orEmpty().map { it.toModel() }
        }
        val rawQuotes = allQuotes.filter { it.code in codes }
        val marketIndices = allQuotes.filter { it.code in MARKET_INDEX_CODES }
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
            marketIndices = marketIndices,
            assessment = assessment,
            positionRatio = settings.positionRatio,
            dataHealth = DataHealth(
                source = if (usingCache) "ROOM_CACHE" else "TENCENT",
                isStale = usingCache,
                message = if (usingCache) "腾讯请求失败，已降级到本地缓存" else "实时行情正常"
            )
        )
    }

    suspend fun loadDailyBars(code: String, limit: Int = 30): List<DailyBar> = getHistory(code, limit).takeLast(limit)

    /**
     * 为同花顺未暴露代码的持仓名称补全代码：Room 优先，远端候选必须再用行情名称精确核验。
     * 搜索失败只返回空映射，不会生成猜测代码或覆盖现有持仓。
     */
    suspend fun resolveCodesByNames(names: List<String>): Map<String, String> {
        val requested = names.map(String::trim).filter(String::isNotBlank).distinct()
        if (requested.isEmpty()) return emptyMap()
        val resolved = linkedMapOf<String, String>()
        cacheDao?.getQuotesByNames(requested).orEmpty().forEach { cached -> resolved[cached.name] = cached.code }
        requested.filterNot(resolved::containsKey).take(10).forEach { name ->
            val candidates = runCatching { tencent.searchCodes(name) }.getOrDefault(emptyList()).take(8)
            val verified = runCatching { tencent.fetchQuotes(candidates) }.getOrDefault(emptyList())
                .firstOrNull { normalizeStockName(it.name) == normalizeStockName(name) }
            if (verified != null) {
                resolved[name] = verified.code
                cacheDao?.let { dao -> runCatching { dao.upsertQuotes(listOf(verified.toCacheEntity())) } }
            }
        }
        return resolved
    }

    suspend fun loadMinuteSeries(code: String, date: LocalDate = marketDate()): MinuteSeries {
        val cached = cacheDao?.getMinuteBars(code, date.toString()).orEmpty()
        val isHistorical = date != marketDate()
        // 历史数据不会再变化，优先读取 Room；今日数据必须先请求接口，避免缓存伪装成实时行情。
        if (isHistorical && cached.isNotEmpty()) {
            return MinuteSeries(
                date = date,
                bars = cached.map { it.toModel() },
                intervalMinutes = cached.first().intervalMinutes,
                fromCache = true,
                isHistorical = true
            )
        }
        val intervalMinutes = if (isHistorical) 5 else 1
        val remote = if (isHistorical) historicalMinute.fetch5Minute(code, date) else minute.fetch(code)
        if (remote.isNotEmpty()) {
            cacheDao?.let { dao ->
                runCatching {
                    dao.upsertMinuteBars(remote.map { it.toCacheEntity(code, date.toString(), intervalMinutes) })
                }
            }
        }
        if (remote.isNotEmpty()) {
            return MinuteSeries(date, remote, intervalMinutes, fromCache = false, isHistorical = isHistorical)
        }
        // 接口失败时允许展示缓存，但调用方可通过 fromCache 禁止发出实时买卖提醒。
        return MinuteSeries(
            date = date,
            bars = cached.map { it.toModel() },
            intervalMinutes = cached.firstOrNull()?.intervalMinutes ?: intervalMinutes,
            fromCache = true,
            isHistorical = isHistorical
        )
    }

    suspend fun loadMinuteBars(code: String): List<MinuteBar> {
        val date = marketDate()
        val remote = runCatching { minute.fetch(code) }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            cacheDao?.let { dao ->
                runCatching { dao.upsertMinuteBars(remote.map { it.toCacheEntity(code, date.toString(), 1) }) }
            }
            return remote
        }
        return cacheDao?.getMinuteBars(code, date.toString()).orEmpty().map { it.toModel() }
    }

    suspend fun buildPortfolioCurve(positions: List<Position>, limit: Int = 30): List<Pair<String, Double>> {
        if (positions.isEmpty()) return emptyList()
        val series = positions.associate { it.code to loadDailyBars(it.code, limit) }
        val dates = series.values.flatMap { bars -> bars.map { it.date } }.distinct().sorted()
        val values = dates.mapNotNull { date ->
            var total = 0.0
            var covered = 0
            positions.forEach { p ->
                val close = series[p.code]?.lastOrNull { it.date <= date }?.close
                if (close != null) { total += close * p.shares; covered++ }
            }
            if (covered == 0) null else date to total
        }
        val base = values.firstOrNull()?.second?.takeIf { it > 0 } ?: return emptyList()
        return values.map { it.first to it.second / base * 100.0 }
    }

    private suspend fun getHistory(code: String, limit: Int = 30): List<DailyBar> {
        val now = System.currentTimeMillis()
        historyCache[code]
            ?.takeIf { now - it.at < 30 * 60 * 1000L && it.requestedLimit >= limit }
            ?.let { return it.bars }
        val remote = runCatching { history.fetchDaily(code, limit) }.getOrDefault(emptyList())
        val bars = if (remote.isNotEmpty()) {
            cacheDao?.let { dao -> runCatching { dao.upsertDailyBars(remote.map { it.toCacheEntity(code, now) }) } }
            remote
        } else {
            cacheDao?.getDailyBars(code, limit).orEmpty().map { it.toModel() }.reversed()
        }
        historyCache[code] = Cache(now, bars, limit)
        return bars
    }

    companion object {
        val MARKET_INDEX_CODES = listOf("000001.SH", "399001.SZ", "399006.SZ")
        private val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        fun marketDate(): LocalDate = LocalDate.now(MARKET_ZONE)
        private fun normalizeStockName(name: String): String = name.trim().replace(" ", "").uppercase()
    }
}
