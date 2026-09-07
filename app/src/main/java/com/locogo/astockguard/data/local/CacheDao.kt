package com.locogo.astockguard.data.local

/*
 * 文件职责：定义 Room 查询与写入边界；Flow 用于持续观察，挂起函数用于一次性事务，调用方不得绕过 DAO 直接操作数据库。
 * 架构边界：修改实体或字段前先设计 Room 版本迁移、导出 schema，并验证旧库升级。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertQuotes(items: List<QuoteCacheEntity>)
    @Query("SELECT * FROM quote_cache WHERE code IN (:codes)") suspend fun getQuotes(codes: List<String>): List<QuoteCacheEntity>
    @Query("SELECT * FROM quote_cache WHERE name IN (:names)") suspend fun getQuotesByNames(names: List<String>): List<QuoteCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDailyBars(items: List<DailyBarCacheEntity>)
    @Query("SELECT * FROM daily_bar_cache WHERE code = :code ORDER BY date DESC LIMIT :limit") suspend fun getDailyBars(code: String, limit: Int): List<DailyBarCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMinuteBars(items: List<MinuteBarCacheEntity>)
    @Query("SELECT * FROM minute_bar_cache WHERE code = :code AND date = :date ORDER BY time ASC")
    suspend fun getMinuteBars(code: String, date: String): List<MinuteBarCacheEntity>
    @Query("SELECT DISTINCT date FROM minute_bar_cache WHERE code = :code ORDER BY date DESC")
    suspend fun getMinuteBarDates(code: String): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFundFlow(items: List<FundFlowCacheEntity>)
    @Query("SELECT * FROM fund_flow_cache WHERE code = :code AND period = :period ORDER BY time ASC") suspend fun getFundFlow(code: String, period: String): List<FundFlowCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSectorFundFlow(items: List<SectorFundFlowCacheEntity>)
    @Query("SELECT * FROM sector_fund_flow_cache WHERE type = :type ORDER BY mainNet DESC") suspend fun getSectorFundFlow(type: String): List<SectorFundFlowCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFundFlowRecords(items: List<FundFlowEntity>)
    @Query("SELECT * FROM fund_flow WHERE symbol = :symbol ORDER BY date DESC LIMIT :limit") suspend fun getFundFlowRecords(symbol: String, limit: Int = 20): List<FundFlowEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertStrategySignal(item: StrategySignalEntity): Long
    @Query("SELECT * FROM strategy_signal WHERE symbol = :symbol ORDER BY time DESC LIMIT :limit") suspend fun getStrategySignals(symbol: String, limit: Int = 100): List<StrategySignalEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAlertRecord(item: AlertRecordEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAlertRecords(items: List<AlertRecordEntity>)
    @Query("SELECT * FROM alert_record WHERE code = :code AND status = 'PENDING' ORDER BY signalAt ASC")
    suspend fun getPendingAlertRecords(code: String): List<AlertRecordEntity>
    @Query("SELECT * FROM alert_record ORDER BY signalAt DESC LIMIT :limit")
    suspend fun getAlertRecords(limit: Int = 100): List<AlertRecordEntity>
    @Query("UPDATE alert_record SET status = :status, evaluatedAt = :evaluatedAt, exitPrice = :exitPrice, netEdgePct = :netEdgePct, maxFavorablePct = :maxFavorablePct, maxAdversePct = :maxAdversePct WHERE id = :id AND status = 'PENDING'")
    suspend fun evaluateAlertRecord(id: Long, status: String, evaluatedAt: Long, exitPrice: Double, netEdgePct: Double, maxFavorablePct: Double, maxAdversePct: Double): Int
    /** 读取某证券最后一次雷达提醒状态，供跨进程十分钟冷却和状态迁移判断。 */
    @Query("SELECT * FROM volume_radar_state WHERE code = :code LIMIT 1")
    suspend fun getVolumeRadarState(code: String): VolumeRadarStateEntity?
    /** 原子覆盖某证券雷达状态；只有真正发送通知后才能调用。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVolumeRadarState(item: VolumeRadarStateEntity)
    /** 写入一个观察周期的评价，重复执行使用REPLACE保证任务幂等。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVolumeSignalOutcome(item: VolumeSignalOutcomeEntity)
    /** 查询提醒已经完成的观察周期，避免轮询重复计算已经稳定的结果。 */
    @Query("SELECT * FROM volume_signal_outcome WHERE alertId = :alertId ORDER BY horizonMinutes")
    suspend fun getVolumeSignalOutcomes(alertId: Long): List<VolumeSignalOutcomeEntity>
    /** 导出备份时读取全部雷达状态，按代码排序保证JSON输出稳定。 */
    @Query("SELECT * FROM volume_radar_state ORDER BY code")
    suspend fun getAllVolumeRadarStates(): List<VolumeRadarStateEntity>
    /** 导出备份时读取全部多周期评价，按提醒和周期排序保证审计顺序稳定。 */
    @Query("SELECT * FROM volume_signal_outcome ORDER BY alertId, horizonMinutes")
    suspend fun getAllVolumeSignalOutcomes(): List<VolumeSignalOutcomeEntity>
    /** 查询指定证券仍需要多周期评价的雷达提醒。 */
    @Query("SELECT * FROM alert_record WHERE code = :code AND alertType = 'VOLUME_RADAR' ORDER BY signalAt DESC LIMIT :limit")
    suspend fun getVolumeRadarAlerts(code: String, limit: Int = 50): List<AlertRecordEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSignalState(item: SignalStateEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSignalStates(items: List<SignalStateEntity>)
    @Query("SELECT * FROM signal_state WHERE code = :code LIMIT 1") suspend fun getSignalState(code: String): SignalStateEntity?
    @Query("SELECT * FROM signal_state ORDER BY lastTransitionAt DESC") suspend fun getSignalStates(): List<SignalStateEntity>

    @Insert suspend fun insertSignalEvent(item: SignalEventEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSignalEvents(items: List<SignalEventEntity>)
    @Query("SELECT * FROM signal_event ORDER BY eventAt DESC LIMIT :limit") suspend fun getSignalEvents(limit: Int = 100): List<SignalEventEntity>
    @Insert suspend fun insertTradeRecord(item: TradeRecordEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTradeRecords(items: List<TradeRecordEntity>)
    @Query("SELECT * FROM trade_record ORDER BY tradeAt ASC") suspend fun getTradeRecords(): List<TradeRecordEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportedAccountMetrics(items: List<ImportedAccountMetricEntity>)
    @Query("SELECT * FROM imported_account_metric")
    fun observeImportedAccountMetrics(): kotlinx.coroutines.flow.Flow<List<ImportedAccountMetricEntity>>
    @Query("DELETE FROM imported_account_metric")
    suspend fun clearImportedAccountMetrics()

    @Insert suspend fun insertAccountLedger(item: AccountLedgerEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAccountLedgers(items: List<AccountLedgerEntity>)
    @Query("SELECT * FROM account_ledger ORDER BY occurredAt DESC, id DESC") suspend fun getAccountLedgers(): List<AccountLedgerEntity>

    @Insert suspend fun insertAiAnalysis(item: AiAnalysisEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAiAnalyses(items: List<AiAnalysisEntity>)
    @Query("SELECT * FROM ai_analysis ORDER BY createdAt DESC LIMIT :limit") suspend fun latestAiAnalysis(limit: Int = 20): List<AiAnalysisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertNews(items: List<NewsItemEntity>)
    @Query("SELECT * FROM news_item WHERE publishedAt = 0 OR publishedAt >= :since ORDER BY CASE WHEN publishedAt = 0 THEN fetchedAt ELSE publishedAt END DESC") suspend fun getNewsSince(since: Long): List<NewsItemEntity>
    @Query("DELETE FROM news_item WHERE fetchedAt < :before") suspend fun deleteOldNews(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLevel2(item: Level2SnapshotEntity)
    @Query("SELECT * FROM level2_snapshot WHERE code = :code LIMIT 1") suspend fun getLevel2(code: String): Level2SnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLevel2History(item: Level2SnapshotHistoryEntity): Long
    @Query("SELECT * FROM level2_snapshot_history WHERE code = :code AND capturedAt >= :since ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun getRecentLevel2History(code: String, since: Long, limit: Int): List<Level2SnapshotHistoryEntity>
    @Query("DELETE FROM level2_snapshot_history WHERE capturedAt < :before")
    suspend fun deleteOldLevel2History(before: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPaperAccount(item: PaperAccountEntity)
    @Query("SELECT * FROM paper_account WHERE id = 1 LIMIT 1") suspend fun getPaperAccount(): PaperAccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPaperPosition(item: PaperPositionEntity)
    @Query("SELECT * FROM paper_position WHERE code = :code LIMIT 1") suspend fun getPaperPosition(code: String): PaperPositionEntity?
    @Query("SELECT * FROM paper_position ORDER BY code") suspend fun getPaperPositions(): List<PaperPositionEntity>
    @Query("DELETE FROM paper_position WHERE code = :code") suspend fun deletePaperPosition(code: String)
    @Insert suspend fun insertPaperOrder(item: PaperOrderEntity): Long
    @Query("SELECT * FROM paper_order ORDER BY createdAt DESC LIMIT :limit") suspend fun getPaperOrders(limit: Int = 50): List<PaperOrderEntity>
    @Insert suspend fun insertPaperEquity(item: PaperEquityEntity): Long
    @Query("SELECT * FROM paper_equity ORDER BY recordedAt ASC") suspend fun getPaperEquity(): List<PaperEquityEntity>
    @Query("SELECT * FROM paper_equity ORDER BY recordedAt DESC LIMIT 1") suspend fun getLatestPaperEquity(): PaperEquityEntity?
    @Query("DELETE FROM paper_position") suspend fun clearPaperPositions()
    @Query("DELETE FROM paper_order") suspend fun clearPaperOrders()
    @Query("DELETE FROM paper_equity") suspend fun clearPaperEquity()

    @Query("DELETE FROM signal_state") suspend fun clearSignalStates()
    @Query("DELETE FROM signal_event") suspend fun clearSignalEvents()
    @Query("DELETE FROM trade_record") suspend fun clearTradeRecords()
    @Query("DELETE FROM account_ledger") suspend fun clearAccountLedgers()
    @Query("DELETE FROM alert_record") suspend fun clearAlertRecords()
    /** 清理雷达冷却和多周期结果；仅由明确的恢复/重置流程调用。 */
    @Query("DELETE FROM volume_radar_state") suspend fun clearVolumeRadarStates()
    @Query("DELETE FROM volume_signal_outcome") suspend fun clearVolumeSignalOutcomes()
    @Query("DELETE FROM ai_analysis") suspend fun clearAiAnalysis()
}
