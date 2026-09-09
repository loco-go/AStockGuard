package com.locogo.astockguard.ui.chart

import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.locogo.astockguard.DailyBar
import com.locogo.astockguard.chart.ChartPeriod
import com.locogo.astockguard.chart.MinuteCandle
import com.locogo.astockguard.ui.messages.MessageCenterActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineChartTest {
    @Test fun packagedChartsRenderWithEmptyCacheAndNetworkBlocked() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bytes = context.assets.open(ChartAssetClient.SCRIPT_PATH).use { it.readBytes() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("db288a8c5d910a907f1e74fd355bc1d7a9219022549e7dc575e3076e5c31b46a", digest)
        assertTrue(context.assets.open("vendor/klinecharts-10.0.2/LICENSE").bufferedReader().use { it.readText() }.contains("Apache License"))
        // 真机系统可能拦截测试进程的后台页面启动；先通过桌面入口把应用带到前台。
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/.MainActivity").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        val activity = instrumentation.startActivitySync(Intent(context, MessageCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var chart: KLineChartView
        lateinit var web: WebView
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            instrumentation.runOnMainSync {
                chart = KLineChartView(activity)
                activity.setContentView(chart)
                web = chart.getChildAt(0) as WebView
                web.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                            errors.add(message.message())
                        }
                        return true
                    }
                }
                web.clearCache(true)
                assertTrue(web.settings.blockNetworkLoads)
            }
            val bars = (1..20).map { day -> DailyBar("2026-08-${day.toString().padStart(2, '0')}", 10.0, 10.5, 11.0, 9.0, 1000.0) }
            for ((period, label) in listOf(ChartPeriod.DAY to "日K", ChartPeriod.WEEK to "周K", ChartPeriod.MONTH to "月K")) {
                val candles = ChartDataMapper.aggregate(bars, period)
                instrumentation.runOnMainSync { chart.render(period, candles, emptyList()) }
                val rendered = withTimeoutOrNull(10_000) {
                    while (true) {
                        val result = CompletableDeferred<String>()
                        instrumentation.runOnMainSync {
                            web.evaluateJavascript("""
                                (() => {
                                    if (typeof klinecharts === 'undefined' || klinecharts.version() !== '10.0.2' ||
                                        !document.getElementById('period').textContent.includes('$label')) return false;
                                    const instance = klinecharts.init('chart');
                                    const data = instance.getDataList();
                                    const indicators = instance.getIndicators();
                                    return data.length === ${candles.size} && data[0].timestamp === ${candles.first().timestamp} &&
                                        data[data.length - 1].close === ${candles.last().close} &&
                                        indicators.some(i => i.name === 'MA' && i.result.length === data.length) &&
                                        indicators.some(i => i.name === 'VOL' && i.result.length === data.length) &&
                                        Array.from(document.querySelectorAll('canvas')).some(c => c.width > 0 && c.height > 0);
                                })()
                            """.trimIndent()) { result.complete(it) }
                        }
                        if (result.await() == "true") break
                        delay(100)
                    }
                    true
                }
                val diagnostic = CompletableDeferred<String>()
                instrumentation.runOnMainSync {
                    web.evaluateJavascript("""
                        JSON.stringify({text:document.body.innerText,version:typeof klinecharts,
                            data:typeof klinecharts === 'undefined' ? [] : klinecharts.init('chart').getDataList(),
                            indicators:typeof klinecharts === 'undefined' ? [] : klinecharts.init('chart').getIndicators().map(i=>({name:i.name,count:i.result.length})),
                            canvases:Array.from(document.querySelectorAll('canvas')).map(c=>[c.width,c.height])})
                    """.trimIndent()) { diagnostic.complete(it) }
                }
                assertEquals("$period failed: $errors; ${withTimeout(5000) { diagnostic.await() }}", true, rendered)
                assertTrue("$period JavaScript errors: $errors", errors.isEmpty())
                val visualReady = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync {
                    web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { visualReady.complete(Unit) }
                    })
                }
                withTimeout(5000) { visualReady.await() }
                instrumentation.waitForIdleSync()
                // 等待首屏 Activity 转场及 WebView 合成提交后再留存真机截图。
                delay(300)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    val directory = java.io.File(context.getExternalFilesDir(null), "chart-test").apply { mkdirs() }
                    java.io.File(directory, "${period.name}.png").outputStream().use {
                        assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    screenshot.recycle()
                }
            }
            instrumentation.runOnMainSync {
                chart.render(ChartPeriod.MINUTE, emptyList(), listOf(
                    MinuteCandle("09:35", 10.0, 10.5, 9.9, 10.4, 10.2, 1000.0),
                    MinuteCandle("09:40", 10.4, 10.5, 10.1, 10.2, 10.3, 1200.0)
                ))
            }
            withTimeout(5000) {
                while (true) {
                    val result = CompletableDeferred<String>()
                    instrumentation.runOnMainSync {
                        web.evaluateJavascript("document.getElementById('chart').tagName === 'CANVAS' && document.getElementById('chart').width > 0 && document.getElementById('empty').hidden") { result.complete(it) }
                    }
                    if (result.await() == "true") break
                    delay(100)
                }
            }
            assertTrue("JavaScript errors: $errors", errors.isEmpty())
            instrumentation.runOnMainSync { chart.render(ChartPeriod.DAY, emptyList(), emptyList()) }
            withTimeout(5000) {
                while (true) {
                    val result = CompletableDeferred<String>()
                    instrumentation.runOnMainSync { web.evaluateJavascript("document.body.textContent.includes('暂无K线数据')") { result.complete(it) } }
                    if (result.await() == "true") break
                    delay(100)
                }
            }
        } finally {
            instrumentation.runOnMainSync { chart.release(); activity.finish() }
        }
    }
}
