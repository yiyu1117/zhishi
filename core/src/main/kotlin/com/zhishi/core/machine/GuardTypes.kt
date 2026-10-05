package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.time.AppClock

/** 守护状态机状态。 */
enum class GuardState {
    /** 无管控：机制全关且无每日限额，协调器不应为此类应用创建状态机。 */
    INACTIVE,

    /** 待选择：显示时间选择浮层。 */
    AWAITING_SELECTION,

    /** 会话中：前台计时。 */
    RUNNING,

    /** 宽限期：退后台/息屏，会话计时暂停，宽限倒计时推进。 */
    GRACE,

    /** 时间已到：用户仍停留在应用内，显示"时间已到"浮层。 */
    TIME_UP,

    /** 延时使用中：前台计时，冷却冻结。 */
    EXTENSION_RUNNING,

    /** 到期冷却：倒计时仅当用户不处于该应用内时推进。 */
    EXPIRY_COOLDOWN,

    /** 放弃冷却：宽限期耗尽判中途放弃后的禁入时段。 */
    ABANDON_COOLDOWN,

    /** 今日额度用尽：当天禁用，零点重置。 */
    QUOTA_EXHAUSTED,

    /** 黑名单：禁止使用。 */
    BLACKLISTED,

    /** 直接使用：无时间选择机制但有每日限额的分组，前台直接计时并记账。 */
    DIRECT_RUNNING,
}

/** 送入守护状态机的事件。 */
sealed interface GuardEvent {
    /** 检测到应用进入前台。 */
    data object ForegroundEntered : GuardEvent

    /** 应用退到后台或息屏（视同退后台）。 */
    data object Backgrounded : GuardEvent

    /** 用户在时间选择浮层选定时长（快选或自定义）。 */
    data class DurationSelected(val minutes: Int) : GuardEvent

    /** 用户在"时间已到"/"额度用尽"浮层点击确定（UI 层已施加按钮保护）。 */
    data object ConfirmExit : GuardEvent

    /** 用户点击延时按钮（UI 层已施加按钮保护）。 */
    data object ExtensionRequested : GuardEvent

    /** 时间驱动：协调器按固定节奏（约 1 秒）调用。 */
    data object Tick : GuardEvent

    /** 自然日切换（系统本地零点）。 */
    data object DayChanged : GuardEvent
}

/** 浮层形态规格：状态机输出，UI 层负责渲染。 */
sealed interface OverlaySpec {
    /** 时间选择浮层。 */
    data class TimeSelection(
        val quickOptionsMinutes: List<Int>,
        val customMaxMinutes: Int,
        /** 今日剩余额度分钟（null = 不限额）。 */
        val todayRemainingMinutes: Int?,
        val quickProtectMillis: Long,
    ) : OverlaySpec

    /** "时间已到"浮层。 */
    data class TimeUp(
        val confirmProtectMillis: Long,
        val extensionAvailable: Boolean,
        val extensionProtectMillis: Long,
    ) : OverlaySpec

    /** "今日 N 分钟已用完，零点重置"浮层：无延时。 */
    data class QuotaExhausted(
        val usedLimitMinutes: Int,
        val confirmProtectMillis: Long,
    ) : OverlaySpec

    /** 禁止使用浮层（黑名单），无任何按钮。 */
    data object Forbidden : OverlaySpec

    /** 冷却倒计时浮层："X 分钟后可打开"。 */
    data class Cooldown(
        val kind: CooldownKind,
        val extensionAvailable: Boolean,
        val extensionProtectMillis: Long,
    ) : OverlaySpec
}

/** Toast 提醒事件：状态机输出结构化事件，UI 层负责文案。 */
sealed interface ToastEvent {
    /** 周期用量提示：本会话累计使用分钟。 */
    data class UsagePeriod(val elapsedMinutes: Int) : ToastEvent

    /** 剩余提醒：距实际到期点的分钟数。 */
    data class TimeRemaining(val remainingMinutes: Int) : ToastEvent

    /** 延时结束前预告。 */
    data class ExtensionPrewarn(val secondsLeft: Int) : ToastEvent
}

/** 状态机的效果输出：由协调器翻译为系统动作。 */
sealed interface GuardEffect {
    /** 显示拦截浮层（全屏覆盖目标应用）。 */
    data class ShowOverlay(val spec: OverlaySpec) : GuardEffect

    /** 关闭当前浮层。 */
    data object HideOverlay : GuardEffect

    /** 弹出 Toast 提醒。 */
    data class ShowToast(val toast: ToastEvent) : GuardEffect

    /** 主动送回桌面（无障碍全局动作或启动桌面）。 */
    data object GoHome : GuardEffect

    /** 请求持久化当前状态与记账（重启恢复用）。 */
    data object FlushState : GuardEffect
}

/** 状态快照：UI/协调器观测用，随每次迁移与 Tick 更新。 */
data class GuardSnapshot(
    val packageName: String,
    val state: GuardState,
    /** 当前应显示的浮层；null = 无浮层。 */
    val overlay: OverlaySpec?,
    /** 活动倒计时剩余：会话剩余 / 宽限剩余 / 延时剩余 / 冷却剩余（按状态语义）；null = 无。 */
    val remainingMillis: Long?,
    /** 当日已用毫秒（含本机状态机累计）。 */
    val usedTodayMillis: Long,
    /** 今日剩余额度分钟（null = 不限额）。 */
    val todayRemainingMinutes: Int?,
)

/** 持久化恢复数据：重启后重建状态机。 */
data class GuardStateData(
    val kind: GuardState,
    /** 会话/宽限/延时的剩余（毫秒）。 */
    val remainingMillis: Long? = null,
    /** 冷却绝对截止时刻（epoch millis，按离开时刻起算，停机时间计入）。 */
    val cooldownDeadlineMillis: Long? = null,
    /** 本会话延时是否已使用。 */
    val extensionUsed: Boolean = false,
) {
    companion object {
        val EMPTY = GuardStateData(GuardState.INACTIVE)
    }
}

/** 守护状态机：每个守护应用一个实例，纯 Kotlin，注入时钟源。 */
abstract class GuardStateMachine(
    val packageName: String,
    protected val clock: AppClock,
    protected var config: GroupConfig,
) {
    /** 当前快照流。 */
    abstract val snapshot: kotlinx.coroutines.flow.StateFlow<GuardSnapshot>

    /** 处理事件，返回本次迁移产生的效果。 */
    abstract fun dispatch(event: GuardEvent): List<GuardEffect>

    /** 分组配置被编辑后热更新（不得清空会话/冷却状态；换组由协调器重建实例处理）。 */
    abstract fun onConfigUpdated(newConfig: GroupConfig)

    /** 导出可持久化状态。 */
    abstract fun exportState(): GuardStateData
}
