package com.zhishi.detect

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.zhishi.AppGraph
import com.zhishi.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍前台检测 provider（ADR 0001 默认模式）。
 * 消费 TYPE_WINDOW_STATE_CHANGED 事件里的包名作为焦点前台（不读窗口内容，仅取根节点包名），
 * 并扫描可见窗口集合喂给 [ForegroundMonitor]——分屏/自由窗口中被管理应用未获焦也据此拦截
 * （ADR 0003）。通过 [ForegroundDetector] 契约喂给 [ForegroundMonitor]。
 *
 * 焦点校验（v1.1.1）：事件包名必须等于当前焦点窗口的根包名才认作前台切换。
 * 背景应用的窗口事件（任务重排、SDK 弹窗、退后台后的播放恢复等）不代表前台切换，
 * 不校验会把"人在桌面"误判成"应用回前台"→ 从闲置态重入弹出浮层。窗口扫描
 * （[flagRetrieveInteractiveWindows]）不可用时退回旧行为，事件照报。
 */
open class ZhishiAccessibilityService : AccessibilityService(), ForegroundDetector {

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
        AppLog.d(TAG, "窗口事件 pkg=$pkg")
        val scan = scanWindows()
        if (scan != null) listener?.onVisiblePackagesChanged(scan.visiblePackages)
        val focused = scan?.focusedPackage
        if (focused != null && focused != pkg) {
            AppLog.d(TAG, "丢弃事件 pkg=$pkg（焦点在 $focused）")
            return
        }
        listener?.onForegroundChanged(pkg)
    }

    /**
     * 单次窗口扫描：汇总屏幕上全部窗口的归属包名，并取焦点窗口的包名。
     * 需 flagRetrieveInteractiveWindows；标志未生效（旧绑定）时 windows 为空列表，
     * 返回 null（等价于不提供可见窗口与焦点信号，事件退回旧行为照报）。
     */
    private fun scanWindows(): WindowScan? {
        val windowList = windows ?: return null
        if (windowList.isEmpty()) return null
        val packages = HashSet<String>(windowList.size)
        var focusedPackage: String? = null
        for (window in windowList) {
            try {
                val root = window.root
                val pkg = root?.packageName?.toString()
                pkg?.let(packages::add)
                if (window.isFocused && focusedPackage == null) focusedPackage = pkg
                @Suppress("DEPRECATION") // API 26–32 必须回收节点，33+ 为空操作
                root?.recycle()
            } catch (_: Exception) {
                // 窗口可能正被系统回收
            } finally {
                @Suppress("DEPRECATION") // API 26–32 必须回收窗口，33+ 为空操作
                window.recycle()
            }
        }
        return WindowScan(packages, focusedPackage)
    }

    /** 单次窗口扫描结果：可见窗口包名集合 + 焦点窗口的根包名（可能为 null：无窗口持焦）。 */
    private data class WindowScan(val visiblePackages: Set<String>, val focusedPackage: String?)

    /**
     * 立即复扫窗口（v1.2，退后台挂起确认的兜底证据）：不依赖新事件到来。
     * 可见窗口集合照常推给监视器，保持协调器"可见即前台"视角同步刷新。
     */
    override fun rescan(): WindowScanResult? {
        val scan = scanWindows() ?: return null
        listener?.onVisiblePackagesChanged(scan.visiblePackages)
        return WindowScanResult(scan.visiblePackages, scan.focusedPackage)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        bound.value = false
        instance = null
        // 仅当无障碍 provider 仍是监视器属主时才拆线（模式可能已切到轮询，不得误拆）。
        // 协调器不在此停止：其生命周期与进程一致，模式切换由协调器统一调度。
        if (AppGraph.foregroundMonitor.currentDetector() === this) {
            AppGraph.foregroundMonitor.detach()
            AppGraph.foregroundMonitor.reset()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        bound.value = false
        instance = null
    }

    companion object {
        /** 全链路诊断日志统一 TAG（事件 → 监视器 → 状态机 → 浮层）。 */
        const val TAG = "ZhishiGuard"

        private val bound = MutableStateFlow(false)

        /** 无障碍是否已连接（系统已绑定本服务）。 */
        val isBound: StateFlow<Boolean> = bound.asStateFlow()

        private var instance: ZhishiAccessibilityService? = null

        /** ExitAction 判定用全局动作还是桌面 intent。 */
        val isConnected: Boolean get() = instance != null

        /** 已连接的服务实例（协调器在轮询切回无障碍模式时重新 attach 用）。 */
        fun boundInstance(): ZhishiAccessibilityService? = instance

        /** 执行全局回桌面动作；服务未连接时返回 false。 */
        fun performGlobalHome(): Boolean =
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true

        /** 读取系统安全设置判断本服务是否已启用（本服务或 HyperOS 兼容别名任一即可，MainActivity 引导页用）。 */
        fun isServiceEnabled(context: Context): Boolean {
            val resolver = context.contentResolver
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val components = listOf(
                ComponentName(context, ZhishiAccessibilityService::class.java),
                ComponentName(context, SelectToSpeakService::class.java),
            )
            return enabled.split(':').any { entry ->
                components.any { cn ->
                    entry.equals(cn.flattenToString(), ignoreCase = true) ||
                        entry.equals(cn.flattenToShortString(), ignoreCase = true)
                }
            }
        }
    }
}
