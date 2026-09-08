package com.locogo.astockguard.data.repository

import com.locogo.astockguard.MarketAssessment
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.domain.strategy.ChartSignal
import com.locogo.astockguard.domain.volume.IntradayRadarSnapshot
import com.locogo.astockguard.domain.volume.IntradayVolumeCoordinator
import java.time.LocalDate

/** 详情页的数据边界；来源选择仍由既有 Repository 负责。 */
interface StockDetailDataSource {
    suspend fun daily(code: String, limit: Int): MarketRepository.DailySeries
    suspend fun minute(code: String, date: LocalDate): MarketRepository.MinuteSeries
    suspend fun radar(code: String, assessment: MarketAssessment?, exposureStatus: String?, stale: Boolean): IntradayRadarSnapshot
    suspend fun persist(code: String, signal: ChartSignal)
}

class RepositoryStockDetailDataSource(
    private val market: MarketRepository,
    private val signals: StrategySignalRepository,
    private val settings: SettingsRepository,
    private val coordinator: IntradayVolumeCoordinator
) : StockDetailDataSource {
    override suspend fun daily(code: String, limit: Int) = market.loadDailySeries(code, limit)
    override suspend fun minute(code: String, date: LocalDate) = market.loadMinuteSeries(code, date)
    override suspend fun radar(code: String, assessment: MarketAssessment?, exposureStatus: String?, stale: Boolean) =
        coordinator.load(code, settings.positions().firstOrNull { it.code == code }, assessment, exposureStatus, stale)
    override suspend fun persist(code: String, signal: ChartSignal) { signals.persist(code, signal) }
}
