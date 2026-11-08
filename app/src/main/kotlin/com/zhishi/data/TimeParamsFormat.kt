package com.zhishi.data

import com.zhishi.core.model.TimeParams

/**
 * TimeParams 的统一编解码（DataStore 单字符串，分号分隔 20 段）。
 *
 * 供 GroupRepository（分组内嵌配置）与 SettingsRepository（全局默认参数）共用，
 * 保证两处编码格式永不分叉；导入侧的损坏拒绝走 [decodeStrict]，段布局知识单点居所。
 * 新字段一律追加在串尾并带默认值回退，旧数据天然兼容。
 */
internal object TimeParamsFormat {

    private const val QUICK_OPTION_COUNT = 6
    private const val SEGMENT_COUNT = 20

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

    /**
     * time 串的严格解码：先做结构校验（段数、各段类型形状），任何一段损坏即返回 null，
     * 校验通过后经 [decode] 取值。与 [decode] 的差异：[decode] 对损坏段静默回退默认值
     * （存储侧兼容旧数据），本函数用于导入等必须整体拒绝损坏数据的场景——绝不带病写入。
     *
     * 段布局与 [decode]/[encode] 同居本对象，新字段追加串尾时须同步更新此处校验。
     */
    fun decodeStrict(encoded: String): TimeParams? {
        val parts = encoded.split(';')
        if (parts.size != SEGMENT_COUNT) return null
        // 段 0：快选档位 = 6 个正整数
        val quick = parts[0].split(',')
        if (quick.size != QUICK_OPTION_COUNT || quick.any { (it.toIntOrNull() ?: 0) <= 0 }) return null
        // 段 1/2/3/6/7/8/19：毫秒值（可为任意整数，语义钳制沿用 decode）
        listOf(1, 2, 3, 6, 7, 8, 19).forEach { idx ->
            if (parts[idx].toLongOrNull() == null) return null
        }
        // 段 4 延时分钟 / 段 9 提示间隔 / 段 11 自定义上限 / 段 18 试探期：正整数
        listOf(4, 9, 11, 18).forEach { idx ->
            if ((parts[idx].toIntOrNull() ?: 0) <= 0) return null
        }
        if (parts[5].toIntOrNull() == null) return null // 段 5 预告秒（decode 钳制 0–600）
        // 段 10：提醒刻度——可整段为空（用户删光刻度的合法状态），否则每段均为正整数
        if (parts[10].isNotEmpty() &&
            parts[10].split(',').any { (it.toIntOrNull() ?: 0) <= 0 }
        ) return null
        // 段 12/16：布尔（0/1）；段 13/14/15/17：比例整数（decode 钳制 0–100）
        listOf(12, 16).forEach { idx ->
            if (parts[idx] != "0" && parts[idx] != "1") return null
        }
        listOf(13, 14, 15, 17).forEach { idx ->
            if (parts[idx].toIntOrNull() == null) return null
        }
        return decode(encoded)
    }

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
