package com.zhishi.core.machine

import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.isForbiddenByQuota
import com.zhishi.core.time.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 守护状态机默认实现（ticket 03–07 的实现对象）。
 *
 * 语义要点（来自 spec.md Implementation Decisions）：
 * - 会话仅前台计时；退后台/息屏进入宽限期，宽限耗尽判中途放弃。
 * - 冷却推进规则：冷却倒计时仅当用户不处于该应用内时推进（停留浮层/延时期间冻结）。
 * - 实际到期 = min(所选时长剩余, 当日剩余额度)。
 * - 延时每会话一次，未用则带入该会话的冷却期，延时期间冷却冻结。
 *
 * 当前为最小桩实现：仅黑名单（每日限额 = -1）路径可用，供 ticket 01/02 的拦截闭环接线；
 * 其余状态的行为由 ticket 03–07 落地。
 */
class DefaultGuardStateMachine(
    packageName: String,
    clock: AppClock,
    config: GroupConfig,
    todayUsedMillis: Long,
    /** 重启恢复：非空时按保存的状态续接。 */
    restored: GuardStateData? = null,
) : GuardStateMachine(packageName, clock, config) {

    private val mutableSnapshot = MutableStateFlow(
        GuardSnapshot(
            packageName = packageName,
            state = if (config.isForbiddenByQuota()) GuardState.BLACKLISTED else GuardState.INACTIVE,
            overlay = null,
            remainingMillis = null,
            usedTodayMillis = todayUsedMillis,
            todayRemainingMinutes = null,
        ),
    )

    override val snapshot: StateFlow<GuardSnapshot> = mutableSnapshot

    override fun dispatch(event: GuardEvent): List<GuardEffect> {
        // 桩实现：仅黑名单路径生效（ticket 03–07 将整体重写）
        if (!config.isForbiddenByQuota()) return emptyList()
        return when (event) {
            is GuardEvent.ForegroundEntered -> listOf(
                GuardEffect.ShowOverlay(OverlaySpec.Forbidden),
            ).also {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    state = GuardState.BLACKLISTED,
                    overlay = OverlaySpec.Forbidden,
                )
            }
            is GuardEvent.Backgrounded -> listOf(GuardEffect.HideOverlay).also {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    state = GuardState.BLACKLISTED,
                    overlay = null,
                )
            }
            else -> emptyList()
        }
    }

    override fun exportState(): GuardStateData {
        return GuardStateData(mutableSnapshot.value.state)
    }

    override fun onConfigUpdated(newConfig: GroupConfig) {
        config = newConfig
        mutableSnapshot.value = mutableSnapshot.value.copy(
            state = if (newConfig.isForbiddenByQuota()) GuardState.BLACKLISTED else GuardState.INACTIVE,
        )
    }
}
