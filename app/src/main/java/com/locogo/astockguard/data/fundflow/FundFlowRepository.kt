package com.locogo.astockguard.data.fundflow

import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.FundFlowCacheEntity
import com.locogo.astockguard.data.local.SectorFundFlowCacheEntity

class FundFlowRepository(
    private val client: EastMoneyFundFlowClient = EastMoneyFundFlowClient(),
    private val cacheDao: CacheDao
) {
    suspend fun stock(code: String): StockFundFlow {
        val minuteRemote = runCatching { client.stockMinute(code) }.getOrDefault(emptyList())
        if (minuteRemote.isNotEmpty()) cacheDao.upsertFundFlow(minuteRemote.map { FundFlowCacheEntity.from(code, "MINUTE", it) })
        val minute = if (minuteRemote.isNotEmpty()) minuteRemote else cacheDao.getFundFlow(code, "MINUTE").map { it.toModel() }

        val dailyRemote = runCatching { client.stockDaily(code, 20) }.getOrDefault(emptyList())
        if (dailyRemote.isNotEmpty()) cacheDao.upsertFundFlow(dailyRemote.map { FundFlowCacheEntity.from(code, "DAY", it) })
        val daily = if (dailyRemote.isNotEmpty()) dailyRemote else cacheDao.getFundFlow(code, "DAY").map { it.toModel() }
        val stale = minuteRemote.isEmpty() && dailyRemote.isEmpty()
        return StockFundFlow(
            code = code,
            minute = minute,
            periods = listOf(1, 3, 5, 10).map { aggregate(daily, it) },
            source = if (stale) "ROOM_CACHE" else "EASTMONEY",
            stale = stale
        )
    }

    suspend fun sectors(type: String = "INDUSTRY"): SectorFundFlowResult {
        val remote = runCatching { client.sectors(type, 40) }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) cacheDao.upsertSectorFundFlow(remote.map { SectorFundFlowCacheEntity.from(it) })
        val rows = if (remote.isNotEmpty()) remote else cacheDao.getSectorFundFlow(type.uppercase()).map { it.toModel() }
        return SectorFundFlowResult(type.uppercase(), rows, if (remote.isEmpty()) "ROOM_CACHE" else "EASTMONEY", remote.isEmpty())
    }

    private fun aggregate(rows: List<FundFlowPoint>, days: Int): FundFlowSummary {
        val tail = rows.takeLast(days)
        return FundFlowSummary(
            days = days,
            mainNet = tail.sumOf { it.mainNet }, smallNet = tail.sumOf { it.smallNet }, mediumNet = tail.sumOf { it.mediumNet },
            largeNet = tail.sumOf { it.largeNet }, superLargeNet = tail.sumOf { it.superLargeNet }
        )
    }
}
