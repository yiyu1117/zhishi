package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.Mechanism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 同组共享冷却（v1.1，状态机侧）：协调器按组配置把其它成员的冷却按比例分发给本机，
 * 仅闲置/待选择接收，浮层不提供延时按钮；进行中的会话/宽限/冷却一律不受影响。
 */
class SharedCooldownMachineTest {

    private val config = testConfig(
        Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN, Mechanism.ABANDON_COOLDOWN,
    )

    /** 闲置/待选择机器接受共享冷却，浮层翻转为冷却且无延时按钮。 */
    @Test
    fun `闲置机器接受共享冷却且浮层无延时`() {
        val machine = newMachine(config)
        machine.dispatch(GuardEvent.ForegroundEntered) // 待选择浮层
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)

        machine.applySharedCooldown(CooldownKind.EXPIRY, 600_000L)

        val snapshot = machine.snapshot.value
        assertEquals(GuardState.EXPIRY_COOLDOWN, snapshot.state)
        assertEquals(600_000L, snapshot.remainingMillis)
        val cooldown = assertIs<OverlaySpec.Cooldown>(snapshot.overlay)
        assertEquals(true, cooldown.shared)
        assertEquals(false, cooldown.extensionAvailable) // 接收方未使用过，无延时

        // 请求延时被忽略
        assertEquals(emptyList(), machine.dispatch(GuardEvent.ExtensionRequested))
    }

    /** 后台闲置接收：无浮层效果，下次打开显示冷却浮层。 */
    @Test
    fun `后台闲置接收共享冷却在打开时显示`() {
        val machine = newMachine(config)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)

        machine.applySharedCooldown(CooldownKind.ABANDON, 300_000L)

        assertNull(machine.snapshot.value.overlay)
        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(effects).single())
        assertEquals(CooldownKind.ABANDON, cooldown.kind)
        assertEquals(true, cooldown.shared)
    }

    /** 待选择浮层被共享冷却覆盖；冷却走完原地回时间选择。 */
    @Test
    fun `待选择被共享冷却覆盖走完原地回时间选择`() {
        val clock = FakeClock()
        val machine = newMachine(config, clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)

        machine.applySharedCooldown(CooldownKind.EXPIRY, 60_000L)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)

        val effects = tickAfter(machine, clock, 60_000L)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
    }

    /** 会话进行中不接受共享冷却。 */
    @Test
    fun `会话中不接受共享冷却`() {
        val clock = FakeClock()
        val machine = newMachine(config, clock)
        startSession(machine, 10)

        machine.applySharedCooldown(CooldownKind.EXPIRY, 600_000L)

        assertEquals(GuardState.RUNNING, machine.snapshot.value.state)
        assertEquals(10 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 宽限期与试探期不接受共享冷却。 */
    @Test
    fun `宽限期与试探期不接受共享冷却`() {
        val clock = FakeClock()
        val graceMachine = newMachine(config, clock)
        startSession(graceMachine, 10)
        graceMachine.dispatch(GuardEvent.Backgrounded)
        assertEquals(GuardState.GRACE, graceMachine.snapshot.value.state)
        graceMachine.applySharedCooldown(CooldownKind.EXPIRY, 600_000L)
        assertEquals(GuardState.GRACE, graceMachine.snapshot.value.state)

        val trialConfig = testConfig(Mechanism.DELAYED_SELECTION, Mechanism.EXPIRY_COOLDOWN)
        val trialMachine = newMachine(trialConfig, clock)
        trialMachine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.TRIAL_RUNNING, trialMachine.snapshot.value.state)
        trialMachine.applySharedCooldown(CooldownKind.EXPIRY, 600_000L)
        assertEquals(GuardState.TRIAL_RUNNING, trialMachine.snapshot.value.state)
    }

    /** 已在冷却中不接受共享冷却（不覆盖自身剩余）。 */
    @Test
    fun `冷却中不接受共享冷却`() {
        val clock = FakeClock()
        val machine = newMachine(config, clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L) // 自有冷却 8 分钟，前台显示冷却浮层
        val own = assertIs<OverlaySpec.Cooldown>(machine.snapshot.value.overlay)
        assertEquals(false, own.shared)

        machine.dispatch(GuardEvent.Backgrounded)
        machine.applySharedCooldown(CooldownKind.ABANDON, 300_000L)

        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(480_000L, machine.snapshot.value.remainingMillis)
    }

    /** 共享冷却恢复推进与自有冷却一致：离开后墙钟走完回 INACTIVE。 */
    @Test
    fun `共享冷却离开后推进走完回闲置`() {
        val clock = FakeClock()
        val machine = newMachine(config, clock)
        machine.applySharedCooldown(CooldownKind.EXPIRY, 60_000L)
        machine.dispatch(GuardEvent.ForegroundEntered)
        machine.dispatch(GuardEvent.Backgrounded)

        tickAfter(machine, clock, 60_000L)

        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        assertNull(machine.snapshot.value.remainingMillis)
    }

    /** 延时结束回到冷却是同一 Episode 的续接：不再发 CooldownStarted（共享不二次分发）。 */
    @Test
    fun `延时结束回冷却不重发共享触发`() {
        val clock = FakeClock()
        val machine = newMachine(config, clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L) // 到期 → 冷却（发 CooldownStarted）
        machine.dispatch(GuardEvent.ExtensionRequested) // 冻结冷却，进延时
        val effects = tickAfter(machine, clock, 2 * 60_000L) // 延时走完 → 回冷却

        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(480_000L, machine.snapshot.value.remainingMillis)
        assertEquals(emptyList(), effects.filterIsInstance<GuardEffect.CooldownStarted>())
    }
}
