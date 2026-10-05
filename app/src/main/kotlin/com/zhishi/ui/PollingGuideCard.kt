package com.zhishi.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhishi.detect.PollingForegroundDetector

/**
 * 兼容模式（UsageStats 轮询）引导卡（ticket 09）。
 *
 * 独立文件，供主控嵌入设置页/引导页（不改动 ui/ 既有文件）：
 * - 使用情况访问权限：实时状态 + 跳转 ACTION_USAGE_ACCESS_SETTINGS；
 * - 电池优化豁免：实时状态 + ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（失败回退设置总页）；
 * - 开机自启与后台保活：无统一系统 API，展示 ROM 手动设置说明（中性图标）；
 * - 通知权限（Android 13+）：常驻守护通知可见性所需。
 *
 * 从设置页返回（ON_RESUME）时自动刷新各项授予状态。
 */
@Composable
fun PollingGuideCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var usageAccessGranted by remember { mutableStateOf(false) }
    var batteryExempt by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }

    fun refresh() {
        usageAccessGranted = PollingForegroundDetector.hasUsageAccess(context)
        batteryExempt = isIgnoringBatteryOptimizations(context)
        notificationGranted =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
    }

    // 用户跳系统设置授权后返回本页时刷新状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { refresh() }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "兼容模式（UsageStats 轮询）引导",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "兼容模式按约 1 秒轮询识别前台应用（拦截前目标应用可能短暂闪现），" +
                    "需以下授权保持可靠运行：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuideRow(
                title = "使用情况访问权限",
                description = "轮询模式的前提：允许「知时」读取应用使用情况统计。",
                granted = usageAccessGranted,
                actionLabel = if (usageAccessGranted) null else "去授权",
                onAction = { openUsageAccessSettings(context) },
            )

            GuideRow(
                title = "电池优化豁免",
                description = "关闭电池优化，避免轮询服务在息屏后被系统休眠杀掉。",
                granted = batteryExempt,
                actionLabel = if (batteryExempt) null else "去设置",
                onAction = { requestIgnoreBatteryOptimizations(context) },
            )

            // 开机自启/后台保活无统一系统 API（各家 ROM 自行管理），无法探测，仅说明
            GuideRow(
                title = "开机自启与后台保活",
                description = "部分 ROM 会杀后台：请在系统设置中允许「知时」自启动与后台运行，" +
                    "可在最近任务中锁定本应用，并保持常驻通知开启。",
                granted = null,
                actionLabel = null,
                onAction = null,
            )

            if (Build.VERSION.SDK_INT >= 33) {
                GuideRow(
                    title = "通知权限",
                    description = "允许通知后常驻守护通知才可见（守护本身不受影响）。",
                    granted = notificationGranted,
                    actionLabel = if (notificationGranted) null else "去授权",
                    onAction = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    title: String,
    description: String,
    /** null = 无法探测（仅说明项），展示中性图标。 */
    granted: Boolean?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 状态圆点：绿✓ 已就绪 / 橙! 未就绪 / 灰i 无法探测（仅说明项）
        val (symbol, dotColor, contentDesc) = when (granted) {
            true -> Triple("✓", Color(0xFF43A047), "已就绪")
            false -> Triple("!", Color(0xFFFB8C00), "未就绪")
            null -> Triple("i", MaterialTheme.colorScheme.onSurfaceVariant, "需手动确认")
        }
        Box(
            Modifier
                .size(28.dp)
                .background(dotColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                symbol,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = dotColor,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

private fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "未找到「使用情况访问」设置页，请在系统设置中手动开启", Toast.LENGTH_LONG).show()
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        try {
            // 回退：电池优化设置总列表页
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "未找到电池优化设置，请手动在系统设置中关闭本应用的电池优化", Toast.LENGTH_LONG).show()
        }
    }
}
