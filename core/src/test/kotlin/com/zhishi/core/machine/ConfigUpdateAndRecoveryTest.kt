package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 配置热更新（onConfigUpdated）与重启恢复（exportState / restored）语义。
 */
class ConfigUpdateAndRecoveryTest {

    // ---------------------------------------------------------------- 热更新

    @Test
    fun `热更新不清理进行中的会话`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 3 * 60_000L)

        machine.onConfigUpdated(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN))

        val snapshot = machine.snapshot.value
        assertEquals(GuardState.RUNNING, snapshot.state)
        assertEquals(7 * 60_000L, snapshot.remainingMillis)
        assertNull(snapshot.overlay)
    }

    @Test
    fun `热更新后的宽限期参数立即生效`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 10)
        machine.onConfigUpdated(
            testConfig(Mechanism.TIME_SELECTION, time = TimeParams(gracePeriodMillis = 30_000L)),
        )

        machine.dispatch(GuardEvent.Backgrounded)
        assertEquals(30_000L, machine.snapshot.value.remainingMillis)
    }

    @Test
    fun `热更新使当日额度立即超限时前台消耗态即刻中断`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 10), clock)
        startSession(machine, 15)
        tickAfter(machine, clock, 6 * 60_000L) // 已用 6 分钟

        machine.onConfigUpdated(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 5))

        val snapshot = machine.snapshot.value
        assertEquals(GuardState.QUOTA_EXHAUSTED, snapshot.state)
        assertEquals(OverlaySpec.QuotaExhausted(5, 5_000L), snapshot.overlay) // 前台停留，直接换浮层
    }

    @Test
    fun `热更新刷新时间选择浮层的配置派生载荷`() {
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION))
        machine.dispatch(GuardEvent.ForegroundEntered)

        machine.onConfigUpdated(
            testConfig(Mechanism.TIME_SELECTION, time = TimeParams(quickOptionsMinutes = listOf(1, 2, 3), customMaxMinutes = 30)),
        )

        assertEquals(
            OverlaySpec.TimeSelection(listOf(1, 2, 3), 30, null, 5_000L),
            machine.snapshot.value.overlay,
        )
    }

    @Test
    fun `热更新不清理冷却状态`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 3 * 60_000L) // 剩 5 分钟

        machine.onConfigUpdated(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN, Mechanism.EXTENSION))

        val snapshot = machine.snapshot.value
        assertEquals(GuardState.EXPIRY_COOLDOWN, snapshot.state)
        assertEquals(5 * 60_000L, snapshot.remainingMillis)
    }

    // ---------------------------------------------------------------- 导出与恢复

    @Test
    fun `RUNNING 导出后恢复为宽限期且会话剩余保持`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 3 * 60_000L)

        val data = machine.exportState()
        assertEquals(GuardStateData(GuardState.RUNNING, remainingMillis = 7 * 60_000L, extensionUsed = false), data)

        val restoredMachine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock, restored = data)
        val snapshot = restoredMachine.snapshot.value
        assertEquals(GuardState.GRACE, snapshot.state)
        assertEquals(120_000L, snapshot.remainingMillis) // 宽限从头计

        restoredMachine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.RUNNING, restoredMachine.snapshot.value.state)
        assertEquals(7 * 60_000L, restoredMachine.snapshot.value.remainingMillis) // 会话剩余不变
    }

    @Test
    fun `宽限期按剩余恢复并可继续走完判放弃`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L)

        val data = machine.exportState()
        assertEquals(GuardState.GRACE, data.kind)
        assertEquals(60_000L, data.remainingMillis)

        val restored = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock, restored = data)
        assertEquals(60_000L, restored.snapshot.value.remainingMillis)

        tickAfter(restored, clock, 61_000L)
        assertEquals(GuardState.ABANDON_COOLDOWN, restored.snapshot.value.state)
    }

    @Test
    fun `冷却按绝对截止时刻恢复且停机时间计入`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 60_000L) // 剩 7 分钟

        val data = machine.exportState()
        assertEquals(360_000L + 7 * 60_000L, data.cooldownDeadlineMillis)

        // 停机 100 秒后重启
        clock.advance(100_000L)
        val restored = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock, restored = data)
        assertEquals(GuardState.EXPIRY_COOLDOWN, restored.snapshot.value.state)
        assertEquals(7 * 60_000L - 100_000L, restored.snapshot.value.remainingMillis)
    }

    @Test
    fun `冷却截止已过则恢复为 INACTIVE`() {
        val clock = FakeClock()
        val data = GuardStateData(
            kind = GuardState.EXPIRY_COOLDOWN,
            cooldownDeadlineMillis = clock.nowMillis() - 1_000L, // 已过期
        )
        val restored = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock, restored = data)
        assertEquals(GuardState.INACTIVE, restored.snapshot.value.state)
    }

    @Test
    fun `延时期按剩余恢复且回前台前冻结`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION), clock)
        startSession(machine, 2)
        tickAfter(machine, clock, 2 * 60_000L) // → TIME_UP
        machine.dispatch(GuardEvent.ExtensionRequested)
        tickAfter(machine, clock, 30_000L) // 延时剩 90 秒

        val data = machine.exportState()
        assertEquals(GuardState.EXTENSION_RUNNING, data.kind)
        assertEquals(90_000L, data.remainingMillis)
        assertEquals(true, data.extensionUsed)

        val restored = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION), clock, restored = data)
        assertEquals(GuardState.EXTENSION_RUNNING, restored.snapshot.value.state)
        assertEquals(90_000L, restored.snapshot.value.remainingMillis)

        // 回到前台前不消耗
        tickAfter(restored, clock, 60_000L)
        assertEquals(90_000L, restored.snapshot.value.remainingMillis)

        // 回前台后恢复计时并走完回 TIME_UP（延时已用）
        restored.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(restored, clock, 90_000L)
        assertEquals(GuardState.TIME_UP, restored.snapshot.value.state)
        val timeUp = assertIs<OverlaySpec.TimeUp>(restored.snapshot.value.overlay)
        assertEquals(false, timeUp.extensionAvailable)
    }

    @Test
    fun `时间已到与延时已用标记完整恢复`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 2)
        tickAfter(machine, clock, 2 * 60_000L)
        machine.dispatch(GuardEvent.ExtensionRequested)
        tickAfter(machine, clock, 2 * 60_000L) // 延时走完 → TIME_UP

        val data = machine.exportState()
        assertEquals(GuardState.TIME_UP, data.kind)
        assertEquals(true, data.extensionUsed)

        val restored = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN), clock, restored = data)
        assertEquals(GuardState.TIME_UP, restored.snapshot.value.state)

        val effects = restored.dispatch(GuardEvent.ForegroundEntered)
        val timeUp = assertIs<OverlaySpec.TimeUp>(overlaySpecs(effects).single())
        assertEquals(false, timeUp.extensionAvailable) // 延时已用标记随恢复保留

        // 确定后进入到期冷却，流程闭环
        restored.dispatch(GuardEvent.ConfirmExit)
        assertEquals(GuardState.EXPIRY_COOLDOWN, restored.snapshot.value.state)
        assertEquals(480_000L, restored.snapshot.value.remainingMillis)
    }

    @Test
    fun `额度用尽状态导出恢复且零点重置生效`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 5), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 5 * 60_000L)

        val data = machine.exportState()
        assertEquals(GuardState.QUOTA_EXHAUSTED, data.kind)

        val restored = newMachine(
            testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 5),
            clock,
            usedToday = 5 * 60_000L,
            restored = data,
        )
        assertEquals(GuardState.QUOTA_EXHAUSTED, restored.snapshot.value.state)
        assertEquals(5 * 60_000L, restored.snapshot.value.usedTodayMillis)

        val reopen = restored.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(listOf(GuardEffect.ShowOverlay(OverlaySpec.QuotaExhausted(5, 5_000L))), reopen)

        // 用户停留额度浮层跨零点 → 立即回到时间选择（新的一天重新开始）
        restored.dispatch(GuardEvent.DayChanged)
        assertEquals(GuardState.AWAITING_SELECTION, restored.snapshot.value.state)
        assertEquals(0L, restored.snapshot.value.usedTodayMillis)
    }

    @Test
    fun `无保存状态时黑名单组直接以黑名单态启动`() {
        val machine = newMachine(testConfig(dailyLimitMinutes = -1))
        assertEquals(GuardState.BLACKLISTED, machine.snapshot.value.state)
        assertEquals(GuardStateData(GuardState.BLACKLISTED), machine.exportState())
    }

    @Test
    fun `当日已用毫秒通过构造参数注入记账起点`() {
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 30),
            usedToday = 25 * 60_000L,
        )
        assertEquals(25 * 60_000L, machine.snapshot.value.usedTodayMillis)

        machine.dispatch(GuardEvent.ForegroundEntered)
        val selection = assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
        assertEquals(5, selection.todayRemainingMinutes)
    }
}
