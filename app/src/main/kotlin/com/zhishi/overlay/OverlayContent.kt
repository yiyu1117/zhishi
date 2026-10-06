package com.zhishi.overlay

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhishi.core.machine.OverlaySpec
import com.zhishi.core.model.CooldownKind
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** 浮层背景：近乎不透明的深色，保证盖住底层应用内容。 */
private val OverlayScrim = Color(0xF5101418)
private val OnOverlay = Color(0xFFE6EDF3)
private val OnOverlayDim = OnOverlay.copy(alpha = 0.65f)
private val OnOverlayFaint = OnOverlay.copy(alpha = 0.45f)
private val WarnAmber = Color(0xFFFFC66D)

/** 浮层内容模型：同一时刻只有一个浮层（最新守护前台应用）。 */
sealed interface OverlayContent {
    /**
     * 状态机驱动的浮层（[OverlaySpec] 五形态）。
     * @param appearanceEpoch 浮层出现纪元（每次实际隐藏后递增，见 [OverlayManager]）：
     *   与包名、形态身份共同构成按钮保护计时键 —— 同一形态持续刷新不重置保护，隐藏重现则重新计时。
     */
    data class Machine(
        val packageName: String,
        val appName: String,
        val spec: OverlaySpec,
        val remainingMillis: Long? = null,
        val appearanceEpoch: Long = 0L,
    ) : OverlayContent
}

@Composable
internal fun OverlayRoot(
    contentState: StateFlow<OverlayContent?>,
    interactions: OverlayInteractions,
) {
    val content by contentState.collectAsState()
    Box(Modifier.fillMaxSize().background(OverlayScrim)) {
        when (val c = content) {
            null -> Unit
            is OverlayContent.Machine -> MachineOverlay(c, interactions)
        }
        (content as? OverlayContent.Machine)?.let { c ->
            OverlayChrome(c, interactions, Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * 浮层右上角工具行（所有形态通用）：
 * - ↻ 刷新：前台自检逃生门——检测当前真实前台，若并非本浮层对应应用则立即关闭
 *   （ROM 异常导致的残留浮层自救），不受按钮保护计时约束；确认仍在前台时在浮层上
 *   内联提示（Toast 会被 TYPE_APPLICATION_OVERLAY 盖住，不可用）；
 * - ? 说明：在浮层上内联展开用途说明（悬浮窗内弹 Dialog 会创建对话框窗口导致崩溃）。
 */
@Composable
private fun OverlayChrome(
    content: OverlayContent.Machine,
    interactions: OverlayInteractions,
    modifier: Modifier = Modifier,
) {
    var showHelp by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }

    Column(modifier.padding(top = 4.dp, end = 4.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val kept = interactions.onRefreshRequested(content.packageName)
                if (kept) refreshNote = "已确认仍在 ${content.appName} 前台，浮层保留"
            }) {
                Text("↻", fontSize = 20.sp, color = OnOverlayFaint)
            }
            TextButton(onClick = { showHelp = !showHelp }) {
                Text("?", fontSize = 18.sp, color = OnOverlayFaint)
            }
        }
        refreshNote?.let { note ->
            Text(
                note,
                fontSize = 13.sp,
                color = OnOverlayDim,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(end = 8.dp),
            )
            LaunchedEffect(note) {
                delay(3000)
                refreshNote = null
            }
        }
        if (showHelp) {
            Text(
                "这是「知时」的拦截浮层：打开被管控的应用时，先选择本次使用时长，" +
                    "时间用尽会强制休息并进入冷却。\n\n" +
                    "↻ 刷新：检测当前真实前台应用，若它并不是本浮层对应的应用" +
                    "（误弹或系统异常导致的残留浮层），会立即关闭浮层。",
                fontSize = 13.sp,
                color = OnOverlayDim,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 按钮保护计时（spec 契约第 11 条）
// ---------------------------------------------------------------------------

/** 保护倒计时驱动节奏。 */
private const val PROTECTION_TICK_MILLIS = 200L

/** 快选按钮每行列数（6 档默认排两行）。 */
private const val QUICK_COLUMNS = 3

/**
 * 按钮保护计时：起点绑定"浮层出现"时刻（单调时钟，不受系统时间改动影响）。
 * 由 [rememberFormProtection] 以"包名 + 出现纪元 + 形态身份"为键 remember ——
 * 同一形态持续刷新（如冷却每秒更新 remainingMillis）沿用同一起点不重置；
 * 形态切换或浮层隐藏重现（纪元递增 / 组合重建）时重新计时。
 */
@Stable
private class FormProtection(val startedAtElapsed: Long) {
    // 类本身 file-private，setter 不再收窄可见性以便 rememberFormProtection 推进时钟
    var nowElapsed by mutableLongStateOf(startedAtElapsed)

    /** 指定保护窗口的剩余毫秒；<= 0 表示保护已过、可点击。 */
    fun remainingMillis(protectMillis: Long): Long =
        (protectMillis - (nowElapsed - startedAtElapsed)).coerceAtLeast(0L)
}

/**
 * 取当前形态的保护计时器。[maxProtectMillis] 取该形态所有受保护按钮的最大保护时长，
 * 仅决定内部倒计时循环何时停止，不影响计时起点。
 */
@Composable
private fun rememberFormProtection(formKey: String, maxProtectMillis: Long): FormProtection {
    val protection = remember(formKey) { FormProtection(SystemClock.elapsedRealtime()) }
    LaunchedEffect(formKey, maxProtectMillis) {
        while (true) {
            protection.nowElapsed = SystemClock.elapsedRealtime()
            if (protection.nowElapsed - protection.startedAtElapsed >= maxProtectMillis) break
            delay(PROTECTION_TICK_MILLIS)
        }
    }
    return protection
}

/** 毫秒向上取整到秒（保护/冷却倒计时显示用）。 */
private fun millisToCeilSeconds(millis: Long): Int = ((millis + 999) / 1000).toInt()

/** 受保护按钮：保护期内禁用并在下方显示"N 秒后可点"倒计时，保护过后恢复可点。 */
@Composable
private fun ProtectedButton(
    label: String,
    protection: FormProtection,
    protectMillis: Long,
    onClick: () -> Unit,
) {
    val remaining = protection.remainingMillis(protectMillis)
    val enabled = remaining <= 0L
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onClick, enabled = enabled) {
            Text(label, fontSize = 17.sp)
        }
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            if (!enabled) {
                Text(
                    "${millisToCeilSeconds(remaining)} 秒后可点",
                    fontSize = 13.sp,
                    color = OnOverlayFaint,
                )
            }
        }
    }
}

/** 共用的"N 秒后可点"提示行：可点后收起但保留高度，避免布局跳动。 */
@Composable
private fun CountdownHint(enabled: Boolean, protection: FormProtection, protectMillis: Long) {
    Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
        if (!enabled) {
            Text(
                "${millisToCeilSeconds(protection.remainingMillis(protectMillis))} 秒后可点",
                fontSize = 13.sp,
                color = OnOverlayFaint,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 五形态渲染
// ---------------------------------------------------------------------------

@Composable
private fun MachineOverlay(content: OverlayContent.Machine, interactions: OverlayInteractions) {
    when (val spec = content.spec) {
        is OverlaySpec.TimeSelection -> TimeSelectionOverlay(content, spec, interactions)
        is OverlaySpec.TimeUp -> TimeUpOverlay(content, spec, interactions)
        is OverlaySpec.QuotaExhausted -> QuotaExhaustedOverlay(content, spec, interactions)
        is OverlaySpec.Cooldown -> CooldownOverlay(content, spec, interactions)
        OverlaySpec.Forbidden -> ForbiddenOverlay(content.appName)
    }
}

/** 通用标题区：应用名 + 大字标题。 */
@Composable
private fun OverlayHeader(appName: String, title: String) {
    Text(appName, fontSize = 18.sp, color = OnOverlayDim, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        title,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = OnOverlay,
        textAlign = TextAlign.Center,
    )
}

// ------------------------------- 时间选择 -----------------------------------

@Composable
private fun TimeSelectionOverlay(
    content: OverlayContent.Machine,
    spec: OverlaySpec.TimeSelection,
    interactions: OverlayInteractions,
) {
    val formKey = "${content.packageName}|${content.appearanceEpoch}|TimeSelection"
    val protection = rememberFormProtection(formKey, spec.quickProtectMillis)
    val quickEnabled = protection.remainingMillis(spec.quickProtectMillis) <= 0L
    var keyboardOpen by remember(formKey) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OverlayHeader(content.appName, "选择本次使用时长")
        Spacer(Modifier.height(12.dp))
        TodayRemainingText(spec.todayRemainingMinutes)
        Spacer(Modifier.height(28.dp))

        if (!keyboardOpen) {
            QuickOptionsGrid(spec.quickOptionsMinutes, quickEnabled) { minutes ->
                interactions.onDurationSelected(content.packageName, minutes)
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { keyboardOpen = true }, enabled = quickEnabled) {
                Text("自定义", fontSize = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            CountdownHint(quickEnabled, protection, spec.quickProtectMillis)
        } else {
            CustomKeyboardPanel(
                packageName = content.packageName,
                maxMinutes = spec.customMaxMinutes,
                protection = protection,
                protectMillis = spec.quickProtectMillis,
                onDismiss = { keyboardOpen = false },
                onConfirm = { minutes -> interactions.onDurationSelected(content.packageName, minutes) },
            )
            Spacer(Modifier.height(6.dp))
            CountdownHint(quickEnabled, protection, spec.quickProtectMillis)
        }
    }
}

/** 今日剩余额度：null = 不限额（不显示）；0 = 已用尽；其余显示剩余分钟。 */
@Composable
private fun TodayRemainingText(todayRemainingMinutes: Int?) {
    when (todayRemainingMinutes) {
        null -> Box(Modifier.height(24.dp))
        0 -> Text(
            "今日额度已用尽",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = WarnAmber,
        )
        else -> Text(
            "今日剩余 $todayRemainingMinutes 分钟",
            fontSize = 16.sp,
            color = OnOverlayDim,
        )
    }
}

/** 快选按钮：按 [QUICK_COLUMNS] 列排布，保护期内整体禁用。 */
@Composable
private fun QuickOptionsGrid(
    optionsMinutes: List<Int>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        optionsMinutes.chunked(QUICK_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { minutes ->
                    Button(
                        onClick = { onSelect(minutes) },
                        enabled = enabled,
                        modifier = Modifier.widthIn(min = 96.dp),
                    ) {
                        Text("$minutes 分钟", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/**
 * 自定义时长假数字键盘：0–9、退格、确认、取消，全部自绘按钮。
 * 拦截浮层不可获焦（FLAG_NOT_FOCUSABLE），系统输入法永不弹出 —— 这是"假键盘"的意义。
 * 输入超过 [maxMinutes] 即拒绝按键并提示"上限 X 分钟"。
 */
@Composable
private fun CustomKeyboardPanel(
    packageName: String,
    maxMinutes: Int,
    protection: FormProtection,
    protectMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val confirmEnabled = protection.remainingMillis(protectMillis) <= 0L

    fun appendDigit(digit: Char) {
        hint = null
        // 前导零折叠："0" 后续输入直接替换
        val base = if (input == "0") "" else input
        val next = base + digit
        // 位数硬上限（上限位数 + 1）：防极端配置下无限增长
        if (next.length > maxMinutes.toString().length + 1) return
        val value = next.toIntOrNull() ?: return
        if (value > maxMinutes) {
            hint = "上限 $maxMinutes 分钟"
            return
        }
        input = next
    }

    fun confirm() {
        val value = input.toIntOrNull()
        if (value == null || value <= 0) {
            hint = "请输入有效分钟数"
            return
        }
        if (value > maxMinutes) {
            hint = "上限 $maxMinutes 分钟"
            return
        }
        onConfirm(value)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (input.isEmpty()) "输入分钟数" else input,
            fontSize = if (input.isEmpty()) 20.sp else 40.sp,
            fontWeight = FontWeight.Bold,
            color = if (input.isEmpty()) OnOverlayFaint else OnOverlay,
        )
        Text("分钟", fontSize = 14.sp, color = OnOverlayDim)
        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            hint?.let { Text(it, fontSize = 13.sp, color = WarnAmber) }
        }
        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            KeyboardDigitRow(listOf('1', '2', '3'), ::appendDigit)
            KeyboardDigitRow(listOf('4', '5', '6'), ::appendDigit)
            KeyboardDigitRow(listOf('7', '8', '9'), ::appendDigit)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { input = input.dropLast(1); hint = null },
                    modifier = KeySize,
                ) {
                    Text("退格", fontSize = 15.sp)
                }
                OutlinedButton(onClick = { appendDigit('0') }, modifier = KeySize) {
                    Text("0", fontSize = 20.sp)
                }
                Button(onClick = ::confirm, enabled = confirmEnabled, modifier = KeySize) {
                    Text("确认", fontSize = 15.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onDismiss) {
            Text("取消", fontSize = 15.sp, color = OnOverlayDim)
        }
    }
}

/** 假键盘统一按键尺寸。 */
private val KeySize = Modifier.size(width = 88.dp, height = 56.dp)

/** 假键盘数字行：三个自绘数字按键。 */
@Composable
private fun KeyboardDigitRow(digits: List<Char>, onDigit: (Char) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        digits.forEach { digit ->
            OutlinedButton(onClick = { onDigit(digit) }, modifier = KeySize) {
                Text(digit.toString(), fontSize = 20.sp)
            }
        }
    }
}

// ------------------------------- 时间已到 -----------------------------------

@Composable
private fun TimeUpOverlay(
    content: OverlayContent.Machine,
    spec: OverlaySpec.TimeUp,
    interactions: OverlayInteractions,
) {
    val formKey = "${content.packageName}|${content.appearanceEpoch}|TimeUp:${spec.extensionAvailable}"
    val maxProtect = if (spec.extensionAvailable) {
        maxOf(spec.confirmProtectMillis, spec.extensionProtectMillis)
    } else {
        spec.confirmProtectMillis
    }
    val protection = rememberFormProtection(formKey, maxProtect)

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OverlayHeader(content.appName, "时间已到")
        Spacer(Modifier.height(36.dp))
        ProtectedButton("确定，返回桌面", protection, spec.confirmProtectMillis) {
            interactions.onConfirmExit(content.packageName)
        }
        if (spec.extensionAvailable) {
            Spacer(Modifier.height(18.dp))
            ProtectedButton("延时使用（限一次）", protection, spec.extensionProtectMillis) {
                interactions.onExtensionRequested(content.packageName)
            }
        }
    }
}

// ------------------------------ 额度用尽 ------------------------------------

@Composable
private fun QuotaExhaustedOverlay(
    content: OverlayContent.Machine,
    spec: OverlaySpec.QuotaExhausted,
    interactions: OverlayInteractions,
) {
    val formKey = "${content.packageName}|${content.appearanceEpoch}|QuotaExhausted"
    val protection = rememberFormProtection(formKey, spec.confirmProtectMillis)

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OverlayHeader(content.appName, "今日额度已用完")
        Spacer(Modifier.height(16.dp))
        Text(
            "今日 ${spec.usedLimitMinutes} 分钟已用完，零点重置。",
            fontSize = 16.sp,
            color = OnOverlayDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        // 契约：无任何延时，确定后由协调器送回桌面
        ProtectedButton("确定，返回桌面", protection, spec.confirmProtectMillis) {
            interactions.onConfirmExit(content.packageName)
        }
    }
}

// -------------------------------- 冷却中 ------------------------------------

@Composable
private fun CooldownOverlay(
    content: OverlayContent.Machine,
    spec: OverlaySpec.Cooldown,
    interactions: OverlayInteractions,
) {
    val formKey = "${content.packageName}|${content.appearanceEpoch}|Cooldown:${spec.kind}:${spec.extensionAvailable}"
    val protection = rememberFormProtection(
        formKey,
        if (spec.extensionAvailable) spec.extensionProtectMillis else 0L,
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OverlayHeader(content.appName, "冷却中")
        Spacer(Modifier.height(12.dp))
        Text(
            when (spec.kind) {
                CooldownKind.EXPIRY -> "本次会话已结束"
                CooldownKind.ABANDON -> "本次会话已中途放弃"
            },
            fontSize = 16.sp,
            color = OnOverlayDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        // remainingMillis 每秒随状态机快照刷新；保护计时键不含它，故不被重置
        Text(
            cooldownCountdownText(content.remainingMillis),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = OnOverlay,
            textAlign = TextAlign.Center,
        )
        if (spec.extensionAvailable) {
            Spacer(Modifier.height(36.dp))
            ProtectedButton("延时使用（限一次）", protection, spec.extensionProtectMillis) {
                interactions.onExtensionRequested(content.packageName)
            }
        }
    }
}

/** 冷却剩余文案：>= 1 分钟显示"X 分钟"（向上取整），< 1 分钟显示秒。 */
private fun cooldownCountdownText(remainingMillis: Long?): String {
    if (remainingMillis == null) return "冷却中，稍后再回来。"
    if (remainingMillis <= 0L) return "即将可打开"
    return if (remainingMillis >= 60_000L) {
        val minutes = ceil(remainingMillis / 60_000.0).toInt()
        "$minutes 分钟后可打开"
    } else {
        "${millisToCeilSeconds(remainingMillis)} 秒后可打开"
    }
}

// ------------------------------ 禁止使用 ------------------------------------

/** 黑名单禁止浮层：全屏提示，无任何按钮、无退出提示 —— 用户用系统手势回桌面。 */
@Composable
private fun ForbiddenOverlay(appName: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("禁止使用", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = OnOverlay)
        Spacer(Modifier.height(12.dp))
        Text(appName, fontSize = 20.sp, color = OnOverlayDim)
        Spacer(Modifier.height(24.dp))
        Text(
            "此应用已被完全禁止使用。",
            fontSize = 16.sp,
            color = OnOverlayDim,
            textAlign = TextAlign.Center,
        )
    }
}
