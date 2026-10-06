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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 后台保活引导卡（两种检测模式通用）。
 *
 * 背景（真机实测，HyperOS 3）：应用切后台后会被系统 cgroup 冻结（`cgroup.events frozen 1`），
 * 无障碍绑定、电池优化白名单均不能豁免，前台服务也只能延迟几分钟 —— 冻结期间事件回调全部
 * 挂起，拦截浮层失灵。唯一可靠解法是用户手动授予：自启动权限、省电策略「无限制」、
 * 最近任务锁定。这些均无公开 API，只能引导。
 */
@Composable
fun KeepAliveGuideCard(modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val context = LocalContext.current

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
                "实测 HyperOS/MIUI 会冻结后台进程：被冻结期间无障碍与轮询都收不到前台切换，" +
                    "打开守护应用不会弹浮层（表现为时好时坏）。请完成以下授权：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuideRow(
                title = "自启动权限",
                description = "关键项：HyperOS 只要不冻结「已允许自启动」应用的后台。" +
                    "设置 → 应用设置 → 应用管理 → 知时 → 自启动。",
                granted = null,
                actionLabel = "尝试直达",
                onAction = { openAutoStartSettings(context) },
            )
            GuideRow(
                title = "省电策略：无限制",
                description = "应用信息 → 省电策略 → 无限制（智能限制后台运行仍会冻结）。",
                granted = null,
                actionLabel = "去设置",
                onAction = { openAppDetailsSettings(context) },
            )
            GuideRow(
                title = "最近任务锁定",
                description = "在最近任务中下拉「知时」卡片点锁定（各 ROM 入口略有差异）。",
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
