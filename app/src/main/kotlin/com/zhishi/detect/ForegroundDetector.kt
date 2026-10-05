package com.zhishi.detect

/**
 * 前台变化监听：packageName 为 null 表示退到后台或息屏。
 */
fun interface ForegroundListener {
    fun onForegroundChanged(packageName: String?)
}

/**
 * 前台检测统一契约（ADR 0001）。两个实现共用同一接口，设置页可切换：
 * - [ZhishiAccessibilityService]：无障碍 provider（默认模式，毫秒级，无常驻服务）
 * - ticket 09 的 UsageStats 轮询 provider（兼容模式，前台服务 + 常驻通知）
 */
interface ForegroundDetector {
    /** 开始检测，前台变化回调到 listener；重复调用以最后一次为准。 */
    fun start(listener: ForegroundListener)

    /** 停止检测并释放回调。 */
    fun stop()
}
