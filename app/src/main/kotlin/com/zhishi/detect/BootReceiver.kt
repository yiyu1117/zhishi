package com.zhishi.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zhishi.core.model.DetectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自启接收器（ticket 09 兼容模式）。
 *
 * BOOT_COMPLETED（系统保护广播，仅系统可发送）后异步判定：
 * (a) 使用情况访问已授予 且 (b) 当前检测模式为 POLLING
 * → startForegroundService 拉起 [PollingForegroundService]。
 *
 * 检测模式经 [DetectionModeSourceProvider] 读取（fail-safe 返回 ACCESSIBILITY，
 * 即未绑定设置源时绝不误拉起轮询服务）。
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val result = goAsync() // 判定含 AppOps/设置读取，转异步避免阻塞主线程
        scope.launch {
            try {
                val usageAccessGranted = PollingForegroundDetector.hasUsageAccess(appContext)
                val modeIsPolling =
                    DetectionModeSourceProvider.currentModeBlocking() == DetectionMode.POLLING
                if (usageAccessGranted && modeIsPolling) {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, PollingForegroundService::class.java),
                    )
                }
            } finally {
                result.finish() // 必须归还 goAsync 结果，否则 ANR
            }
        }
    }
}
