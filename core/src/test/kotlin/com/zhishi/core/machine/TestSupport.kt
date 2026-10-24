package com.zhishi.core.machine

import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import com.zhishi.core.time.AppClock

/** 可手动推进的假时钟。 */
class FakeClock(startMillis: Long = 0L) : AppClock {
    private var current: Long = startMillis

    override fun nowMillis(): Long = current

    fun advance(millis: Long) {
        current += millis
    }
}

internal fun testConfig(
    vararg mechanisms: Mechanism,
    dailyLimitMinutes: Int = 0,
    time: TimeParams = DEFAULT_TEST_TIME,
): GroupConfig = GroupConfig(mechanisms.toSet(), dailyLimitMinutes, time)

/**
 * 测试基座的时间参数：显式固定"智能联动关、共享冷却关"。
 * v1.2 起生产默认值为开（新分组默认启用智能/共享），测试大多针对固定值语义，
 * 需要智能/共享行为的用例各自显式开启。
 */
internal val DEFAULT_TEST_TIME = TimeParams(
    smartTimingEnabled = false,
    sharedCooldownEnabled = false,
)

internal fun newMachine(
    config: GroupConfig,
    clock: FakeClock = FakeClock(),
    usedToday: Long = 0L,
    restored: GuardStateData? = null,
    packageName: String = "com.test.app",
): DefaultGuardStateMachine = DefaultGuardStateMachine(packageName, clock, config, usedToday, restored)

/** 组合"进入前台 + 选定时长"开始一次会话。 */
internal fun startSession(machine: DefaultGuardStateMachine, minutes: Int) {
    machine.dispatch(GuardEvent.ForegroundEntered)
    machine.dispatch(GuardEvent.DurationSelected(minutes))
}

/** 前进指定毫秒并派发一次 Tick（模拟协调器 1s 节拍的时间跳跃等价形式）。 */
internal fun tickAfter(machine: DefaultGuardStateMachine, clock: FakeClock, millis: Long): List<GuardEffect> {
    clock.advance(millis)
    return machine.dispatch(GuardEvent.Tick)
}

/** 从效果列表中提取 ShowOverlay 的载荷。 */
internal fun overlaySpecs(effects: List<GuardEffect>): List<OverlaySpec> =
    effects.filterIsInstance<GuardEffect.ShowOverlay>().map { it.spec }

internal fun toasts(effects: List<GuardEffect>): List<ToastEvent> =
    effects.filterIsInstance<GuardEffect.ShowToast>().map { it.toast }
