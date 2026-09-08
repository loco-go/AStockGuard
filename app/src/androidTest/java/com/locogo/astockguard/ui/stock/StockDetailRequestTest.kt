package com.locogo.astockguard.ui.stock

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locogo.astockguard.*
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.data.repository.StockDetailDataSource
import com.locogo.astockguard.domain.strategy.ChartSignal
import com.locogo.astockguard.domain.trading.*
import com.locogo.astockguard.domain.volume.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class StockDetailRequestTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun lateInitialResultCannotOverwriteNewSelectionOrAbaReload() = runBlocking {
        val source = ControlledSource()
        val store = ViewModelStore()
        val model = StockDetailViewModel(source)
        main { store.put("detail", model); model.load("A") }
        val a1 = source.next()
        try {
            main { model.load("B"); model.selectPeriod(ChartPeriod.DAY) }
            assertTrue(model.state.value.candles.isEmpty())
            val b = source.next()
            main { model.load("A"); model.selectPeriod(ChartPeriod.DAY) }
            val a2 = source.next()
            a2.finish("A", 30.0)
            withTimeout(5000) { model.state.first { !it.loading && it.candles.isNotEmpty() } }
            a1.finish("A", 10.0)
            b.finish("B", 20.0)
            instrumentation.waitForIdleSync()
            main {
                assertEquals("A", model.state.value.code)
                assertEquals(30.0, model.state.value.candles.last().close, 0.001)
                assertEquals(30.0, model.state.value.dynamicTPlan!!.referencePrice, 0.001)
                model.load("C"); model.selectPeriod(ChartPeriod.DAY)
                assertTrue(model.state.value.candles.isEmpty())
                assertNull(model.state.value.dynamicTPlan)
            }
            source.next().finish("C", 40.0)
        } finally { main { store.clear() } }
    }

    @Test fun initialAndRefreshResultsCannotReplaceSelectedHistory() = runBlocking {
        val source = ControlledSource()
        val store = ViewModelStore()
        val model = StockDetailViewModel(source)
        val yesterday = MarketRepository.marketDate().minusDays(1)
        main { store.put("detail", model); model.load("A") }
        val initial = source.next()
        try {
            main { model.selectMinuteDate(yesterday) }
            val historical = withTimeout(5000) { source.minutes.receive() }
            historical.complete(series(yesterday, 8.0, true))
            withTimeout(5000) { model.state.first { !it.minuteLoading } }
            initial.finish("A", 10.0)
            withTimeout(5000) { model.state.first { !it.loading } }
            main {
                assertEquals(yesterday, model.state.value.selectedMinuteDate)
                assertTrue(model.state.value.minuteDataIsHistorical)
                assertNull(model.state.value.dynamicTPlan)
                model.selectMinuteDate(MarketRepository.marketDate())
            }
            withTimeout(5000) { source.minutes.receive() }.complete(series(MarketRepository.marketDate(), 10.0, false))
            withTimeout(5000) { model.state.first { !it.minuteLoading } }
            main { model.updateMarketContext(null, null, null, false) }
            val refresh = withTimeout(5000) { source.radars.receive() }
            main { model.selectMinuteDate(yesterday) }
            withTimeout(5000) { source.minutes.receive() }.complete(series(yesterday, 8.0, true))
            withTimeout(5000) { model.state.first { !it.minuteLoading } }
            refresh.complete(radar("A", 99.0))
            instrumentation.waitForIdleSync()
            main {
                assertEquals(yesterday, model.state.value.selectedMinuteDate)
                assertNull(model.state.value.dynamicTPlan)
                assertTrue(model.state.value.minuteDataIsHistorical)
            }
        } finally { main { store.clear() } }
    }

    private class ControlledSource : StockDetailDataSource {
        val days = Channel<CompletableDeferred<MarketRepository.DailySeries>>(Channel.UNLIMITED)
        val radars = Channel<CompletableDeferred<IntradayRadarSnapshot>>(Channel.UNLIMITED)
        val minutes = Channel<CompletableDeferred<MarketRepository.MinuteSeries>>(Channel.UNLIMITED)
        override suspend fun daily(code: String, limit: Int) = delayed(days)
        override suspend fun radar(code: String, assessment: MarketAssessment?, exposureStatus: String?, stale: Boolean) = delayed(radars)
        override suspend fun minute(code: String, date: LocalDate) = delayed(minutes)
        override suspend fun persist(code: String, signal: ChartSignal) = Unit
        // 模拟取消后仍然返回的底层调用，不能仅依赖网络取消来保证页面一致性。
        private suspend fun <T> delayed(channel: Channel<CompletableDeferred<T>>): T = withContext(NonCancellable) {
            val result = CompletableDeferred<T>(); channel.send(result); result.await()
        }
        suspend fun next() = withTimeout(5000) { Pending(days.receive(), radars.receive()) }
    }
    private class Pending(val daily: CompletableDeferred<MarketRepository.DailySeries>, val volume: CompletableDeferred<IntradayRadarSnapshot>) {
        fun finish(code: String, price: Double) {
            daily.complete(MarketRepository.DailySeries(listOf(DailyBar("2026-09-07", price, price, price, price, 100.0)), "TEST", true, false, 1L, ""))
            volume.complete(radar(code, price))
        }
    }
    companion object {
        private fun series(date: LocalDate, price: Double, history: Boolean) = MarketRepository.MinuteSeries(date,
            listOf(MinuteBar("10:00", price, price, price, price, 100.0, 1000.0)), 1, history, history, "TEST")
        private fun radar(code: String, price: Double): IntradayRadarSnapshot {
            val series = series(MarketRepository.marketDate(), price, false)
            val analysis = IntradayVolumeAnalyzer().analyze(code, series.bars, dataFresh = true, now = System.currentTimeMillis())
            val plan = DynamicTPlanner.plan(null, series.bars, analysis.features, analysis.signal, TradingRiskContext(true))
            return IntradayRadarSnapshot(series, null, null, analysis, plan, "TEST")
        }
    }
}
