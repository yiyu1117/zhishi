package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.Mechanism
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 延时冻结冷却的连续性（code-review 修复回归）：
 * 从冷却浮层发起延时后，无论以哪条路径离开"时间已到"（确定 / 自行离开 / 重启恢复），
 * 均按被冻结的冷却剩余续接，不得重置为满额。
 */
class PendingCooldownContinuityTest {

    private val config = testConfig(
        Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN,
    )

    /** 前置：会话到时 → 确定 → 冷却推进 6 分钟（剩 2 分钟）→ 发起延时（冻结 2 分钟冷却）。 */
    private fun machineWithPendingCooldown(clock: FakeClock): DefaultGuardStateMachine {
        val machine = newMachine(config, clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L) // → TIME_UP
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 6 * 60_000L) // 冷却剩 2 分钟
        machine.dispatch(GuardEvent.ForegroundEntered) // 冷却浮层（冻结）
        machine.dispatch(GuardEvent.ExtensionRequested) // → EXTENSION_RUNNING
        return machine
    }

    /** 延时结束后回 TIME_UP，用户自行离开：按冻结剩余（2 分钟）续接冷却，而非满额 8 分钟。 */
    @Test
    fun `延时结束后自行离开按冻结剩余续接冷却`() {
        val clock = FakeClock()
        val machine = machineWithPendingCooldown(clock)
        tickAfter(machine, clock, 2 * 60_000L) // 延时耗尽 → TIME_UP

        machine.dispatch(GuardEvent.Backgrounded)

        val snapshot = machine.snapshot.value
        assertEquals(GuardState.EXPIRY_COOLDOWN, snapshot.state)
        assertEquals(2 * 60_000L, snapshot.remainingMillis)
    }

    /** 延时结束后回 TIME_UP，点确定：同样按冻结剩余续接（此路径原本已正确，一并钉住）。 */
    @Test
    fun `延时结束后确定退出按冻结剩余续接冷却`() {
        val clock = FakeClock()
        val machine = machineWithPendingCooldown(clock)
        tickAfter(machine, clock, 2 * 60_000L)

        machine.dispatch(GuardEvent.ConfirmExit)

        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(2 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 重启恢复：EXTENSION_RUNNING 连同冻结冷却一起恢复，延时走完后按剩余续接。 */
    @Test
    fun `延时中重启恢复冻结冷却并按剩余续接`() {
        val clock = FakeClock()
        val machine = machineWithPendingCooldown(clock)
        clock.advance(30_000L) // 延时进行 30 秒
        val exported = machine.exportState()

        val restored = newMachine(config, clock, restored = exported)
        tickAfter(restored, clock, 90_000L) // 延时剩余 90 秒耗尽 → TIME_UP
        restored.dispatch(GuardEvent.Backgrounded)

        assertEquals(GuardState.EXPIRY_COOLDOWN, restored.snapshot.value.state)
        assertEquals(2 * 60_000L, restored.snapshot.value.remainingMillis)
    }
}
