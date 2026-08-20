package com.locogo.astockguard

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MarketMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var settings: SettingsRepository
    private lateinit var marketRepository: MarketRepository
    private var lastRisk = ""
    private val lastSignal = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        settings = SettingsRepository(this)
        marketRepository = MarketRepository(settings)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> startLoop()
        }
        return START_STICKY
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        startForeground(NOTIFICATION_ID, NotificationHelper.serviceNotification(this, "正在初始化 iFinD..."))
        loopJob = scope.launch {
            while (isActive) {
                try {
                    val snapshot = marketRepository.refresh()
                    MonitorBus.update(snapshot)
                    emitAlerts(snapshot)
                    val text = "${snapshot.assessment.eventRisk}/${snapshot.assessment.marketPhase}  仓位${"%.1f".format(snapshot.positionRatio)}%  上限${"%.0f".format(snapshot.assessment.maxPositionRatio * 100)}%"
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, NotificationHelper.serviceNotification(this@MarketMonitorService, text))
                } catch (t: Throwable) {
                    Log.e("MarketMonitor", "refresh failed", t)
                }
                delay(3_000)
            }
        }
    }

    private fun emitAlerts(snapshot: MonitorSnapshot) {
        val risk = snapshot.assessment.eventRisk
        if (risk != lastRisk && risk == "E2") {
            NotificationHelper.alert(this, 2001, "E2 风险：先降β", snapshot.assessment.advice)
        }
        lastRisk = risk

        snapshot.assessment.signals.forEachIndexed { index, s ->
            val sig = "${s.level}:${s.action}"
            if (lastSignal[s.code] != sig && (s.level == "RED" || s.level == "YELLOW")) {
                NotificationHelper.alert(this, 3000 + index, "${s.code} ${s.action}", s.reason)
            }
            lastSignal[s.code] = sig
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    companion object {
        const val ACTION_START = "com.locogo.astockguard.START"
        const val ACTION_STOP = "com.locogo.astockguard.STOP"
        const val NOTIFICATION_ID = 1001
    }
}
