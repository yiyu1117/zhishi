package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.Mechanism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ticket 05 —— 到期冷却、延时使用（一次/带入/冻结/预告）。
 */
class ExpiryCooldownAndExtensionTest {

    /** 会话到时 → 确定送回桌面 → 进入 8 分钟到期冷却。 */
    @Test
    fun `到时确定后送回桌面并进入8分钟到期冷却`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L) // → TIME_UP

        val effects = machine.dispatch(GuardEvent.ConfirmExit)

        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.HideOverlay, GuardEffect.FlushState), effects)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.EXPIRY_COOLDOWN, snapshot.state)
        assertEquals(480_000L, snapshot.remainingMillis)
        assertEquals(null, snapshot.overlay)
    }

    /** 用户在到时浮层自行离开：同样进冷却，但不送回桌面。 */
    @Test
    fun `到时浮层自行离开进冷却但不送回桌面`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)

        val effects = machine.dispatch(GuardEvent.Backgrounded)

        assertEquals(listOf(GuardEffect.HideOverlay), effects)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
    }

    /** 冷却中重开应用显示冷却浮层（"X 分钟后可打开"），停留期间冻结。 */
    @Test
    fun `冷却中重开显示冷却浮层且停留期间冻结`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L) // 冷却剩 7 分钟

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(effects).single())
        assertEquals(CooldownKind.EXPIRY, cooldown.kind)
        assertEquals(true, cooldown.extensionAvailable)

        tickAfter(machine, clock, 60_000L) // 停留 1 分钟
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis) // 冻结
    }

    /** 冷却推进 1 分钟后发起延时：冷却冻结于 7 分钟，延时 2 分钟前台计时。 */
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

    /** 延时耗尽回到"时间已到"浮层，按钮不可再用。 */
    @Test
    fun `延时结束回到时间已到且无延时按钮`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ExtensionRequested)

        val effects = tickAfter(machine, clock, 120_000L)
        assertEquals(listOf(GuardEffect.ShowOverlay(OverlaySpec.TimeUp(5_000L, false, 10_000L))), effects)
        assertEquals(GuardState.TIME_UP, machine.snapshot.value.state)

        // 延时已用：再次请求被忽略
        assertEquals(emptyList(), machine.dispatch(GuardEvent.ExtensionRequested))
    }

    /** 延时来自冷却浮层时，冷却冻结于发起时刻的剩余；再次确定按剩余冷却继续。 */
    @Test
    fun `延时结束后再次确定按剩余冷却继续`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L) // 冷却消耗 1 分钟 → 剩 7 分钟
        machine.dispatch(GuardEvent.ForegroundEntered)
        machine.dispatch(GuardEvent.ExtensionRequested) // 冷却冻结于 7 分钟
        tickAfter(machine, clock, 120_000L) // 延时走完 → TIME_UP

        val effects = machine.dispatch(GuardEvent.ConfirmExit)

        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.HideOverlay, GuardEffect.FlushState), effects)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis) // 冻结的剩余
    }

    /** 延时期间离开：视为延时提前结束，按冻结剩余进冷却。 */
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

        assertEquals(listOf(GuardEffect.HideOverlay), effects)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis)

        // 重开：冷却浮层不再显示延时按钮
        val reopen = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(reopen).single())
        assertEquals(false, cooldown.extensionAvailable)
    }

    /** 延时从"时间已到"浮层直接发起（冷却未开始）：结束回 TIME_UP，确定后进满额到期冷却。 */
    @Test
    fun `时间已到发起延时结束后确定进满额到期冷却`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ExtensionRequested)
        tickAfter(machine, clock, 120_000L) // → TIME_UP

        val effects = machine.dispatch(GuardEvent.ConfirmExit)
        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.HideOverlay, GuardEffect.FlushState), effects)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(480_000L, machine.snapshot.value.remainingMillis) // 满额
    }

    /** "时间已到"浮层延时期间离开：进满额到期冷却，延时标记已用。 */
    @Test
    fun `时间已到浮层延时期离开进满额到期冷却`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ExtensionRequested)
        tickAfter(machine, clock, 60_000L)

        val effects = machine.dispatch(GuardEvent.Backgrounded)
        assertEquals(listOf(GuardEffect.HideOverlay), effects)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)
        assertEquals(480_000L, machine.snapshot.value.remainingMillis)
    }

    /** 未用延时带入冷却：冷却浮层显示延时按钮；冷却结束后新会话延时配额恢复。 */
    @Test
    fun `未用延时带入冷却且新会话配额恢复`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)

        // 本会话未用延时 → 冷却浮层按钮可用
        val reopen = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(reopen).single())
        assertEquals(true, cooldown.extensionAvailable)

        // 冷却自然结束 → 新会话 → 延时仍可用
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 480_000L)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        val timeUp = assertIs<OverlaySpec.TimeUp>(machine.snapshot.value.overlay)
        assertEquals(true, timeUp.extensionAvailable)
        assertEquals(listOf(GuardEffect.HideOverlay), machine.dispatch(GuardEvent.ExtensionRequested))
    }

    @Test
    fun `延时机制关闭时按钮不可用且请求被忽略`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)

        val timeUp = assertIs<OverlaySpec.TimeUp>(machine.snapshot.value.overlay)
        assertEquals(false, timeUp.extensionAvailable)
        assertEquals(emptyList(), machine.dispatch(GuardEvent.ExtensionRequested))
        assertEquals(GuardState.TIME_UP, machine.snapshot.value.state)

        machine.dispatch(GuardEvent.ConfirmExit)
        val reopen = machine.dispatch(GuardEvent.ForegroundEntered)
        val cooldown = assertIs<OverlaySpec.Cooldown>(overlaySpecs(reopen).single())
        assertEquals(false, cooldown.extensionAvailable)
    }

    @Test
    fun `到期冷却机制关闭时确定退出直接回 INACTIVE`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)

        val effects = machine.dispatch(GuardEvent.ConfirmExit)
        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.HideOverlay, GuardEffect.FlushState), effects)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
    }
}
