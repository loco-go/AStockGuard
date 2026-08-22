package com.locogo.astockguard

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.locogo.astockguard.data.news.NewsRepository
import kotlinx.coroutines.*

class MarketMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var settings: SettingsRepository
    private lateinit var marketRepository: MarketRepository
    private lateinit var newsRepository: NewsRepository
    private var lastRisk = ""
    private var lastNewsRisk = "E0"
    private var lastNewsRefreshAt = 0L
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

    override fun onDestroy() { loopJob?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf(startId) }

    companion object {
        const val ACTION_START = "com.locogo.astockguard.START"
        const val ACTION_STOP = "com.locogo.astockguard.STOP"
        const val NOTIFICATION_ID = 1001
    }
}
