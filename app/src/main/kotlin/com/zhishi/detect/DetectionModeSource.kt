package com.zhishi.detect

import com.zhishi.core.model.DetectionMode

/**
 * 检测模式读取源：供无法使用挂起函数的场合（如开机广播）同步读取当前检测模式。
 *
 * 主控集成时把 [com.zhishi.data.SettingsRepository.detectionMode] 绑定进来（见
 * [DetectionModeSourceProvider]）；未绑定或读取失败时 fail-safe 返回 ACCESSIBILITY
 * （无障碍为默认模式，绝不误拉起轮询服务）。
 */
fun interface DetectionModeSource {

    /** 同步（阻塞）读取当前检测模式；实现方须保证调用短耗时（开机广播约 10s 预算）。 */
    fun currentModeBlocking(): DetectionMode
}

/**
 * [DetectionModeSource] 的进程级绑定点（detect 包内接缝，主控接线用，一行接入）：
 *
 * ```
 * // Application/AppGraph 初始化阶段执行一次即可：
 * DetectionModeSourceProvider.bind(DetectionModeSource {
 *     runBlocking { settingsRepository.detectionMode.first() }
 * })
 * ```
 *
 * 默认（未绑定）实现读不到任何设置，按 fail-safe 语义返回 ACCESSIBILITY；
 * 绑定后读取抛出的任何异常也按 ACCESSIBILITY 处理。
 */
object DetectionModeSourceProvider {

    @Volatile
    private var source: DetectionModeSource? = null

    /** 主控接线：绑定真实实现；可重复调用，以最后一次为准。 */
    fun bind(source: DetectionModeSource) {
        this.source = source
    }

    /** 当前检测模式；未绑定 / 读取异常 → ACCESSIBILITY（fail-safe，默认模式）。 */
    fun currentModeBlocking(): DetectionMode = try {
        source?.currentModeBlocking() ?: DetectionMode.ACCESSIBILITY
    } catch (_: Exception) {
        DetectionMode.ACCESSIBILITY
    }
}
