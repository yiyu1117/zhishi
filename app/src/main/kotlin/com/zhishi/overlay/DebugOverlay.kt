package com.zhishi.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.zhishi.core.machine.GuardState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil

/** 调试悬浮窗的单机条目：状态机实时状态与活动倒计时。 */
data class MachineDebugEntry(
    val appName: String,
    val state: GuardState,
    /** 活动倒计时剩余（会话/宽限/延时/冷却，随状态语义）；null = 无。 */
    val remainingMillis: Long?,
    val usedTodayMillis: Long,
)

/**
 * 调试小悬浮窗（设置页「调试模式」开关控制显隐）：
 * 列出全部状态机的实时状态与倒计时（支持多应用同时倒计时），用于排查拦截/计时问题。
 * 窗口不可获焦且不可触摸（FLAG_NOT_TOUCHABLE），完全不挡操作；仅诊断用，读不到任何输入。
 */
class DebugOverlayController(
    private val appContext: Context,
    private val states: StateFlow<List<MachineDebugEntry>>,
) {
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    /** 显示/隐藏调试小窗（重复调用幂等）。 */
    fun setVisible(visible: Boolean) {
        if (visible) attach() else detach()
    }

    private fun attach() {
        if (composeView != null) return
        if (!Settings.canDrawOverlays(appContext)) return
        val owner = OverlayLifecycleOwner().apply { performCreate() }
        val view = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { DebugOverlayContent(states) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "debug overlay addView failed", e)
            owner.destroy()
            return
        }
        composeView = view
        this.owner = owner
        owner.resume()
    }

    private fun detach() {
        val view = composeView ?: return
        val owner = this.owner
        composeView = null
        this.owner = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            // 视图可能已被窗口侧移除
        }
        owner?.destroy()
    }

    private companion object {
        const val TAG = "ZhishiGuard"
    }
}

private val DebugScrim = Color(0xCC101418)
private val DebugOn = Color(0xFFE6EDF3)
private val DebugOnDim = Color(0xB3E6EDF3)

@Composable
private fun DebugOverlayContent(states: StateFlow<List<MachineDebugEntry>>) {
    val list by states.collectAsState()
    Column(
        Modifier
            .background(DebugScrim, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("知时调试", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DebugOn)
        if (list.isEmpty()) {
            Text("无状态机", fontSize = 11.sp, color = DebugOnDim)
        }
        list.forEach { entry ->
            Text(
                "${entry.appName} · ${stateLabel(entry.state)}${remainingSuffix(entry.remainingMillis)}",
                fontSize = 11.sp,
                color = DebugOnDim,
            )
        }
    }
}

private fun stateLabel(state: GuardState): String = when (state) {
    GuardState.INACTIVE -> "闲置"
    GuardState.AWAITING_SELECTION -> "待选择"
    GuardState.RUNNING -> "会话中"
    GuardState.GRACE -> "宽限期"
    GuardState.TIME_UP -> "时间已到"
    GuardState.EXTENSION_RUNNING -> "延时中"
    GuardState.EXPIRY_COOLDOWN -> "到期冷却"
    GuardState.ABANDON_COOLDOWN -> "放弃冷却"
    GuardState.QUOTA_EXHAUSTED -> "额度用尽"
    GuardState.BLACKLISTED -> "禁止使用"
    GuardState.DIRECT_RUNNING -> "直接计时"
}

/** 倒计时后缀：" 4:32"；无倒计时为空串。 */
private fun remainingSuffix(millis: Long?): String {
    if (millis == null) return ""
    val totalSeconds = ceil(millis.coerceAtLeast(0L) / 1000.0).toInt()
    return " ${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
