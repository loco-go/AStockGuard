package com.locogo.astockguard.integration.ths

import android.text.InputType
import android.widget.EditText
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locogo.astockguard.SettingsRepository
import com.locogo.astockguard.data.local.AStockDatabase
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 使用内存数据库验证真实 Main Dispatcher 与 Android 数字输入过滤，不修改用户持仓。 */
@RunWith(AndroidJUnit4::class)
class ThsAccountImportRegressionTest {
    @Test fun largeAmountSurvivesNumericInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val input = EditText(instrumentation.targetContext).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            listOf(10000000.0, -12345678.25, 0.0001).forEach { value ->
                input.setText(accountAmountInput(value))
                assertEquals(value, input.text.toString().toDouble(), 0.000001)
            }
        }
    }

    @Test fun emptyCandidateClearKeepsFourInputsAndManualSource() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AStockDatabase::class.java).build()
        val repository = ThsPositionImportRepository(SettingsRepository(instrumentation.targetContext), database.cacheDao())
        val store = ViewModelStore()
        lateinit var model: ThsPositionImportViewModel
        try {
            instrumentation.runOnMainSync {
                model = ThsPositionImportViewModel(repository)
                store.put("account-import", model)
            }
            withTimeout(5000) { model.accountRows.filter { it.size == 4 }.first() }
            instrumentation.runOnMainSync {
                model.clear()
                model.clear()
                assertEquals(4, model.accountRows.value.size)
                model.editAccount(ThsAccountMetric.TOTAL_ASSETS, 10000000.0)
                model.selectAccount(ThsAccountMetric.TOTAL_ASSETS, true)
                model.confirmAccount()
            }
            val imported = withTimeout(5000) { repository.importedAccount.filter { it.isNotEmpty() }.first() }
            assertEquals(10000000.0, imported.single().value, 0.001)
            assertEquals("THS_MANUAL", imported.single().source)
            val unchanged = ThsAccountCandidate(ThsAccountMetric.TOTAL_ASSETS, imported.single().value,
                imported.single().observedAt, imported.single().source)
            repository.confirmAccount(listOf(unchanged))
            assertEquals("THS_MANUAL", repository.importedAccount.first().single().source)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            database.close()
        }
    }
}
