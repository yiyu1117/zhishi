package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * ticket 04 —— 宽限期、中途放弃与放弃冷却、冷却推进规则（冻结/恢复）。
 */
class GraceAndAbandonCooldownTest {

    /** 退后台 1 分钟内回来，剩余时间与离开前一致。 */
    @Test
    fun `宽限期内返回会话剩余保持不变`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 3 * 60_000L)

        machine.dispatch(GuardEvent.Backgrounded) // 息屏同样走该事件，视同退后台
        tickAfter(machine, clock, 60_000L)
        val effects = machine.dispatch(GuardEvent.ForegroundEntered)

        assertEquals(emptyList(), effects)
        assertEquals(GuardState.RUNNING, machine.snapshot.value.state)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis)
        assertEquals(3 * 60_000L, machine.snapshot.value.usedTodayMillis) // 宽限期不记账
    }

    /** 宽限期耗尽判中途放弃：会话作废，进 5 分钟放弃冷却；用户不在应用内，无浮层效果。 */
    @Test
    fun `宽限期耗尽判中途放弃进入放弃冷却`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)

        val effects = tickAfter(machine, clock, 120_000L) // 恰好耗尽

        assertEquals(listOf(GuardEffect.CooldownStarted(CooldownKind.ABANDON, 300_000L)), effects)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.ABANDON_COOLDOWN, snapshot.state)
        assertEquals(300_000L, snapshot.remainingMillis) // 默认放弃冷却 5 分钟
        assertNull(snapshot.overlay)
    }

    /** 宽限恰好耗尽的同刻回到应用：算放弃，直接看到放弃冷却浮层。 */
    @Test
    fun `宽限恰好耗同时刻回来算放弃并弹冷却浮层`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)
        clock.advance(120_000L)

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)

        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)
        val spec = overlaySpecs(effects).single()
        val cooldown = assertIs<OverlaySpec.Cooldown>(spec)
        assertEquals(CooldownKind.ABANDON, cooldown.kind)
    }

    /** 停留冷却浮层期间倒计时照常推进（v1.1 墙钟规则），走完原地回时间选择。 */
    @Test
    fun `停留冷却浮层倒计时照常推进`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 120_000L)
        machine.dispatch(GuardEvent.ForegroundEntered)

        val effects = tickAfter(machine, clock, 60_000L)

        assertEquals(emptyList(), effects)
        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)
        assertEquals(240_000L, machine.snapshot.value.remainingMillis) // 墙钟照走
        assertIs<OverlaySpec.Cooldown>(machine.snapshot.value.overlay)
    }

    /** 离开后冷却恢复推进，到点回 INACTIVE（无效果），再打开回时间选择（新会话）。 */
    @Test
    fun `离开后冷却推进到点回 INACTIVE 且下次打开回时间选择`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 120_000L)
        machine.dispatch(GuardEvent.ForegroundEntered) // 停留冷却浮层

        val hide = machine.dispatch(GuardEvent.Backgrounded)
        assertEquals(listOf(GuardEffect.HideOverlay), hide)

        val effects = tickAfter(machine, clock, 300_000L)
        assertEquals(emptyList(), effects)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        assertNull(machine.snapshot.value.remainingMillis)

        val reopen = machine.dispatch(GuardEvent.ForegroundEntered)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(reopen).single())
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
    }

    /** 宽限耗尽后多出的过冲时间计入放弃冷却（与连续 Tick 等价）。 */
    @Test
    fun `宽限过冲时间计入放弃冷却`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)

        tickAfter(machine, clock, 121_000L) // 超出宽限 1 秒

        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)
        assertEquals(299_000L, machine.snapshot.value.remainingMillis)
    }

    /** 宽限期与放弃冷却时长可在配置中修改。 */
    @Test
    fun `宽限期与放弃冷却时长取自定义配置`() {
        val clock = FakeClock()
        val time = TimeParams(gracePeriodMillis = 30_000L, abandonCooldownMillis = 45_000L)
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN, time = time), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)

        tickAfter(machine, clock, 29_000L)
        assertEquals(GuardState.GRACE, machine.snapshot.value.state)
        assertEquals(1_000L, machine.snapshot.value.remainingMillis)

        tickAfter(machine, clock, 1_000L)
        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)
        assertEquals(45_000L, machine.snapshot.value.remainingMillis)
    }

    @Test
    fun `放弃冷却机制关闭则宽限耗尽直接回 INACTIVE`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)

        val effects = tickAfter(machine, clock, 120_000L)

        assertEquals(emptyList(), effects)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        // 冷却跳过 → 重开直接回时间选择
        val reopen = machine.dispatch(GuardEvent.ForegroundEntered)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(reopen).single())
    }

    /** 冷却浮层载荷：延时可用性反映本会话延时未用 + 机制开关，保护时长来自 config。 */
    @Test
    fun `放弃冷却浮层携带延时可用与保护时长`() {
        val clock = FakeClock()
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN, Mechanism.EXTENSION),
            clock,
        )
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 120_000L)

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(effects).single())
        assertEquals(CooldownKind.ABANDON, cooldown.kind)
        assertEquals(true, cooldown.extensionAvailable)
        assertEquals(10_000L, cooldown.extensionProtectMillis)
    }

    /** 重复退后台不重置宽限（息屏与切后台都是同一事件，宽限从第一次离开起算）。 */
    @Test
    fun `重复退后台不重置宽限倒计时`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L)
        machine.dispatch(GuardEvent.Backgrounded) // 二次事件不重置

        tickAfter(machine, clock, 61_000L) // 累计 121s > 120s 宽限
        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)
    }

    @Test
    fun `冷却浮层未使用延时的可用性随机制开关`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 120_000L)

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(effects).single())
        assertEquals(false, cooldown.extensionAvailable) // EXTENSION 机制关
    }
}
