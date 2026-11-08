package com.zhishi.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联二次确认状态机（票 06）：悬浮窗窗口不可获焦、无法承载模态对话框，
 * 「重置所有计时」以按钮确认态就地二次确认——取消不执行、确认才清空、超时自动复位。
 * 时间由调用方注入（单调毫秒时钟），超时判定确定性可测。
 */
class InlineConfirmGateTest {

    private val timeout = 5_000L

    @Test
    fun `初始空闲时确认无效`() {
        val gate = InlineConfirmGate(timeout)
        assertFalse(gate.pending.value)
        assertFalse(gate.confirm(1_000L))
    }

    @Test
    fun `进入确认态后窗口内确认才执行并复位`() {
        val gate = InlineConfirmGate(timeout)
        gate.arm(100L)
        assertTrue(gate.pending.value)
        assertTrue(gate.confirm(100L + timeout - 1))
        assertFalse(gate.pending.value)
    }

    @Test
    fun `超窗确认不执行并自动复位`() {
        val gate = InlineConfirmGate(timeout)
        gate.arm(100L)
        assertFalse(gate.confirm(100L + timeout))
        assertFalse(gate.pending.value)
    }

    @Test
    fun `取消复位且不执行`() {
        val gate = InlineConfirmGate(timeout)
        gate.arm(100L)
        gate.cancel()
        assertFalse(gate.pending.value)
        assertFalse(gate.confirm(100L + 1))
    }

    @Test
    fun `确认态重复进入不续期`() {
        val gate = InlineConfirmGate(timeout)
        gate.arm(100L)
        gate.arm(100L + 3_000L) // 重复进入应忽略，窗口仍从首次进入起算
        // 若第二次 arm 续期，此处（距首 arm 已满超时）会被误判为窗口内
        assertFalse(gate.confirm(100L + timeout))
        assertFalse(gate.pending.value)
    }

    @Test
    fun `取消后可再次进入确认态`() {
        val gate = InlineConfirmGate(timeout)
        gate.arm(0L)
        gate.cancel()
        gate.arm(10_000L)
        assertTrue(gate.pending.value)
        assertTrue(gate.confirm(10_000L + timeout - 1))
    }

    @Test
    fun `超时复位检查到点自动回空闲`() {
        val gate = InlineConfirmGate(timeout)
        gate.arm(100L)
        assertFalse(gate.expireIfDue(100L + timeout - 1)) // 窗口内不复位
        assertTrue(gate.pending.value)
        assertTrue(gate.expireIfDue(100L + timeout)) // 到点自动复位
        assertFalse(gate.pending.value)
    }
}
