package com.locogo.astockguard.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AStockDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AStockDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate8To9CreatesTradingTables() {
        helper.createDatabase(TEST_DB, 8).close()
        val database = helper.runMigrationsAndValidate(TEST_DB, 9, true, AStockDatabase.MIGRATION_8_9)
        database.query("SELECT symbol, date, main_in, main_out, net_flow FROM fund_flow").close()
        database.query("SELECT id, symbol, time, price, action, score, reason FROM strategy_signal").close()
        database.close()
    }

    @Test
    fun migrate9To10ScopesMinuteBarsByDate() {
        helper.createDatabase(TEST_DB_9_10, 9).apply {
            execSQL("""INSERT INTO minute_bar_cache
                (code, time, price, avgPrice, high, low, volume, amount, cachedAt)
                VALUES ('000001.SZ', '09:30', 10, 10, 10, 10, 100, 100000, 1756252800000)""")
            close()
        }
        val database = helper.runMigrationsAndValidate(TEST_DB_9_10, 10, true, AStockDatabase.MIGRATION_9_10)
        database.query("SELECT code, date, time, intervalMinutes FROM minute_bar_cache").use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals(1, cursor.getInt(3))
        }
        database.close()
    }

    @Test
    fun migrate10To11CreatesAccountLedger() {
        helper.createDatabase(TEST_DB_10_11, 10).close()
        val database = helper.runMigrationsAndValidate(TEST_DB_10_11, 11, true, AStockDatabase.MIGRATION_10_11)
        database.query("SELECT id, occurredAt, type, amount, code, source, note FROM account_ledger").close()
        database.close()
    }

    @Test
    fun migrate11To12AddsStrategyVersion() {
        helper.createDatabase(TEST_DB_11_12, 11).close()
        val database = helper.runMigrationsAndValidate(TEST_DB_11_12, 12, true, AStockDatabase.MIGRATION_11_12)
        database.query("SELECT strategyVersion FROM strategy_signal").close()
        database.close()
    }

    @Test
    fun migrate12To13CreatesAlertHistory() {
        helper.createDatabase(TEST_DB_12_13, 12).close()
        val database = helper.runMigrationsAndValidate(TEST_DB_12_13, 13, true, AStockDatabase.MIGRATION_12_13)
        database.query("SELECT alertKey, signalAt, strategyVersion, status, netEdgePct FROM alert_record").close()
        database.close()
    }

    @Test
    fun migrate13To14AddsTypedPlanEvidence() {
        helper.createDatabase(TEST_DB_13_14, 13).close()
        val database = helper.runMigrationsAndValidate(TEST_DB_13_14, 14, true, AStockDatabase.MIGRATION_13_14)
        database.query("SELECT alertType, targetPrice, stopPrice, evidenceJson FROM alert_record").close()
        database.close()
    }

    @Test
    fun migrate14To15CreatesLevel2History() {
        helper.createDatabase(TEST_DB_14_15, 14).close()
        val database = helper.runMigrationsAndValidate(TEST_DB_14_15, 15, true, AStockDatabase.MIGRATION_14_15)
        database.query("SELECT code, capturedAt, payloadJson, source, providerUpdatedAt FROM level2_snapshot_history").close()
        database.close()
    }

    private companion object {
        const val TEST_DB = "migration-8-9"
        const val TEST_DB_9_10 = "migration-9-10"
        const val TEST_DB_10_11 = "migration-10-11"
        const val TEST_DB_11_12 = "migration-11-12"
        const val TEST_DB_12_13 = "migration-12-13"
        const val TEST_DB_13_14 = "migration-13-14"
        const val TEST_DB_14_15 = "migration-14-15"
    }
}
