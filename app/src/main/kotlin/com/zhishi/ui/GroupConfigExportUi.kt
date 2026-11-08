@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhishi.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhishi.data.ExportGlobalSettings
import com.zhishi.data.ExportScope
import com.zhishi.data.GroupConfigExport
import com.zhishi.data.GroupRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「分组配置导入/导出」节（v1.2.1，本版仅导出）：
 * - 点导出先选两档（仅分组配置 / 含全局设置），再经系统文档选择器（CreateDocument）保存 JSON；
 * - 导出为只读操作，自律锁生效期间仍可用——本节不参与锁定门禁。
 */
@Composable
internal fun GroupConfigExportSection() {
    val context = LocalContext.current
    val settingsRepo = rememberSettingsRepository()
    val groupRepo = remember(context) { GroupRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var showScopeDialog by remember { mutableStateOf(false) }
    var pendingScope by remember { mutableStateOf<ExportScope?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val picked = pendingScope
        if (uri == null || picked == null) {
            // 用户在保存框取消，或进程重建后状态丢失：静默复位，不视为错误
            pendingScope = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 写入时现取仓库快照（而非组合期旧值），保证导出内容即当前配置
                    val groups = groupRepo.groups.first()
                    val assignments = groupRepo.assignments.first()
                    val global = if (picked == ExportScope.FULL) {
                        ExportGlobalSettings(
                            detectionMode = settingsRepo.detectionMode.first(),
                            defaultTimeParams = settingsRepo.defaultTimeParams.first(),
                            newGroupDailyLimitMinutes = settingsRepo.newGroupDailyLimitMinutes.first(),
                            disciplineLock = settingsRepo.disciplineLock.first(),
                            notificationUsageVisible = settingsRepo.notificationUsageVisible.first(),
                        )
                    } else {
                        null
                    }
                    val json = GroupConfigExport.encode(
                        scope = picked,
                        groups = groups,
                        assignments = assignments,
                        global = global,
                        exportedAtMillis = System.currentTimeMillis(),
                    )
                    // "wt" 截断写入，避免个别文档提供方追加写导致文件损坏
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                }
            }
            result.fold(
                onSuccess = {
                    Toast.makeText(context, "分组配置已导出", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        context,
                        "导出失败：${e.message ?: "未知错误"}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
            pendingScope = null
        }
    }

    SettingsSection(title = "分组配置导入/导出") {
        Text(
            "将分组配置导出为 JSON 文件，用于换机迁移或分享管控方案。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { showScopeDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("导出分组配置", fontSize = 15.sp)
        }
    }

    if (showScopeDialog) {
        ExportScopeDialog(
            onSelect = { picked ->
                showScopeDialog = false
                pendingScope = picked
                exportLauncher.launch(suggestedExportFileName(picked))
            },
            onDismiss = { showScopeDialog = false },
        )
    }
}

/** 两档选择框：确认后才拉起系统保存框。 */
@Composable
private fun ExportScopeDialog(
    onSelect: (ExportScope) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(ExportScope.GROUPS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出分组配置") },
        text = {
            Column {
                ScopeOption(
                    title = "仅分组配置",
                    description = "三个内置组的配置、全部自定义组（名称/配置/顺序）与应用分配；" +
                        "不含自律锁时段等私人设置。",
                    selected = selected == ExportScope.GROUPS,
                    onSelect = { selected = ExportScope.GROUPS },
                )
                Spacer(Modifier.height(8.dp))
                ScopeOption(
                    title = "含全局设置",
                    description = "以上全部内容，另含检测模式、默认参数、新建组默认每日限额、" +
                        "自律锁开关与时段、常驻通知显示剩余时长开关。",
                    selected = selected == ExportScope.FULL,
                    onSelect = { selected = ExportScope.FULL },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selected) }) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ScopeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 建议文件名：zhishi-groups-2026-11-08.json / zhishi-full-2026-11-08.json。 */
private fun suggestedExportFileName(scope: ExportScope): String =
    "zhishi-${scope.jsonName}-${LocalDate.now()}.json"
