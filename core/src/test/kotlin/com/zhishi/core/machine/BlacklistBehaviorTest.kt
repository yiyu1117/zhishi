package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 黑名单组（每日限额 = -1）行为：与原桩实现一致 —— ForegroundEntered → Forbidden 浮层，
 * Backgrounded → HideOverlay；无会话、无延时、无冷却。
 */
class BlacklistBehaviorTest {

    private val machine = newMachine(testConfig(dailyLimitMinutes = -1))

    @Test
    fun `黑名单初始状态为 BLACKLISTED 且无浮层`() {
        val snapshot = machine.snapshot.value
        assertEquals(GuardState.BLACKLISTED, snapshot.state)
        assertNull(snapshot.overlay)
        assertNull(snapshot.remainingMillis)
    }

    @Test
    fun `进入前台弹禁止浮层`() {
        val effects = machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(listOf(GuardEffect.ShowOverlay(OverlaySpec.Forbidden)), effects)
        assertEquals(OverlaySpec.Forbidden, machine.snapshot.value.overlay)
        assertEquals(GuardState.BLACKLISTED, machine.snapshot.value.state)
    }

    @Test
    fun `退后台关闭禁止浮层但保持黑名单状态`() {
        machine.dispatch(GuardEvent.ForegroundEntered)
        val effects = machine.dispatch(GuardEvent.Backgrounded)
        assertEquals(listOf(GuardEffect.HideOverlay), effects)
        assertNull(machine.snapshot.value.overlay)
        assertEquals(GuardState.BLACKLISTED, machine.snapshot.value.state)
    }

    @Test
    fun `黑名单忽略会话延时与时间事件`() {
        machine.dispatch(GuardEvent.ForegroundEntered)
        assertEquals(emptyList(), machine.dispatch(GuardEvent.DurationSelected(10)))
        assertEquals(emptyList(), machine.dispatch(GuardEvent.ExtensionRequested))
        assertEquals(emptyList(), machine.dispatch(GuardEvent.ConfirmExit))
        assertEquals(emptyList(), machine.dispatch(GuardEvent.Tick))
        assertEquals(emptyList(), machine.dispatch(GuardEvent.DayChanged))
        assertEquals(GuardStateData(GuardState.BLACKLISTED, extensionUsed = false), machine.exportState())
        assertIs<OverlaySpec.Forbidden>(machine.snapshot.value.overlay)
    }
}
