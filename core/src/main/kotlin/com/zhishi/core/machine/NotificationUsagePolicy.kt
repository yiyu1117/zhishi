package com.zhishi.core.machine

/**
 * 常驻通知剩余用量策略（v1.2.1）：无障碍与轮询两种检测模式共用的纯逻辑。
 *
 * 输入各状态机快照与退后台保留信息，输出应发布的通知用量或 null（回落通用守护文案），
 * 协调器消费其结果写入现有 `notificationUsage` 数据源——两种模式天然同享同一行为。
 *
 * 规则：
 * 1. 前台机器优先：处于会话中（RUNNING）/ 延时中（EXTENSION_RUNNING）且剩余非空者，
 *    按协调器候选序（焦点机器优先）取首个发布实时剩余；
 * 2. 无可发布的前台会话时，退后台保留期内（会话退后台后 [HOLD_DURATION_MILLIS] 内，
 *    机器仍在宽限期 GRACE）继续展示退后台时刻冻结的会话剩余——宽限期内会话计时暂停，
 *    冻结值与真实状态一致；
 * 3. 其余情况（保留期满、机器转入冷却/额度耗尽/被移除、无任何活动会话）回落 null。
 */
object NotificationUsagePolicy {

    /** 退后台后通知继续显示剩余时长的保留窗口（毫秒）：常量，不进设置页。 */
    const val HOLD_DURATION_MILLIS = 20_000L

    /**
     * 解析应发布的通知用量。
     *
     * @param foregroundSnapshots 前台机器快照（协调器候选序：焦点所在机器优先）
     * @param held 退后台保留中的用量（无则 null）
     * @param heldSnapshot 保留中应用的当前快照（机器已被移除为 null）
     * @param nowMillis 当前时刻
     */
    fun resolve(
        foregroundSnapshots: List<GuardSnapshot>,
        held: HeldNotificationUsage?,
        heldSnapshot: GuardSnapshot?,
        nowMillis: Long,
    ): NotificationUsagePayload? {
        foregroundSnapshots.firstOrNull { it.isPublishableRunning() }?.let {
            return NotificationUsagePayload(it.packageName, it.remainingMillis!!)
        }
        held?.let { hold ->
            if (holdInWindow(hold, heldSnapshot, nowMillis)) {
                return NotificationUsagePayload(hold.packageName, hold.remainingMillis)
            }
        }
        return null
    }

    /** 保留有效：仍在保留窗口内且机器仍处宽限期（会话计时暂停，冻结值与真实状态一致）。 */
    private fun holdInWindow(hold: HeldNotificationUsage, heldSnapshot: GuardSnapshot?, nowMillis: Long): Boolean =
        heldSnapshot != null &&
            heldSnapshot.state == GuardState.GRACE &&
            nowMillis - hold.backgroundedAtMillis < HOLD_DURATION_MILLIS

    /** 会话/延时进行中且剩余非空：可发布为通知用量的前台快照。 */
    private fun GuardSnapshot.isPublishableRunning(): Boolean =
        (state == GuardState.RUNNING || state == GuardState.EXTENSION_RUNNING) &&
            remainingMillis != null && remainingMillis > 0L
}

/** 策略输出：应发布的通知用量载荷（包名 + 剩余毫秒）；协调器据此组装应用名后发布。 */
data class NotificationUsagePayload(val packageName: String, val remainingMillis: Long)

/** 退后台保留中的用量：退后台时刻冻结的会话剩余（宽限期内会话计时暂停，冻结值与真实状态一致）。 */
data class HeldNotificationUsage(
    val packageName: String,
    val remainingMillis: Long,
    val backgroundedAtMillis: Long,
)
