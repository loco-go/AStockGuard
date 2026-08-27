package com.locogo.astockguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val SERVICE_CHANNEL = "market_monitor"
    const val ALERT_CHANNEL = "trade_alerts"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "行情监控", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "风险提醒", NotificationManager.IMPORTANCE_HIGH))
    }

    fun serviceNotification(context: Context, text: String) = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("股衡实时监控运行中")
        .setContentText(text)
        .setOngoing(true)
        .build()

    fun alert(context: Context, id: Int, title: String, text: String) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
