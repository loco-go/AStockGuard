package com.locogo.astockguard

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

class MarketMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var settings: SettingsRepository
    private lateinit var marketRepository: MarketRepository
    private var lastRisk = ""
    private lateinit var signalLifecycle: com.locogo.astockguard.domain.signal.SignalLifecycleManager

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        settings = appContainer.settings
        marketRepository = appContainer.marketRepository
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

    private suspend fun emitAlerts(s: MonitorSnapshot) {
        if (s.dataHealth.isStale) return
        val risk = s.assessment.eventRisk
        if (risk != lastRisk && risk == "E2") NotificationHelper.alert(this, 2001, "E2 风险：先降β", s.assessment.advice)
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
