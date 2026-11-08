package com.zhishi.ui

import com.zhishi.core.model.DetectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮询模式实验性确认决策（票 05）：
 * 仅「从非轮询切向轮询」需要确认——切回无障碍不拦，重复点选已在用的模式也不视为切换。
 */
class PollingModeConfirmTest {

    @Test
    fun `从无障碍切向轮询需要确认`() {
        assertTrue(
            shouldConfirmPollingSwitch(DetectionMode.ACCESSIBILITY, DetectionMode.POLLING),
        )
    }

    @Test
    fun `从轮询切回无障碍不拦截`() {
        assertFalse(
            shouldConfirmPollingSwitch(DetectionMode.POLLING, DetectionMode.ACCESSIBILITY),
        )
    }

    @Test
    fun `重复点选当前模式不拦截`() {
        assertFalse(
            shouldConfirmPollingSwitch(DetectionMode.POLLING, DetectionMode.POLLING),
        )
        assertFalse(
            shouldConfirmPollingSwitch(DetectionMode.ACCESSIBILITY, DetectionMode.ACCESSIBILITY),
        )
    }
}
