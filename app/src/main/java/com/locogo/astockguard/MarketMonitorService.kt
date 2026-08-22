package com.locogo.astockguard

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.locogo.astockguard.data.news.NewsRepository
import com.locogo.astockguard.domain.trading.TTradePlanner
import kotlinx.coroutines.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class MarketMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var settings: SettingsRepository
    private lateinit var marketRepository: MarketRepository
    private lateinit var newsRepository: NewsRepository
    private var lastRisk = ""
    private var lastNewsRisk = "E0"
    private var lastNewsRefreshAt = 0L
    private var lastTScanAt = 0L
    private val lastTStatus = mutableMapOf<String, String>()
    private lateinit var signalLifecycle: com.locogo.astockguard.domain.signal.SignalLifecycleManager

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        settings = appContainer.settings
        marketRepository = appContainer.marketRepository
        newsRepository = appContainer.newsRepository
        signalLifecycle = appContainer.signalLifecycle
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf() else startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, NotificationHelper.serviceNotification(this, "腾讯行情初始化..."))
        loopJob = scope.launch {
            while (isActive) {
                try {
                    val s = marketRepository.refresh()
                    MonitorBus.update(s)
                    emitAlerts(s)
                    refreshNewsIfDue()
                    scanTPlansIfDue(s)
                    val source = if (s.dataHealth.isStale) "缓存" else "实时"
                    val text = "$source ${s.assessment.eventRisk}/${s.assessment.marketPhase} 仓位${"%.1f".format(s.positionRatio)}% 上限${"%.0f".format(s.assessment.maxPositionRatio * 100)}%"
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, NotificationHelper.serviceNotification(this@MarketMonitorService, text))
                } catch (t: Throwable) {
                    Log.e("MarketMonitor", "refresh failed: ${t.message}", t)
                }
                delay(settings.refreshSeconds * 1000L)
            }
        }
    }

    private suspend fun refreshNewsIfDue() {
        if (!settings.newsEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastNewsRefreshAt < settings.newsRefreshMinutes * 60_000L) return
        lastNewsRefreshAt = now
        val news = runCatching { newsRepository.refresh() }.getOrElse { newsRepository.cached() }
        if (news.level != lastNewsRisk && news.level == "E2") {
            val evidence = news.evidence.take(3).joinToString("；") { it.title }
            NotificationHelper.alert(
                this,
                2101,
                "新闻黑天鹅 E2 · score ${news.score}",
                evidence.ifBlank { "新闻风险覆盖层升级，请人工核实证据后再调整仓位。" }
            )
        }
        lastNewsRisk = news.level
    }

    private suspend fun scanTPlansIfDue(snapshot: MonitorSnapshot) {
        if (snapshot.dataHealth.isStale) return
        val now = System.currentTimeMillis()
        if (now - lastTScanAt < T_SCAN_INTERVAL_MS || !isAshareTradingTime(now)) return
        lastTScanAt = now

        val quoteMap = snapshot.quotes.associateBy { it.code }
        settings.positions().asSequence()
            .filter { it.shares >= 100 }
            .take(MAX_T_SCAN_POSITIONS)
            .forEach { position ->
                val quote = quoteMap[position.code] ?: return@forEach
                val bars = runCatching { marketRepository.loadMinuteBars(position.code) }.getOrDefault(emptyList())
                val plan = TTradePlanner.plan(
                    quote = quote,
                    position = position,
                    minuteBars = bars,
                    fundFlow = null,
                    marketPhase = snapshot.assessment.marketPhase,
                    dataStale = snapshot.dataHealth.isStale,
                    now = now
                )
                val previous = lastTStatus.put(position.code, plan.status)
                if (previous == plan.status) return@forEach

                when (plan.status) {
                    "BUY_ZONE" -> NotificationHelper.alert(
                        this,
                        tNotificationId(position.code, 1),
                        "${position.name} · T买入观察区",
                        "${"%.2f".format(plan.buyZoneLow)}~${"%.2f".format(plan.buyZoneHigh)}，建议T仓${plan.suggestedQuantity}股；目标卖区${"%.2f".format(plan.sellZoneLow)}~${"%.2f".format(plan.sellZoneHigh)}，失效位${"%.2f".format(plan.invalidPrice)}。等待承接确认，不自动下单。"
                    )
                    "SELL_ZONE" -> NotificationHelper.alert(
                        this,
                        tNotificationId(position.code, 2),
                        "${position.name} · T卖出观察区",
                        "现价${quote.latest?.let { "%.2f".format(it) } ?: "-"}进入/超过计划卖区${"%.2f".format(plan.sellZoneLow)}~${"%.2f".format(plan.sellZoneHigh)}；评估卖出T仓${plan.suggestedQuantity}股，保留底仓纪律。"
                    )
                    "INVALIDATED" -> NotificationHelper.alert(
                        this,
                        tNotificationId(position.code, 3),
                        "${position.name} · T计划失效",
                        "价格跌破计划失效位${"%.2f".format(plan.invalidPrice)}，停止机械补仓，重新评估趋势和风险。"
                    )
                }
            }
    }

    private suspend fun emitAlerts(s: MonitorSnapshot) {
        if (s.dataHealth.isStale) return
        val risk = s.assessment.eventRisk
        if (risk != lastRisk && risk == "E2") NotificationHelper.alert(this, 2001, "本地行情 E2 风险：先降β", s.assessment.advice)
        lastRisk = risk
        signalLifecycle.evaluate(s).forEachIndexed { index, transition ->
            if (!transition.important) return@forEachIndexed
            if (!signalLifecycle.canNotify(transition.code, s.updatedAt)) return@forEachIndexed
            NotificationHelper.alert(this, 3000 + index, "${transition.code} ${transition.from.name} → ${transition.to.name}", transition.reason)
            signalLifecycle.markNotified(transition.code, s.updatedAt)
        }
    }

    private fun isAshareTradingTime(epochMs: Long): Boolean {
        val dt = Instant.ofEpochMilli(epochMs).atZone(CHINA_ZONE)
        if (dt.dayOfWeek == DayOfWeek.SATURDAY || dt.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = dt.toLocalTime()
        val morning = !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(11, 30))
        val afternoon = !t.isBefore(LocalTime.of(13, 0)) && !t.isAfter(LocalTime.of(15, 0))
        return morning || afternoon
    }

    private fun tNotificationId(code: String, type: Int): Int = 4000 + (abs(code.hashCode()) % 700) * 4 + type

    override fun onDestroy() { loopJob?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf(startId) }

    companion object {
        const val ACTION_START = "com.locogo.astockguard.START"
        const val ACTION_STOP = "com.locogo.astockguard.STOP"
        const val NOTIFICATION_ID = 1001
        private const val T_SCAN_INTERVAL_MS = 30_000L
        private const val MAX_T_SCAN_POSITIONS = 6
        private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
