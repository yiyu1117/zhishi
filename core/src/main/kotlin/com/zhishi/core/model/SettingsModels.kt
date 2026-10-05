package com.zhishi.core.model

/** 冷却种类。 */
enum class CooldownKind { EXPIRY, ABANDON }

/** 自律锁配置：每天仅指定时段可修改配置，默认关。 */
data class DisciplineLockConfig(
    val enabled: Boolean = false,
    /** 可修改时段起点（当日分钟数，含）。 */
    val startMinuteOfDay: Int = 22 * 60,
    /** 可修改时段终点（当日分钟数，不含；小于 start 视为跨零点）。 */
    val endMinuteOfDay: Int = 23 * 60,
) {
    /** 给定当日分钟数是否处于可修改时段内。 */
    fun isUnlockedAt(minuteOfDay: Int): Boolean {
        if (!enabled) return true
        return if (startMinuteOfDay <= endMinuteOfDay) {
            minuteOfDay in startMinuteOfDay until endMinuteOfDay
        } else {
            minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
        }
    }
}

/** 检测模式。 */
enum class DetectionMode { ACCESSIBILITY, POLLING }
