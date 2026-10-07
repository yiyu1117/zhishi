package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * ticket 06 —— 每日限额（min 到期、额度中断、零点重置）、直接记账模式。
 */
class DailyLimitAndLedgerTest {

    /** 当日累计达限额即立即中断会话（即使所选时长未尽）。 */
    @Test
    fun `当日累计达限额立即中断会话`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 10), clock)
        startSession(machine, 15)

        val effects = tickAfter(machine, clock, 10 * 60_000L)

        assertEquals(
            listOf(GuardEffect.ShowOverlay(OverlaySpec.QuotaExhausted(usedLimitMinutes = 10, confirmProtectMillis = 5_000L))),
            effects,
        )
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.QUOTA_EXHAUSTED, snapshot.state)
        assertEquals(10 * 60_000L, snapshot.usedTodayMillis) // 只记到额度耗尽点
        assertNull(snapshot.remainingMillis)
    }

    /** 所选时长先尽（额度充裕）→ 正常到期（原地回时间选择）而非额度中断。 */
    @Test
    fun `所选时长先尽则正常到时而非额度中断`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, dailyLimitMinutes = 30), clock)
        startSession(machine, 5)

        tickAfter(machine, clock, 5 * 60_000L)

        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
    }

    /** 额度用尽浮层：确定送回桌面并落盘（无 HideOverlay、无延时），停留额度用尽态。 */
    @Test
    fun `额度用尽浮层确定送回桌面并停留该状态`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 10), clock)
        startSession(machine, 15)
        tickAfter(machine, clock, 10 * 60_000L)

        val effects = machine.dispatch(GuardEvent.ConfirmExit)
        assertEquals(listOf(GuardEffect.GoHome, GuardEffect.FlushState), effects)
        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)

        // 之后回到应用：仍然只有额度用尽浮层
        val reopen = machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(listOf(GuardEffect.ShowOverlay(OverlaySpec.QuotaExhausted(10, 5_000L))), reopen)

        val hide = machine.dispatch(GuardEvent.Backgrounded)
        assertEquals(listOf(GuardEffect.HideOverlay), hide)
        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)
    }

    /** 时间选择浮层显示今日剩余额度分钟（限额 20，已用 8 分 20 秒 → 剩 11）。 */
    @Test
    fun `时间选择浮层显示今日剩余额度`() {
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 20),
            usedToday = 500_000L,
        )
        machine.dispatch(GuardEvent.ForegroundEntered)
        val selection = assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
        assertEquals(11, selection.todayRemainingMinutes)
    }

    /** 每日限额 0 = 不限：只受所选时长约束，剩余额度显示为 null。 */
    @Test
    fun `限额为0表示不限额度`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 0), clock)
        startSession(machine, 5)
        assertNull(machine.snapshot.value.todayRemainingMinutes)

        tickAfter(machine, clock, 5 * 60_000L)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
    }

    /** 零点重置：额度用尽态在 DayChanged 后解除（用户不在应用内则静默回 INACTIVE）。 */
    @Test
    fun `零点重置解除额度禁用并可重新使用`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 10), clock)
        startSession(machine, 15)
        tickAfter(machine, clock, 10 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)

        val effects = machine.dispatch(GuardEvent.DayChanged)
        assertEquals(emptyList(), effects)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.INACTIVE, snapshot.state)
        assertEquals(0L, snapshot.usedTodayMillis)

        // 零点后可重新选时使用，额度恢复满额
        machine.dispatch(GuardEvent.ForegroundEntered)
        val selection = assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
        assertEquals(10, selection.todayRemainingMinutes)
        machine.dispatch(GuardEvent.DurationSelected(10))
        assertEquals(10 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 用户停留在额度浮层跨零点：立即切换到时间选择浮层。 */
    @Test
    fun `停留在额度浮层跨零点立即回到时间选择`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 10), clock)
        startSession(machine, 15)
        tickAfter(machine, clock, 10 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        machine.dispatch(GuardEvent.ForegroundEntered) // 停留额度浮层

        val effects = machine.dispatch(GuardEvent.DayChanged)

        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
        assertEquals(0L, machine.snapshot.value.usedTodayMillis)
    }

    /** 会话中跨零点：仅重置记账，会话继续。 */
    @Test
    fun `会话中跨零点仅重置记账`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 30), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 5 * 60_000L)

        val effects = machine.dispatch(GuardEvent.DayChanged)

        assertEquals(emptyList(), effects)
        assertEquals(GuardState.RUNNING, machine.snapshot.value.state)
        assertEquals(0L, machine.snapshot.value.usedTodayMillis)
        assertEquals(5 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 无时间选择机制 + 有限额：直接记账模式达限中断。 */
    @Test
    fun `直接记账模式达限中断`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(dailyLimitMinutes = 5), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)

        val effects = tickAfter(machine, clock, 5 * 60_000L)

        assertEquals(listOf(GuardEffect.ShowOverlay(OverlaySpec.QuotaExhausted(5, 5_000L))), effects)
        assertEquals(5 * 60_000L, machine.snapshot.value.usedTodayMillis)
    }

    /** 直接记账模式退后台暂停累计，回来继续。 */
    @Test
    fun `直接记账模式退后台暂停累计`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(dailyLimitMinutes = 30), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 60_000L)

        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 10 * 60_000L) // 后台不走账
        assertEquals(60_000L, machine.snapshot.value.usedTodayMillis)

        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 60_000L)
        assertEquals(120_000L, machine.snapshot.value.usedTodayMillis)
        assertEquals(GuardState.DIRECT_RUNNING, machine.snapshot.value.state)
    }

    /** 额度恰好与所选时长同时耗尽：按额度耗尽处理。 */
    @Test
    fun `额度与所选时长同时耗尽按额度中断处理`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 5), clock)
        startSession(machine, 5)

        tickAfter(machine, clock, 5 * 60_000L)

        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)
    }

    /** 宽限期耗尽判放弃时，额度用尽日的会话记账已如实累计（记账与状态独立）。 */
    @Test
    fun `会话被中断后当日记账保留至零点`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN, dailyLimitMinutes = 20), clock)
        startSession(machine, 20)
        tickAfter(machine, clock, 8 * 60_000L)
        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 120_000L) // 中途放弃
        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)
        assertEquals(8 * 60_000L, machine.snapshot.value.usedTodayMillis)
    }
}
