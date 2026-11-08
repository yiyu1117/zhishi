package com.zhishi.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 轮询模式实验性确认弹窗（票 05）：设置页切向轮询与首启引导「改用兼容模式」
 * 共用同一弹窗，正文与按钮文案取自 [POLLING_EXPERIMENTAL_CONFIRM_MESSAGE] 等常量；
 * 是否弹出的决策见 [shouldConfirmPollingSwitch]（调用方先判定再置状态）。
 * 「取消」/点框外均不切换，「仍要开启」才执行调用方传入的原切换逻辑。
 */
@Composable
internal fun PollingModeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换为轮询模式") },
        text = { Text(POLLING_EXPERIMENTAL_CONFIRM_MESSAGE) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(POLLING_CONFIRM_BUTTON_LABEL) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(POLLING_CANCEL_BUTTON_LABEL) }
        },
    )
}
