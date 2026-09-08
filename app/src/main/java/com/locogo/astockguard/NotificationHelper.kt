package com.locogo.astockguard

/*
 * 文件职责：创建通知渠道并发布风险、信号和做 T 提醒；调用者负责实时性与去重，本类只处理 Android 通知协议。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.locogo.astockguard.domain.review.MonitorMessagePolicy
import com.locogo.astockguard.ui.messages.MessageCenterActivity

object NotificationHelper {
    const val SERVICE_CHANNEL = "market_monitor"
    const val ALERT_CHANNEL = "trade_alerts"
    const val T_CHANNEL = "dynamic_t_alerts"
    const val VOLUME_CHANNEL = "volume_radar_alerts"
    const val SECTOR_CHANNEL = "sector_flow_alerts"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "行情监控", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "风险提醒", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(T_CHANNEL, "做T提醒", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(VOLUME_CHANNEL, "量能提醒", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(SECTOR_CHANNEL, "板块资金", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun serviceNotification(context: Context, text: String) = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("股衡实时监控运行中")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(messageIntent(context, ""))
        .build()

    /** tag 按证券和类别隔离，量能更新不会覆盖做T；API接受不代表用户已阅读。 */
    fun message(context: Context, type: String, code: String, title: String, text: String): Boolean {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = when (type) { "DYNAMIC_T" -> T_CHANNEL; "VOLUME_RADAR" -> VOLUME_CHANNEL; else -> SECTOR_CHANNEL }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return try {
            manager.notify(MonitorMessagePolicy.notificationTag(type, code), 1, NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(messageIntent(context, type))
                .setAutoCancel(true).build())
            true
        } catch (_: SecurityException) { false }
    }

    private fun messageIntent(context: Context, type: String): PendingIntent = PendingIntent.getActivity(
        context, type.hashCode(), Intent(context, MessageCenterActivity::class.java)
            .putExtra("message_type", type).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

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
