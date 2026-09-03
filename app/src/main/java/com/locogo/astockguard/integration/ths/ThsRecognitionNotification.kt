package com.locogo.astockguard.integration.ths

/*
 * 文件职责：显示只读识别服务是否运行及最近诊断；通知内容不得泄漏完整页面文本和账户敏感信息。
 * 架构边界：集成层只读取用户授权的数据，不保存整页原文，不执行真实交易。
 * 风险说明：本应用只提供交易研究和决策辅助，不保证收益，也不会自动提交真实账户委托。
 */

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 同花顺识别通知栏卡片：只展示状态并提供手动扫描入口，不执行任何交易动作。 */
object ThsRecognitionNotification {
    const val CHANNEL_ID = "ths_recognition_status"
    const val NOTIFICATION_ID = 1102

    fun show(context: Context, status: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "同花顺识别状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示持仓和成交识别状态，并提供立即识别按钮"
                setShowBadge(false)
            }
        )
        val scanIntent = Intent(context, ThsScanActionReceiver::class.java).setAction(ThsScanActionReceiver.ACTION_SCAN)
        val scanAction = PendingIntent.getBroadcast(
            context,
            0,
            scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detailIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, ThsTradeSyncActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val updatedAt = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("股衡 · 同花顺自动识别")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n更新时间：$updatedAt"))
            .setContentIntent(detailIntent)
            .addAction(android.R.drawable.ic_popup_sync, "立即识别", scanAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** 通知按钮使用显式应用内广播，避免尝试直接启动系统绑定的 AccessibilityService。 */
class ThsScanActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SCAN) ThsScanCommandBus.requestScan()
    }

    companion object {
        const val ACTION_SCAN = "com.locogo.astockguard.action.THS_SCAN_NOW"
    }
}

/** 进程内手动扫描命令总线；辅助服务存活时负责消费。 */
object ThsScanCommandBus {
    private val mutableRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests = mutableRequests.asSharedFlow()

    fun requestScan() {
        mutableRequests.tryEmit(Unit)
    }
}
