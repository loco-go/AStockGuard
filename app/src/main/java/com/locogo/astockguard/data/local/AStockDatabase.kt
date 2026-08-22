package com.locogo.astockguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QuoteCacheEntity::class, DailyBarCacheEntity::class, MinuteBarCacheEntity::class,
        FundFlowCacheEntity::class, SectorFundFlowCacheEntity::class, SignalStateEntity::class, AiAnalysisEntity::class],
    version = 4,
    exportSchema = true
)
abstract class AStockDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS minute_bar_cache (
                        code TEXT NOT NULL, time TEXT NOT NULL, price REAL NOT NULL, avgPrice REAL NOT NULL,
                        high REAL NOT NULL, low REAL NOT NULL, volume REAL NOT NULL, amount REAL NOT NULL,
                        cachedAt INTEGER NOT NULL, PRIMARY KEY(code, time)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS fund_flow_cache (
                        code TEXT NOT NULL, period TEXT NOT NULL, time TEXT NOT NULL,
                        mainNet REAL NOT NULL, smallNet REAL NOT NULL, mediumNet REAL NOT NULL,
                        largeNet REAL NOT NULL, superLargeNet REAL NOT NULL, cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(code, period, time)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sector_fund_flow_cache (
                        type TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL, changePct REAL NOT NULL,
                        mainNet REAL NOT NULL, mainPct REAL NOT NULL, superLargeNet REAL NOT NULL,
                        largeNet REAL NOT NULL, mediumNet REAL NOT NULL, smallNet REAL NOT NULL,
                        leadStockName TEXT NOT NULL, leadStockCode TEXT NOT NULL, cachedAt INTEGER NOT NULL,
                        PRIMARY KEY(type, code)
                    )
                """.trimIndent())
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS signal_state (
                        code TEXT NOT NULL PRIMARY KEY, stage TEXT NOT NULL, lastAction TEXT NOT NULL,
                        consecutiveCount INTEGER NOT NULL, lastTransitionAt INTEGER NOT NULL,
                        lastNotifiedAt INTEGER NOT NULL, reason TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        @Volatile private var instance: AStockDatabase? = null
        fun get(context: Context): AStockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AStockDatabase::class.java, "astock_guard.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
