package com.zhishi.data

import com.zhishi.core.model.TimeParams

/**
 * TimeParams 的统一编解码（DataStore 单字符串，分号分隔 20 段）。
 *
 * 供 GroupRepository（分组内嵌配置）与 SettingsRepository（全局默认参数）共用，
 * 保证两处编码格式永不分叉。新字段一律追加在串尾并带默认值回退，旧数据天然兼容。
 */
internal object TimeParamsFormat {

    private const val QUICK_OPTION_COUNT = 6

    /** 解码；无法解析（结构损坏）返回 null，由调用方回退种子/默认值。 */
    fun decode(encoded: String): TimeParams? = runCatching {
        val parts = encoded.split(';')
        val seed = TimeParams()
        TimeParams(
            quickOptionsMinutes = parts.getOrNull(0)
                ?.split(',')?.mapNotNull(String::toIntOrNull)?.filter { it > 0 }
                ?.takeIf { it.size == QUICK_OPTION_COUNT }
                ?: seed.quickOptionsMinutes,
            quickProtectMillis = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.quickProtectMillis,
            confirmProtectMillis = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.confirmProtectMillis,
            extensionProtectMillis = parts.getOrNull(3)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.extensionProtectMillis,
            extensionMinutes = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 }
                ?: seed.extensionMinutes,
            extensionPrewarnSeconds = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 600)
                ?: seed.extensionPrewarnSeconds,
            gracePeriodMillis = parts.getOrNull(6)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.gracePeriodMillis,
            abandonCooldownMillis = parts.getOrNull(7)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.abandonCooldownMillis,
            expiryCooldownMillis = parts.getOrNull(8)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.expiryCooldownMillis,
            usageToastIntervalMillis = parts.getOrNull(9)?.toLongOrNull()?.takeIf { it > 0 }
                ?: seed.usageToastIntervalMillis,
            // 空串 = 用户删光刻度（合法状态，不回退到默认 5/2/1）
            reminderMarksMinutes = parts.getOrNull(10)
                ?.split(',')?.mapNotNull(String::toIntOrNull)?.filter { it > 0 }
                ?.distinct()?.sortedDescending()
                ?: seed.reminderMarksMinutes,
            customMaxMinutes = parts.getOrNull(11)?.toIntOrNull()?.takeIf { it > 0 }
                ?: seed.customMaxMinutes,
            // ---- v1.1 追加字段（旧数据缺段回退默认值） ----
            smartTimingEnabled = parts.getOrNull(12)?.toIntOrNull()?.let { it != 0 }
                ?: seed.smartTimingEnabled,
            smartExpiryCooldownPercent = parts.getOrNull(13)?.toIntOrNull()?.coerceIn(0, 100)
                ?: seed.smartExpiryCooldownPercent,
            smartGracePercent = parts.getOrNull(14)?.toIntOrNull()?.coerceIn(0, 100)
                ?: seed.smartGracePercent,
            smartAbandonCooldownPercent = parts.getOrNull(15)?.toIntOrNull()?.coerceIn(0, 100)
                ?: seed.smartAbandonCooldownPercent,
            sharedCooldownEnabled = parts.getOrNull(16)?.toIntOrNull()?.let { it != 0 }
                ?: seed.sharedCooldownEnabled,
            sharedCooldownPercent = parts.getOrNull(17)?.toIntOrNull()?.coerceIn(0, 100)
                ?: seed.sharedCooldownPercent,
            delayedSelectionMinutes = parts.getOrNull(18)?.toIntOrNull()?.takeIf { it > 0 }
                ?: seed.delayedSelectionMinutes,
            delayedSelectionRetentionMillis = parts.getOrNull(19)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.delayedSelectionRetentionMillis,
        )
    }.getOrNull()

    fun encode(t: TimeParams): String = listOf(
        t.quickOptionsMinutes.joinToString(","),
        t.quickProtectMillis,
        t.confirmProtectMillis,
        t.extensionProtectMillis,
        t.extensionMinutes,
        t.extensionPrewarnSeconds,
        t.gracePeriodMillis,
        t.abandonCooldownMillis,
        t.expiryCooldownMillis,
        t.usageToastIntervalMillis,
        t.reminderMarksMinutes.joinToString(","),
        t.customMaxMinutes,
        if (t.smartTimingEnabled) 1 else 0,
        t.smartExpiryCooldownPercent,
        t.smartGracePercent,
        t.smartAbandonCooldownPercent,
        if (t.sharedCooldownEnabled) 1 else 0,
        t.sharedCooldownPercent,
        t.delayedSelectionMinutes,
        t.delayedSelectionRetentionMillis,
    ).joinToString(";")
}
