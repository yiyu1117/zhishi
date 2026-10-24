package com.zhishi.detect

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.zhishi.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 兼容模式（UsageStats 轮询）前台守护服务（ticket 09）。
 *
 * 职责：被启动后建立常驻通知保活、运行 [PollingForegroundDetector] 并把事件喂给
 * [com.zhishi.detect.ForegroundMonitor]（经 [AppGraph] 获取），同时拉起协调器；
 * START_STICKY 保证被杀后自动重启。模式切换（本服务与无障碍 provider 的 attach/切换）
 * 由主控在协调器完成 —— 切换时须先 stop 本服务再 attach 无障碍 provider（onDestroy 会拆线）。
 *
 * 常驻通知随守护状态刷新：当前前台应用处于会话/延时中时，展示其剩余使用时长
 * （v1.2 起受「常驻通知显示剩余时长」设置控制，默认通用守护文案），值变化才 notify。
 */
class PollingForegroundService : LifecycleService() {

    private var detector: PollingForegroundDetector? = null

    /** 是否已完成 monitor 接线（onDestroy 据此决定是否拆线，避免误拆无障碍 provider）。 */
    private var wired = false

    override fun onCreate() {
        super.onCreate()
        GuardNotifications.ensureChannel(this, "兼容模式（UsageStats 轮询）的常驻守护通知")
        startForegroundCompat(GuardNotifications.buildNotification(this, CONTENT_TEXT_GUARDING, NOTIFICATION_ID))
        lifecycleScope.launch {
            var lastText: String? = null
            // v1.2：剩余时长展示受「常驻通知显示剩余时长」设置控制（默认关——通用守护文案）
            combine(
                AppGraph.coordinator.notificationUsage,
                AppGraph.settingsRepository.notificationUsageVisible,
            ) { usage, visible -> if (visible) usage else null }
                .collect { usage ->
                    val text = usage?.let(GuardNotifications::usageText) ?: CONTENT_TEXT_GUARDING
                    if (text == lastText) return@collect
                    lastText = text
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, GuardNotifications.buildNotification(this@PollingForegroundService, text, NOTIFICATION_ID))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!wired) {
            if (!PollingForegroundDetector.hasUsageAccess(this)) {
                // 运行时发现无使用情况访问权限：更新通知内容提示后自停，
                // 等待用户在引导卡授权后（主控/用户重新拉起）再恢复守护。
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, GuardNotifications.buildNotification(this, CONTENT_TEXT_NO_PERMISSION, NOTIFICATION_ID))
                lifecycleScope.launch {
                    delay(STOP_DELAY_MILLIS) // 留出让通知浮出的时间
                    stopSelf()
                }
                return START_STICKY
            }
            val d = PollingForegroundDetector(applicationContext)
            detector = d
            val monitor = AppGraph.foregroundMonitor
            monitor.start() // 幂等：协调器可能已启动过
            monitor.attach(d)
            wired = true
            AppGraph.coordinator.start() // 幂等：无障碍路径可能已启动过
        }
        return START_STICKY // 被杀后系统自动重启服务
    }

    override fun onDestroy() {
        val d = detector
        detector = null
        d?.stop()
        if (wired) {
            wired = false
            // 仅当监视器属主仍是本服务的轮询实现时才拆线。模式切到无障碍时协调器会立即
            // attach 回无障碍 provider，而 stopService 的 onDestroy 异步晚于该 attach，
            // 无条件拆线会把刚接好的无障碍 provider 拆掉，无障碍模式从此收不到前台事件。
            if (AppGraph.foregroundMonitor.currentDetector() === d) {
                AppGraph.foregroundMonitor.detach()
            }
        }
        super.onDestroy()
    }

    // region 通知

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // 2 参重载在 API 29+ 等价于使用 manifest 声明的 foregroundServiceType
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // endregion

    private companion object {
        const val NOTIFICATION_ID = GuardNotifications.NOTIFICATION_ID_GUARD
        const val STOP_DELAY_MILLIS = 3_000L
        const val CONTENT_TEXT_GUARDING = "正在按秒轮询前台应用，守护使用时长"
        const val CONTENT_TEXT_NO_PERMISSION = "缺少「使用情况访问」权限，守护已暂停，请到引导页授权"
    }
}
