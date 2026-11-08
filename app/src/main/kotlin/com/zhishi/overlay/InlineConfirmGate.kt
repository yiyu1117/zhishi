package com.zhishi.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内联二次确认状态机（纯逻辑，无 Android 依赖）：
 * 空闲 →（arm）→ 待确认 →（confirm 窗口内执行 / cancel 取消 / 超时复位）→ 空闲。
 *
 * 悬浮窗窗口不可获焦（FLAG_NOT_FOCUSABLE），无法承载模态对话框，
 * 危险操作（重置所有计时）以「按钮变确认态 + 取消 + 超时自动复位」就地确认。
 *
 * 时间由调用方注入（单调毫秒时钟，如 SystemClock.elapsedRealtime），超时判定确定性可测；
 * 超时的调度（Compose LaunchedEffect 定时 / 测试直接推进时刻）由外部驱动。
 * 确认窗口为 [armed, armed + timeoutMillis)：到点即视为超时，与外部定时器语义一致。
 */
class InlineConfirmGate(private val timeoutMillis: Long) {

    private val _pending = MutableStateFlow(false)

    /** 是否处于待确认态：按钮呈确认态并显示取消（Compose 侧 collectAsState 观察 recompose）。 */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** 待确认的进入时刻；null = 空闲。 */
    private var armedAtMillis: Long? = null

    /** 进入待确认态；已待确认则忽略（不续期，窗口始终从首次进入起算）。 */
    fun arm(nowMillis: Long) {
        if (_pending.value) return
        armedAtMillis = nowMillis
        _pending.value = true
    }

    /** 取消：复位且不执行。 */
    fun cancel() {
        armedAtMillis = null
        _pending.value = false
    }

    /**
     * 确认：仅在待确认且未超窗时执行（返回 true），并复位；
     * 已超窗或空闲返回 false（不执行），同样复位。返回值即「是否应执行危险操作」。
     */
    fun confirm(nowMillis: Long): Boolean {
        val armedAt = armedAtMillis ?: return false
        val fresh = nowMillis - armedAt < timeoutMillis
        cancel()
        return fresh
    }

    /** 超时复位检查（外部定时器到点调用）：已超窗则回空闲并返回 true（发生了自动复位）。 */
    fun expireIfDue(nowMillis: Long): Boolean {
        val armedAt = armedAtMillis ?: return false
        if (nowMillis - armedAt >= timeoutMillis) {
            cancel()
            return true
        }
        return false
    }
}
