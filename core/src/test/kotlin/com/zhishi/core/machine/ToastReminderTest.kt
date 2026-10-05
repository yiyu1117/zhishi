package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ticket 07 —— 周期用量提示、剩余提醒刻度、撞点顺延、后台不弹。
 */
class ToastReminderTest {

    /** 会话进行中每 10 分钟弹"已使用 X 分钟"（本会话累计）。 */
    @Test
    fun `每10分钟报告本会话累计使用`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.USAGE_TOAST), clock)
        startSession(machine, 35)

        assertEquals(listOf(ToastEvent.UsagePeriod(10)), toasts(tickAfter(machine, clock, 600_000L)))
        assertEquals(listOf(ToastEvent.UsagePeriod(20)), toasts(tickAfter(machine, clock, 600_000L)))
        assertEquals(listOf(ToastEvent.UsagePeriod(30)), toasts(tickAfter(machine, clock, 600_000L)))
    }

    /** 距实际到期剩 5/2/1 分钟时各弹一次剩余提醒。 */
    @Test
    fun `剩余提醒刻度按521触发`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.TIME_REMINDER), clock)
        startSession(machine, 60)

        assertEquals(emptyList(), toasts(tickAfter(machine, clock, 54 * 60_000L))) // 剩 6 分钟，未触发
        assertEquals(listOf(ToastEvent.TimeRemaining(5)), toasts(tickAfter(machine, clock, 60_000L))) // 剩 5
        assertEquals(emptyList(), toasts(tickAfter(machine, clock, 2 * 60_000L))) // 剩 3
        assertEquals(listOf(ToastEvent.TimeRemaining(2)), toasts(tickAfter(machine, clock, 60_000L))) // 剩 2
        assertEquals(listOf(ToastEvent.TimeRemaining(1)), toasts(tickAfter(machine, clock, 60_000L))) // 剩 1
        // 最后一分钟走完 → 到时浮层，无更多刻度
        val expiry = tickAfter(machine, clock, 60_000L)
        assertEquals(1, expiry.size)
        assertIs<GuardEffect.ShowOverlay>(expiry.single())
    }

    /** 撞点时只弹一条：剩余提醒优先，周期提示顺延到下一个 Tick。 */
    @Test
    fun `撞点时剩余提醒优先周期提示顺延`() {
        val clock = FakeClock()
        // 会话 15 分钟，周期 10 分钟，刻度 5/2/1：第 10 分钟同时是"剩 5 分钟"刻度点
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.USAGE_TOAST, Mechanism.TIME_REMINDER),
            clock,
        )
        startSession(machine, 15)

        val atCollision = tickAfter(machine, clock, 600_000L)
        assertEquals(listOf(ToastEvent.TimeRemaining(5)), toasts(atCollision))

        val nextTick = tickAfter(machine, clock, 1_000L)
        assertEquals(listOf(ToastEvent.UsagePeriod(10)), toasts(nextTick))
    }

    /** 提醒刻度可在设置中增删：只保留 3 分钟刻度。 */
    @Test
    fun `提醒刻度可自定义`() {
        val clock = FakeClock()
        val time = TimeParams(reminderMarksMinutes = listOf(3))
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.TIME_REMINDER, time = time), clock)
        startSession(machine, 10)

        assertEquals(emptyList(), toasts(tickAfter(machine, clock, 6 * 60_000L))) // 剩 4 分钟，未触发
        assertEquals(listOf(ToastEvent.TimeRemaining(3)), toasts(tickAfter(machine, clock, 60_000L))) // 剩 3
        // 到时不再有其他刻度
        val expiry = tickAfter(machine, clock, 3 * 60_000L)
        assertEquals(1, expiry.size)
        assertIs<GuardEffect.ShowOverlay>(expiry.single())
    }

    /** 会话开始时已经过的刻度不补发（选 3 分钟：5 分钟刻度永不触发）。 */
    @Test
    fun `会话开始时已过的刻度不补发`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.TIME_REMINDER), clock)
        startSession(machine, 3)

        assertEquals(listOf(ToastEvent.TimeRemaining(2)), toasts(tickAfter(machine, clock, 60_000L)))
        assertEquals(listOf(ToastEvent.TimeRemaining(1)), toasts(tickAfter(machine, clock, 60_000L)))
        val expiry = tickAfter(machine, clock, 60_000L)
        assertEquals(1, expiry.size)
        assertIs<GuardEffect.ShowOverlay>(expiry.single())
    }

    /** 额度先尽时，刻度按额度耗尽点计算（选 30 分钟、限额 7 分钟）。 */
    @Test
    fun `额度先尽时刻度按额度耗尽点计算`() {
        val clock = FakeClock()
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.TIME_REMINDER, dailyLimitMinutes = 7),
            clock,
        )
        startSession(machine, 30)

        assertEquals(emptyList(), toasts(tickAfter(machine, clock, 60_000L))) // 距额度点 6 分钟
        assertEquals(listOf(ToastEvent.TimeRemaining(5)), toasts(tickAfter(machine, clock, 60_000L)))
        assertEquals(emptyList(), toasts(tickAfter(machine, clock, 2 * 60_000L)))
        assertEquals(listOf(ToastEvent.TimeRemaining(2)), toasts(tickAfter(machine, clock, 60_000L)))
        assertEquals(emptyList(), toasts(tickAfter(machine, clock, 59_000L)))
        assertEquals(listOf(ToastEvent.TimeRemaining(1)), toasts(tickAfter(machine, clock, 1_000L)))
        // 最后一分钟走完：额度耗尽中断（此时不再有刻度）
        val expiry = tickAfter(machine, clock, 60_000L)
        assertEquals(1, expiry.size)
        assertIs<GuardEffect.ShowOverlay>(expiry.single())
        assertEquals(GuardState.QUOTA_EXHAUSTED, machine.snapshot.value.state)
    }

    /** 宽限/后台期间两类提醒均不弹，回前台后周期计数从会话累计继续。 */
    @Test
    fun `后台暂停期间两类提醒均不弹`() {
        val clock = FakeClock()
        val time = TimeParams(usageToastIntervalMillis = 60_000L)
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.USAGE_TOAST, time = time), clock)
        startSession(machine, 10)

        assertEquals(listOf(ToastEvent.UsagePeriod(1)), toasts(tickAfter(machine, clock, 60_000L)))

        machine.dispatch(GuardEvent.Backgrounded)
        assertEquals(emptyList(), toasts(tickAfter(machine, clock, 60_000L))) // 宽限期内不弹

        machine.dispatch(GuardEvent.ForegroundEntered) // 宽限期内回来，会话继续
        assertEquals(listOf(ToastEvent.UsagePeriod(2)), toasts(tickAfter(machine, clock, 60_000L))) // 会话累计第 2 分钟
    }

    /** 延时使用期间周期提示照常（无剩余刻度），延时期冷却冻结不受影响。 */
    @Test
    fun `延时使用期间周期提示照常且无剩余刻度`() {
        val clock = FakeClock()
        val time = TimeParams(usageToastIntervalMillis = 180_000L, reminderMarksMinutes = emptyList())
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.USAGE_TOAST, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN, time = time),
            clock,
        )
        startSession(machine, 2)
        tickAfter(machine, clock, 120_000L) // → TIME_UP
        machine.dispatch(GuardEvent.ExtensionRequested)

        assertEquals(listOf(ToastEvent.UsagePeriod(3)), toasts(tickAfter(machine, clock, 60_000L))) // 会话累计 3 分钟
        val expiry = tickAfter(machine, clock, 60_000L)
        assertEquals(1, expiry.size)
        assertIs<GuardEffect.ShowOverlay>(expiry.single()) // 只有浮层效果，无 Toast
    }

    /** 两个 Toast 机制全关时对应提醒不弹。 */
    @Test
    fun `提醒机制关闭时不弹任何提醒`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 10)

        val effects = tickAfter(machine, clock, 10 * 60_000L)
        assertEquals(1, effects.size) // 只有 TIME_UP 浮层
        assertIs<GuardEffect.ShowOverlay>(effects.single())
    }

    /** 单步时间跳跃跨多个周期边界：按时间先后依次补发。 */
    @Test
    fun `时间跳跃跨多个周期边界依次补发`() {
        val clock = FakeClock()
        val machine = newMachine(testConfig(Mechanism.TIME_SELECTION, Mechanism.USAGE_TOAST), clock)
        startSession(machine, 25)

        assertEquals(
            listOf(ToastEvent.UsagePeriod(10), ToastEvent.UsagePeriod(20)),
            toasts(tickAfter(machine, clock, 20 * 60_000L)),
        )
    }
}
