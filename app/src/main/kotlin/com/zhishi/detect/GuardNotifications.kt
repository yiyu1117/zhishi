package com.zhishi.detect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zhishi.MainActivity
import com.zhishi.guard.NotificationUsage

/**
 * 守护通知的公共构件（v1.2）：通道、通知构建、剩余文案在守护前台服务与常驻通知服务间共用。
 */
internal object GuardNotifications {

    const val CHANNEL_ID = "zhishi_guard"
    const val CHANNEL_NAME = "知时守护中"

    /** 轮询守护服务的通知 id（常驻通知服务用 [NOTIFICATION_ID_USAGE]，避免互相覆盖）。 */
    const val NOTIFICATION_ID_GUARD = 1001
    const val NOTIFICATION_ID_USAGE = 1002

    /** 通用守护文案（无会话/设置关闭时的回落）。 */
    const val CONTENT_TEXT_GUARDING = "知时守护运行中"

    fun ensureChannel(context: Context, description: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                this.description = description
                setShowBadge(false)
            },
        )
    }

    fun buildNotification(context: Context, text: String, notificationId: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(CHANNEL_NAME)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 剩余时长文案：「正在使用 {应用}，剩余 N 分钟」；[usage] 为 null 回落通用守护文案。 */
    fun usageText(usage: NotificationUsage?): String =
        usage?.let { "正在使用 ${it.appName}，剩余 ${minutesCeil(it.remainingMillis)} 分钟" }
            ?: CONTENT_TEXT_GUARDING

    /** 毫秒向上取整到分钟（通知剩余时长的展示粒度，分钟值变化才更新通知）。 */
    fun minutesCeil(millis: Long): Long = (millis + 59_999L) / 60_000L
}
