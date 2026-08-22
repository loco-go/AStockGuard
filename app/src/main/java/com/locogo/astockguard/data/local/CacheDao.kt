package com.locogo.astockguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuotes(items: List<QuoteCacheEntity>)

    @Query("SELECT * FROM quote_cache WHERE code IN (:codes)")
    suspend fun getQuotes(codes: List<String>): List<QuoteCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyBars(items: List<DailyBarCacheEntity>)

    @Query("SELECT * FROM daily_bar_cache WHERE code = :code ORDER BY date DESC LIMIT :limit")
    suspend fun getDailyBars(code: String, limit: Int): List<DailyBarCacheEntity>

    @Insert
    suspend fun insertAiAnalysis(item: AiAnalysisEntity): Long

    @Query("SELECT * FROM ai_analysis ORDER BY createdAt DESC LIMIT :limit")
    suspend fun latestAiAnalysis(limit: Int = 20): List<AiAnalysisEntity>
}
