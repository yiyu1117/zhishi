package com.zhishi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
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
            GroupSettingRow("快选档位 ${index + 1}", "$minutes 分钟", enabled = writable) {
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
            "确定按钮保护（额度用尽）",
            "${time.confirmProtectMillis / 1000} 秒",
            enabled = writable,
            description = "「额度用尽」浮层的确定按钮需等待的秒数（时间已到即进冷却，其返回桌面按钮不设保护）。",
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
            if (time.smartTimingEnabled) {
                "智能（${time.smartGracePercent}% × 所选时长，保底 2 分）"
            } else {
                "${time.gracePeriodMillis / 60_000} 分钟"
            },
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
            if (time.smartTimingEnabled) {
                "智能（${time.smartAbandonCooldownPercent}% × 实际已用）"
            } else {
                "${time.abandonCooldownMillis / 60_000} 分钟"
            },
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
            if (time.smartTimingEnabled) {
                "智能（${time.smartExpiryCooldownPercent}% × 实际使用）"
            } else {
                "${time.expiryCooldownMillis / 60_000} 分钟"
            },
            enabled = writable,
            description = "时间用尽后的禁入时长；倒计时按墙钟推进，走完且你仍停留时原地回时间选择。",
        ) {
            editRequest = GroupEditRequest(
                title = "到期冷却（分钟）",
                initial = (time.expiryCooldownMillis / 60_000).toInt(),
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(expiryCooldownMillis = value * 60_000L)))
            }
        }

        GroupSubHeader("智能时长联动（按使用时长比例计算）")
        GroupToggleRow(
            "智能时长联动",
            if (time.smartTimingEnabled) "按比例计算" else "用固定值",
            checked = time.smartTimingEnabled,
            enabled = writable,
            description = "开启后到期冷却 / 宽限期 / 放弃冷却分别按比例 × 基数实时计算" +
                "（结果不足 1 分钟保留秒级，达到 1 分钟向上取整；宽限保底 2 分钟）。",
        ) { on ->
            scope.launch { onUpdate(config.copy(time = time.copy(smartTimingEnabled = on))) }
        }
        if (time.smartTimingEnabled) {
            GroupSettingRow("到期冷却比例", "${time.smartExpiryCooldownPercent}%", enabled = writable) {
                editRequest = GroupEditRequest(
                    title = "到期冷却比例（%）",
                    initial = time.smartExpiryCooldownPercent,
                    min = 0, max = 100, unit = "%",
                ) { value ->
                    onUpdate(config.copy(time = time.copy(smartExpiryCooldownPercent = value)))
                }
            }
            GroupSettingRow("宽限期比例", "${time.smartGracePercent}%", enabled = writable) {
                editRequest = GroupEditRequest(
                    title = "宽限期比例（%）",
                    initial = time.smartGracePercent,
                    min = 0, max = 100, unit = "%",
                ) { value ->
                    onUpdate(config.copy(time = time.copy(smartGracePercent = value)))
                }
            }
            GroupSettingRow("放弃冷却比例", "${time.smartAbandonCooldownPercent}%", enabled = writable) {
                editRequest = GroupEditRequest(
                    title = "放弃冷却比例（%）",
                    initial = time.smartAbandonCooldownPercent,
                    min = 0, max = 100, unit = "%",
                ) { value ->
                    onUpdate(config.copy(time = time.copy(smartAbandonCooldownPercent = value)))
                }
            }
        }

        GroupSubHeader("同组共享冷却")
        GroupToggleRow(
            "同组共享冷却",
            if (time.sharedCooldownEnabled) "已开启" else "已关闭",
            checked = time.sharedCooldownEnabled,
            enabled = writable,
            description = "本组某应用进入冷却时，按比例分发给组内闲置/待选择的成员" +
                "（不打断会话中/宽限中/已在冷却中的成员；接收方无延时按钮）。",
        ) { on ->
            scope.launch { onUpdate(config.copy(time = time.copy(sharedCooldownEnabled = on))) }
        }
        if (time.sharedCooldownEnabled) {
            GroupSettingRow("共享折算比例", "${time.sharedCooldownPercent}%", enabled = writable) {
                editRequest = GroupEditRequest(
                    title = "共享折算比例（%）",
                    initial = time.sharedCooldownPercent,
                    min = 0, max = 100, unit = "%",
                ) { value ->
                    onUpdate(config.copy(time = time.copy(sharedCooldownPercent = value)))
                }
            }
        }

        GroupSubHeader("延迟选时（打开后先试探后选时）")
        GroupSettingRow(
            "试探期时长",
            "${time.delayedSelectionMinutes} 分钟",
            enabled = writable,
            description = "「延迟选时」机制开启时，打开应用后前台累计满该时长弹时间选择浮层；计入每日限额。",
        ) {
            editRequest = GroupEditRequest(
                title = "试探期时长（分钟）",
                initial = time.delayedSelectionMinutes,
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(delayedSelectionMinutes = value)))
            }
        }
        GroupSettingRow(
            "试探中断保留",
            "${time.delayedSelectionRetentionMillis / 60_000} 分钟",
            enabled = writable,
            description = "试探期退后台的保留时长：超过后进度清零，保留期内回来续计。",
        ) {
            editRequest = GroupEditRequest(
                title = "试探中断保留（分钟）",
                initial = (time.delayedSelectionRetentionMillis / 60_000).toInt(),
                min = 1, max = 1440, unit = "分钟",
            ) { value ->
                onUpdate(config.copy(time = time.copy(delayedSelectionRetentionMillis = value * 60_000L)))
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

/** 开关型配置行（与 [GroupSettingRow] 同版式，右侧为 Switch）。 */
@Composable
private fun GroupToggleRow(
    label: String,
    value: String,
    checked: Boolean,
    enabled: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
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
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
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
    var committing by remember { mutableStateOf(false) }
    val value = text.toIntOrNull()
    val valid = value != null && value in request.min..request.max
    AlertDialog(
        onDismissRequest = { if (!committing) onDismiss() },
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
            if (committing) {
                // 写入经 DataStore 落盘并触发热更新，期间对话框保持 + 转圈反馈
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("应用中…", fontSize = 13.sp)
                }
            } else {
                TextButton(
                    enabled = valid,
                    onClick = {
                        committing = true
                        val commit = value
                        scope.launch {
                            commit?.let { request.onCommit(it) }
                            onDismiss()
                        }
                    },
                ) { Text("确定") }
            }
        },
        dismissButton = { TextButton(enabled = !committing, onClick = onDismiss) { Text("取消") } },
    )
}

// endregion
