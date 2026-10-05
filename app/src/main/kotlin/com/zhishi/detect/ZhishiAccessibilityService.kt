package com.zhishi.detect

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.zhishi.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍前台检测 provider（ADR 0001 默认模式）。
 * 只消费 TYPE_WINDOW_STATE_CHANGED 事件里的包名（canRetrieveWindowContent=false），
 * 通过 [ForegroundDetector] 契约喂给 [ForegroundMonitor]。
 */
class ZhishiAccessibilityService : AccessibilityService(), ForegroundDetector {

    private var listener: ForegroundListener? = null

    override fun start(listener: ForegroundListener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bound.value = true
        instance = this
        AppGraph.foregroundMonitor.attach(this)
        // 服务被系统拉起（含开机自恢复）即启动检测闭环
        AppGraph.coordinator.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        listener?.onForegroundChanged(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        bound.value = false
        instance = null
        AppGraph.foregroundMonitor.detach()
        AppGraph.coordinator.stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        bound.value = false
        instance = null
    }

    companion object {
        private val bound = MutableStateFlow(false)

        /** 无障碍是否已连接（系统已绑定本服务）。 */
        val isBound: StateFlow<Boolean> = bound.asStateFlow()

        private var instance: ZhishiAccessibilityService? = null

        /** ExitAction 判定用全局动作还是桌面 intent。 */
        val isConnected: Boolean get() = instance != null

        /** 执行全局回桌面动作；服务未连接时返回 false。 */
        fun performGlobalHome(): Boolean =
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true

        /** 读取系统安全设置判断本服务是否已启用（MainActivity 引导页用）。 */
        fun isServiceEnabled(context: Context): Boolean {
            val resolver = context.contentResolver
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val cn = ComponentName(context, ZhishiAccessibilityService::class.java)
            val flat = cn.flattenToString()
            val short = cn.flattenToShortString()
            return enabled.split(':').any { it.equals(flat, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        }
    }
}
