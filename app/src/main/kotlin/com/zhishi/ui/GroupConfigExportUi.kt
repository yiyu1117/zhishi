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
import com.zhishi.AppGraph
import com.zhishi.data.ExportGlobalSettings
import com.zhishi.data.ExportScope
import com.zhishi.data.GroupConfigExport
import com.zhishi.data.GroupConfigImport
import com.zhishi.data.GroupImportPlan
import com.zhishi.data.ImportMergeResult
import com.zhishi.data.ImportParseResult
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「分组配置导入/导出」节（v1.2.1）：
 * - 导出：先选两档（仅分组配置 / 含全局设置），再经系统文档选择器（CreateDocument）保存 JSON；
 * - 导入：经文档选择器（OpenDocument）选本应用导出的 JSON，解析出概况后弹确认框，
 *   确认后按分组 id 合并覆盖一次性写入（全量校验先行，损坏/不合规文件整体拒绝）；
 * - 导出为只读操作，自律锁生效期间仍可用；导入是写操作，锁定期间禁用（与其他写操作一致 fail-closed）。
 */
@Composable
internal fun GroupConfigExportSection(lock: DisciplineLockUi) {
    val context = LocalContext.current
    val settingsRepo = rememberSettingsRepository()
    val groupRepo = AppGraph.groupRepository
    val scope = rememberCoroutineScope()
    var showScopeDialog by remember { mutableStateOf(false) }
    var pendingScope by remember { mutableStateOf<ExportScope?>(null) }
    // 已解析并合并好的导入计划（非空即展示确认框）
    var pendingPlan by remember { mutableStateOf<GroupImportPlan?>(null) }

    // —— 导入：选文件 →（IO）解析 + 对照本机快照合并 → 概况确认框 → 一次性写入 ——
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                    val payload = when (val parsed = GroupConfigImport.parse(text)) {
                        is ImportParseResult.Ok -> parsed.payload
                        is ImportParseResult.Rejected -> error(parsed.reason)
                    }
                    // 合并对照写入时刻的仓库快照（而非组合期旧值），保证概况即将发生的事实
                    when (val merged = GroupConfigImport.planMerge(
                        payload,
                        groupRepo.groups.first(),
                        groupRepo.assignments.first(),
                    )) {
                        is ImportMergeResult.Ok -> merged.plan
                        is ImportMergeResult.Rejected -> error(merged.reason)
                    }
                }
            }
            result.fold(
                onSuccess = { plan -> pendingPlan = plan },
                onFailure = { e ->
                    Toast.makeText(
                        context,
                        "导入失败：${e.message ?: "未知错误"}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

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
        Spacer(Modifier.height(2.dp))
        Text(
            "导入此前导出的 JSON 文件，按分组 id 合并覆盖：" +
                "同 id 覆盖、新组新建、本机其余分组与配置原样保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                // 兼容不同文档提供方对 .json 的类型标注（部分提供方标注为文本或二进制流）
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            enabled = !lock.locked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("导入分组配置", fontSize = 15.sp)
        }
        if (lock.locked) {
            Text(
                "自律锁生效期间不可导入；导出为只读操作，不受限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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

    pendingPlan?.let { plan ->
        ImportConfirmDialog(
            plan = plan,
            onConfirm = {
                // 弹窗存续期间锁可能已生效：确认时再次校验，fail-closed
                if (lock.locked) {
                    pendingPlan = null
                    Toast.makeText(context, "自律锁生效期间不可导入", Toast.LENGTH_LONG).show()
                    return@ImportConfirmDialog
                }
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            // 分组与分配单事务整体写入；全局设置经现有 setter 逐项应用，
                            // 检测模式切换等服务启停联动自然发生
                            groupRepo.applyImportPlan(plan)
                            plan.global?.let { g ->
                                settingsRepo.setDetectionMode(g.detectionMode)
                                settingsRepo.setDefaultTimeParams(g.defaultTimeParams)
                                settingsRepo.setNewGroupDailyLimitMinutes(g.newGroupDailyLimitMinutes)
                                settingsRepo.setDisciplineLock(g.disciplineLock)
                                settingsRepo.setNotificationUsageVisible(g.notificationUsageVisible)
                            }
                        }
                    }
                    result.fold(
                        onSuccess = {
                            Toast.makeText(context, "分组配置已导入", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Toast.makeText(
                                context,
                                "导入失败：${e.message ?: "未知错误"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                    pendingPlan = null
                }
            },
            onDismiss = { pendingPlan = null },
        )
    }
}

/** 导入概况确认框：展示文件将覆盖/新建哪些分组、分配条数与是否含全局设置，确认才写入。 */
@Composable
private fun ImportConfirmDialog(
    plan: GroupImportPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val overwrittenNames = plan.overwrittenGroups.map { it.name }
    val createdNames = plan.createdGroups.map { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入") },
        text = {
            Column {
                Text(
                    "文件包含 ${plan.groups.size} 个分组：" +
                        "覆盖 ${overwrittenNames.size} 个、新建 ${createdNames.size} 个。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (overwrittenNames.isNotEmpty()) {
                    Text(
                        "覆盖：${overwrittenNames.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (createdNames.isNotEmpty()) {
                    Text(
                        "新建：${createdNames.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "应用分配 ${plan.fileAssignmentCount} 条将逐条覆盖" +
                        "（未提及的应用保持原分配；未安装的应用也会记录，安装后即受管控）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (plan.global != null) {
                        "文件含全局设置：将一并应用检测模式、默认参数、新建组默认限额、" +
                            "自律锁与常驻通知显示剩余时长开关。"
                    } else {
                        "文件不含全局设置。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "同 id 分组将被覆盖；本机其余分组与配置原样保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
