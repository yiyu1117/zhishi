package com.zhishi.guard

/**
 * ticket 01 示例守护应用（bootstrap 演示）：进入前台即弹演示浮层，不走状态机。
 *
 * 移除方式（ticket 02 分组落地后的维护者）：
 * 1. 删除本文件；
 * 2. 删除 GuardCoordinator.onForegroundApp 中两处 SampleGuard.PACKAGE 分支；
 * 3. 删除 OverlayManager.showSample 与 OverlayContent.Sample。
 */
object SampleGuard {
    const val PACKAGE = "com.android.chrome"
}
