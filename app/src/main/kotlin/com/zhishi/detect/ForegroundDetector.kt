package com.zhishi.detect

/**
 * 前台变化监听：packageName 为 null 表示退到后台或息屏。
 */
interface ForegroundListener {
    fun onForegroundChanged(packageName: String?)

    /**
     * 无障碍窗口扫描：当前屏幕上所有可见窗口的包名集合（分屏/自由窗口中的被管理
     * 应用未获焦也应拦截）。轮询实现不提供（保持缺省空实现）；调用频率与窗口事件一致，
     * 实现方自行去重。
     */
    fun onVisiblePackagesChanged(packages: Set<String>) = Unit
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

    /**
     * 立即复扫一次当前窗口（v1.2，退后台挂起确认的兜底证据）：无障碍实现返回
     * 可见窗口集合 + 焦点窗口包名，并把可见窗口集合照常推给 listener；
     * 轮询实现不支持，返回 null（调用方退回 UsageStats 证据路径）。
     */
    fun rescan(): WindowScanResult? = null
}

/** 窗口复扫结果（v1.2）：[focusedPackage] 为 null 表示当前无持焦窗口。 */
data class WindowScanResult(
    val visiblePackages: Set<String>,
    val focusedPackage: String?,
)
