package com.locogo.astockguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertQuotes(items: List<QuoteCacheEntity>)
    @Query("SELECT * FROM quote_cache WHERE code IN (:codes)") suspend fun getQuotes(codes: List<String>): List<QuoteCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDailyBars(items: List<DailyBarCacheEntity>)
    @Query("SELECT * FROM daily_bar_cache WHERE code = :code ORDER BY date DESC LIMIT :limit") suspend fun getDailyBars(code: String, limit: Int): List<DailyBarCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMinuteBars(items: List<MinuteBarCacheEntity>)
    @Query("SELECT * FROM minute_bar_cache WHERE code = :code ORDER BY time ASC") suspend fun getMinuteBars(code: String): List<MinuteBarCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFundFlow(items: List<FundFlowCacheEntity>)
    @Query("SELECT * FROM fund_flow_cache WHERE code = :code AND period = :period ORDER BY time ASC") suspend fun getFundFlow(code: String, period: String): List<FundFlowCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSectorFundFlow(items: List<SectorFundFlowCacheEntity>)
    @Query("SELECT * FROM sector_fund_flow_cache WHERE type = :type ORDER BY mainNet DESC") suspend fun getSectorFundFlow(type: String): List<SectorFundFlowCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFundFlowRecords(items: List<FundFlowEntity>)
    @Query("SELECT * FROM fund_flow WHERE symbol = :symbol ORDER BY date DESC LIMIT :limit") suspend fun getFundFlowRecords(symbol: String, limit: Int = 20): List<FundFlowEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertStrategySignal(item: StrategySignalEntity): Long
    @Query("SELECT * FROM strategy_signal WHERE symbol = :symbol ORDER BY time DESC LIMIT :limit") suspend fun getStrategySignals(symbol: String, limit: Int = 100): List<StrategySignalEntity>
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

    @Insert suspend fun insertAiAnalysis(item: AiAnalysisEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAiAnalyses(items: List<AiAnalysisEntity>)
    @Query("SELECT * FROM ai_analysis ORDER BY createdAt DESC LIMIT :limit") suspend fun latestAiAnalysis(limit: Int = 20): List<AiAnalysisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertNews(items: List<NewsItemEntity>)
    @Query("SELECT * FROM news_item WHERE publishedAt = 0 OR publishedAt >= :since ORDER BY CASE WHEN publishedAt = 0 THEN fetchedAt ELSE publishedAt END DESC") suspend fun getNewsSince(since: Long): List<NewsItemEntity>
    @Query("DELETE FROM news_item WHERE fetchedAt < :before") suspend fun deleteOldNews(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLevel2(item: Level2SnapshotEntity)
    @Query("SELECT * FROM level2_snapshot WHERE code = :code LIMIT 1") suspend fun getLevel2(code: String): Level2SnapshotEntity?

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
    @Query("DELETE FROM ai_analysis") suspend fun clearAiAnalysis()
}
