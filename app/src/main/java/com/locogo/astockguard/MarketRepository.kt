package com.locogo.astockguard

import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.toCacheEntity
import com.locogo.astockguard.data.local.toModel
import com.locogo.astockguard.data.ifind.IFindHttpClient
import com.locogo.astockguard.data.ifind.IFindAuctionTick
import java.time.LocalDate
import java.time.ZoneId

class MarketRepository(
    private val settings: SettingsRepository,
    private val tencent: TencentMarketClient = TencentMarketClient(),
    private val ifind: IFindHttpClient? = null,
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
        val isHistorical: Boolean,
        /** 本次实际使用的数据源；降级时保留 FALLBACK 标识，便于质量中心和提醒记录追踪。 */
        val source: String = "ROOM_CACHE"
    )

    data class AuctionSeries(
        val date: LocalDate,
        val ticks: List<IFindAuctionTick>,
        val source: String,
        val fresh: Boolean,
        val message: String
    )

    private data class Cache(val at: Long, val bars: List<DailyBar>, val requestedLimit: Int)
    private data class QuoteFetch(val quotes: List<Quote>, val source: String, val message: String)
    private data class AuctionCache(val fetchedAt: Long, val series: AuctionSeries)
    private val historyCache = mutableMapOf<String, Cache>()
    private val auctionCache = mutableMapOf<String, AuctionCache>()
    private val latestQuotes = mutableMapOf<String, Quote>()

    suspend fun refresh(): MonitorSnapshot {
        val codes = settings.allCodes()
        val requestCodes = (codes + MARKET_INDEX_CODES).distinct()
        val fetched = fetchQuotesWithFallback(requestCodes)
        val usingCache = fetched.quotes.isEmpty()
        val cachedQuotes = cacheDao?.getQuotes(requestCodes).orEmpty().map { it.toModel() }
        val knownNames = buildMap {
            cachedQuotes.filter { it.name.isNotBlank() }.forEach { put(it.code, it.name) }
            settings.positions().filter { it.name.isNotBlank() }.forEach { put(it.code, it.name) }
        }
        val normalizedRemote = fetched.quotes.map { quote ->
            if (quote.name.isNotBlank()) quote else quote.copy(name = knownNames[quote.code] ?: quote.code)
        }
        val allQuotes = if (normalizedRemote.isNotEmpty()) {
            cacheDao?.let { dao -> runCatching { dao.upsertQuotes(normalizedRemote.map { it.toCacheEntity() }) } }
            normalizedRemote
        } else {
            cachedQuotes
        }
        // 保存最新快照供日K加载使用。历史接口盘中可能尚未生成当天K线，不能把昨日K线误当今天展示。
        latestQuotes.putAll(allQuotes.associateBy { it.code })
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
                source = if (usingCache) "ROOM_CACHE" else fetched.source,
                isStale = usingCache,
                message = if (usingCache) "iFinD与腾讯实时行情均不可用，已降级到本地缓存。${fetched.message}" else fetched.message
            )
        )
    }

    /**
     * 用户选择iFinD时优先请求正版源；任何鉴权、权限、额度或网络异常都会在本轮主动降级腾讯。
     * 降级不会把用户设置永久改掉，下一轮仍可在Token恢复后自动回到iFinD。
     */
    private suspend fun fetchQuotesWithFallback(codes: List<String>): QuoteFetch {
        if (settings.marketDataSource != SettingsRepository.MARKET_SOURCE_IFIND) {
            val free = runCatching { tencent.fetchQuotes(codes) }.getOrDefault(emptyList())
            return QuoteFetch(free, "TENCENT", if (free.isEmpty()) "腾讯免费行情请求失败" else "腾讯免费实时行情正常")
        }

        val ifindResult = if (settings.ifindRefreshToken.isBlank()) {
            Result.failure(IllegalStateException("未填写iFinD refresh token"))
        } else {
            runCatching { ifind?.fetchQuotes(codes).orEmpty().also { require(it.isNotEmpty()) { "iFinD实时行情无数据" } } }
        }
        ifindResult.getOrNull()?.let { return QuoteFetch(it, "IFIND", "iFinD正版实时行情正常") }

        val reason = ifindResult.exceptionOrNull()?.message?.take(160) ?: "未知错误"
        val free = runCatching { tencent.fetchQuotes(codes) }.getOrDefault(emptyList())
        return QuoteFetch(
            quotes = free,
            source = if (free.isEmpty()) "IFIND_FALLBACK_FAILED" else "TENCENT_FALLBACK",
            message = if (free.isEmpty()) "iFinD失败且腾讯免费行情也不可用：$reason"
            else "iFinD不可用，已自动切换腾讯免费实时行情：$reason"
        )
    }

    suspend fun loadDailyBars(code: String, limit: Int = 30): List<DailyBar> =
        mergeRealtimeDailyBar(getHistory(code, limit), latestQuotes[code], marketDate()).takeLast(limit)

    /**
     * 集合竞价只使用 iFinD 正式快照数据；免费源没有等价字段时明确返回不可用，避免伪造竞价判断。
     * 竞价期间短缓存降低接口流量，9:30 后当日结果固定并复用内存缓存。
     */
    suspend fun loadAuctionSeries(code: String, date: LocalDate = marketDate()): AuctionSeries {
        if (settings.marketDataSource != SettingsRepository.MARKET_SOURCE_IFIND) {
            return AuctionSeries(date, emptyList(), "NONE", false, "腾讯免费源不提供可验证的集合竞价快照")
        }
        if (settings.ifindRefreshToken.isBlank()) {
            return AuctionSeries(date, emptyList(), "NONE", false, "未填写iFinD refresh token")
        }
        val key = "$date:$code"
        val now = System.currentTimeMillis()
        auctionCache[key]?.takeIf { cached ->
            val auctionFinalized = LocalDate.now(MARKET_ZONE) != date ||
                java.time.LocalTime.now(MARKET_ZONE).isAfter(java.time.LocalTime.of(9, 30))
            (auctionFinalized && cached.series.fresh) || now - cached.fetchedAt < AUCTION_CACHE_MS
        }?.let { return it.series }

        val result = runCatching { ifind?.fetchAuctionTicks(code, date).orEmpty() }
        val ticks = result.getOrNull().orEmpty()
        val series = if (ticks.isNotEmpty()) {
            AuctionSeries(date, ticks, "IFIND_SNAPSHOT", true, "iFinD集合竞价快照正常")
        } else {
            AuctionSeries(
                date, emptyList(), "NONE", false,
                result.exceptionOrNull()?.message?.take(160) ?: "iFinD集合竞价快照无数据或当前无权限"
            )
        }
        auctionCache[key] = AuctionCache(now, series)
        return series
    }

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
                isHistorical = true,
                source = "ROOM_CACHE"
            )
        }
        val intervalMinutes = if (isHistorical) 5 else 1
        val preferIFind = settings.marketDataSource == SettingsRepository.MARKET_SOURCE_IFIND
        val ifindResult = if (preferIFind && settings.ifindRefreshToken.isNotBlank()) {
            runCatching { ifind?.fetchMinuteBars(code, date, intervalMinutes).orEmpty() }
        } else null
        val ifindBars = ifindResult?.getOrNull().orEmpty()
        val freeBars = if (ifindBars.isEmpty()) {
            runCatching {
                if (isHistorical) historicalMinute.fetch5Minute(code, date) else minute.fetch(code)
            }.getOrDefault(emptyList())
        } else emptyList()
        val remote = ifindBars.ifEmpty { freeBars }
        val remoteSource = when {
            ifindBars.isNotEmpty() -> "IFIND"
            isHistorical && preferIFind -> "EASTMONEY_FALLBACK"
            isHistorical -> "EASTMONEY"
            preferIFind -> "TENCENT_FALLBACK"
            else -> "TENCENT"
        }
        if (remote.isNotEmpty()) {
            cacheDao?.let { dao ->
                runCatching {
                    dao.upsertMinuteBars(remote.map { it.toCacheEntity(code, date.toString(), intervalMinutes) })
                }
            }
        }
        if (remote.isNotEmpty()) {
            return MinuteSeries(
                date, remote, intervalMinutes,
                fromCache = false,
                isHistorical = isHistorical,
                source = remoteSource
            )
        }
        // 接口失败时允许展示缓存，但调用方可通过 fromCache 禁止发出实时买卖提醒。
        return MinuteSeries(
            date = date,
            bars = cached.map { it.toModel() },
            intervalMinutes = cached.firstOrNull()?.intervalMinutes ?: intervalMinutes,
            fromCache = true,
            isHistorical = isHistorical,
            source = "ROOM_CACHE"
        )
    }

    /** 兼容旧调用入口，并统一复用带有 iFinD 降级与缓存安全判断的分时加载流程。 */
    suspend fun loadMinuteBars(code: String): List<MinuteBar> = loadMinuteSeries(code).bars

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
        // 切换首选源后必须重新取数，不能继续命中上一个Provider的内存缓存。
        val historyKey = "${settings.marketDataSource}:$code"
        historyCache[historyKey]
            ?.takeIf { now - it.at < 30 * 60 * 1000L && it.requestedLimit >= limit }
            ?.let { return it.bars }
        val preferIFind = settings.marketDataSource == SettingsRepository.MARKET_SOURCE_IFIND
        val ifindBars = if (preferIFind && settings.ifindRefreshToken.isNotBlank()) {
            val end = marketDate()
            // 自然日范围留出停牌、周末和节假日余量，最终仍按调用方要求截取条数。
            val start = end.minusDays((limit * 2L + 30L).coerceAtLeast(60L))
            runCatching { ifind?.fetchDailyBars(code, start, end).orEmpty() }.getOrDefault(emptyList())
        } else emptyList()
        val remote = ifindBars.ifEmpty {
            // iFinD鉴权、权限、额度或网络异常时自动回到腾讯，不改变用户的长期选择。
            runCatching { history.fetchDaily(code, limit) }.getOrDefault(emptyList())
        }.takeLast(limit)
        val bars = if (remote.isNotEmpty()) {
            cacheDao?.let { dao -> runCatching { dao.upsertDailyBars(remote.map { it.toCacheEntity(code, now) }) } }
            remote
        } else {
            cacheDao?.getDailyBars(code, limit).orEmpty().map { it.toModel() }.reversed()
        }
        historyCache[historyKey] = Cache(now, bars, limit)
        return bars
    }

    companion object {
        val MARKET_INDEX_CODES = listOf("000001.SH", "399001.SZ", "399006.SZ")
        private val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private const val AUCTION_CACHE_MS = 15_000L
        fun marketDate(): LocalDate = LocalDate.now(MARKET_ZONE)

        /**
         * 用实时快照补齐当天未收盘日K。历史接口可能只返回到昨日，或盘中 close 尚未更新；
         * 这里始终以今日 open/latest/high/low 为准，确保红绿颜色与用户看到的实时价格一致。
         */
        fun mergeRealtimeDailyBar(
            history: List<DailyBar>,
            quote: Quote?,
            date: LocalDate = marketDate()
        ): List<DailyBar> {
            val open = quote?.open?.takeIf { it.isFinite() && it > 0.0 } ?: return history
            val latest = quote.latest?.takeIf { it.isFinite() && it > 0.0 } ?: return history
            val high = maxOf(open, latest, quote.high?.takeIf { it.isFinite() && it > 0.0 } ?: latest)
            val low = minOf(open, latest, quote.low?.takeIf { it.isFinite() && it > 0.0 } ?: latest)
            val today = DailyBar(
                date = date.toString(),
                open = open,
                close = latest,
                high = high,
                low = low,
                volume = quote.volume?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            )
            return (history.filterNot { it.date == today.date } + today).sortedBy { it.date }
        }

        private fun normalizeStockName(name: String): String = name.trim().replace(" ", "").uppercase()
    }
}
