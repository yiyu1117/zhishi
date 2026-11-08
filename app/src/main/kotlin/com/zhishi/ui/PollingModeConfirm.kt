package com.zhishi.ui

import com.zhishi.core.model.DetectionMode

/**
 * 轮询（兼容）模式实验性确认——文案与决策的唯一定义（票 05）。
 *
 * 设置页检测模式与首启引导页「不开启无障碍，改用兼容模式」两处共用，
 * 保证口径一致；后续如新增切向轮询的入口，也应走同一决策与文案。
 * 本文件不依赖 Android/Compose，确认决策可在 JVM 单测中直接验证。
 */

/** 确认框正文（规格逐字文案，勿改）。 */
internal const val POLLING_EXPERIMENTAL_CONFIRM_MESSAGE =
    "轮询模式为实验性功能：可能存在较多未知 bug 与拦截问题，拦截响应可能有 1–2 秒延时，" +
        "且此模式目前测试覆盖很少。如无特殊需求，推荐使用无障碍模式。仍要切换吗？"

/** 确认框确认按钮（规格逐字）。 */
internal const val POLLING_CONFIRM_BUTTON_LABEL = "仍要开启"

/** 确认框取消按钮（规格逐字）：取消不切换。 */
internal const val POLLING_CANCEL_BUTTON_LABEL = "取消"

/**
 * 是否需要为本次点选弹实验性确认：仅「从非轮询切向轮询」拦截——
 * 切回无障碍不拦；重复点选已在用的模式不视为切换，同样不拦。
 */
internal fun shouldConfirmPollingSwitch(current: DetectionMode, target: DetectionMode): Boolean =
    target == DetectionMode.POLLING && current != DetectionMode.POLLING
