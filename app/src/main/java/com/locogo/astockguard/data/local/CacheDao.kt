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

    @Query("DELETE FROM signal_state") suspend fun clearSignalStates()
    @Query("DELETE FROM signal_event") suspend fun clearSignalEvents()
    @Query("DELETE FROM trade_record") suspend fun clearTradeRecords()
    @Query("DELETE FROM ai_analysis") suspend fun clearAiAnalysis()
}
