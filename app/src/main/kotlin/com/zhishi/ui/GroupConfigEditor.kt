package com.zhishi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhishi.core.model.GroupConfig
import kotlinx.coroutines.launch

/** 选「限时」但尚无额度时的初始分钟数，与设置页「新建分组默认每日限额」一致。 */
private const val DEFAULT_LIMITED_MINUTES = 60

/**
 * 分组配置编辑区（每日限额三态 + 全部时间参数），交互范式与设置页「默认参数」一致：
 * 行 + 点开数值对话框 + 数字校验。
 *
 * 每次写入经 [onUpdate] 提交完整 [GroupConfig]（只改动目标字段，其余保留），
 * 由调用方经 GroupRepository.updateConfig 落盘并热更新状态机。
 *
 * 自律锁闸门：writable = false（锁生效中）时全部入口禁用（横幅由调用方展示）。
 * 「禁用（-1）」在 [disableConfirmText] 非空时（默认组覆盖面大）先弹确认再写入。
 *
 * 剩余提醒刻度组级只读展示：机制按各组播种值生效，但增删保持在设置页全局一份，
 * 避免与设置页的增删语义冲突。
 */
@Composable
internal fun GroupConfigEditSection(
    config: GroupConfig,
    writable: Boolean,
    disableConfirmText: String?,
    onUpdate: suspend (GroupConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val limit = config.dailyLimitMinutes
    var editRequest by remember { mutableStateOf<GroupEditRequest?>(null) }
    var showDisableConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // region 每日限额（-1 禁用 / 0 不限 / N 限时）
        Text("每日限额", style = MaterialTheme.typography.titleMedium)
        Text(
            "禁用（平禁）/ 不限 / 限时 N 分钟，对应 -1 / 0 / N；保存后立即生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = limit < 0,
                enabled = writable,
                onClick = {
                    if (!writable || limit < 0) return@RadioButton
                    if (disableConfirmText != null) showDisableConfirm = true
                    else scope.launch { onUpdate(config.copy(dailyLimitMinutes = -1)) }
                },
            )
            Text("禁用")
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = limit == 0,
                enabled = writable,
                onClick = {
                    if (!writable || limit == 0) return@RadioButton
                    scope.launch { onUpdate(config.copy(dailyLimitMinutes = 0)) }
                },
            )
            Text("不限")
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = limit > 0,
                enabled = writable,
                onClick = {
                    if (!writable || limit > 0) return@RadioButton
                    scope.launch { onUpdate(config.copy(dailyLimitMinutes = DEFAULT_LIMITED_MINUTES)) }
                },
            )
            Text("限时")
        }
        if (limit > 0) {
            GroupSettingRow("限时额度", "$limit 分钟", enabled = writable) {
                editRequest = GroupEditRequest(
                    title = "每日限额（分钟）",
                    initial = limit,
                    min = 1,
                    max = 1440,
                    unit = "分钟",
                ) { value ->
                    onUpdate(config.copy(dailyLimitMinutes = value))
                }
            }
        }
        // endregion

        Spacer(Modifier.height(12.dp))
        Text("时间参数", style = MaterialTheme.typography.titleMedium)
        val time = config.time

        GroupSubHeader("快选档位（拦截浮层的时长按钮）")
        time.quickOptionsMinutes.forEachIndexed { index, minutes ->
            GroupSettingRow(
                "快选档位 ${index + 1}",
                "$minutes 分钟",
                enabled = writable,
                description = "拦截浮层上的第 ${index + 1} 个时长快选按钮，点击即按该时长开始本次使用。",
            ) {
                editRequest = GroupEditRequest(
                    title = "快选档位 ${index + 1}（分钟）",
                    initial = minutes,
                    min = 1,
                    max = 1440,
                    unit = "分钟",
                ) { value ->
                    val list = time.quickOptionsMinutes.toMutableList()
                    if (index < list.size) list[index] = value else list.add(value)
                    onUpdate(config.copy(time = time.copy(quickOptionsMinutes = list)))
                }
            }
        }

        GroupSubHeader("浮层按钮保护（按下后数秒不可点，防习惯性误触）")
        GroupSettingRow(
            "快选按钮保护",
            "${time.quickProtectMillis / 1000} 秒",
            enabled = writable,
            description = "拦截浮层弹出后，快选/自定义按钮需等待的秒数。",
        ) {
            editRequest = GroupEditRequest(
                title = "快选按钮保护（秒）",
                initial = (time.quickProtectMillis / 1000).toInt(),
                min = 0, max = 600, unit = "秒",
            ) { value ->
                onUpdate(config.copy(time = time.copy(quickProtectMillis = value * 1000L)))
            }
        }
        GroupSettingRow(
            "确定按钮保护（时间已到 / 额度用尽）",
            "${time.confirmProtectMillis / 1000} 秒",
            enabled = writable,
            description = "「确定，返回桌面」按钮需等待的秒数。",
        ) {
            editRequest = GroupEditRequest(
                title = "确定按钮保护（秒）",
                initial = (time.confirmProtectMillis / 1000).toInt(),
                min = 0, max = 600, unit = "秒",
            ) { value ->
                onUpdate(config.copy(time = time.copy(confirmProtectMillis = value * 1000L)))
            }
        }
        GroupSettingRow(
            "延时按钮保护",
            "${time.extensionProtectMillis / 1000} 秒",
            enabled = writable,
            description = "「延时使用」按钮需等待的秒数。",
        ) {
            editRequest = GroupEditRequest(
                title = "延时按钮保护（秒）",
                initial = (time.extensionProtectMillis / 1000).toInt(),
                min = 0, max = 600, unit = "秒",
            ) { value ->
                onUpdate(config.copy(time = time.copy(extensionProtectMillis = value * 1000L)))
            }
        }

        GroupSubHeader("延时使用")
        GroupSettingRow(
            "延时时长",
            "${time.extensionMinutes} 分钟",
            enabled = writable,
            description = "时间用尽后可临时续用的时长（用于收尾），每次会话限用一次。",
        ) {
            editRequest = GroupEditRequest(
                title = "延时时长（分钟）",
                initial = time.extensionMinutes,
                min = 1, max = 180, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(extensionMinutes = value)))
            }
        }
        GroupSettingRow(
            "延时预告提前",
            "${time.extensionPrewarnSeconds} 秒",
            enabled = writable,
            description = "延时结束前多少秒 Toast 预告即将锁定。",
        ) {
            editRequest = GroupEditRequest(
                title = "延时预告提前（秒）",
                initial = time.extensionPrewarnSeconds,
                min = 0, max = 600, unit = "秒",
            ) { value ->
                onUpdate(config.copy(time = time.copy(extensionPrewarnSeconds = value)))
            }
        }

        GroupSubHeader("宽限期与冷却")
        GroupSettingRow(
            "宽限期",
            "${time.gracePeriodMillis / 60_000} 分钟",
            enabled = writable,
            description = "退到后台后计时暂停、会话不重置的最长时长；超过即判中途放弃。",
        ) {
            editRequest = GroupEditRequest(
                title = "宽限期（分钟）",
                initial = (time.gracePeriodMillis / 60_000).toInt(),
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(gracePeriodMillis = value * 60_000L)))
            }
        }
        GroupSettingRow(
            "放弃冷却",
            "${time.abandonCooldownMillis / 60_000} 分钟",
            enabled = writable,
            description = "中途放弃（宽限期耗尽仍未回来）后的禁入时长，默认短于到期冷却。",
        ) {
            editRequest = GroupEditRequest(
                title = "放弃冷却（分钟）",
                initial = (time.abandonCooldownMillis / 60_000).toInt(),
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(abandonCooldownMillis = value * 60_000L)))
            }
        }
        GroupSettingRow(
            "到期冷却",
            "${time.expiryCooldownMillis / 60_000} 分钟",
            enabled = writable,
            description = "时间用尽并确认返回桌面后的禁入时长；倒计时只在你不在该应用内时推进。",
        ) {
            editRequest = GroupEditRequest(
                title = "到期冷却（分钟）",
                initial = (time.expiryCooldownMillis / 60_000).toInt(),
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(expiryCooldownMillis = value * 60_000L)))
            }
        }

        GroupSubHeader("提醒")
        GroupSettingRow(
            "周期用量提示间隔",
            "${time.usageToastIntervalMillis / 60_000} 分钟",
            enabled = writable,
            description = "会话进行中每隔该时长 Toast 报告一次累计已用时间。",
        ) {
            editRequest = GroupEditRequest(
                title = "周期用量提示间隔（分钟）",
                initial = (time.usageToastIntervalMillis / 60_000).toInt(),
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(usageToastIntervalMillis = value * 60_000L)))
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("剩余提醒刻度", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (time.reminderMarksMinutes.isEmpty()) "无刻度（到期前不单独提醒）。" else
                    time.reminderMarksMinutes.joinToString("、") { "$it 分钟" } + "。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "全局参数，各组共用一份：在「设置 → 默认参数」中增删，本页只读展示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GroupSubHeader("自定义与限额")
        GroupSettingRow(
            "自定义时长上限",
            "${time.customMaxMinutes} 分钟",
            enabled = writable,
            description = "拦截浮层假数字键盘可输入的最大分钟数。",
        ) {
            editRequest = GroupEditRequest(
                title = "自定义时长上限（分钟）",
                initial = time.customMaxMinutes,
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(customMaxMinutes = value)))
            }
        }
    }

    editRequest?.let { request ->
        GroupEditDialog(request = request, onDismiss = { editRequest = null })
    }

    // 禁用 = 平禁：默认组覆盖所有未分组应用，写入前确认影响范围
    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text("禁用每日限额（平禁）") },
            text = { Text(disableConfirmText ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableConfirm = false
                    scope.launch { onUpdate(config.copy(dailyLimitMinutes = -1)) }
                }) { Text("确认禁用") }
            },
            dismissButton = { TextButton(onClick = { showDisableConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun GroupSubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun GroupSettingRow(
    label: String,
    value: String,
    enabled: Boolean,
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

// region 数值编辑对话框（与设置页 NumberEditDialog 同范式）

private data class GroupEditRequest(
    val title: String,
    val initial: Int,
    val min: Int,
    val max: Int,
    val unit: String,
    val onCommit: suspend (Int) -> Unit,
)

@Composable
private fun GroupEditDialog(request: GroupEditRequest, onDismiss: () -> Unit) {
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
