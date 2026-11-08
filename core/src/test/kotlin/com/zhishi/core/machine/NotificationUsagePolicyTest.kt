package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ticket 01 —— 常驻通知剩余用量策略：给定各状态机快照与退后台时间戳，
 * 输出应发布的通知用量或 null（回落通用守护文案）。
 */
class NotificationUsagePolicyTest {

    /** 前台会话中的机器照常发布实时剩余（现有行为经由策略保持不变）。 */
    @Test
    fun `前台会话中的机器发布实时剩余`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 10)

        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = listOf(machine.snapshot.value),
            held = null,
            heldSnapshot = null,
            nowMillis = clock.nowMillis(),
        )

        assertEquals(NotificationUsagePayload("com.test.app", 10 * 60_000L), payload)
    }

    /** 会话退后台后 20 秒内，通知保留退后台时刻冻结的会话剩余（而非快照里的宽限剩余）。 */
    @Test
    fun `退后台20秒内保留冻结的会话剩余`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 3 * 60_000L) // 已用 3 分钟，会话剩余 7 分钟
        val frozenRemaining = machine.snapshot.value.remainingMillis!!
        assertEquals(7 * 60_000L, frozenRemaining)
        val backgroundedAt = clock.nowMillis()
        machine.dispatch(GuardEvent.Backgrounded) // 进宽限：快照剩余变为宽限倒计时
        val graceSnapshot = machine.snapshot.value
        assertEquals(GuardState.GRACE, graceSnapshot.state)
        tickAfter(machine, clock, 1_000L) // 宽限推进 1 秒，快照剩余已与冻结值不同

        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = HeldNotificationUsage("com.test.app", frozenRemaining, backgroundedAt),
            heldSnapshot = machine.snapshot.value,
            nowMillis = clock.nowMillis(),
        )

        assertEquals(NotificationUsagePayload("com.test.app", 7 * 60_000L), payload)
    }

    /** 保留窗口恰满 20 秒即回落：窗口内最后一刻仍显示，到点即 null。 */
    @Test
    fun `保留窗口恰满20秒即回落`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        val backgroundedAt = clock.nowMillis()
        machine.dispatch(GuardEvent.Backgrounded)
        val held = HeldNotificationUsage("com.test.app", 10 * 60_000L, backgroundedAt)

        val atEdge = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = held,
            heldSnapshot = machine.snapshot.value,
            nowMillis = backgroundedAt + 19_999L,
        )
        val pastEdge = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = held,
            heldSnapshot = machine.snapshot.value,
            nowMillis = backgroundedAt + 20_000L,
        )

        assertEquals(NotificationUsagePayload("com.test.app", 10 * 60_000L), atEdge)
        assertEquals(null, pastEdge)
    }

    /** 保留期内宽限耗尽转入放弃冷却：立即回落（不等 20 秒），冷却中应用绝不显示剩余。 */
    @Test
    fun `转入放弃冷却立即回落`() {
        val clock = FakeClock()
        val time = TimeParams(gracePeriodMillis = 5_000L, smartTimingEnabled = false)
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN, time = time), clock)
        startSession(machine, 10)
        val backgroundedAt = clock.nowMillis()
        machine.dispatch(GuardEvent.Backgrounded)
        val held = HeldNotificationUsage("com.test.app", 10 * 60_000L, backgroundedAt)
        tickAfter(machine, clock, 6_000L) // 宽限 5 秒耗尽 → 放弃冷却（保留窗口内）
        assertEquals(GuardState.ABANDON_COOLDOWN, machine.snapshot.value.state)

        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = held,
            heldSnapshot = machine.snapshot.value,
            nowMillis = backgroundedAt + 6_000L,
        )

        assertEquals(null, payload)
    }

    /** 保留期内机器转入到期冷却或当日额度耗尽：立即回落，不给冷却/禁用中的应用显示剩余。 */
    @Test
    fun `转入到期冷却或额度耗尽立即回落`() {
        val backgroundedAt = 0L
        val held = HeldNotificationUsage("com.test.app", 10 * 60_000L, backgroundedAt)
        fun heldSnapshotOf(state: GuardState) = GuardSnapshot(
            packageName = "com.test.app",
            state = state,
            overlay = null,
            remainingMillis = null,
            usedTodayMillis = 0L,
            todayRemainingMinutes = null,
        )

        val expiry = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = held,
            heldSnapshot = heldSnapshotOf(GuardState.EXPIRY_COOLDOWN),
            nowMillis = backgroundedAt + 1_000L,
        )
        val quota = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = held,
            heldSnapshot = heldSnapshotOf(GuardState.QUOTA_EXHAUSTED),
            nowMillis = backgroundedAt + 1_000L,
        )

        assertEquals(null, expiry)
        assertEquals(null, quota)
    }

    /** 保留期内另一受管应用开始会话：通知立即接管显示新应用的实时剩余。 */
    @Test
    fun `保留期内另一应用开始会话立即接管`() {
        val clock = FakeClock()
        val heldApp = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN),
            clock,
            packageName = "com.test.held",
        )
        startSession(heldApp, 10)
        val backgroundedAt = clock.nowMillis()
        heldApp.dispatch(GuardEvent.Backgrounded)
        val held = HeldNotificationUsage("com.test.held", 10 * 60_000L, backgroundedAt)

        val other = newMachine(
            testConfig(Mechanism.TIME_SELECTION),
            clock,
            packageName = "com.test.other",
        )
        startSession(other, 15)

        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = listOf(other.snapshot.value),
            held = held,
            heldSnapshot = heldApp.snapshot.value,
            nowMillis = backgroundedAt + 5_000L,
        )

        assertEquals(NotificationUsagePayload("com.test.other", 15 * 60_000L), payload)
    }

    /** 保留期内另一受管应用开始延时（EXTENSION_RUNNING）：通知立即接管显示延时剩余。 */
    @Test
    fun `保留期内另一应用开始延时立即接管`() {
        val clock = FakeClock()
        val heldApp = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN),
            clock,
            packageName = "com.test.held",
        )
        startSession(heldApp, 10)
        val backgroundedAt = clock.nowMillis()
        heldApp.dispatch(GuardEvent.Backgrounded)
        val held = HeldNotificationUsage("com.test.held", 10 * 60_000L, backgroundedAt)

        val other = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN, Mechanism.EXTENSION),
            clock,
            packageName = "com.test.other",
        )
        startSession(other, 5)
        tickAfter(other, clock, 5 * 60_000L) // 会话到期 → 到期冷却（前台停留）
        assertEquals(GuardState.EXPIRY_COOLDOWN, other.snapshot.value.state)
        other.dispatch(GuardEvent.ExtensionRequested) // 发起延时
        assertEquals(GuardState.EXTENSION_RUNNING, other.snapshot.value.state)
        val extensionRemaining = other.snapshot.value.remainingMillis!!

        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = listOf(other.snapshot.value),
            held = held,
            heldSnapshot = heldApp.snapshot.value,
            nowMillis = backgroundedAt + 5_000L,
        )

        assertEquals(NotificationUsagePayload("com.test.other", extensionRemaining), payload)
    }

    /** 回到原应用：宽限期回会话，通知恢复实时倒计时（随前台消耗推进，非冻结值）。 */
    @Test
    fun `回到原应用恢复实时倒计时`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 10)
        tickAfter(machine, clock, 3 * 60_000L) // 会话剩余 7 分钟
        val backgroundedAt = clock.nowMillis()
        machine.dispatch(GuardEvent.Backgrounded)
        val held = HeldNotificationUsage("com.test.app", 7 * 60_000L, backgroundedAt)

        machine.dispatch(GuardEvent.ForegroundEntered) // 回到应用：恢复会话
        val onReturn = NotificationUsagePolicy.resolve(
            foregroundSnapshots = listOf(machine.snapshot.value),
            held = held,
            heldSnapshot = machine.snapshot.value,
            nowMillis = backgroundedAt + 2_000L,
        )
        tickAfter(machine, clock, 60_000L) // 前台继续消耗 1 分钟
        val afterMinute = NotificationUsagePolicy.resolve(
            foregroundSnapshots = listOf(machine.snapshot.value),
            held = held,
            heldSnapshot = machine.snapshot.value,
            nowMillis = backgroundedAt + 62_000L,
        )

        assertEquals(NotificationUsagePayload("com.test.app", 7 * 60_000L), onReturn)
        assertEquals(NotificationUsagePayload("com.test.app", 6 * 60_000L), afterMinute)
    }

    /** 无前台活动会话且无退后台保留：回落通用守护文案（null）。 */
    @Test
    fun `无活动会话且无保留时回落`() {
        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = null,
            heldSnapshot = null,
            nowMillis = 0L,
        )

        assertEquals(null, payload)
    }

    /** 保留中的机器被移除（换组/重置等）：立即回落，不残留冻结信息。 */
    @Test
    fun `保留中的机器被移除立即回落`() {
        val held = HeldNotificationUsage("com.test.app", 10 * 60_000L, backgroundedAtMillis = 0L)

        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = emptyList(),
            held = held,
            heldSnapshot = null,
            nowMillis = 1_000L,
        )

        assertEquals(null, payload)
    }

    /** 保留期内另一前台应用尚无会话（时间选择浮层中）：保留继续显示原应用的冻结剩余。 */
    @Test
    fun `保留期内前台应用无会话时保留继续`() {
        val clock = FakeClock()
        val heldApp = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN),
            clock,
            packageName = "com.test.held",
        )
        startSession(heldApp, 10)
        val backgroundedAt = clock.nowMillis()
        heldApp.dispatch(GuardEvent.Backgrounded)
        val held = HeldNotificationUsage("com.test.held", 10 * 60_000L, backgroundedAt)

        val other = newMachine(
            testConfig(Mechanism.TIME_SELECTION),
            clock,
            packageName = "com.test.other",
        )
        other.dispatch(GuardEvent.ForegroundEntered) // 时间选择浮层中，尚无会话

        val payload = NotificationUsagePolicy.resolve(
            foregroundSnapshots = listOf(other.snapshot.value),
            held = held,
            heldSnapshot = heldApp.snapshot.value,
            nowMillis = backgroundedAt + 5_000L,
        )

        assertEquals(NotificationUsagePayload("com.test.held", 10 * 60_000L), payload)
    }
}
