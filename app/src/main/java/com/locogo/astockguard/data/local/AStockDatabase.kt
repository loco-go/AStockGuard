package com.locogo.astockguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QuoteCacheEntity::class, DailyBarCacheEntity::class, MinuteBarCacheEntity::class, AiAnalysisEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AStockDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS minute_bar_cache (
                        code TEXT NOT NULL,
                        time TEXT NOT NULL,
                        price REAL NOT NULL,
                        avgPrice REAL NOT NULL,
                        high REAL NOT NULL,
                        low REAL NOT NULL,
                        volume REAL NOT NULL,
                        amount REAL NOT NULL,
                        cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(code, time)
                    )
                """.trimIndent())
            }
        }

        @Volatile private var instance: AStockDatabase? = null
        fun get(context: Context): AStockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AStockDatabase::class.java, "astock_guard.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
