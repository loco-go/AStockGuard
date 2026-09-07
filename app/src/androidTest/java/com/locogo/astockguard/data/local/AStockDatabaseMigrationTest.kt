package com.locogo.astockguard.data.local

/*
 * 文件职责：在设备数据库上逐版本验证 Room Migration，确保历史安装升级后表、索引和既有数据仍然可读。
 * 测试边界：使用 app/schemas 中导出的真实 schema 创建旧库；禁止只测试新建数据库而遗漏升级路径。
 * 维护说明：每次数据库版本提升都必须增加对应迁移场景，并验证非敏感用户数据不会丢失或被错误重算。
 */

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class AStockDatabaseMigrationTest {
    @Test
    fun accountImportPersistsSelectedFieldsWithoutDeletingOthers() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, AStockDatabase::class.java).build()
        try {
            val dao = database.cacheDao()
            dao.upsertImportedAccountMetrics(listOf(
                ImportedAccountMetricEntity("TOTAL_ASSETS", 10000.0, 1000, 2000, "THS_CONFIRMED"),
                ImportedAccountMetricEntity("TODAY_PNL", -20.0, 1000, 2000, "THS_CONFIRMED")
            ))
            dao.upsertImportedAccountMetrics(listOf(ImportedAccountMetricEntity("TODAY_PNL", 30.0, 3000, 4000, "THS_MANUAL")))
            val metrics = dao.observeImportedAccountMetrics().first()
            org.junit.Assert.assertEquals(2, metrics.size)
            org.junit.Assert.assertEquals(10000.0, metrics.first { it.metric == "TOTAL_ASSETS" }.value, 0.001)
            org.junit.Assert.assertEquals(30.0, metrics.first { it.metric == "TODAY_PNL" }.value, 0.001)
            dao.clearImportedAccountMetrics()
            org.junit.Assert.assertTrue(dao.observeImportedAccountMetrics().first().isEmpty())
        } finally { database.close() }
    }

    @Test
    fun migrate16To17PreservesLedgerAndCreatesAccountImports() {
        val name = "migration_16_17_account_import"
        helper.createDatabase(name, 16).apply {
            execSQL("INSERT INTO account_ledger (id, occurredAt, type, amount, code, source, note) VALUES (1, 1000, 'DEPOSIT', 500, '', 'USER', '')")
            close()
        }
        helper.runMigrationsAndValidate(name, 17, true, AStockDatabase.MIGRATION_16_17).apply {
            query("SELECT amount FROM account_ledger WHERE id = 1").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                org.junit.Assert.assertEquals(500.0, it.getDouble(0), 0.001)
            }
            query("SELECT * FROM imported_account_metric").use { org.junit.Assert.assertEquals(0, it.count) }
            execSQL("INSERT INTO imported_account_metric VALUES ('TODAY_PNL', -25.5, 1000, 2000, 'THS_CONFIRMED')")
            query("SELECT value FROM imported_account_metric").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                org.junit.Assert.assertEquals(-25.5, it.getDouble(0), 0.001)
            }
            close()
        }
    }

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

    /** 验证15到16升级会保留旧提醒并创建雷达冷却、多周期评价字段和表。 */
    @Test
    fun migrate15To16CreatesVolumeRadarHistory() {
        helper.createDatabase(TEST_DB_15_16, 15).close()
        val database = helper.runMigrationsAndValidate(TEST_DB_15_16, 16, true, AStockDatabase.MIGRATION_15_16)
        database.query("SELECT signalType, confidence FROM alert_record").close()
        database.query("SELECT code, signalType, action, lastNotifiedAt FROM volume_radar_state").close()
        database.query("SELECT alertId, horizonMinutes, returnPct, effective FROM volume_signal_outcome").close()
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
        const val TEST_DB_15_16 = "migration-15-16"
    }
}
