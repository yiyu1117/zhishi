package com.zhishi.core.model

/** 应用分组类型。DEFAULT 兜底、BLACKLIST 平禁、WHITELIST 豁免，三者不可删除；CUSTOM 可自由创建删除。 */
enum class GroupKind { DEFAULT, BLACKLIST, WHITELIST, CUSTOM }

/** 七项可独立开关的机制（每分组默认全关）。 */
enum class Mechanism {
    TIME_SELECTION,   // 开屏时间选择
    USAGE_TOAST,      // 周期用量提示
    TIME_REMINDER,    // 剩余时间提醒
    EXTENSION,        // 延时使用
    EXPIRY_COOLDOWN,  // 到期冷却
    ABANDON_COOLDOWN, // 放弃冷却

    /**
     * 延迟选时：打开应用先进入试探期（前台计时），试探期满才弹时间选择浮层。
     * 与 [TIME_SELECTION] 互斥（UI 层联动开一个关另一个）；枚举追加在末尾，
     * 存量数据按序号存储不受影响。
     */
    DELAYED_SELECTION,
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
    /** 智能时长联动：开启后到期冷却/宽限期/放弃冷却按比例 × 基数实时计算，关闭用下方固定值。 */
    val smartTimingEnabled: Boolean = true,
    /** 智能到期冷却比例（%，基数 = 实际前台消耗，含延时）。 */
    val smartExpiryCooldownPercent: Int = 25,
    /** 智能宽限期比例（%，基数 = 开屏所选时长；保底 2 分钟）。 */
    val smartGracePercent: Int = 17,
    /** 智能放弃冷却比例（%，基数 = 放弃时实际已用；无保底）。 */
    val smartAbandonCooldownPercent: Int = 17,
    /** 同组共享冷却：本组某应用进入冷却时，按比例分发给组内闲置/待选择的成员。 */
    val sharedCooldownEnabled: Boolean = true,
    /** 共享冷却折算比例（%，接收方冷却 = 发起方冷却 × 比例，向上取整到分钟）。 */
    val sharedCooldownPercent: Int = 67,
    /** 延迟选时试探期时长（分钟）：打开应用后前台累计满该时长弹时间选择浮层。 */
    val delayedSelectionMinutes: Int = 10,
    /** 试探期中断保留（毫秒）：退后台超过该时长试探进度清零，保留期内回来续计。 */
    val delayedSelectionRetentionMillis: Long = 600_000L,
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

/** 智能宽限期的保底时长（2 分钟）。 */
const val GRACE_FLOOR_MILLIS = 120_000L

/**
 * 比例计算：base × percent / 100。
 * 结果不足 1 分钟时保留秒级精度（短用短罚，浮层支持秒级倒计时）；
 * 达到 1 分钟后向上取整到整分钟（如 0.25 × 45 分 = 11.25 → 12 分）。
 */
fun scaledByPercent(baseMillis: Long, percent: Int): Long {
    if (baseMillis <= 0L || percent <= 0) return 0L
    val raw = baseMillis * percent / 100L
    if (raw < 60_000L) return raw
    return (raw + 59_999L) / 60_000L * 60_000L
}

/** 到期冷却时长：智能联动开启按 实际前台消耗 × 比例 计算，否则用固定值。 */
fun GroupConfig.expiryCooldownMillisFor(usageMillis: Long): Long =
    if (time.smartTimingEnabled) scaledByPercent(usageMillis, time.smartExpiryCooldownPercent)
    else time.expiryCooldownMillis

/** 宽限期时长：智能联动开启按 开屏所选时长 × 比例 计算（保底 2 分钟），否则用固定值。 */
fun GroupConfig.gracePeriodMillisFor(selectedMillis: Long): Long =
    if (time.smartTimingEnabled) maxOf(GRACE_FLOOR_MILLIS, scaledByPercent(selectedMillis, time.smartGracePercent))
    else time.gracePeriodMillis

/** 放弃冷却时长：智能联动开启按 放弃时实际已用 × 比例 计算（无保底），否则用固定值。 */
fun GroupConfig.abandonCooldownMillisFor(usageMillis: Long): Long =
    if (time.smartTimingEnabled) scaledByPercent(usageMillis, time.smartAbandonCooldownPercent)
    else time.abandonCooldownMillis
