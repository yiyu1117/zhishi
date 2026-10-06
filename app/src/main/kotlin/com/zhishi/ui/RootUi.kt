@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhishi.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhishi.AppGraph
import com.zhishi.core.model.DetectionMode
import com.zhishi.detect.PollingForegroundDetector
import com.zhishi.detect.ZhishiAccessibilityService
import kotlinx.coroutines.launch

/** 应用根 UI：权限未就绪时显示引导（无障碍或兼容模式二选一），就绪后进入分组管理。 */
@Composable
fun ZhishiRootUi() {
    val context = LocalContext.current
    var accessibilityEnabled by remember {
        mutableStateOf(ZhishiAccessibilityService.isServiceEnabled(context))
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var usageAccessGranted by remember {
        mutableStateOf(PollingForegroundDetector.hasUsageAccess(context))
    }
    val detectionMode by rememberSettingsRepository().detectionMode.collectAsStateWithLifecycle(
        initialValue = DetectionMode.ACCESSIBILITY,
    )
    val scope = rememberCoroutineScope()

    // 从系统设置返回时刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = ZhishiAccessibilityService.isServiceEnabled(context)
                overlayGranted = Settings.canDrawOverlays(context)
                usageAccessGranted = PollingForegroundDetector.hasUsageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 守护链路就绪即启动：无障碍已开，或已选定兼容模式且使用情况访问已授予
    LaunchedEffect(accessibilityEnabled, detectionMode, usageAccessGranted) {
        val ready = accessibilityEnabled ||
            (detectionMode == DetectionMode.POLLING && usageAccessGranted)
        if (ready) AppGraph.coordinator.start() else AppGraph.coordinator.stop()
    }

    when {
        accessibilityEnabled -> when {
            !overlayGranted -> OverlayPermissionGuideScreen()
            else -> MainScreen()
        }
        // 无障碍未开启：选过兼容模式走使用情况访问引导，否则引导开无障碍（页内可改选兼容）
        detectionMode == DetectionMode.POLLING -> when {
            !usageAccessGranted -> UsageAccessGuideScreen()
            !overlayGranted -> OverlayPermissionGuideScreen()
            else -> MainScreen()
        }
        else -> AccessibilityGuideScreen(
            onUseCompat = {
                scope.launch { AppGraph.settingsRepository.setDetectionMode(DetectionMode.POLLING) }
            },
        )
    }
}

@Composable
private fun AccessibilityGuideScreen(onUseCompat: () -> Unit) {
    val context = LocalContext.current
    GuidePage(
        title = "开启无障碍权限",
        body = "「知时」需要无障碍权限来感知前台应用切换，从而在守护应用打开的瞬间弹出拦截浮层。\n\n" +
            "本服务只读取窗口切换事件的包名，不读取、不记录任何屏幕内容，也不会联网。\n\n" +
            "HyperOS/MIUI 用户注意：开启后请在系统设置中允许本应用「自启动」并把省电策略设为「无限制」，" +
            "否则进程被后台冻结时将无法弹出拦截浮层（见主界面的后台保活引导）。",
        buttonText = "去开启",
        onButton = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        secondaryText = "不开启无障碍，改用兼容模式",
        onSecondary = onUseCompat,
    )
}

@Composable
private fun UsageAccessGuideScreen() {
    val context = LocalContext.current
    GuidePage(
        title = "授予使用情况访问权限",
        body = "兼容模式通过轮询应用使用情况感知前台切换（约 1–2 秒延迟）。\n\n" +
            "请在系统设置中允许「知时」查看使用情况；授权后还建议按设置页的兼容模式引导" +
            "关闭电池优化并保持常驻通知。",
        buttonText = "去授权",
        onButton = {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

@Composable
private fun OverlayPermissionGuideScreen() {
    val context = LocalContext.current
    GuidePage(
        title = "授予悬浮窗权限",
        body = "拦截浮层需要「显示在其他应用上层」权限，才能在守护应用之上全屏显示时间选择或禁止提示。\n\n" +
            "请在本应用的系统设置中允许显示在其他应用上层。",
        buttonText = "去授权",
        onButton = {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

@Composable
private fun GuidePage(
    title: String,
    body: String,
    buttonText: String,
    onButton: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("知时", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onButton) {
                Text(buttonText, fontSize = 16.sp)
            }
            if (secondaryText != null && onSecondary != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSecondary) { Text(secondaryText) }
            }
        }
    }
}

/** 主界面内的简单屏幕状态切换（不引入导航库）。 */
internal sealed interface Screen {
    data object Groups : Screen
    data class GroupDetail(val groupId: String) : Screen
    data class AppPicker(val groupId: String) : Screen
    data object Settings : Screen
}

/** 屏幕层级：转场动画按深度决定滑动方向（进 deeper 从右滑入，返回从左滑入）。 */
private fun Screen.depth(): Int = when (this) {
    Screen.Groups -> 0
    is Screen.GroupDetail -> 1
    is Screen.AppPicker -> 2
    Screen.Settings -> 1
}

@Composable
internal fun MainScreen() {
    var screen by remember { mutableStateOf<Screen>(Screen.Groups) }
    // 系统返回逐级回退（详情/选择器/设置 → 分组列表），不退出应用
    BackHandler(enabled = screen != Screen.Groups) {
        screen = when (val current = screen) {
            is Screen.GroupDetail -> Screen.Groups
            is Screen.AppPicker -> Screen.GroupDetail(current.groupId)
            Screen.Settings -> Screen.Groups
            Screen.Groups -> Screen.Groups
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState.depth() >= initialState.depth()
                val enter = slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) {
                    if (forward) it else -it
                } + fadeIn(spring(stiffness = Spring.StiffnessMediumLow))
                val exit = slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) {
                    if (forward) -it / 3 else it / 3
                } + fadeOut(spring(stiffness = Spring.StiffnessMediumLow))
                enter togetherWith exit
            },
            contentKey = { it::class },
            label = "screen",
        ) { current ->
            when (val s = current) {
                Screen.Groups -> GroupListScreen(
                    onOpenGroup = { screen = Screen.GroupDetail(it) },
                    onOpenSettings = { screen = Screen.Settings },
                    onPickApps = { screen = Screen.AppPicker(it) },
                )
                is Screen.GroupDetail -> GroupDetailScreen(
                    groupId = s.groupId,
                    onBack = { screen = Screen.Groups },
                    onPickApps = { screen = Screen.AppPicker(s.groupId) },
                )
                is Screen.AppPicker -> AppPickerScreen(
                    groupId = s.groupId,
                    onBack = { screen = Screen.GroupDetail(s.groupId) },
                )
                Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Groups })
            }
        }
    }
}
