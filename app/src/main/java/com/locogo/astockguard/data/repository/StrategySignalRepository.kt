package com.locogo.astockguard.data.repository

/*
 * 文件职责：实现外部数据适配与仓储边界；远端、缓存、模拟、缺失和更新时间必须显式传递。
 * 架构边界：解析失败、超时和字段缺失要显式返回失败或不可用状态，不能用零值伪造有效行情。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import com.locogo.astockguard.data.local.CacheDao
import com.locogo.astockguard.data.local.StrategySignalEntity
import com.locogo.astockguard.domain.strategy.ChartSignal

class StrategySignalRepository(private val cacheDao: CacheDao) {
    suspend fun persist(symbol: String, signal: ChartSignal): Boolean =
        cacheDao.insertStrategySignal(StrategySignalEntity.from(symbol, signal)) != -1L

    suspend fun history(symbol: String, limit: Int = 100): List<StrategySignalEntity> =
        cacheDao.getStrategySignals(symbol, limit)
}
