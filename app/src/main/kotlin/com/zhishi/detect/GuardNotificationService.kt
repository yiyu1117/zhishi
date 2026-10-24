package com.zhishi.detect

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.zhishi.AppGraph
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 常驻通知服务（v1.2）：「常驻通知显示剩余时长」设置开启时承载通知，两种检测模式通用。
 *
 * 无障碍模式本无常驻服务/通知，开启该设置后由此服务补一条轻量前台通知（附带保活收益）；
 * 轮询模式的守护服务自带通知（同通道），本服务通常不与之并存（用户只会处于一种模式）。
 * 内容随守护状态刷新：当前前台应用处于会话/延时中时展示剩余时长（分钟粒度，值变化才
 * notify），否则回落到默认守护文案。关闭设置由协调器 stopService（本服务无需自停逻辑）。
 */
class GuardNotificationService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        GuardNotifications.ensureChannel(this, "常驻通知显示剩余使用时长（可选开启）")
        startForegroundCompat(
            GuardNotifications.buildNotification(this, GuardNotifications.CONTENT_TEXT_GUARDING, NOTIFICATION_ID),
        )
        lifecycleScope.launch {
            var lastText: String? = null
            // 剩余展示受设置控制（防竞态：服务由该设置拉起，但收集时再确认一次）
            combine(
                AppGraph.coordinator.notificationUsage,
                AppGraph.settingsRepository.notificationUsageVisible,
            ) { usage, visible -> if (visible) GuardNotifications.usageText(usage) else null }
                .collect { text ->
                    val next = text ?: GuardNotifications.CONTENT_TEXT_GUARDING
                    if (next == lastText) return@collect
                    lastText = next
                    getSystemService(android.app.NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, GuardNotifications.buildNotification(this@GuardNotificationService, next, NOTIFICATION_ID))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 无守护动作，仅承载通知；START_STICKY 让被杀后随设置仍然开启时自动恢复
        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = GuardNotifications.NOTIFICATION_ID_USAGE
    }
}
