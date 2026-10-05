package com.zhishi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhishi.core.model.DisciplineLockConfig
import com.zhishi.data.SettingsRepository
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * 自律锁 UI 侧共享状态（fail-closed 防绕过）：
 * - 配置尚未从 DataStore 读出时按「已锁定」处理，杜绝加载窗口期的绕过写入；
 * - minuteOfDay 在回到前台与轻量轮询时刷新，锁定期进入/退出时段能及时生效。
 */
internal data class DisciplineLockUi(
    val config: DisciplineLockConfig?,
    val minuteOfDay: Int,
) {
    /** 写操作是否被禁用：配置未加载，或当前不在可修改时段（未启用时 isUnlockedAt 恒真）。 */
    val locked: Boolean get() = config == null || !config.isUnlockedAt(minuteOfDay)

    /** 时段文案，如 "22:00–23:00"。 */
    val windowText: String
        get() = config
            ?.let { "${formatMinuteOfDay(it.startMinuteOfDay)}–${formatMinuteOfDay(it.endMinuteOfDay)}" }
            ?: ""
}

/** UI 侧获取全局设置仓库（DataStore 单例与 GroupRepository 同机制，多实例安全）。 */
@Composable
internal fun rememberSettingsRepository(): SettingsRepository {
    val context = LocalContext.current
    return remember(context) { SettingsRepository(context.applicationContext) }
}

/** 当日分钟数（0–1439），与 DisciplineLockConfig 的时段语义一致。 */
internal fun nowMinuteOfDay(): Int = LocalTime.now().toSecondOfDay() / 60

@Composable
internal fun rememberDisciplineLockUi(): DisciplineLockUi {
    val repo = rememberSettingsRepository()
    val config by repo.disciplineLock.collectAsStateWithLifecycle(initialValue = null)
    var minuteOfDay by remember { mutableIntStateOf(nowMinuteOfDay()) }

    // 从系统设置等返回本应用时立即刷新时刻
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                minuteOfDay = nowMinuteOfDay()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 界面停留期间轻量轮询，跨过时段边界时及时收紧/放开写操作
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            minuteOfDay = nowMinuteOfDay()
        }
    }
    return DisciplineLockUi(config, minuteOfDay)
}

internal fun formatMinuteOfDay(minuteOfDay: Int): String =
    "${(minuteOfDay / 60).toString().padStart(2, '0')}:${(minuteOfDay % 60).toString().padStart(2, '0')}"

/** 锁定提示横幅：错误色容器，醒目展示，用于设置屏与各写入口顶部。 */
@Composable
internal fun DisciplineLockBanner(windowText: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "自律锁生效中",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "每日 $windowText 之外不可修改配置，所有写操作已禁用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
