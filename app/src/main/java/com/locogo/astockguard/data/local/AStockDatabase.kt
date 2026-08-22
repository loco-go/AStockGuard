package com.locogo.astockguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QuoteCacheEntity::class, DailyBarCacheEntity::class, AiAnalysisEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AStockDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        @Volatile private var instance: AStockDatabase? = null

        fun get(context: Context): AStockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AStockDatabase::class.java,
                "astock_guard.db"
            ).build().also { instance = it }
        }
    }
}
