package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.Mechanism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 到期即冷却（v1.1）、冷却墙钟推进、延时使用（一次/带入/冻结/预告）。
 */
class ExpiryCooldownAndExtensionTest {

    /** 会话到时 → 直接进入 8 分钟到期冷却，冷却浮层即刻显示（无独立确认浮层）。 */
    @Test
    fun `到时直接进入到期冷却并显示冷却浮层`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)

        val effects = tickAfter(machine, clock, 5 * 60_000L)

        assertIs<GuardEffect.CooldownStarted>(effects.filterIsInstance<GuardEffect.CooldownStarted>().single())
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(effects).single())
        assertEquals(CooldownKind.EXPIRY, cooldown.kind)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.EXPIRY_COOLDOWN, snapshot.state)
        assertEquals(480_000L, snapshot.remainingMillis)
        assertEquals(cooldown, snapshot.overlay)
    }

    /** 冷却浮层「返回桌面」即刻可点：送回桌面并落盘，冷却按墙钟继续。 */
    @Test
    fun `冷却浮层返回桌面送回桌面且冷却继续`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)

        val effects = machine.dispatch(GuardEvent.ConfirmExit)

        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.HideOverlay, GuardEffect.FlushState), effects)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(480_000L, machine.snapshot.value.remainingMillis)

        tickAfter(machine, clock, 60_000L)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 停留在冷却浮层上冷却照常推进（墙钟）；走完原地回时间选择。 */
    @Test
    fun `停留冷却浮层冷却照常推进走完原地回时间选择`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L) // → 冷却 8 分钟，用户停留

        tickAfter(machine, clock, 60_000L)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis)

        val effects = tickAfter(machine, clock, 7 * 60_000L)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
    }

    /** 冷却中重开应用显示冷却浮层；停留期间倒计时照常推进（v1.1 取消冻结）。 */
    @Test
    fun `冷却中重开显示冷却浮层且停留照常推进`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L) // 冷却剩 7 分钟

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(effects).single())
        assertEquals(CooldownKind.EXPIRY, cooldown.kind)
        assertEquals(true, cooldown.extensionAvailable)

        tickAfter(machine, clock, 60_000L) // 停留 1 分钟，照常走
        assertEquals(6 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 冷却中发起延时：冷却冻结为 pending，延时 2 分钟前台计时。 */
    @Test
    fun `冷却中请求延时进入延时而冷却冻结`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L) // 冷却剩 7 分钟
        machine.dispatch(GuardEvent.ForegroundEntered)

        val effects = machine.dispatch(GuardEvent.ExtensionRequested)

        assertEquals(listOf(GuardEffect.HideOverlay), effects)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.EXTENSION_RUNNING, snapshot.state)
        assertEquals(120_000L, snapshot.remainingMillis)

        tickAfter(machine, clock, 60_000L)
        assertEquals(60_000L, machine.snapshot.value.remainingMillis) // 延时在走
        assertEquals(360_000L, machine.snapshot.value.usedTodayMillis) // 5 分钟会话 + 1 分钟延时，计入当日用量
    }

    /** 延时结束前 30 秒预告一次，不重复。 */
    @Test
    fun `延时结束前30秒预告一次`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ExtensionRequested)

        val prewarn = tickAfter(machine, clock, 90_000L)
        assertEquals(listOf(GuardEffect.ShowToast(ToastEvent.ExtensionPrewarn(30))), prewarn)

        val again = tickAfter(machine, clock, 1_000L)
        assertEquals(emptyList(), again) // 不重复预告
    }

    /** 延时耗尽直接回到冷却（按发起时冻结的剩余续接），冷却浮层不再提供延时。 */
    @Test
    fun `延时结束回到冷却且无延时按钮`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ExtensionRequested) // pending = 满额 8 分钟

        val effects = tickAfter(machine, clock, 120_000L)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(480_000L, machine.snapshot.value.remainingMillis)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(effects).single())
        assertEquals(false, cooldown.extensionAvailable)

        // 延时已用：再次请求被忽略
        assertEquals(emptyList(), machine.dispatch(GuardEvent.ExtensionRequested))
    }

    /** 延时来自推进过的冷却浮层时，冷却冻结于发起时刻的剩余；延时结束按剩余续接。 */
    @Test
    fun `延时结束后按冻结剩余续接冷却`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L) // 冷却消耗 1 分钟 → 剩 7 分钟
        machine.dispatch(GuardEvent.ForegroundEntered)
        machine.dispatch(GuardEvent.ExtensionRequested) // 冷却冻结于 7 分钟
        tickAfter(machine, clock, 120_000L) // 延时走完 → 直接回冷却

        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis) // 冻结的剩余
    }

    /** 延时期离开：视为延时提前结束，按冻结剩余进冷却，延时标记已用。 */
    @Test
    fun `延时期离开按冻结剩余进冷却且延时标记已用`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L) // 剩 7 分钟
        machine.dispatch(GuardEvent.ForegroundEntered)
        machine.dispatch(GuardEvent.ExtensionRequested)
        tickAfter(machine, clock, 30_000L) // 延时进行到一半

        val effects = machine.dispatch(GuardEvent.Backgrounded)

        // pending 续接是同一冷却 Episode：不再发 CooldownStarted（共享不二次分发）
        assertEquals(emptyList(), effects)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis)

        // 重开：冷却浮层不再显示延时按钮
        val reopen = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(reopen).single())
        assertEquals(false, cooldown.extensionAvailable)
    }

    /** 未用延时带入冷却：冷却浮层显示延时按钮；冷却结束后新会话延时配额恢复。 */
    @Test
    fun `未用延时带入冷却且新会话配额恢复`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)

        // 本会话未用延时 → 冷却浮层按钮可用
        val cooldown = assertIs<OverlaySpec.Cooldown>(machine.snapshot.value.overlay)
        assertEquals(true, cooldown.extensionAvailable)

        // 冷却自然结束（离开后墙钟推进）→ 新会话 → 延时仍可用
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 480_000L)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        val again = assertIs<OverlaySpec.Cooldown>(machine.snapshot.value.overlay)
        assertEquals(true, again.extensionAvailable)
        assertEquals(listOf(GuardEffect.HideOverlay), machine.dispatch(GuardEvent.ExtensionRequested))
    }

    @Test
    fun `延时机制关闭时按钮不可用且请求被忽略`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)

        val cooldown = assertIs<OverlaySpec.Cooldown>(machine.snapshot.value.overlay)
        assertEquals(false, cooldown.extensionAvailable)
        assertEquals(emptyList(), machine.dispatch(GuardEvent.ExtensionRequested))
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
    }

    @Test
    fun `到期冷却机制关闭时到期原地回时间选择`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION), clock)
        startSession(machine, 5)

        val effects = tickAfter(machine, clock, 5 * 60_000L)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())

        // 选时浮层的「退出」：送回桌面
        val exit = machine.dispatch(GuardEvent.ConfirmExit)
        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.HideOverlay), exit)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
    }
}
