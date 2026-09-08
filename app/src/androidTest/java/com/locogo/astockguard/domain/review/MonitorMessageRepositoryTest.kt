package com.locogo.astockguard.domain.review

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locogo.astockguard.MarketRepository
import com.locogo.astockguard.MinuteBar
import com.locogo.astockguard.data.local.AStockDatabase
import com.locogo.astockguard.data.fundflow.SectorFundFlowResult
import com.locogo.astockguard.domain.trading.*
import com.locogo.astockguard.domain.volume.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MonitorMessageRepositoryTest {
    @Test fun notificationsCoexistAndMessagePageOpens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val types = listOf("DYNAMIC_T", "VOLUME_RADAR", "SECTOR_FLOW")
        try {
            types.forEach { type ->
                assertTrue(com.locogo.astockguard.NotificationHelper.message(context, type, "MESSAGE_TEST", "消息功能测试", "仅测试通知展示，无交易含义"))
            }
            val expected = types.map { MonitorMessagePolicy.notificationTag(it, "MESSAGE_TEST") }.toSet()
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (!manager.activeNotifications.map { it.tag }.containsAll(expected) && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
            assertTrue(manager.activeNotifications.map { it.tag }.containsAll(expected))
            val activity = instrumentation.startActivitySync(android.content.Intent(context, com.locogo.astockguard.ui.messages.MessageCenterActivity::class.java)
                .putExtra("message_type", "DYNAMIC_T").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertFalse(activity.isFinishing); activity.finish() }
        } finally {
            types.forEach { manager.cancel(MonitorMessagePolicy.notificationTag(it, "MESSAGE_TEST"), 1) }
        }
    }

    @Test fun independentCooldownHistoryFiltersAndSavedDelivery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AStockDatabase::class.java).build()
        try {
            val repository = MonitorMessageRepository(db.cacheDao())
            val now = 1_789_000_000_000L
            val bars = listOf(MinuteBar("10:00", 10.0, 10.0, 10.1, 9.9, 100.0, 1000.0))
            val analysis = IntradayVolumeAnalyzer().analyze(code = "TEST", bars = bars, dataFresh = true, now = now)
            val plan = DynamicTPlanner.plan(null, bars, analysis.features, analysis.signal, TradingRiskContext(true))
            val radar = IntradayRadarSnapshot(MarketRepository.MinuteSeries(LocalDate.of(2026, 9, 8), bars, 1, false, false), null, null, analysis, plan, "TEST")
            val first = repository.recordDynamicT("测试", radar, now)!!
            repository.delivery(first, false)
            assertNull(repository.recordDynamicT("测试", radar, now + 1000))
            assertNotNull(repository.recordDynamicT("测试", radar.copy(plan = plan.copy(action = DynamicTAction.WAIT_BUYBACK)), now + 2000))
            assertNotNull(repository.recordDynamicT("测试", radar, now + 600_000))
            assertNull(repository.recordDynamicT("测试", radar.copy(minuteSeries = radar.minuteSeries.copy(fromCache = true)), now + 1_200_000))
            val flow = SectorFundFlowResult("INDUSTRY", emptyList(), "CACHE", true)
            assertNotNull(repository.recordSectorFlow(flow, now))
            assertNull(repository.recordSectorFlow(flow, now + 1000))
            assertEquals(3, repository.observe("DYNAMIC_T", 100).first().size)
            assertEquals(1, repository.observe("SECTOR_FLOW", 100).first().size)
            assertEquals(2, repository.observe("", 2).first().size)
            val saved = repository.observe("DYNAMIC_T", 100).first().last()
            assertEquals("SAVED_ONLY", JSONObject(saved.evidenceJson).getString("delivery"))
            assertEquals("RECORDED", saved.status)
            assertEquals(4, MonitorMessageRepository(db.cacheDao()).observe("", 100).first().size)
            assertNotEquals(MonitorMessagePolicy.notificationTag("DYNAMIC_T", "TEST"), MonitorMessagePolicy.notificationTag("VOLUME_RADAR", "TEST"))
        } finally { db.close() }
    }
}
