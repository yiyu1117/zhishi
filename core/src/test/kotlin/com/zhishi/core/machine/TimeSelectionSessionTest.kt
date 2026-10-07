package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * ticket 03 —— 开屏时间选择、会话前台计时、到时与确定退出。
 */
class TimeSelectionSessionTest {

    /** 打开应用 → 时间选择浮层，载荷全部来自 config。 */
    @Test
    fun `开启时间选择机制的应用打开即弹时间选择浮层`() {
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION))

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)

        val spec = overlaySpecs(effects).single()
        val timeSelection = assertIs<OverlaySpec.TimeSelection>(spec)
        assertEquals(listOf(5, 10, 15, 20, 30, 60), timeSelection.quickOptionsMinutes)
        assertEquals(120, timeSelection.customMaxMinutes)
        assertNull(timeSelection.todayRemainingMinutes) // 每日限额 0 = 不限
        assertEquals(5_000L, timeSelection.quickProtectMillis)

        val snapshot = machine.snapshot.value
        assertEquals(GuardState.AWAITING_SELECTION, snapshot.state)
        assertEquals(spec, snapshot.overlay)
        assertNull(snapshot.remainingMillis)
    }

    @Test
    fun `选定时长后进入会话并关闭浮层`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)

        val effects = machine.dispatch(GuardEvent.DurationSelected(15))

        assertEquals(listOf(GuardEffect.HideOverlay), effects)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.RUNNING, snapshot.state)
        assertNull(snapshot.overlay)
        assertEquals(15 * 60_000L, snapshot.remainingMillis)
    }

    @Test
    fun `零负数与超上限的时长被忽略且浮层保持`() {
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION))
        machine.dispatch(GuardEvent.ForegroundEntered)

        for (minutes in listOf(0, -3, 121)) {
            assertEquals(emptyList(), machine.dispatch(GuardEvent.DurationSelected(minutes)), "minutes=$minutes 应被忽略")
            assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
            assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
        }

        // 上限边界值 120 合法
        assertEquals(listOf(GuardEffect.HideOverlay), machine.dispatch(GuardEvent.DurationSelected(120)))
        assertEquals(GuardState.RUNNING, machine.snapshot.value.state)
    }

    @Test
    fun `前台使用至时间耗尽且无冷却机制时原地回时间选择`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 5)

        tickAfter(machine, clock, 4 * 60_000L)
        assertEquals(GuardState.RUNNING, machine.snapshot.value.state)
        assertEquals(60_000L, machine.snapshot.value.remainingMillis)

        val effects = tickAfter(machine, clock, 60_000L)
        // 到期即冷却，冷却机制未开启 → 原地重入入口语义（时间选择）
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
    }

    @Test
    fun `到时回时间选择后退出送回桌面`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L) // → 原地回时间选择
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)

        val effects = machine.dispatch(GuardEvent.ConfirmExit)

        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.HideOverlay), effects)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        assertNull(machine.snapshot.value.overlay)
    }

    @Test
    fun `切后台计时暂停回到应用从剩余时间继续`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 3 * 60_000L)

        assertEquals(emptyList(), machine.dispatch(GuardEvent.Backgrounded))
        assertEquals(GuardState.GRACE, machine.snapshot.value.state)
        assertEquals(120_000L, machine.snapshot.value.remainingMillis) // 宽限剩余
        tickAfter(machine, clock, 60_000L) // 后台停留 1 分钟，会话不走

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(emptyList(), effects)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.RUNNING, snapshot.state)
        assertEquals(7 * 60_000L, snapshot.remainingMillis) // 与离开前一致
        assertEquals(3 * 60_000L, snapshot.usedTodayMillis) // 后台时间不记账
    }

    @Test
    fun `机制关闭且无限额的分组不被拦截`() {
        val machine = newMachine(testConfig())
        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(emptyList(), effects)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        assertNull(machine.snapshot.value.overlay)
    }

    @Test
    fun `机制关闭但有限额的分组直接前台记账`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(dailyLimitMinutes = 30), clock)

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(emptyList(), effects) // 无浮层
        assertEquals(GuardState.DIRECT_RUNNING, machine.snapshot.value.state)
        assertNull(machine.snapshot.value.overlay)

        tickAfter(machine, clock, 60_000L)
        tickAfter(machine, clock, 60_000L)
        tickAfter(machine, clock, 60_000L)
        assertEquals(3 * 60_000L, machine.snapshot.value.usedTodayMillis)
        assertEquals(GuardState.DIRECT_RUNNING, machine.snapshot.value.state)
    }

    @Test
    fun `所选时长超过今日剩余额度时按剩余封顶会话`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 10), clock, usedToday = 7 * 60_000L)

        machine.dispatch(GuardEvent.ForegroundEntered)
        val selection = assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
        assertEquals(3, selection.todayRemainingMinutes)

        machine.dispatch(GuardEvent.DurationSelected(15))
        assertEquals(3 * 60_000L, machine.snapshot.value.remainingMillis) // min(15, 3)

        val effects = tickAfter(machine, clock, 3 * 60_000L)
        // 额度与所选同时耗尽 → 按额度耗尽处理（当日禁用）
        assertEquals(listOf(GuardEffect.ShowOverlay(OverlaySpec.QuotaExhausted(10, 5_000L))), effects)
        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)
        assertEquals(10 * 60_000L, machine.snapshot.value.usedTodayMillis)
    }

    @Test
    fun `时间选择浮层的今日剩余分钟向下取整`() {
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 10),
            usedToday = 3 * 60_000L + 30_000L,
        )
        machine.dispatch(GuardEvent.ForegroundEntered)
        val selection = assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
        assertEquals(6, selection.todayRemainingMinutes) // 剩余 6 分 30 秒 → 显示 6
    }

    @Test
    fun `离开时间选择浮层后重开会重新弹浮层`() {
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION))
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(listOf(GuardEffect.HideOverlay), machine.dispatch(GuardEvent.Backgrounded))
        assertNull(machine.snapshot.value.overlay)

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
    }
}
