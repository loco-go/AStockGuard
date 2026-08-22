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
    private val lastSignal = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate(); NotificationHelper.ensureChannels(this)
        settings = SettingsRepository(this); marketRepository = MarketRepository(settings)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf() else startLoop(); return START_STICKY
    }
    private fun startLoop() {
        if (loopJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, NotificationHelper.serviceNotification(this, "腾讯行情初始化..."))
        loopJob = scope.launch {
            while (isActive) {
                try {
                    val s = marketRepository.refresh(); MonitorBus.update(s); emitAlerts(s)
                    val text = "${s.assessment.eventRisk}/${s.assessment.marketPhase} 仓位${"%.1f".format(s.positionRatio)}% 上限${"%.0f".format(s.assessment.maxPositionRatio * 100)}%"
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, NotificationHelper.serviceNotification(this@MarketMonitorService, text))
                } catch (t: Throwable) { Log.e("MarketMonitor", "refresh failed: ${t.message}") }
                delay(settings.refreshSeconds * 1000L)
            }
        }
    }
    private fun emitAlerts(s: MonitorSnapshot) {
        val risk = s.assessment.eventRisk
        if (risk != lastRisk && risk == "E2") NotificationHelper.alert(this, 2001, "E2 风险：先降β", s.assessment.advice)
        lastRisk = risk
        s.assessment.signals.forEachIndexed { index, sig ->
            val key = "${sig.level}:${sig.action}"
            if (lastSignal[sig.code] != key && sig.level in setOf("RED", "YELLOW", "GREEN") && sig.action != "HOLD")
                NotificationHelper.alert(this, 3000 + index, "${sig.code} ${sig.action}", sig.reason)
            lastSignal[sig.code] = key
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
