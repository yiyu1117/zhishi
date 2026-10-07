package com.zhishi.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.zhishi.core.machine.GuardState
import com.zhishi.log.AppLog
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil

/** 调试悬浮窗的单机条目：状态机实时状态与活动倒计时。 */
data class MachineDebugEntry(
    /** 包名：调试注入按包名定位状态机（应用名仅展示用）。 */
    val packageName: String,
    val appName: String,
    val state: GuardState,
    /** 活动倒计时剩余（会话/宽限/延时/冷却，随状态语义）；null = 无。 */
    val remainingMillis: Long?,
    val usedTodayMillis: Long,
)

/**
 * 调试用小悬浮窗宿主（TYPE_APPLICATION_OVERLAY）：
 * - [touchable] = false：不可触摸（状态悬浮窗），完全不挡操作；
 * - [touchable] = true：可点按（调试工具窗），仍不可获焦，系统手势不受影响；
 * - [reorderToFront]：拦截浮层同为 APPLICATION_OVERLAY、按添加顺序叠放，浮层弹出时
 *   由协调器调用本方法（移除后重加）把调试窗顶回最前，避免被拦截浮层盖住。
 */
class DebugOverlayWindow(
    private val appContext: Context,
    private val gravity: Int,
    private val x: Int,
    private val y: Int,
    private val touchable: Boolean,
    private val content: @Composable () -> Unit,
) {
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null

    /** 显示/隐藏调试小窗（重复调用幂等）。 */
    fun setVisible(visible: Boolean) {
        if (visible) attach() else detach()
    }

    /** 若已显示则重新挂载，把窗口顶回同类型窗口的最上层。 */
    fun reorderToFront() {
        val view = composeView ?: return
        val p = params ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            return
        }
        try {
            windowManager.addView(view, p)
        } catch (e: Exception) {
            Log.w(TAG, "debug overlay re-add failed", e)
        }
    }

    private fun attach() {
        if (composeView != null) return
        if (!Settings.canDrawOverlays(appContext)) return
        val owner = OverlayLifecycleOwner().apply { performCreate() }
        val view = ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { content() }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = this@DebugOverlayWindow.gravity
            this.x = this@DebugOverlayWindow.x
            this.y = this@DebugOverlayWindow.y
        }
        try {
            windowManager.addView(view, p)
        } catch (e: Exception) {
            Log.w(TAG, "debug overlay addView failed", e)
            owner.destroy()
            return
        }
        composeView = view
        params = p
        this.owner = owner
        owner.resume()
    }

    private fun detach() {
        val view = composeView ?: return
        val owner = this.owner
        composeView = null
        params = null
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

// ---------------------------------------------------------------------------
// 状态悬浮窗（只读）
// ---------------------------------------------------------------------------

@Composable
fun DebugStatesPanel(states: StateFlow<List<MachineDebugEntry>>) {
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

// ---------------------------------------------------------------------------
// 调试工具悬浮窗（可点按：重置 / 注入状态 / 日志）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugToolsPanel(
    appContext: Context,
    states: StateFlow<List<MachineDebugEntry>>,
    onResetAll: () -> Unit,
    onInject: (packageName: String, state: GuardState, remainingMillis: Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun noteAndClear(text: String) {
        note = text
    }

    if (!expanded) {
        Text(
            "🛠 调试工具",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = DebugOn,
            modifier = Modifier
                .background(DebugScrim, RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }

    Column(
        Modifier
            .width(300.dp)
            .background(DebugScrim, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("调试工具", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DebugOn, modifier = Modifier.weight(1f))
            Text("✕", fontSize = 13.sp, color = DebugOnDim, modifier = Modifier.clickable { expanded = false })
        }
        Spacer(Modifier.height(8.dp))

        if (showLogs) {
            LogViewer()
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val cm = appContext.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("zhishi-log", AppLog.asText()))
                    noteAndClear("日志已复制到剪贴板")
                }) { Text("复制全部", fontSize = 12.sp) }
                TextButton(onClick = { AppLog.clear(); noteAndClear("日志已清空") }) { Text("清空日志", fontSize = 12.sp) }
                TextButton(onClick = { showLogs = false }) { Text("返回菜单", fontSize = 12.sp) }
            }
        } else {
            Button(onClick = {
                onResetAll()
                noteAndClear("已重置所有计时（会话/冷却/当日累计）")
            }, modifier = Modifier.fillMaxWidth()) {
                Text("重置所有计时", fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("重置 = 清空会话/冷却/宽限与当日累计，回到待选择。", fontSize = 10.sp, color = DebugOnDim)

            Spacer(Modifier.height(10.dp))
            Text("按应用注入状态", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DebugOn)
            val list by states.collectAsState()
            if (list.isEmpty()) Text("（无状态机）", fontSize = 11.sp, color = DebugOnDim)
            list.forEach { entry ->
                Spacer(Modifier.height(6.dp))
                Text("${entry.appName} · ${stateLabel(entry.state)}", fontSize = 11.sp, color = DebugOnDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 按包名注入（协调器以包名定位状态机；应用名仅用于展示）
                    InjectChip("会话5分") { onInject(entry.packageName, GuardState.RUNNING, 5 * 60_000L) }
                    InjectChip("会话1分") { onInject(entry.packageName, GuardState.RUNNING, 60_000L) }
                    InjectChip("延时1分") { onInject(entry.packageName, GuardState.EXTENSION_RUNNING, 60_000L) }
                    InjectChip("宽限30秒") { onInject(entry.packageName, GuardState.GRACE, 30_000L) }
                    InjectChip("到期冷却5分") { onInject(entry.packageName, GuardState.EXPIRY_COOLDOWN, 5 * 60_000L) }
                    InjectChip("放弃冷却5分") { onInject(entry.packageName, GuardState.ABANDON_COOLDOWN, 5 * 60_000L) }
                    InjectChip("试探中") { onInject(entry.packageName, GuardState.TRIAL_RUNNING, null) }
                    InjectChip("待选择") { onInject(entry.packageName, GuardState.AWAITING_SELECTION, null) }
                    InjectChip("清除") { onInject(entry.packageName, GuardState.INACTIVE, null) }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.fillMaxWidth()) {
                Text("查看日志（最近 ${AppLog.entries.value.size} 条）", fontSize = 12.sp)
            }
        }

        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 11.sp, color = DebugOn)
        }
    }
}

@Composable
private fun InjectChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = DebugOn,
        modifier = Modifier
            .background(Color(0x33E6EDF3), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun LogViewer() {
    val entries by AppLog.entries.collectAsState()
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .background(Color(0x66000000), RoundedCornerShape(8.dp)),
    ) {
        items(entries.asReversed()) { entry ->
            Text(
                "${FORMATTER_PREVIEW.format(entry.timeMillis)} ${entry.level}/${entry.tag}: ${entry.message}",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = DebugOnDim,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

private val FORMATTER_PREVIEW = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

// ---------------------------------------------------------------------------
// 公共
// ---------------------------------------------------------------------------

private val DebugScrim = Color(0xCC101418)
private val DebugOn = Color(0xFFE6EDF3)
private val DebugOnDim = Color(0xB3E6EDF3)

fun stateLabel(state: GuardState): String = when (state) {
    GuardState.INACTIVE -> "闲置"
    GuardState.AWAITING_SELECTION -> "待选择"
    GuardState.RUNNING -> "会话中"
    GuardState.GRACE -> "宽限期"
    GuardState.TIME_UP -> "时间已到（旧）"
    GuardState.EXTENSION_RUNNING -> "延时中"
    GuardState.EXPIRY_COOLDOWN -> "到期冷却"
    GuardState.ABANDON_COOLDOWN -> "放弃冷却"
    GuardState.QUOTA_EXHAUSTED -> "额度用尽"
    GuardState.BLACKLISTED -> "禁止使用"
    GuardState.DIRECT_RUNNING -> "直接计时"
    GuardState.TRIAL_RUNNING -> "试探中"
}

private fun remainingSuffix(millis: Long?): String {
    if (millis == null) return ""
    val totalSeconds = ceil(millis.coerceAtLeast(0L) / 1000.0).toInt()
    return " ${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
