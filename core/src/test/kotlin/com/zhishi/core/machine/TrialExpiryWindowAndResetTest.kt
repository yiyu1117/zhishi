package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.2 试探期与延迟选时的两项变更：
 * - 试探暂停超保留自动转闲置（Tick 补偿驱动，不再等下次前台才发现）；
 * - 试探期满未选时间、退出浮层后，保留窗口内重开直接弹选时浮层（每次退出重新起算）。
 * 另含配置保存重置（resetRuntimeState）与开屏选时媒体暂停延迟标记的回归。
 */
class TrialExpiryWindowAndResetTest {

    private fun trialConfig(
        trialMinutes: Int = 10,
        retentionMillis: Long = 600_000L,
    ) = testConfig(
        Mechanism.DELAYED_SELECTION,
        time = TimeParams(
            delayedSelectionMinutes = trialMinutes,
            delayedSelectionRetentionMillis = retentionMillis,
        ),
    )

    private fun selectionConfig() = testConfig(Mechanism.TIME_SELECTION)

    // ---------------------------------------------------------------- 试探保留自动清零

    /** 暂停超保留期：无需前台事件，Tick 补偿即转闲置（调试悬浮窗不再挂过期倒计时）。 */
    @Test
    fun `暂停超保留自动转闲置`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 3 * 60_000L)
        machine.dispatch(GuardEvent.Backgrounded)

        val effects = tickAfter(machine, clock, 600_000L + 1_000L)

        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        assertEquals(emptyList(), effects)
        assertNull(machine.snapshot.value.resetCountdownMillis)

        // 转闲置后重开：重新计满试探期（与旧"下次前台才发现"语义一致）
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.TRIAL_RUNNING, machine.snapshot.value.state)
        assertEquals(600_000L, machine.snapshot.value.remainingMillis)
        assertEquals(3 * 60_000L, machine.snapshot.value.usedTodayMillis) // 已用不回退
    }

    /** 保留期内暂停：不转闲置，快照带清零倒计时；回来续计不受影响。 */
    @Test
    fun `保留期内暂停带清零倒计时`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 3 * 60_000L)
        machine.dispatch(GuardEvent.Backgrounded)

        tickAfter(machine, clock, 60_000L)
        assertEquals(GuardState.TRIAL_RUNNING, machine.snapshot.value.state)
        assertEquals(540_000L, machine.snapshot.value.resetCountdownMillis)

        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 60_000L)
        assertEquals(4 * 60_000L, machine.snapshot.value.usedTodayMillis)
        assertEquals(6 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    // ---------------------------------------------------------------- 试探期满重开窗口

    /** 试探期满弹选时浮层不带媒体暂停延迟（使用中期满，媒体已在播放）。 */
    @Test
    fun `试探期满浮层无媒体暂停延迟`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)

        val effects = tickAfter(machine, clock, 10 * 60_000L)

        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertEquals(listOf(0L), overlayDelays(effects))
    }

    /** 退出试探期满的选时浮层：进入重开窗口（状态保持待选择，截止 = 现在 + 保留时长）。 */
    @Test
    fun `试探期满退出起算重开窗口`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 10 * 60_000L)

        val exitEffects = machine.dispatch(GuardEvent.ConfirmExit)

        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state) // 未回闲置
        assertTrue(GuardEffect.GoHome in exitEffects)
        assertEquals(clock.nowMillis() + 600_000L, machine.exportState().selectionDeadlineMillis)
    }

    /** 经 Home/返回键离开试探期满的浮层（非「退出」按钮）：同样起算重开窗口。 */
    @Test
    fun `Home离开浮层同样起算重开窗口`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 10 * 60_000L)

        machine.dispatch(GuardEvent.Backgrounded) // 浮层展示中按 Home
        assertEquals(clock.nowMillis() + 600_000L, machine.exportState().selectionDeadlineMillis)

        // 窗口内重开：直接弹选时
        clock.advance(60_000L)
        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())

        // 超窗：转闲置（不再滞留待选择）
        machine.dispatch(GuardEvent.Backgrounded)
        machine.dispatch(GuardEvent.ConfirmExit) // 重新起算后退出
        tickAfter(machine, clock, 600_000L + 1_000L)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
    }

    /** 重开窗口内打开应用：直接弹选时浮层（带开屏媒体暂停延迟），不再重新计满试探期。 */
    @Test
    fun `窗口内重开直接弹选时`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 10 * 60_000L) // 期满弹选时
        machine.dispatch(GuardEvent.ConfirmExit) // 退出 → 起算窗口
        clock.advance(60_000L)

        val effects = machine.dispatch(GuardEvent.ForegroundEntered)

        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
        assertEquals(OPEN_SCREEN_MEDIA_PAUSE_DELAY_MILLIS, overlayDelays(effects).single())
        assertEquals(10 * 60_000L, machine.snapshot.value.usedTodayMillis) // 未重新试探、未重复记账
    }

    /** 窗口内重开后再退出：窗口重新起算（每次退出重新计保留时长）。 */
    @Test
    fun `重开后再退出窗口重新起算`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 10 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)
        clock.advance(60_000L)
        machine.dispatch(GuardEvent.ForegroundEntered) // 窗口内重开，弹选时

        assertNull(machine.exportState().selectionDeadlineMillis) // 展示中无窗口；退出才起算
        machine.dispatch(GuardEvent.ConfirmExit)

        val deadline = checkNotNull(machine.exportState().selectionDeadlineMillis)
        assertEquals(clock.nowMillis() + 600_000L, deadline) // 重新起算（而非沿用第一次的截止）

        // 第一次的截止（已过去）不再约束：仍能直接弹选时 → 证明窗口确实重新起算了
        clock.advance(595_000L)
        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())
    }

    /** 超过重开窗口：自动转闲置，下次打开重新计满试探期；重开窗口的清零倒计时可见。 */
    @Test
    fun `超窗转闲置重开重新试探`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 10 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)

        tickAfter(machine, clock, 60_000L)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
        assertEquals(540_000L, machine.snapshot.value.resetCountdownMillis)

        tickAfter(machine, clock, 540_000L)
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)

        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.TRIAL_RUNNING, machine.snapshot.value.state)
        assertEquals(600_000L, machine.snapshot.value.remainingMillis)
        assertEquals(10 * 60_000L, machine.snapshot.value.usedTodayMillis) // 已用不回退
    }

    /** 重开窗口状态导出恢复：截止为绝对时刻，恢复后窗口语义保持；跨过截止则按闲置。 */
    @Test
    fun `重开窗口导出恢复`() {
        val clock = FakeClock()
        val machine = newMachine(trialConfig(retentionMillis = 600_000L), clock)
        machine.dispatch(GuardEvent.ForegroundEntered)
        tickAfter(machine, clock, 10 * 60_000L)
        machine.dispatch(GuardEvent.ConfirmExit)

        val data = machine.exportState()
        assertEquals(clock.nowMillis() + 600_000L, data.selectionDeadlineMillis)

        clock.advance(120_000L)
        val restored = newMachine(trialConfig(retentionMillis = 600_000L), clock, restored = data)
        assertEquals(GuardState.AWAITING_SELECTION, restored.snapshot.value.state)
        val effects = restored.dispatch(GuardEvent.ForegroundEntered)
        assertIs<OverlaySpec.TimeSelection>(overlaySpecs(effects).single())

        clock.advance(600_000L) // 已跨过截止
        val expired = newMachine(trialConfig(retentionMillis = 600_000L), clock, restored = data)
        expired.dispatch(GuardEvent.Tick)
        assertEquals(GuardState.INACTIVE, expired.snapshot.value.state)
    }

    /** 开屏时间选择（非试探期满）退出浮层：仍直接回闲置，无重开窗口。 */
    @Test
    fun `开屏选时退出仍回闲置`() {
        val machine = newMachine(selectionConfig())
        machine.dispatch(GuardEvent.ForegroundEntered)
        machine.dispatch(GuardEvent.ConfirmExit)

        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        assertNull(machine.exportState().selectionDeadlineMillis)
    }

    // ---------------------------------------------------------------- 开屏选时媒体暂停延迟

    /** 开屏选时浮层带 3 秒媒体暂停延迟标记。 */
    @Test
    fun `开屏选时带媒体暂停延迟`() {
        val machine = newMachine(selectionConfig())
        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(listOf(OPEN_SCREEN_MEDIA_PAUSE_DELAY_MILLIS), overlayDelays(effects))
    }

    // ---------------------------------------------------------------- 配置保存重置

    /** 会话中重置：回闲置，当日已用保留，剩余清零。 */
    @Test
    fun `重置回闲置保留当日已用`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 30), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 3 * 60_000L)
        assertEquals(3 * 60_000L, machine.snapshot.value.usedTodayMillis)

        machine.resetRuntimeState()

        val snapshot = machine.snapshot.value
        assertEquals(GuardState.INACTIVE, snapshot.state)
        assertNull(snapshot.overlay)
        assertEquals(3 * 60_000L, snapshot.usedTodayMillis)
        // 重置后重开：按入口语义重新进入
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
    }

    /** 当日额度已用尽时重置：落额度用尽（禁用语义保留），不回闲置。 */
    @Test
    fun `额度用尽时重置仍禁用`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, dailyLimitMinutes = 5), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 5 * 60_000L) // 5 分钟额度先于会话耗尽
        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)

        machine.resetRuntimeState()

        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)
    }

    /** 冷却中重置：冷却清除（不按墙钟续走），回闲置。 */
    @Test
    fun `冷却中重置清冷却`() {
        val clock = FakeClock()
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN),
            clock,
        )
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L) // 到期进冷却
        assertEquals(GuardState.EXPIRY_COOLDOWN, machine.snapshot.value.state)

        machine.resetRuntimeState()
        assertEquals(GuardState.INACTIVE, machine.snapshot.value.state)
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(GuardState.AWAITING_SELECTION, machine.snapshot.value.state)
    }

    // ---------------------------------------------------------------- helpers

    private fun overlayDelays(effects: List<GuardEffect>): List<Long> =
        effects.filterIsInstance<GuardEffect.ShowOverlay>().map { it.mediaPauseDelayMillis }
}
