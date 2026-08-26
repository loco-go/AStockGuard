package com.locogo.astockguard.data.repository

import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.StrategySignalEntity
import com.locogo.astockguard.domain.strategy.ChartSignal

class StrategySignalRepository(private val cacheDao: CacheDao) {
    suspend fun persist(symbol: String, signal: ChartSignal): Boolean =
        cacheDao.insertStrategySignal(StrategySignalEntity.from(symbol, signal)) != -1L

    suspend fun history(symbol: String, limit: Int = 100): List<StrategySignalEntity> =
        cacheDao.getStrategySignals(symbol, limit)
}
