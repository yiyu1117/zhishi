package com.zhishi.core.model

/** 应用分组类型。DEFAULT 兜底、BLACKLIST 平禁、WHITELIST 豁免，三者不可删除；CUSTOM 可自由创建删除。 */
enum class GroupKind { DEFAULT, BLACKLIST, WHITELIST, CUSTOM }

/** 六项可独立开关的机制（每分组默认全关）。 */
enum class Mechanism {
    TIME_SELECTION,   // 开屏时间选择
    USAGE_TOAST,      // 周期用量提示
    TIME_REMINDER,    // 剩余时间提醒
    EXTENSION,        // 延时使用
    EXPIRY_COOLDOWN,  // 到期冷却
    ABANDON_COOLDOWN, // 放弃冷却
}

/** 全部时间参数。分组创建时由全局默认值播种，之后每组独立编辑。 */
data class TimeParams(
    /** 快选档位（分钟），默认 6 档 5/10/15/20/30/60。 */
    val quickOptionsMinutes: List<Int> = listOf(5, 10, 15, 20, 30, 60),
    /** 快选按钮保护时长。 */
    val quickProtectMillis: Long = 5_000,
    /** 确定（时间已到/额度用尽）按钮保护时长。 */
    val confirmProtectMillis: Long = 5_000,
    /** 延时按钮保护时长。 */
    val extensionProtectMillis: Long = 10_000,
    /** 延时使用时长（分钟）。 */
    val extensionMinutes: Int = 2,
    /** 延时结束前预告提前量（秒）。 */
    val extensionPrewarnSeconds: Int = 30,
    /** 宽限期时长。 */
    val gracePeriodMillis: Long = 120_000,
    /** 放弃冷却时长。 */
    val abandonCooldownMillis: Long = 300_000,
    /** 到期冷却时长。 */
    val expiryCooldownMillis: Long = 480_000,
    /** 周期用量提示间隔。 */
    val usageToastIntervalMillis: Long = 600_000,
    /** 剩余提醒刻度（分钟，距实际到期点）。 */
    val reminderMarksMinutes: List<Int> = listOf(5, 2, 1),
    /** 自定义时长上限（分钟）。 */
    val customMaxMinutes: Int = 120,
)

/**
 * 分组内嵌配置：机制开关 + 每日限额 + 全部时间参数。
 * 每日限额三元值：-1 = 禁用（黑名单组即此取值）；0 = 不限（默认）；N = 当日累计 N 分钟后不可再用。
 */
data class GroupConfig(
    val mechanisms: Set<Mechanism> = emptySet(),
    val dailyLimitMinutes: Int = 0,
    val time: TimeParams = TimeParams(),
) {
    fun mechanismEnabled(m: Mechanism): Boolean = m in mechanisms

    /** 当日剩余额度（毫秒）。null = 不限；0 = 已耗尽。 */
    fun remainingQuotaMillis(usedTodayMillis: Long): Long? = when {
        dailyLimitMinutes < 0 -> 0L
        dailyLimitMinutes == 0 -> null
        else -> (dailyLimitMinutes * 60_000L - usedTodayMillis).coerceAtLeast(0L)
    }
}

/** 分组：每个应用同一时刻恰好属于一个分组。 */
data class Group(
    val id: String,
    val name: String,
    val kind: GroupKind,
    val config: GroupConfig,
) {
    val isBuiltIn: Boolean get() = kind != GroupKind.CUSTOM
}

/** 每日限额为禁用（-1），即黑名单语义。 */
fun GroupConfig.isForbiddenByQuota(): Boolean = dailyLimitMinutes < 0
