package com.zhishi.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhishi.core.machine.OverlaySpec
import kotlin.math.ceil
import kotlinx.coroutines.flow.StateFlow

/** 浮层背景：近乎不透明的深色，保证盖住底层应用内容。 */
private val OverlayScrim = Color(0xF5101418)
private val OnOverlay = Color(0xFFE6EDF3)

/** 浮层内容模型：同一时刻只有一个浮层（最新守护前台应用）。 */
sealed interface OverlayContent {
    /** 状态机驱动的浮层（[OverlaySpec] 五形态）。 */
    data class Machine(
        val packageName: String,
        val appName: String,
        val spec: OverlaySpec,
        val remainingMillis: Long? = null,
    ) : OverlayContent

    /** ticket 01 示例守护应用演示浮层（不走状态机）。 */
    data class Sample(val appName: String) : OverlayContent
}

@Composable
internal fun OverlayRoot(contentState: StateFlow<OverlayContent?>, onSampleBackHome: () -> Unit) {
    val content by contentState.collectAsState()
    Box(Modifier.fillMaxSize().background(OverlayScrim)) {
        when (val c = content) {
            null -> Unit
            is OverlayContent.Sample -> SampleOverlay(c.appName, onSampleBackHome)
            is OverlayContent.Machine -> MachineOverlay(c)
        }
    }
}

/** ticket 01 演示浮层：示例守护应用 + 应用名 + 回桌面按钮。 */
@Composable
private fun SampleOverlay(appName: String, onBackHome: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("示例守护应用", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = OnOverlay)
        Spacer(Modifier.height(12.dp))
        Text(appName, fontSize = 20.sp, color = OnOverlay.copy(alpha = 0.8f))
        Spacer(Modifier.height(48.dp))
        Button(onClick = onBackHome) {
            Text("回桌面", fontSize = 18.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "（ticket 01 演示：检测到示例应用进入前台）",
            fontSize = 13.sp,
            color = OnOverlay.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MachineOverlay(content: OverlayContent.Machine) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (content.spec) {
            // 黑名单禁止浮层：按契约无任何按钮，用户用系统手势回桌面（FLAG_NOT_FOCUSABLE 保证手势可用）
            is OverlaySpec.Forbidden -> {
                Text("禁止使用", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = OnOverlay)
                Spacer(Modifier.height(12.dp))
                Text(content.appName, fontSize = 20.sp, color = OnOverlay.copy(alpha = 0.8f))
                Spacer(Modifier.height(24.dp))
                Text(
                    "此应用属于黑名单分组，已被完全禁止使用。",
                    fontSize = 16.sp,
                    color = OnOverlay.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text("请使用系统手势返回桌面。", fontSize = 14.sp, color = OnOverlay.copy(alpha = 0.5f))
            }
            is OverlaySpec.Cooldown -> {
                Text("请稍后再试", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = OnOverlay)
                Spacer(Modifier.height(12.dp))
                Text(content.appName, fontSize = 20.sp, color = OnOverlay.copy(alpha = 0.8f))
                Spacer(Modifier.height(24.dp))
                Text(cooldownText(content.remainingMillis), fontSize = 18.sp, color = OnOverlay)
            }
            is OverlaySpec.TimeUp -> {
                Text("时间已到", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = OnOverlay)
                Spacer(Modifier.height(12.dp))
                Text(content.appName, fontSize = 20.sp, color = OnOverlay.copy(alpha = 0.8f))
            }
            is OverlaySpec.QuotaExhausted -> {
                Text("今日额度已用完", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = OnOverlay)
                Spacer(Modifier.height(12.dp))
                Text(
                    "今日 ${content.spec.usedLimitMinutes} 分钟已用完，零点重置。",
                    fontSize = 16.sp,
                    color = OnOverlay.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
            is OverlaySpec.TimeSelection -> {
                Text("请选择使用时长", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OnOverlay)
                Spacer(Modifier.height(12.dp))
                Text(
                    "时长选择按钮将在后续版本提供。",
                    fontSize = 15.sp,
                    color = OnOverlay.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun cooldownText(remainingMillis: Long?): String {
    if (remainingMillis == null) return "冷却中，稍后再回来。"
    val minutes = ceil(remainingMillis / 60_000.0).toInt()
    return if (minutes <= 1) "不足 1 分钟后可打开" else "约 $minutes 分钟后可打开"
}
