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

    private companion object {
        const val TEST_DB = "migration-8-9"
    }
}
