package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 延迟选时（v1.1 第七机制）：打开应用先进入试探期（前台计时、计入当日限额），
 * 期满弹时间选择浮层；退后台暂停，中断超保留期清零；与开屏时间选择互斥（选时优先）。
 */
class DelayedSelectionTest {

    private fun trialConfig(
        dailyLimitMinutes: Int = 0,
        trialMinutes: Int = 10,
        retentionMillis: Long = 600_000L,
    ) = testConfig(
        Mechanism.DELAYED_SELECTION,
        dailyLimitMinutes = dailyLimitMinutes,
        time = TimeParams(
            delayedSelectionMinutes = trialMinutes,
            delayedSelectionRetentionMillis = retentionMillis,
        ),
    )

    /** 打开应用进入试探期：无浮层，剩余为试探期时长。 */
    @Test
    fun `打开应用进入试探期不弹浮层`() {
        val machine = newMachine(trialConfig())

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)

        assertEquals(emptyList(), effects)
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.TRIAL_RUNNING, snapshot.state)
        assertNull(snapshot.overlay)
        assertEquals(600_000L, snapshot.remainingMillis)
    }

    /** 试探期前台计时并计入当日限额；退后台暂停、回来续计。 */
    @Test
    fun `试探期前台计时退后台暂停回来续计`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(dailyLimitMinutes = 30), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)

        tickAfter(machine, clock, 3 * 60_000L)
        assertEquals(3 * 60_000L, machine.snapshot.value.usedTodayMillis)
        assertEquals(7 * 60_000L, machine.snapshot.value.remainingMillis)

        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 5 * 60_000L) // 后台不走账、试探不走（保留期内）
        assertEquals(GuardState.TRIAL_RUNNING, machine.snapshot.value.state)
        assertEquals(3 * 60_000L, machine.snapshot.value.usedTodayMillis)

        machine.dispatch(GuardEvent.ForegroundEntered) // 保留期内回来
        tickAfter(machine, clock, 60_000L)
        assertEquals(4 * 60_000L, machine.snapshot.value.usedTodayMillis)
        assertEquals(6 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 试探期满弹时间选择浮层，选定后进入正常会话。 */
    @Test
    fun `试探期满弹时间选择选定后进正常会话`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)

        val effects = tickAfter(machine, clock, 10 * 60_000L)

        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
        assertEquals(10 * 60_000L, machine.snapshot.value.usedTodayMillis) // 试探期已记账

        machine.dispatch(GuardEvent.DurationSelected(15))
        assertEquals(GuardState.RUNNING, machine.snapshot.value.state)
        assertEquals(15 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 试探期内周期用量提示照常（按本应用前台累计）。 */
    @Test
    fun `试探期周期用量提示照常`() {
        val clock = FakeClock()
        val time = TimeParams(
            delayedSelectionMinutes = 30,
            usageToastIntervalMillis = 60_000L,
        )
        val machine = newMachine(
            testConfig(Mechanism.DELAYED_SELECTION, Mechanism.USAGE_TOAST, time = time),
            clock,
        )
        machine.dispatch(GuardEvent.ForegroundEntered)

        assertEquals(listOf(ToastEvent.UsagePeriod(1)), toasts(tickAfter(machine, clock, 60_000L)))
    }

    /** 退后台未超中断保留期：回来续计；超过保留期：试探进度清零重计。 */
    @Test
    fun `中断保留期内续计超期清零`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 9 * 60_000L) // 剩 1 分钟

        // 保留期内回来：续计
        machine.dispatch(GuardEvent.Backgrounded)
        clock.advance(5 * 60_000L)
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(60_000L, machine.snapshot.value.remainingMillis)

        // 超过保留期：清零重计
        machine.dispatch(GuardEvent.Backgrounded)
        clock.advance(600_000L + 1_000L)
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(600_000L, machine.snapshot.value.remainingMillis)
    }

    /** 试探期额度耗尽进「额度用尽」。 */
    @Test
    fun `试探期额度耗尽进额度用尽`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(dailyLimitMinutes = 5), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)

        val effects = tickAfter(machine, clock, 5 * 60_000L)

        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)
        assertIs<OverlaySpec.QuotaExhausted>(overlaySpecs(effects).single())
    }

    /** 浮层退出后下次打开重新计满试探期；当日已用不回退。 */
    @Test
    fun `浮层退出后下次打开重新试探`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 10 * 60_000L) // → 时间选择
        machine.dispatch(GuardEvent.ConfirmExit) // 退出
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)

        machine.dispatch(GuardEvent.ForegroundEntered)

        assertEquals(GuardState.TRIAL_RUNNING, machine.snapshot.value.state)
        assertEquals(600_000L, machine.snapshot.value.remainingMillis) // 重新计满
        assertEquals(10 * 60_000L, machine.snapshot.value.usedTodayMillis) // 已用不回退
    }

    /** 开屏时间选择与延迟选时同时开启时，开屏选时优先。 */
    @Test
    fun `开屏选时优先于延迟选时`() {
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.DELAYED_SELECTION))

        machine.dispatch(GuardEvent.ForegroundEntered)

        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(machine.snapshot.value.overlay)
    }

    /** 共享冷却走完时停在应用内：延迟选时组原地进入新试探期。 */
    @Test
    fun `冷却走完原地重入试探期`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(), clock)
        // 后台闲置接收共享冷却 → 打开应用见冷却浮层 → 停留至走完
        machine.applySharedCooldown(CooldownKind.EXPIRY, 60_000L)
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)

        val effects = tickAfter(machine, clock, 60_000L)

        assertEquals(GuardState.TRIAL_RUNNING, machine.snapshot.value.state)
        assertEquals(600_000L, machine.snapshot.value.remainingMillis)
        assertNull(machine.snapshot.value.overlay)
    }

    /** 试探期状态导出恢复：剩余与中断保留语义一致。 */
    @Test
    fun `试探期导出恢复`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 4 * 60_000L)
        machine.dispatch(GuardEvent.Backgrounded)

        val data = machine.exportState()
        assertEquals(GuardState.TRIAL_RUNNING, data.kind)
        assertEquals(6 * 60_000L, data.remainingMillis)

        // 停机 20 分钟（超保留期）后重启恢复，回来重新计满
        clock.advance(20 * 60_000L)
        val restored = newMachine(trialConfig(), clock, restored = data)
        assertEquals(GuardState.TRIAL_RUNNING, restored.snapshot.value.state)
        restored.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(600_000L, restored.snapshot.value.remainingMillis)
    }
}
