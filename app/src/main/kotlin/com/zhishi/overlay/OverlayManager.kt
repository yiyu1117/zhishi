package com.zhishi.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.ComposeView
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.zhishi.core.machine.OverlaySpec
import com.zhishi.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 拦截浮层管理器：TYPE_APPLICATION_OVERLAY 全屏悬浮窗承载 ComposeView。
 * 同一时刻只显示一个浮层（最新守护前台应用）；FLAG_NOT_FOCUSABLE 保证系统手势仍可用，
 * 用户永远能回到桌面 —— 防锁死底线。
 *
 * @param interactions 浮层按钮交互回调（快选/确定/延时），由协调器接线；
 *   默认空实现保证不接线也编译通过、按钮点击静默忽略。
 */
class OverlayManager(
    private val appContext: Context,
    private val interactions: OverlayInteractions = OverlayInteractions.NONE,
) {
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val content = MutableStateFlow<OverlayContent?>(null)

    /**
     * 「↻ 刷新」自检按钮可用性：逃生门只对轮询模式有意义——无障碍模式下事件源与前台
     * 判定本就一致，且 UsageStats 落账有约 1 秒延迟，刚打开应用时点刷新会误判关闭浮层，
     * 反成绕过口子。由协调器随检测模式设置；默认关闭。
     */
    private val refreshEnabled = MutableStateFlow(false)

    private var currentPackage: String? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    /** 浮层可见性回调：(包名, 是否可见)。窗口 attach 成功 / detach 时触发，协调器据此接线音频静音。 */
    var onVisibilityChanged: ((packageName: String, visible: Boolean) -> Unit)? = null

    /** 设置「↻ 刷新」按钮可用性（协调器随检测模式切换调用）。 */
    fun setRefreshEnabled(enabled: Boolean) {
        refreshEnabled.value = enabled
    }

    /**
     * 浮层出现纪元：每次实际隐藏后递增。作为浮层内容身份键的一部分，
     * 让按钮保护计时与"浮层出现"绑定 —— 同一形态持续刷新不重置，隐藏重现则重新计时。
     */
    private var appearanceEpoch = 0L

    /** 状态机路径：显示/刷新浮层。 */
    fun show(packageName: String, appName: String, spec: OverlaySpec, remainingMillis: Long? = null) {
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "overlay blocked (no SYSTEM_ALERT_WINDOW) pkg=$packageName")
            return
        }
        currentPackage = packageName
        Log.d(TAG, "overlay show pkg=$packageName spec=${spec::class.simpleName}")
        AppLog.d(TAG, "浮层显示 pkg=$packageName 形态=${spec::class.simpleName}")
        content.value = OverlayContent.Machine(packageName, appName, spec, remainingMillis, appearanceEpoch)
        attachIfNeeded()
    }

    /** 关闭浮层；指定包名时仅当当前浮层属于该包才关闭。 */
    fun hide(packageName: String? = null) {
        if (packageName != null && currentPackage != packageName) return
        val detachedPkg = currentPackage
        currentPackage = null
        content.value = null
        appearanceEpoch++ // 隐藏重现须重新计时（保护计时绑定"浮层出现"）
        if (detach() && detachedPkg != null) {
            AppLog.d(TAG, "浮层隐藏 pkg=$detachedPkg")
            onVisibilityChanged?.invoke(detachedPkg, false)
        }
    }

    fun hideAll() = hide(null)

    /**
     * 快照同步：浮层内容以状态机快照收敛（效果路径之外的兜底，含冷却倒计时刷新）。
     * 只允许当前正在显示的包刷新或隐藏自己，不会抢占别的浮层。
     */
    fun syncFromMachine(packageName: String, appName: String, spec: OverlaySpec?, remainingMillis: Long?) {
        val showing = currentPackage
        if (spec == null) {
            if (showing == packageName) hide(packageName)
            return
        }
        if (showing != null && showing != packageName) return
        show(packageName, appName, spec, remainingMillis)
    }

    private fun attachIfNeeded() {
        if (composeView != null) return
        if (!Settings.canDrawOverlays(appContext)) return

        val owner = OverlayLifecycleOwner().apply { performCreate() }
        val view = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    OverlayRoot(content, refreshEnabled, interactions)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // 权限可能刚被撤销等窗口级异常：不致命，放弃本次显示，但留诊断日志
            Log.w(TAG, "overlay addView failed pkg=$currentPackage", e)
            owner.destroy()
            return
        }
        composeView = view
        lifecycleOwner = owner
        owner.resume() // attach 后推进到 RESUMED
        currentPackage?.let { pkg -> onVisibilityChanged?.invoke(pkg, true) }
    }

    /** detach 已挂载的浮层视图；返回是否确实移除了窗口（调用方据此发可见性回调）。 */
    private fun detach(): Boolean {
        val view = composeView ?: return false
        val owner = lifecycleOwner
        composeView = null
        lifecycleOwner = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            // 视图可能已被窗口侧移除
        }
        owner?.destroy() // detach 后销毁，下次显示重建全新宿主与组合
        return true
    }

    private companion object {
        const val TAG = "ZhishiGuard"
    }
}
