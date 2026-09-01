package com.locogo.astockguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QuoteCacheEntity::class, DailyBarCacheEntity::class, MinuteBarCacheEntity::class,
        FundFlowCacheEntity::class, SectorFundFlowCacheEntity::class, SignalStateEntity::class,
        SignalEventEntity::class, TradeRecordEntity::class, AiAnalysisEntity::class, NewsItemEntity::class,
        Level2SnapshotEntity::class, PaperAccountEntity::class, PaperPositionEntity::class,
        PaperOrderEntity::class, PaperEquityEntity::class, StrategySignalEntity::class,
        FundFlowEntity::class, AccountLedgerEntity::class, AlertRecordEntity::class],
    version = 13,
    exportSchema = true
)
abstract class AStockDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""
            CREATE TABLE IF NOT EXISTS minute_bar_cache (
                code TEXT NOT NULL, time TEXT NOT NULL, price REAL NOT NULL, avgPrice REAL NOT NULL,
                high REAL NOT NULL, low REAL NOT NULL, volume REAL NOT NULL, amount REAL NOT NULL,
                cachedAt INTEGER NOT NULL, PRIMARY KEY(code, time))
        """.trimIndent()) } }
        val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS fund_flow_cache (
                code TEXT NOT NULL, period TEXT NOT NULL, time TEXT NOT NULL, mainNet REAL NOT NULL,
                smallNet REAL NOT NULL, mediumNet REAL NOT NULL, largeNet REAL NOT NULL,
                superLargeNet REAL NOT NULL, cachedAt INTEGER NOT NULL, PRIMARY KEY(code, period, time))""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS sector_fund_flow_cache (
                type TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL, changePct REAL NOT NULL,
                mainNet REAL NOT NULL, mainPct REAL NOT NULL, superLargeNet REAL NOT NULL,
                largeNet REAL NOT NULL, mediumNet REAL NOT NULL, smallNet REAL NOT NULL,
                leadStockName TEXT NOT NULL, leadStockCode TEXT NOT NULL, cachedAt INTEGER NOT NULL,
                PRIMARY KEY(type, code))""".trimIndent())
        } }
        val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""
            CREATE TABLE IF NOT EXISTS signal_state (
                code TEXT NOT NULL PRIMARY KEY, stage TEXT NOT NULL, lastAction TEXT NOT NULL,
                consecutiveCount INTEGER NOT NULL, lastTransitionAt INTEGER NOT NULL,
                lastNotifiedAt INTEGER NOT NULL, reason TEXT NOT NULL)
        """.trimIndent()) } }
        val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS signal_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, code TEXT NOT NULL, eventAt INTEGER NOT NULL,
                fromStage TEXT NOT NULL, toStage TEXT NOT NULL, action TEXT NOT NULL, price REAL NOT NULL,
                reason TEXT NOT NULL)""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS trade_record (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tradeAt INTEGER NOT NULL, code TEXT NOT NULL,
                side TEXT NOT NULL, quantity INTEGER NOT NULL, price REAL NOT NULL, fee REAL NOT NULL,
                source TEXT NOT NULL, note TEXT NOT NULL)""".trimIndent())
        } }
        val MIGRATION_5_6 = object : Migration(5, 6) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS news_item (
                id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL,
                publishedAt INTEGER NOT NULL, fetchedAt INTEGER NOT NULL)""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_news_item_publishedAt ON news_item(publishedAt)")
        } }
        val MIGRATION_6_7 = object : Migration(6, 7) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS level2_snapshot (
                code TEXT NOT NULL PRIMARY KEY, payloadJson TEXT NOT NULL, source TEXT NOT NULL,
                simulated INTEGER NOT NULL, cachedAt INTEGER NOT NULL)""".trimIndent())
        } }
        val MIGRATION_7_8 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS paper_account (
                id INTEGER NOT NULL PRIMARY KEY, initialCash REAL NOT NULL, cash REAL NOT NULL, updatedAt INTEGER NOT NULL)""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS paper_position (
                code TEXT NOT NULL PRIMARY KEY, quantity INTEGER NOT NULL, avgCost REAL NOT NULL, updatedAt INTEGER NOT NULL)""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS paper_order (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL, code TEXT NOT NULL,
                side TEXT NOT NULL, quantity INTEGER NOT NULL, price REAL NOT NULL, fee REAL NOT NULL,
                status TEXT NOT NULL, source TEXT NOT NULL, note TEXT NOT NULL)""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS paper_equity (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordedAt INTEGER NOT NULL, equity REAL NOT NULL,
                cash REAL NOT NULL, marketValue REAL NOT NULL)""".trimIndent())
        } }
        val MIGRATION_8_9 = object : Migration(8, 9) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS strategy_signal (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, symbol TEXT NOT NULL, time INTEGER NOT NULL,
                price REAL NOT NULL, action TEXT NOT NULL, score INTEGER NOT NULL, reason TEXT NOT NULL)""".trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_strategy_signal_symbol_time_action ON strategy_signal(symbol, time, action)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS fund_flow (
                symbol TEXT NOT NULL, date TEXT NOT NULL, main_in REAL, main_out REAL,
                net_flow REAL NOT NULL, source TEXT NOT NULL, cached_at INTEGER NOT NULL,
                PRIMARY KEY(symbol, date))""".trimIndent())
        } }
        val MIGRATION_9_10 = object : Migration(9, 10) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS minute_bar_cache_new (
                code TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, intervalMinutes INTEGER NOT NULL,
                price REAL NOT NULL, avgPrice REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL,
                volume REAL NOT NULL, amount REAL NOT NULL, cachedAt INTEGER NOT NULL,
                PRIMARY KEY(code, date, time))""".trimIndent())
            db.execSQL("""INSERT INTO minute_bar_cache_new
                (code, date, time, intervalMinutes, price, avgPrice, high, low, volume, amount, cachedAt)
                SELECT code, strftime('%Y-%m-%d', cachedAt / 1000, 'unixepoch', 'localtime'), time, 1,
                       price, avgPrice, high, low, volume, amount, cachedAt
                FROM minute_bar_cache""".trimIndent())
            db.execSQL("DROP TABLE minute_bar_cache")
            db.execSQL("ALTER TABLE minute_bar_cache_new RENAME TO minute_bar_cache")
        } }
        val MIGRATION_10_11 = object : Migration(10, 11) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS account_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                occurredAt INTEGER NOT NULL,
                type TEXT NOT NULL,
                amount REAL NOT NULL,
                code TEXT NOT NULL,
                source TEXT NOT NULL,
                note TEXT NOT NULL)""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_account_ledger_occurredAt ON account_ledger(occurredAt)")
        } }
        val MIGRATION_11_12 = object : Migration(11, 12) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE strategy_signal ADD COLUMN strategyVersion TEXT NOT NULL DEFAULT 'LEGACY'")
        } }
        val MIGRATION_12_13 = object : Migration(12, 13) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS alert_record (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                alertKey TEXT NOT NULL,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                signalAt INTEGER NOT NULL,
                signalDate TEXT NOT NULL,
                signalTime TEXT NOT NULL,
                action TEXT NOT NULL,
                price REAL NOT NULL,
                score INTEGER NOT NULL,
                strategyVersion TEXT NOT NULL,
                source TEXT NOT NULL,
                dataSource TEXT NOT NULL,
                reason TEXT NOT NULL,
                status TEXT NOT NULL,
                evaluatedAt INTEGER NOT NULL,
                exitPrice REAL NOT NULL,
                netEdgePct REAL NOT NULL,
                maxFavorablePct REAL NOT NULL,
                maxAdversePct REAL NOT NULL,
                horizonBars INTEGER NOT NULL)""".trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_alert_record_alertKey ON alert_record(alertKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alert_record_signalAt ON alert_record(signalAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alert_record_status ON alert_record(status)")
        } }

        @Volatile private var instance: AStockDatabase? = null
        fun get(context: Context): AStockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AStockDatabase::class.java, "astock_guard.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13
                )
                .build().also { instance = it }
        }
    }
}
