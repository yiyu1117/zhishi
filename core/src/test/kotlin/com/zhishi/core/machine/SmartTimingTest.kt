package com.zhishi.core.machine

import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 智能时长联动（v1.1）：到期冷却/宽限期/放弃冷却按比例 × 基数计算；
 * 不足 1 分钟保留秒级精度，达到 1 分钟向上取整到整分钟。
 */
class SmartTimingTest {

    private fun smartConfig(
        vararg mechanisms: Mechanism,
        expiryPercent: Int = 25,
        gracePercent: Int = 17,
        abandonPercent: Int = 17,
    ) = testConfig(
        *mechanisms,
        time = TimeParams(
            smartTimingEnabled = true,
            smartExpiryCooldownPercent = expiryPercent,
            smartGracePercent = gracePercent,
            smartAbandonCooldownPercent = abandonPercent,
        ),
    )

    /** 到期冷却 = 25% × 实际前台消耗：玩 1h → 冷却 15 分钟。 */
    @Test
    fun `智能到期冷却按实际消耗比例计算`() {
        val clock = FakeClock()
        val machine = newMachine(smartConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 60)

        val effects = tickAfter(machine, clock, 60 * 60_000L)

        val started = effects.filterIsInstance<GuardEffect.CooldownStarted>().single()
        assertEquals(15 * 60_000L, started.durationMillis)
        assertEquals(15 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 非整分钟结果向上取整：45 分 × 25% = 11.25 分 → 12 分。 */
    @Test
    fun `智能到期冷却向上取整到整分钟`() {
        val clock = FakeClock()
        val machine = newMachine(smartConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 45)

        tickAfter(machine, clock, 45 * 60_000L)

        assertEquals(12 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 不足 1 分钟保留秒级精度（短用短罚）：1 分 × 25% = 15 秒。 */
    @Test
    fun `智能到期冷却短用时保留秒级`() {
        val clock = FakeClock()
        val machine = newMachine(smartConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), clock)
        startSession(machine, 1)

        tickAfter(machine, clock, 60_000L)

        assertEquals(15_000L, machine.snapshot.value.remainingMillis)
    }

    /** 延时续用的前台时间计入到期冷却基数：5 分会话 + 2 分延时 → 7 分 × 25% = 105 秒 → 2 分。 */
    @Test
    fun `智能到期冷却基数包含延时时间`() {
        val clock = FakeClock()
        val machine = newMachine(
            smartConfig(Mechanism.TIME_SELECTION, Mechanism.EXTENSION, Mechanism.EXPIRY_COOLDOWN),
            clock,
        )
        startSession(machine, 5)
        tickAfter(machine, clock, 5 * 60_000L)
        machine.dispatch(GuardEvent.ExtensionRequested)
        tickAfter(machine, clock, 2 * 60_000L)

        // 7 分 × 25% = 1.75 分 → 向上取整 2 分
        assertEquals(120_000L, machine.snapshot.value.remainingMillis)
    }

    /** 智能宽限期 = 17% × 所选时长：60 分 → 10.2 分 → 11 分。 */
    @Test
    fun `智能宽限期按所选时长比例计算`() {
        val clock = FakeClock()
        val machine = newMachine(smartConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 60)

        machine.dispatch(GuardEvent.Backgrounded)

        assertEquals(11 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 智能宽限期保底 2 分钟：5 分 × 17% = 51 秒 → 保底 2 分。 */
    @Test
    fun `智能宽限期保底两分钟`() {
        val clock = FakeClock()
        val machine = newMachine(smartConfig(Mechanism.TIME_SELECTION), clock)
        startSession(machine, 5)

        machine.dispatch(GuardEvent.Backgrounded)

        assertEquals(120_000L, machine.snapshot.value.remainingMillis)
    }

    /** 智能放弃冷却 = 17% × 放弃时实际已用：30 分 → 5.1 分 → 6 分。 */
    @Test
    fun `智能放弃冷却按实际已用比例计算`() {
        val clock = FakeClock()
        val machine = newMachine(smartConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 60)
        tickAfter(machine, clock, 30 * 60_000L)

        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 11 * 60_000L) // 智能宽限（60 分选时 → 11 分）耗尽判放弃

        assertEquals(6 * 60_000L, machine.snapshot.value.remainingMillis)
    }

    /** 智能放弃冷却无保底：用 1 分 → 10.2 秒冷却（秒级显示）。 */
    @Test
    fun `智能放弃冷却短用时无保底`() {
        val clock = FakeClock()
        val machine = newMachine(smartConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN), clock)
        startSession(machine, 60)
        tickAfter(machine, clock, 60_000L)

        machine.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine, clock, 11 * 60_000L) // 智能宽限耗尽判放弃

        assertEquals(10_200L, machine.snapshot.value.remainingMillis)
    }

    /** 智能联动关闭时沿用固定配置值（回归锚点）。 */
    @Test
    fun `智能联动关闭回退固定值`() {
        val clock = FakeClock()
        val time = TimeParams(
            smartTimingEnabled = false,
            expiryCooldownMillis = 480_000L,
            gracePeriodMillis = 120_000L,
            abandonCooldownMillis = 300_000L,
        )
        val machine = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN, Mechanism.ABANDON_COOLDOWN, time = time),
            clock,
        )
        startSession(machine, 60)
        tickAfter(machine, clock, 60 * 60_000L)
        assertEquals(480_000L, machine.snapshot.value.remainingMillis) // 固定 8 分钟

        val machine2 = newMachine(
            testConfig(Mechanism.TIME_SELECTION, Mechanism.ABANDON_COOLDOWN, time = time),
            clock,
        )
        startSession(machine2, 60)
        machine2.dispatch(GuardEvent.Backgrounded)
        tickAfter(machine2, clock, 120_000L)
        assertEquals(300_000L, machine2.snapshot.value.remainingMillis) // 固定 5 分钟
    }
}
