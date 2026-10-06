@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.zhishi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhishi.core.model.DetectionMode
import com.zhishi.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * 设置屏：自律锁 / 检测模式 / 默认参数（对新创建的分组生效）。
 * 自律锁生效中（时段外）时本页所有写操作禁用，仅保留浏览与返回。
 */
@Composable
internal fun SettingsScreen(onBack: () -> Unit) {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    val lock = rememberDisciplineLockUi()

    val params by repo.defaultTimeParams.collectAsStateWithLifecycle(initialValue = null)
    val mode by repo.detectionMode.collectAsStateWithLifecycle(initialValue = null)
    val newGroupLimit by repo.newGroupDailyLimitMinutes.collectAsStateWithLifecycle(initialValue = null)
    val debugMode by repo.debugMode.collectAsStateWithLifecycle(initialValue = false)

    var editRequest by remember { mutableStateOf<NumberEditRequest?>(null) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }
    var showLockEnableDialog by remember { mutableStateOf(false) }

    val tp = params
    val currentMode = mode
    val currentLimit = newGroupLimit

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("‹", fontSize = 28.sp) }
                },
            )
        },
    ) { padding ->
        if (tp == null || currentMode == null || currentLimit == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val lockConfig = lock.config
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (lockConfig != null && lock.locked) {
                DisciplineLockBanner(lock.windowText)
            }

            // region 自律锁
            SettingsSection(title = "自律锁") {
                if (lockConfig == null) {
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启用自律锁", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "每天仅指定时段可修改配置，防住不自律时刻的自己。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = lockConfig.enabled,
                            enabled = !lock.locked,
                            onCheckedChange = { on ->
                                if (on) showLockEnableDialog = true
                                else scope.launch { repo.setDisciplineLockEnabled(false) }
                            },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    SettingRow(
                        label = "可修改时段起点",
                        value = formatMinuteOfDay(lockConfig.startMinuteOfDay),
                        enabled = !lock.locked,
                    ) { editingStart = true }
                    SettingRow(
                        label = "可修改时段终点",
                        value = formatMinuteOfDay(lockConfig.endMinuteOfDay),
                        enabled = !lock.locked,
                    ) { editingEnd = true }
                    if (lockConfig.enabled) {
                        val crossMidnight = lockConfig.startMinuteOfDay > lockConfig.endMinuteOfDay
                        Text(
                            if (crossMidnight) {
                                "时段跨零点：每天 ${formatMinuteOfDay(lockConfig.startMinuteOfDay)} " +
                                    "至次日 ${formatMinuteOfDay(lockConfig.endMinuteOfDay)} 之外不可修改配置。"
                            } else {
                                "每天 ${formatMinuteOfDay(lockConfig.startMinuteOfDay)}–" +
                                    "${formatMinuteOfDay(lockConfig.endMinuteOfDay)} 之外不可修改配置。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (lock.locked) "当前状态：已锁定（写操作已禁用，包括本页的自律锁设置）"
                            else "当前状态：处于可修改时段内",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (lock.locked) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // endregion

            // region 检测模式
            SettingsSection(title = "检测模式") {
                DetectionMode.entries.forEach { candidate ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !lock.locked) {
                                scope.launch { repo.setDetectionMode(candidate) }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentMode == candidate,
                            enabled = !lock.locked,
                            onClick = { scope.launch { repo.setDetectionMode(candidate) } },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(detectionModeLabel(candidate), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                detectionModeDesc(candidate),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // 兼容模式授权与保活引导（ticket 09）
                if (currentMode == DetectionMode.POLLING) {
                    Spacer(Modifier.height(8.dp))
                    PollingGuideCard()
                }
            }
            // endregion

            // region 调试模式
            SettingsSection(title = "调试模式") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("状态调试悬浮窗", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "在屏幕角落以小悬浮窗列出全部守护应用的实时状态与倒计时" +
                                "（会话/宽限/延时/冷却，支持多应用），用于排查拦截与计时问题；" +
                                "悬浮窗不可触摸、不影响任何操作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = debugMode,
                        enabled = !lock.locked,
                        onCheckedChange = { on -> scope.launch { repo.setDebugMode(on) } },
                    )
                }
            }
            // endregion

            // region 默认参数
            SettingsSection(title = "默认参数") {
                Text(
                    "以下参数对新创建的分组生效；已存在分组请在分组详情修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val writable = !lock.locked

                SectionSubHeader("快选档位（拦截浮层的 6 个时长按钮）")
                tp.quickOptionsMinutes.forEachIndexed { index, minutes ->
                    SettingRow(
                        "快选档位 ${index + 1}",
                        "$minutes 分钟",
                        enabled = writable,
                        description = "拦截浮层上的第 ${index + 1} 个时长快选按钮，点击即按该时长开始本次使用。",
                    ) {
                        editRequest = NumberEditRequest(
                            title = "快选档位 ${index + 1}（分钟）",
                            initial = minutes,
                            min = 1,
                            max = 1440,
                            unit = "分钟",
                        ) { value ->
                            scope.launch {
                                repo.updateDefaultTimeParams { t ->
                                    val list = t.quickOptionsMinutes.toMutableList()
                                    if (index < list.size) list[index] = value else list.add(value)
                                    t.copy(quickOptionsMinutes = list)
                                }
                            }
                        }
                    }
                }

                SectionSubHeader("浮层按钮保护（按下后数秒不可点，防习惯性误触）")
                SettingRow(
                    "快选按钮保护",
                    "${tp.quickProtectMillis / 1000} 秒",
                    enabled = writable,
                    description = "拦截浮层弹出后，快选/自定义按钮需等待的秒数。",
                ) {
                    editRequest = secondsRequest("快选按钮保护（秒）", tp.quickProtectMillis) { value ->
                        repo.updateDefaultTimeParams { it.copy(quickProtectMillis = value * 1000L) }
                    }
                }
                SettingRow(
                    "确定按钮保护（时间已到 / 额度用尽）",
                    "${tp.confirmProtectMillis / 1000} 秒",
                    enabled = writable,
                    description = "「确定，返回桌面」按钮需等待的秒数。",
                ) {
                    editRequest = secondsRequest("确定按钮保护（秒）", tp.confirmProtectMillis) { value ->
                        repo.updateDefaultTimeParams { it.copy(confirmProtectMillis = value * 1000L) }
                    }
                }
                SettingRow(
                    "延时按钮保护",
                    "${tp.extensionProtectMillis / 1000} 秒",
                    enabled = writable,
                    description = "「延时使用」按钮需等待的秒数。",
                ) {
                    editRequest = secondsRequest("延时按钮保护（秒）", tp.extensionProtectMillis) { value ->
                        repo.updateDefaultTimeParams { it.copy(extensionProtectMillis = value * 1000L) }
                    }
                }

                SectionSubHeader("延时使用")
                SettingRow(
                    "延时时长",
                    "${tp.extensionMinutes} 分钟",
                    enabled = writable,
                    description = "时间用尽后可临时续用的时长（用于收尾），每次会话限用一次。",
                ) {
                    editRequest = NumberEditRequest("延时时长（分钟）", tp.extensionMinutes, 1, 180, "分钟") { value ->
                        scope.launch { repo.updateDefaultTimeParams { it.copy(extensionMinutes = value) } }
                    }
                }
                SettingRow(
                    "延时预告提前",
                    "${tp.extensionPrewarnSeconds} 秒",
                    enabled = writable,
                    description = "延时结束前多少秒 Toast 预告即将锁定。",
                ) {
                    editRequest = NumberEditRequest("延时预告提前（秒）", tp.extensionPrewarnSeconds, 0, 600, "秒") { value ->
                        scope.launch { repo.updateDefaultTimeParams { it.copy(extensionPrewarnSeconds = value) } }
                    }
                }

                SectionSubHeader("宽限期与冷却")
                SettingRow(
                    "宽限期",
                    "${tp.gracePeriodMillis / 60_000} 分钟",
                    enabled = writable,
                    description = "退到后台后计时暂停、会话不重置的最长时长；超过即判中途放弃。",
                ) {
                    editRequest = minutesRequest("宽限期（分钟）", tp.gracePeriodMillis) { value ->
                        repo.updateDefaultTimeParams { it.copy(gracePeriodMillis = value * 60_000L) }
                    }
                }
                SettingRow(
                    "放弃冷却",
                    "${tp.abandonCooldownMillis / 60_000} 分钟",
                    enabled = writable,
                    description = "中途放弃（宽限期耗尽仍未回来）后的禁入时长，默认短于到期冷却。",
                ) {
                    editRequest = minutesRequest("放弃冷却（分钟）", tp.abandonCooldownMillis) { value ->
                        repo.updateDefaultTimeParams { it.copy(abandonCooldownMillis = value * 60_000L) }
                    }
                }
                SettingRow(
                    "到期冷却",
                    "${tp.expiryCooldownMillis / 60_000} 分钟",
                    enabled = writable,
                    description = "时间用尽并确认返回桌面后的禁入时长；倒计时只在你不在该应用内时推进。",
                ) {
                    editRequest = minutesRequest("到期冷却（分钟）", tp.expiryCooldownMillis) { value ->
                        repo.updateDefaultTimeParams { it.copy(expiryCooldownMillis = value * 60_000L) }
                    }
                }

                SectionSubHeader("提醒")
                SettingRow(
                    "周期用量提示间隔",
                    "${tp.usageToastIntervalMillis / 60_000} 分钟",
                    enabled = writable,
                    description = "会话进行中每隔该时长 Toast 报告一次累计已用时间。",
                ) {
                    editRequest = minutesRequest("周期用量提示间隔（分钟）", tp.usageToastIntervalMillis) { value ->
                        repo.updateDefaultTimeParams { it.copy(usageToastIntervalMillis = value * 60_000L) }
                    }
                }
                ReminderMarksEditor(
                    marks = tp.reminderMarksMinutes,
                    enabled = writable,
                    onChange = { next ->
                        scope.launch {
                            repo.updateDefaultTimeParams { it.copy(reminderMarksMinutes = next) }
                        }
                    },
                )

                SectionSubHeader("自定义与限额")
                SettingRow(
                    "自定义时长上限",
                    "${tp.customMaxMinutes} 分钟",
                    enabled = writable,
                    description = "拦截浮层假数字键盘可输入的最大分钟数。",
                ) {
                    editRequest = NumberEditRequest("自定义时长上限（分钟）", tp.customMaxMinutes, 1, 1440, "分钟") { value ->
                        scope.launch { repo.updateDefaultTimeParams { it.copy(customMaxMinutes = value) } }
                    }
                }
                NewGroupDailyLimitEditor(
                    limit = currentLimit,
                    enabled = writable,
                    onSelect = { value -> scope.launch { repo.setNewGroupDailyLimitMinutes(value) } },
                    onEditValue = {
                        editRequest = NumberEditRequest("每日限额（分钟）", currentLimit, 1, 1440, "分钟") { value ->
                            scope.launch { repo.setNewGroupDailyLimitMinutes(value) }
                        }
                    },
                )
            }
            // endregion
        }
    }

    if (showLockEnableDialog) {
        val windowText = lock.windowText
        AlertDialog(
            onDismissRequest = { showLockEnableDialog = false },
            title = { Text("启用自律锁") },
            text = {
                Text(
                    "开启后每天仅 $windowText 可修改配置；时段外将无法修改任何配置" +
                        "（包括关闭自律锁本身）。确认开启？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLockEnableDialog = false
                    scope.launch { repo.setDisciplineLockEnabled(true) }
                }) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { showLockEnableDialog = false }) { Text("取消") }
            },
        )
    }

    editRequest?.let { request ->
        NumberEditDialog(request = request, onDismiss = { editRequest = null })
    }

    if (editingStart) {
        TimePickerDialog(
            title = "可修改时段起点",
            initialMinuteOfDay = lock.config?.startMinuteOfDay ?: (22 * 60),
            onConfirm = { value ->
                editingStart = false
                scope.launch {
                    repo.setDisciplineLockWindow(value, lock.config?.endMinuteOfDay ?: (23 * 60))
                }
            },
            onDismiss = { editingStart = false },
        )
    }
    if (editingEnd) {
        TimePickerDialog(
            title = "可修改时段终点",
            initialMinuteOfDay = lock.config?.endMinuteOfDay ?: (23 * 60),
            onConfirm = { value ->
                editingEnd = false
                scope.launch {
                    repo.setDisciplineLockWindow(lock.config?.startMinuteOfDay ?: (22 * 60), value)
                }
            },
            onDismiss = { editingEnd = false },
        )
    }
}

// region 通用小组件

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SectionSubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onClick, enabled = enabled) { Text(value) }
    }
}

private fun secondsRequest(
    title: String,
    currentMillis: Long,
    commit: suspend (Int) -> Unit,
): NumberEditRequest = NumberEditRequest(title, (currentMillis / 1000).toInt(), 0, 600, "秒") { value ->
    commit(value)
}

private fun minutesRequest(
    title: String,
    currentMillis: Long,
    commit: suspend (Int) -> Unit,
): NumberEditRequest = NumberEditRequest(title, (currentMillis / 60_000).toInt(), 1, 1440, "分钟") { value ->
    commit(value)
}

// endregion

// region 数值编辑对话框

private data class NumberEditRequest(
    val title: String,
    val initial: Int,
    val min: Int,
    val max: Int,
    val unit: String,
    val onCommit: suspend (Int) -> Unit,
)

@Composable
private fun NumberEditDialog(request: NumberEditRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(request) { mutableStateOf(request.initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in request.min..request.max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                label = { Text("请输入 ${request.min}–${request.max} ${request.unit}") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onDismiss()
                    value?.let { commit -> scope.launch { request.onCommit(commit) } }
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// endregion

// region 时段选择（简单步进器，无新依赖）

@Composable
private fun TimePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by remember(initialMinuteOfDay) { mutableIntStateOf(initialMinuteOfDay / 60) }
    var minute by remember(initialMinuteOfDay) { mutableIntStateOf(initialMinuteOfDay % 60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StepperColumn("时", hour, 0, 23, 1) { hour = it }
                StepperColumn("分", minute, 0, 55, 5) { minute = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StepperColumn(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { onChange(if (value + step > max) min else value + step) }) {
            Text("＋", fontSize = 20.sp)
        }
        Text(
            value.toString().padStart(2, '0'),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = { onChange(if (value - step < min) max else value - step) }) {
            Text("−", fontSize = 20.sp)
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

// endregion

// region 剩余提醒刻度（增删，ticket 07）

@Composable
private fun ReminderMarksEditor(marks: List<Int>, enabled: Boolean, onChange: (List<Int>) -> Unit) {
    var input by remember { mutableStateOf("") }
    val parsed = input.toIntOrNull()
    val addable = parsed != null && parsed in 1..720 && parsed !in marks

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("剩余提醒刻度", style = MaterialTheme.typography.bodyLarge)
        Text(
            "会话到期前按这些分钟数提醒（距实际到期点）；点击刻度可删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            marks.forEach { mark ->
                InputChip(
                    selected = false,
                    onClick = { onChange(marks - mark) },
                    enabled = enabled,
                    label = { Text("$mark 分钟") },
                    trailingIcon = { Text("✕", fontSize = 14.sp) },
                )
            }
            if (marks.isEmpty()) {
                Text(
                    "无刻度：到期前不再单独提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter(Char::isDigit).take(3) },
                label = { Text("新增刻度（分钟）") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = enabled && addable,
                onClick = {
                    val value = parsed
                    if (value != null) {
                        onChange((marks + value).distinct().sortedDescending())
                        input = ""
                    }
                },
            ) { Text("添加") }
        }
    }
}

// endregion

// region 新建分组默认每日限额（-1 / 0 / N 三态）

private const val DEFAULT_LIMITED_MINUTES = 60

@Composable
private fun NewGroupDailyLimitEditor(
    limit: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onEditValue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("新建分组默认每日限额", style = MaterialTheme.typography.bodyLarge)
        Text(
            "禁用（平禁）/ 不限 / 限时 N 分钟，语义与分组每日限额一致。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = limit < 0, enabled = enabled, onClick = { onSelect(-1) })
            Text("禁用")
            Spacer(Modifier.width(16.dp))
            RadioButton(selected = limit == 0, enabled = enabled, onClick = { onSelect(0) })
            Text("不限")
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = limit > 0,
                enabled = enabled,
                onClick = { if (limit <= 0) onSelect(DEFAULT_LIMITED_MINUTES) },
            )
            Text("限时")
        }
        if (limit > 0) {
            SettingRow("限时额度", "$limit 分钟", enabled = enabled, onClick = onEditValue)
        }
    }
}

// endregion

private fun detectionModeLabel(mode: DetectionMode): String = when (mode) {
    DetectionMode.ACCESSIBILITY -> "无障碍（默认，推荐）"
    DetectionMode.POLLING -> "兼容轮询"
}

private fun detectionModeDesc(mode: DetectionMode): String = when (mode) {
    DetectionMode.ACCESSIBILITY -> "毫秒级响应，无常驻服务。"
    DetectionMode.POLLING -> "有 1–2 秒检测延迟，需常驻通知、电池优化豁免与开机自启。"
}
