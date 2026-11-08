@file:OptIn(ExperimentalLayoutApi::class)

package com.zhishi.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 后台保活引导卡（面向所有系统，两种检测模式通用）。
 *
 * 部分系统会冻结或清理后台进程（实测如 HyperOS 3 的 cgroup 冻结）：冻结期间事件回调
 * 全部挂起，无障碍与轮询都收不到前台切换，拦截浮层失灵。各授权项大多没有公开 API，
 * 只能引导：
 * - 电池优化豁免：全系统通用 API，实时探测 + 系统豁免弹窗直达
 *   （复用轮询引导卡的探测与直达逻辑及状态圆点版式）；
 * - 自启动：无公开 API，中性文案引导，MIUI 尝试直达（失败回退应用详情页）；
 * - 最近任务锁定：无公开 API，仅说明。
 *
 * 从系统设置返回（ON_RESUME）时自动刷新探测状态。
 */
@Composable
fun KeepAliveGuideCard(modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var batteryExempt by remember { mutableStateOf(false) }

    fun refresh() {
        batteryExempt = isIgnoringBatteryOptimizations(context)
    }

    // 首帧与跳系统设置授权后返回本页都经 ON_RESUME 触发刷新：观察者注册时若已处于
    // Resumed 状态，Lifecycle 会补发 ON_RESUME，无需再单独跑一遍首帧刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "后台保活引导（防拦截失灵）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "部分系统会冻结或清理后台进程：被冻结期间无障碍与轮询都收不到前台切换，" +
                    "打开受管应用不会弹浮层（表现为时好时坏）。请完成以下授权：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuideRow(
                title = "电池优化豁免",
                description = "关键项：关闭电池优化，降低本应用后台被系统冻结或清理的概率。",
                granted = batteryExempt,
                actionLabel = if (batteryExempt) null else "去设置",
                onAction = { requestIgnoreBatteryOptimizations(context) },
            )
            GuideRow(
                title = "自启动权限",
                description = "关键项：允许「知时」自启动与后台运行" +
                    "（各 ROM 入口不同，一般在应用信息或安全中心/手机管家中）。",
                granted = null,
                actionLabel = "尝试直达",
                onAction = { openAutoStartSettings(context) },
            )
            GuideRow(
                title = "最近任务锁定",
                description = "在最近任务中下拉「知时」卡片点锁定（部分 ROM 支持，入口略有差异）。",
                granted = null,
                actionLabel = null,
                onAction = null,
            )

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "完成授权后此卡可关闭；拦截再次失灵时请回到本页查看。",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

private fun openAutoStartSettings(context: Context) {
    // MIUI/HyperOS 安全中心的自启动管理页（无公开 API，组件名随版本可能变化，失败回退应用详情页）
    val miuiAutoStart = Intent().setClassName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity",
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(miuiAutoStart)
    } catch (_: ActivityNotFoundException) {
        openAppDetailsSettings(context)
        Toast.makeText(context, "未找到自启动设置页，请在应用信息中开启自启动", Toast.LENGTH_LONG).show()
    } catch (_: Exception) {
        openAppDetailsSettings(context)
    }
}

internal fun openAppDetailsSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // 理论上不可达：应用详情页所有 ROM 都有
    }
}
