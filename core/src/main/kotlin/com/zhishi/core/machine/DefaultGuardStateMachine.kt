package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.abandonCooldownMillisFor
import com.zhishi.core.model.expiryCooldownMillisFor
import com.zhishi.core.model.gracePeriodMillisFor
import com.zhishi.core.model.isForbiddenByQuota
import com.zhishi.core.time.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 守护状态机默认实现。
 *
 * 设计要点：
 * - **事件补偿**：每次 [dispatch] 先按当前状态的时间规则把内部时间线推进到 now，再处理事件；
 *   内部记录 [inForeground] 与 [lastProcessedMillis]。Tick 稀疏或事件间时间跳跃均安全。
 * - **冷却推进规则（v1.1）**：冷却倒计时按墙钟推进，与用户是否在应用内无关；
 *   唯一冻结点是延时使用期间（延时以 EXTENSION_RUNNING 状态独立计时，冷却剩余冻结为 pending）。
 *   冷却走完时用户若仍停留在应用内，原地按分组入口语义重入（选时/试探/直接计时）。
 * - **到期即冷却（v1.1）**：会话自然到期不再有独立"时间已到"确认浮层，直接进入到期冷却；
 *   冷却浮层兼作"时间已到"提示（副标题「时间已到，冷却中」），返回桌面按钮即刻可点。
 * - **智能时长联动（v1.1）**：配置开启时，到期冷却/宽限期/放弃冷却分别按
 *   实际前台消耗 / 开屏所选时长 / 放弃时实际已用 与比例计算（见 CoreModels 的 *For 扩展）。
 * - **实际到期** = min(会话剩余, 当日剩余额度)；额度先尽立即中断（同刻打平按额度耗尽处理）。
 * - **延时**：每会话一次；从冷却浮层发起时冻结冷却剩余（pending），延时结束回到冷却
 *   （按 pending 剩余续接，非满额）。
 * - **提醒**：仅前台消耗期间产生；同刻撞点剩余提醒优先；一旦剩余提醒触发过，
 *   本会话后续周期提示一律抑制（设计草稿规则）。
 * - **延迟选时（v1.1）**：TRIAL_RUNNING 试探期前台计时（计入当日限额），退后台暂停；
   中断超过保留期试探进度清零，期满弹时间选择浮层。
 * - 宽限期补偿时若发生过冲（overshoot），把过冲时间计入随后开始的放弃冷却（与连续 Tick 等价）。
 */
class DefaultGuardStateMachine(
    packageName: String,
    clock: AppClock,
    config: GroupConfig,
    todayUsedMillis: Long,
    /** 重启恢复：非空时按保存的状态续接。 */
    restored: GuardStateData? = null,
) : GuardStateMachine(packageName, clock, config) {

    private var state: GuardState = GuardState.INACTIVE

    /** 用户是否当前处于该应用前台（据事件与恢复推断）。 */
    private var inForeground: Boolean = false

    /** 内部时间线已推进到的时刻。 */
    private var lastProcessedMillis: Long = clock.nowMillis()

    private var usedTodayMillis: Long = todayUsedMillis.coerceAtLeast(0L)

    // ---- 会话相关（随新会话重置） ----
    private var sessionRemainingMillis: Long = 0L

    /** 本会话开屏所选时长（智能宽限期的计算基数）。 */
    private var sessionSelectedMillis: Long = 0L
    private var graceRemainingMillis: Long = 0L
    private var extensionRemainingMillis: Long = 0L
    private var extensionUsed: Boolean = false
    private var extensionPrewarnFired: Boolean = false

    // ---- 延迟选时试探期（独立于会话） ----
    private var trialRemainingMillis: Long = 0L

    /** 试探期退后台的暂停时刻（epoch millis）；null = 未处于中断保留判定中。 */
    private var trialPausedAtMillis: Long? = null

    /**
     * 试探期满未选时间的重开窗口截止（epoch millis，v1.2）：退出选时浮层时起算保留时长，
     * 窗口内重开直接弹选时浮层（不再重新计满试探期）；null = 无窗口语义（常规待选择，
     * 或试探期满的浮层正在展示中——窗口仅在浮层退出后起算，每次退出重新起算）。
     */
    private var selectionDeadlineMillis: Long? = null

    /** 当前待选择是否源自试探期满（区别于开屏时间选择：退出浮层后才有重开窗口语义）。 */
    private var awaitingFromTrialExpiry: Boolean = false

    /** 本会话已触发的剩余提醒刻度（每刻度每会话一次）。 */
    private val firedReminderMarks = mutableSetOf<Int>()

    /** 会话前台累计（周期用量提示的计时基数，RUNNING 与 EXTENSION_RUNNING 均累计）。 */
    private var usageElapsedMillis: Long = 0L

    /**
     * 剩余提醒是否已在本会话触发过。设计规则（设计草稿）：剩余提醒优先于已用提醒，
     * 且一旦触发过，本会话后续所有"已用 X 分钟"提示一律不显示（含撞点当步与顺延补发）。
     */
    private var usageToastsSuppressed = false

    /** 撞点顺延的周期提示（分钟值队列），在下一个无撞点的消耗步补发。 */
    private val deferredUsageToasts = ArrayDeque<Int>()

    // ---- 冷却相关 ----
    private var cooldownKind: CooldownKind = CooldownKind.EXPIRY
    private var cooldownRemainingMillis: Long = 0L

    /** 当前冷却是否为同组共享冷却（接收方，未使用过 → 浮层无延时按钮）。 */
    private var cooldownShared: Boolean = false

    /** 延时发起前被冻结的冷却（kind + 剩余），延时结束后据此继续。 */
    private var pendingCooldownKind: CooldownKind? = null
    private var pendingCooldownRemainingMillis: Long = 0L

    private var currentOverlay: OverlaySpec? = null

    private val mutableSnapshot = MutableStateFlow(
        GuardSnapshot(
            packageName = packageName,
            state = GuardState.INACTIVE,
            overlay = null,
            remainingMillis = null,
            usedTodayMillis = usedTodayMillis,
            todayRemainingMinutes = null,
        ),
    )
    override val snapshot: StateFlow<GuardSnapshot> = mutableSnapshot

    init {
        applyRestored(restored)
        publishSnapshot()
    }

    // ------------------------------------------------------------------ dispatch

    override fun dispatch(event: GuardEvent): List<GuardEffect> {
        val now = clock.nowMillis()
        val effects = compensate(now).toMutableList()
        when (event) {
            GuardEvent.ForegroundEntered -> effects += onForegroundEntered()
            GuardEvent.Backgrounded -> effects += onBackgrounded()
            is GuardEvent.DurationSelected -> effects += onDurationSelected(event.minutes)
            GuardEvent.ConfirmExit -> effects += onConfirmExit()
            GuardEvent.ExtensionRequested -> effects += onExtensionRequested()
            GuardEvent.Tick -> Unit // 仅补偿
            GuardEvent.DayChanged -> effects += onDayChanged()
        }
        lastProcessedMillis = now
        publishSnapshot()
        return effects
    }

    /**
     * 事件补偿：把当前状态的时间规则推进到 now，返回迁移产生的效果。
     * 前台消耗态仅在用户处于前台时消耗；宽限仅当用户不在应用内时推进
     * （宽限的防线防止异常滞留 GRACE 时被 Tick 悄悄耗尽判成中途放弃）；
     * 冷却按墙钟推进，与用户是否在应用内无关（v1.1，停留浮层也走）。
     */
    private fun compensate(now: Long): List<GuardEffect> {
        val elapsed = now - lastProcessedMillis
        if (elapsed <= 0L) return emptyList()
        return when (state) {
            GuardState.RUNNING -> consumeSession(elapsed)
            GuardState.EXTENSION_RUNNING -> consumeExtension(elapsed)
            GuardState.DIRECT_RUNNING -> consumeDirect(elapsed)
            GuardState.TRIAL_RUNNING -> {
                // 中断保留自动清零（v1.2）：暂停超保留期即刻转闲置，不再等下次前台进入
                // 才发现（调试悬浮窗不再挂着过期的试探倒计时）；前台试探不受影响
                val pausedAt = trialPausedAtMillis
                if (!inForeground && pausedAt != null &&
                    now - pausedAt >= config.time.delayedSelectionRetentionMillis
                ) {
                    trialRemainingMillis = 0L
                    trialPausedAtMillis = null
                    state = GuardState.INACTIVE
                    emptyList()
                } else {
                    consumeTrial(elapsed)
                }
            }
            GuardState.GRACE -> if (!inForeground) advanceGrace(elapsed) else emptyList()
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> advanceCooldown(elapsed)
            GuardState.AWAITING_SELECTION -> advanceSelectionDeadline(now)
            else -> emptyList()
        }
    }

    /**
     * 待选择重开窗口推进（v1.2）：试探期满未选时间、退出浮层后，保留期内重开直接弹选时；
     * 窗口（每次退出重新起算的保留时长）走完即转闲置，下次打开重新计满试探期。
     */
    private fun advanceSelectionDeadline(now: Long): List<GuardEffect> {
        val deadline = selectionDeadlineMillis ?: return emptyList()
        if (now < deadline) return emptyList()
        selectionDeadlineMillis = null
        awaitingFromTrialExpiry = false
        state = GuardState.INACTIVE
        return emptyList() // 用户不在应用内（浮层已退），无需浮层效果
    }

    // ------------------------------------------------------------------ 前台消耗

    /** RUNNING：前台消耗会话剩余并累计当日用量；检查刻度/周期提醒与到期。 */
    private fun consumeSession(elapsed: Long): List<GuardEffect> {
        if (!inForeground || elapsed <= 0L) return emptyList()
        val effects = mutableListOf<GuardEffect>()
        val sessionBefore = sessionRemainingMillis
        val quotaBefore = config.remainingQuotaMillis(usedTodayMillis)
        val expiryBefore = minOf(sessionBefore, quotaBefore ?: Long.MAX_VALUE)
        val usageBefore = usageElapsedMillis

        val consumed = minOf(elapsed, sessionBefore, quotaBefore ?: Long.MAX_VALUE)
        sessionRemainingMillis = sessionBefore - consumed
        usedTodayMillis += consumed
        usageElapsedMillis = usageBefore + consumed

        val quotaAfter = config.remainingQuotaMillis(usedTodayMillis)
        val expiryAfter = minOf(sessionRemainingMillis, quotaAfter ?: Long.MAX_VALUE)

        val newMarks = if (config.mechanismEnabled(Mechanism.TIME_REMINDER)) {
            crossedReminderMarks(expiryBefore, expiryAfter)
        } else {
            emptyList()
        }
        for (mark in newMarks) effects += GuardEffect.ShowToast(ToastEvent.TimeRemaining(mark))
        if (newMarks.isNotEmpty()) usageToastsSuppressed = true

        emitUsageToasts(effects, crossedUsageBoundaries(usageBefore, usageElapsedMillis), collision = newMarks.isNotEmpty())

        when {
            quotaAfter != null && quotaAfter <= 0L -> enterQuotaExhausted(effects)
            sessionRemainingMillis <= 0L -> enterExpiryCooldown(effects)
        }
        return effects
    }

    /** EXTENSION_RUNNING：前台消耗延时剩余并累计当日用量；预告一次；额度/延时到期检查。 */
    private fun consumeExtension(elapsed: Long): List<GuardEffect> {
        if (!inForeground || elapsed <= 0L) return emptyList()
        val effects = mutableListOf<GuardEffect>()
        val extensionBefore = extensionRemainingMillis
        val quotaBefore = config.remainingQuotaMillis(usedTodayMillis)
        val usageBefore = usageElapsedMillis

        val consumed = minOf(elapsed, extensionBefore, quotaBefore ?: Long.MAX_VALUE)
        extensionRemainingMillis = extensionBefore - consumed
        usedTodayMillis += consumed
        usageElapsedMillis = usageBefore + consumed

        val quotaAfter = config.remainingQuotaMillis(usedTodayMillis)

        if (!extensionPrewarnFired && extensionRemainingMillis in 1L..prewarnThresholdMillis()) {
            extensionPrewarnFired = true
            val secondsLeft = ((extensionRemainingMillis + 999L) / 1000L).toInt()
            effects += GuardEffect.ShowToast(ToastEvent.ExtensionPrewarn(secondsLeft))
        }

        // 延时期间无剩余刻度（到期点已过），周期提示照常且不存在撞点。
        emitUsageToasts(effects, crossedUsageBoundaries(usageBefore, usageElapsedMillis), collision = false)

        when {
            quotaAfter != null && quotaAfter <= 0L -> enterQuotaExhausted(effects)
            extensionRemainingMillis <= 0L -> enterExtensionEndCooldown(effects)
        }
        return effects
    }

    /** DIRECT_RUNNING：前台直接累计当日用量并执行限额（无会话/提醒语义）。 */
    private fun consumeDirect(elapsed: Long): List<GuardEffect> {
        if (!inForeground || elapsed <= 0L) return emptyList()
        val effects = mutableListOf<GuardEffect>()
        val quotaBefore = config.remainingQuotaMillis(usedTodayMillis) ?: return emptyList()
        usedTodayMillis += minOf(elapsed, quotaBefore)
        val quotaAfter = config.remainingQuotaMillis(usedTodayMillis)
        if (quotaAfter != null && quotaAfter <= 0L) enterQuotaExhausted(effects)
        return effects
    }

    /**
     * TRIAL_RUNNING（延迟选时试探期）：前台计时并记账，试探期满弹时间选择浮层。
     * 无会话语义：无宽限/放弃/剩余提醒；周期用量提示照常（按本应用前台累计）。
     */
    private fun consumeTrial(elapsed: Long): List<GuardEffect> {
        if (!inForeground || elapsed <= 0L) return emptyList()
        val effects = mutableListOf<GuardEffect>()
        val quotaBefore = config.remainingQuotaMillis(usedTodayMillis)
        val usageBefore = usageElapsedMillis
        val consumed = minOf(elapsed, trialRemainingMillis, quotaBefore ?: Long.MAX_VALUE)
        trialRemainingMillis -= consumed
        usedTodayMillis += consumed
        usageElapsedMillis = usageBefore + consumed
        emitUsageToasts(effects, crossedUsageBoundaries(usageBefore, usageElapsedMillis), collision = false)
        val quotaAfter = config.remainingQuotaMillis(usedTodayMillis)
        when {
            quotaAfter != null && quotaAfter <= 0L -> enterQuotaExhausted(effects)
            trialRemainingMillis <= 0L -> {
                state = GuardState.AWAITING_SELECTION
                awaitingFromTrialExpiry = true // 退出浮层后起算重开窗口（v1.2）
                effects += show(timeSelectionOverlay())
            }
        }
        return effects
    }

    // ------------------------------------------------------------------ 宽限与冷却推进

    /** GRACE：宽限倒计时推进；耗尽判中途放弃，过冲时间计入随后开始的放弃冷却。 */
    private fun advanceGrace(elapsed: Long): List<GuardEffect> {
        val effects = mutableListOf<GuardEffect>()
        graceRemainingMillis -= elapsed
        if (graceRemainingMillis > 0L) return effects
        val overshoot = -graceRemainingMillis
        graceRemainingMillis = 0L
        abandonSession(overshoot, effects)
        return effects
    }

    /**
     * 冷却推进（墙钟，与用户是否在应用内无关）：自然走完时若用户停留在应用内，
     * 原地按分组入口语义重入（选时/试探/直接计时），否则回 INACTIVE 等下次进入。
     */
    private fun advanceCooldown(elapsed: Long): List<GuardEffect> {
        cooldownRemainingMillis -= elapsed
        if (cooldownRemainingMillis > 0L) return emptyList()
        return completeCooldown()
    }

    /** 冷却走完（或过冲耗尽）：清冷却态，前台停留时原地重入入口语义。 */
    private fun completeCooldown(): List<GuardEffect> {
        cooldownRemainingMillis = 0L
        cooldownShared = false
        selectionDeadlineMillis = null
        awaitingFromTrialExpiry = false
        state = GuardState.INACTIVE
        currentOverlay = null
        if (!inForeground) return emptyList()
        val effects = mutableListOf<GuardEffect>(GuardEffect.HideOverlay)
        effects += enterFromInactive()
        return effects
    }

    // ------------------------------------------------------------------ 事件处理

    private fun onForegroundEntered(): List<GuardEffect> {
        inForeground = true
        return when (state) {
            GuardState.INACTIVE -> enterFromInactive()
            GuardState.AWAITING_SELECTION -> {
                val deadline = selectionDeadlineMillis
                if (deadline != null) {
                    if (clock.nowMillis() < deadline) {
                        // 重开窗口内（v1.2）：直接弹选时浮层，不再重新计满试探期；
                        // 窗口清除——本次退出后重新起算
                        selectionDeadlineMillis = null
                        return show(
                            timeSelectionOverlay(),
                            mediaPauseDelayMillis = OPEN_SCREEN_MEDIA_PAUSE_DELAY_MILLIS,
                        )
                    }
                    // 窗口已过（补偿兜底路径）：按入口语义重新计满试探期
                    selectionDeadlineMillis = null
                    awaitingFromTrialExpiry = false
                    state = GuardState.INACTIVE
                    return enterFromInactive()
                }
                // 常规待选择（开屏时间选择 / 试探期满的浮层仍在流程中）：重开即重弹
                show(timeSelectionOverlay(), mediaPauseDelayMillis = OPEN_SCREEN_MEDIA_PAUSE_DELAY_MILLIS)
            }
            GuardState.RUNNING, GuardState.DIRECT_RUNNING, GuardState.EXTENSION_RUNNING -> emptyList()
            GuardState.TRIAL_RUNNING -> {
                // 中断保留：退后台超过保留期试探进度清零，保留期内回来续计
                val pausedAt = trialPausedAtMillis
                trialPausedAtMillis = null
                if (pausedAt != null &&
                    clock.nowMillis() - pausedAt >= config.time.delayedSelectionRetentionMillis
                ) {
                    trialRemainingMillis = config.time.delayedSelectionMinutes * 60_000L
                }
                emptyList()
            }
            GuardState.GRACE -> {
                // 宽限期内回来：会话剩余保持，继续前台计时
                state = GuardState.RUNNING
                emptyList()
            }
            // 旧版遗留状态，恢复路径已转换；防御性兜底按入口语义重入
            GuardState.TIME_UP -> enterFromInactive()
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> show(cooldownOverlay())
            GuardState.QUOTA_EXHAUSTED -> show(quotaOverlay())
            GuardState.BLACKLISTED -> show(OverlaySpec.Forbidden)
        }
    }

    private fun onBackgrounded(): List<GuardEffect> {
        inForeground = false
        return when (state) {
            GuardState.RUNNING -> {
                // 退后台/息屏：会话计时暂停，宽限倒计时开始（智能联动按所选时长计算）
                state = GuardState.GRACE
                graceRemainingMillis = config.gracePeriodMillisFor(sessionSelectedMillis)
                emptyList()
            }
            GuardState.GRACE, GuardState.INACTIVE, GuardState.DIRECT_RUNNING -> emptyList()
            GuardState.TRIAL_RUNNING -> {
                // 试探暂停：记录时刻供中断保留判定；无会话语义，无宽限/放弃
                trialPausedAtMillis = clock.nowMillis()
                emptyList()
            }
            GuardState.TIME_UP -> {
                val had = currentOverlay != null
                currentOverlay = null
                if (had) listOf(GuardEffect.HideOverlay) else emptyList()
            }
            GuardState.EXTENSION_RUNNING -> {
                // 延时提前结束：按被冻结的冷却剩余继续（无 pending 则进满额到期冷却）；
                // pending 续接不发 CooldownStarted（同一冷却 Episode）
                extensionUsed = true
                extensionRemainingMillis = 0L
                val effects = mutableListOf<GuardEffect>()
                val pendingKind = pendingCooldownKind
                pendingCooldownKind = null
                if (pendingKind != null) {
                    enterCooldown(pendingKind, pendingCooldownRemainingMillis, effects, flushState = false, emitStarted = false)
                } else {
                    enterCooldown(CooldownKind.EXPIRY, config.expiryCooldownMillisFor(usageElapsedMillis), effects, flushState = false)
                }
                effects
            }
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> {
                // 冷却照常推进（墙钟），仅隐藏浮层
                val had = currentOverlay != null
                currentOverlay = null
                if (had) listOf(GuardEffect.HideOverlay) else emptyList()
            }
            GuardState.AWAITING_SELECTION, GuardState.QUOTA_EXHAUSTED, GuardState.BLACKLISTED -> {
                val had = currentOverlay != null
                currentOverlay = null
                // 试探期满的待选择经 Home/返回键离开（v1.2）：与「退出」按钮同语义——
                // 起算重开窗口，期内重开直接弹选时；否则窗口永不超时、试探永不重计
                if (state == GuardState.AWAITING_SELECTION && awaitingFromTrialExpiry && selectionDeadlineMillis == null) {
                    selectionDeadlineMillis = clock.nowMillis() + config.time.delayedSelectionRetentionMillis
                }
                if (had) listOf(GuardEffect.HideOverlay) else emptyList()
            }
        }
    }

    private fun onDurationSelected(minutes: Int): List<GuardEffect> {
        if (state != GuardState.AWAITING_SELECTION) return emptyList()
        // 非法输入：忽略，浮层保持
        if (minutes <= 0 || minutes > config.time.customMaxMinutes) return emptyList()

        val quota = config.remainingQuotaMillis(usedTodayMillis)
        if (quota != null && quota <= 0L && config.dailyLimitMinutes > 0) {
            // 无额度可选：直接进入当日禁用
            state = GuardState.QUOTA_EXHAUSTED
            return show(quotaOverlay())
        }

        // 新会话：重置会话级状态
        extensionUsed = false
        extensionPrewarnFired = false
        extensionRemainingMillis = 0L
        firedReminderMarks.clear()
        deferredUsageToasts.clear()
        usageToastsSuppressed = false
        usageElapsedMillis = 0L
        pendingCooldownKind = null
        selectionDeadlineMillis = null
        awaitingFromTrialExpiry = false
        sessionSelectedMillis = minutes * 60_000L
        sessionRemainingMillis = minOf(sessionSelectedMillis, quota ?: Long.MAX_VALUE)

        state = GuardState.RUNNING
        currentOverlay = null
        return listOf(GuardEffect.HideOverlay)
    }

    private fun onConfirmExit(): List<GuardEffect> = when (state) {
        GuardState.QUOTA_EXHAUSTED -> {
            inForeground = false
            listOf(GuardEffect.GoHome, GuardEffect.FlushState)
        }
        GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> {
            // 冷却浮层的「返回桌面」：冷却照常推进（墙钟，与是否在应用内无关）
            inForeground = false
            currentOverlay = null
            listOf(GuardEffect.GoHome, GuardEffect.HideOverlay, GuardEffect.FlushState)
        }
        GuardState.AWAITING_SELECTION -> {
            inForeground = false
            currentOverlay = null
            if (awaitingFromTrialExpiry) {
                // 试探期满未选时间（v1.2）：不回闲置，起算重开窗口（每次退出重新起算保留
                // 时长）——窗口内重开直接弹选时浮层；超窗由补偿转闲置，下次打开重新计满
                selectionDeadlineMillis = clock.nowMillis() + config.time.delayedSelectionRetentionMillis
                listOf(GuardEffect.GoHome, GuardEffect.HideOverlay)
            } else {
                // 时间选择浮层的「退出」：未开始会话，无冷却语义；回到闲置，下次打开按入口语义重入
                state = GuardState.INACTIVE
                listOf(GuardEffect.GoHome, GuardEffect.HideOverlay)
            }
        }
        else -> emptyList()
    }

    private fun onExtensionRequested(): List<GuardEffect> {
        if (!config.mechanismEnabled(Mechanism.EXTENSION)) return emptyList()
        if (extensionUsed) return emptyList()
        // 共享冷却的接收方未使用过，不提供延时（浮层按钮已隐藏，此处防御重复拦截）
        if (cooldownShared) return emptyList()
        return when (state) {
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> {
                extensionUsed = true
                extensionPrewarnFired = false
                extensionRemainingMillis = config.time.extensionMinutes * 60_000L
                pendingCooldownKind = cooldownKind
                pendingCooldownRemainingMillis = cooldownRemainingMillis
                state = GuardState.EXTENSION_RUNNING
                currentOverlay = null
                listOf(GuardEffect.HideOverlay)
            }
            else -> emptyList()
        }
    }

    private fun onDayChanged(): List<GuardEffect> {
        usedTodayMillis = 0L
        if (state == GuardState.QUOTA_EXHAUSTED) {
            state = GuardState.INACTIVE
            currentOverlay = null
            // 用户停留在额度浮层跨零点：立即按新的一天重新进入
            return if (inForeground) enterFromInactive() else emptyList()
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ 迁移辅助

    private fun enterFromInactive(): List<GuardEffect> {
        selectionDeadlineMillis = null
        awaitingFromTrialExpiry = false
        val quota = config.remainingQuotaMillis(usedTodayMillis)
        if (quota != null && quota <= 0L && config.dailyLimitMinutes > 0) {
            state = GuardState.QUOTA_EXHAUSTED
            return show(quotaOverlay())
        }
        return when {
            config.mechanismEnabled(Mechanism.TIME_SELECTION) -> {
                state = GuardState.AWAITING_SELECTION
                show(timeSelectionOverlay(), mediaPauseDelayMillis = OPEN_SCREEN_MEDIA_PAUSE_DELAY_MILLIS)
            }
            config.mechanismEnabled(Mechanism.DELAYED_SELECTION) -> {
                // 延迟选时：进入试探期（前台计时，期满弹时间选择浮层）
                state = GuardState.TRIAL_RUNNING
                trialRemainingMillis = config.time.delayedSelectionMinutes * 60_000L
                trialPausedAtMillis = null
                emptyList()
            }
            config.dailyLimitMinutes > 0 -> {
                state = GuardState.DIRECT_RUNNING
                emptyList()
            }
            else -> {
                state = GuardState.INACTIVE
                emptyList()
            }
        }
    }

    /**
     * 会话到时：到期即进入冷却（v1.1，无独立确认浮层）。用户仍停留在应用内，
     * 冷却浮层即刻显示并按墙钟推进；冷却基数按智能联动/固定值取。
     */
    private fun enterExpiryCooldown(effects: MutableCollection<GuardEffect>) {
        enterCooldown(CooldownKind.EXPIRY, config.expiryCooldownMillisFor(usageElapsedMillis), effects, flushState = true)
    }

    /** 延时用尽：回到冷却——延时时被冻结的冷却剩余续接（无 pending 则进当刻满额到期冷却）。 */
    private fun enterExtensionEndCooldown(effects: MutableCollection<GuardEffect>) {
        val pendingKind = pendingCooldownKind
        pendingCooldownKind = null
        if (pendingKind != null) {
            // 同一次冷却 Episode 的续接（非新开始），不再发 CooldownStarted 二次触发共享
            enterCooldown(pendingKind, pendingCooldownRemainingMillis, effects, flushState = true, emitStarted = false)
        } else {
            enterCooldown(CooldownKind.EXPIRY, config.expiryCooldownMillisFor(usageElapsedMillis), effects, flushState = true)
        }
    }

    /** 额度耗尽：立即中断，当日禁用（无延时）。 */
    private fun enterQuotaExhausted(effects: MutableCollection<GuardEffect>) {
        state = GuardState.QUOTA_EXHAUSTED
        sessionRemainingMillis = 0L
        extensionRemainingMillis = 0L
        pendingCooldownKind = null
        usageElapsedMillis = 0L
        firedReminderMarks.clear()
        deferredUsageToasts.clear()
        currentOverlay = quotaOverlay()
        effects += GuardEffect.ShowOverlay(currentOverlay!!)
    }

    /**
     * 中途放弃：会话作废，进放弃冷却（机制关则按入口语义重入）。用户不在应用内，无浮层效果。
     * [overshootMillis] = 宽限补偿的过冲时间，从冷却（含共享分发的折算基数）中扣除。
     */
    private fun abandonSession(overshootMillis: Long, effects: MutableCollection<GuardEffect>) {
        // 智能联动以放弃时实际已用为基数，须在清零前计算
        val cooldownMillis = (config.abandonCooldownMillisFor(usageElapsedMillis) - overshootMillis)
            .coerceAtLeast(0L)
        sessionRemainingMillis = 0L
        usageElapsedMillis = 0L
        firedReminderMarks.clear()
        deferredUsageToasts.clear()
        pendingCooldownKind = null
        enterCooldown(CooldownKind.ABANDON, cooldownMillis, effects, flushState = false)
    }

    /**
     * 进入指定冷却（机制关闭或剩余非正则按入口语义重入）。
     * 用户在应用内时即刻显示冷却浮层（到期路径）；否则等下次前台进入时显示。
     * 携带 [GuardEffect.CooldownStarted] 供协调器做同组共享分发。
     */
    private fun enterCooldown(
        kind: CooldownKind,
        remainingMillis: Long,
        effects: MutableCollection<GuardEffect>,
        flushState: Boolean,
        emitStarted: Boolean = true,
    ) {
        currentOverlay = null
        val mechanism = if (kind == CooldownKind.EXPIRY) Mechanism.EXPIRY_COOLDOWN else Mechanism.ABANDON_COOLDOWN
        if (config.mechanismEnabled(mechanism) && remainingMillis > 0L) {
            state = if (kind == CooldownKind.EXPIRY) GuardState.EXPIRY_COOLDOWN else GuardState.ABANDON_COOLDOWN
            cooldownKind = kind
            cooldownRemainingMillis = remainingMillis
            cooldownShared = false
            // 信息性效果，供协调器做同组共享分发；冷却 Episode 的续接（延时后）不重发
            if (emitStarted) effects += GuardEffect.CooldownStarted(kind, remainingMillis)
            if (inForeground) {
                currentOverlay = cooldownOverlay()
                effects += GuardEffect.ShowOverlay(currentOverlay!!)
            }
        } else {
            state = GuardState.INACTIVE
            cooldownRemainingMillis = 0L
            if (inForeground) {
                effects += GuardEffect.HideOverlay
                effects += enterFromInactive()
            }
        }
        if (flushState) effects += GuardEffect.FlushState
    }

    private fun show(spec: OverlaySpec, mediaPauseDelayMillis: Long = 0L): List<GuardEffect> {
        currentOverlay = spec
        return listOf(GuardEffect.ShowOverlay(spec, mediaPauseDelayMillis))
    }

    // ------------------------------------------------------------------ 提醒

    /** 消耗一步内新跨过的剩余提醒刻度（每刻度每会话一次，降序 = 时间先后）。 */
    private fun crossedReminderMarks(beforeMillis: Long, afterMillis: Long): List<Int> {
        if (afterMillis >= beforeMillis) return emptyList()
        return config.time.reminderMarksMinutes
            .filter { it > 0 }
            .distinct()
            .filter { m -> beforeMillis > m * 60_000L && afterMillis <= m * 60_000L }
            .filter { firedReminderMarks.add(it) }
            .toList()
            .sortedDescending()
    }

    /** 消耗一步内跨过的周期提示边界（本会话累计分钟，升序 = 时间先后）。 */
    private fun crossedUsageBoundaries(beforeMillis: Long, afterMillis: Long): List<Int> {
        if (afterMillis <= beforeMillis) return emptyList()
        val interval = config.time.usageToastIntervalMillis
        if (interval <= 0L) return emptyList()
        val firstMultiple = beforeMillis / interval + 1
        val lastMultiple = afterMillis / interval
        if (lastMultiple < firstMultiple) return emptyList()
        return (firstMultiple..lastMultiple).map { k -> (k * interval / 60_000L).toInt() }
    }

    /**
     * 周期提示发射：与剩余提醒同刻撞点时剩余优先；设计规则——一旦本会话触发过剩余
     * 提醒，后续所有周期提示（含撞点当步与此前顺延的）一律不显示。
     */
    private fun emitUsageToasts(effects: MutableCollection<GuardEffect>, newBoundaries: List<Int>, collision: Boolean) {
        if (!config.mechanismEnabled(Mechanism.USAGE_TOAST)) {
            deferredUsageToasts.clear()
            return
        }
        if (usageToastsSuppressed) {
            deferredUsageToasts.clear()
            return
        }
        if (collision) {
            deferredUsageToasts.addAll(newBoundaries)
            return
        }
        while (deferredUsageToasts.isNotEmpty()) {
            effects += GuardEffect.ShowToast(ToastEvent.UsagePeriod(deferredUsageToasts.removeFirst()))
        }
        for (minutes in newBoundaries) {
            effects += GuardEffect.ShowToast(ToastEvent.UsagePeriod(minutes))
        }
    }

    private fun prewarnThresholdMillis(): Long = config.time.extensionPrewarnSeconds * 1000L

    // ------------------------------------------------------------------ 浮层载荷

    private fun timeSelectionOverlay(): OverlaySpec = OverlaySpec.TimeSelection(
        quickOptionsMinutes = config.time.quickOptionsMinutes,
        customMaxMinutes = config.time.customMaxMinutes,
        todayRemainingMinutes = todayRemainingMinutes(),
        quickProtectMillis = config.time.quickProtectMillis,
    )

    private fun quotaOverlay(): OverlaySpec = OverlaySpec.QuotaExhausted(
        usedLimitMinutes = config.dailyLimitMinutes,
        confirmProtectMillis = config.time.confirmProtectMillis,
    )

    private fun cooldownOverlay(): OverlaySpec = OverlaySpec.Cooldown(
        kind = cooldownKind,
        extensionAvailable = !cooldownShared &&
            config.mechanismEnabled(Mechanism.EXTENSION) && !extensionUsed,
        extensionProtectMillis = config.time.extensionProtectMillis,
        shared = cooldownShared,
    )

    private fun todayRemainingMinutes(): Int? =
        config.remainingQuotaMillis(usedTodayMillis)?.let { (it / 60_000L).toInt() }

    // ------------------------------------------------------------------ 快照 / 恢复 / 热更新

    private fun publishSnapshot() {
        val remaining = when (state) {
            GuardState.RUNNING -> sessionRemainingMillis
            GuardState.GRACE -> graceRemainingMillis
            GuardState.EXTENSION_RUNNING -> extensionRemainingMillis
            GuardState.TRIAL_RUNNING -> trialRemainingMillis
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> cooldownRemainingMillis
            else -> null
        }
        // 进度清零倒计时（v1.2，调试悬浮窗）：暂停试探的保留剩余 / 待选择重开窗口剩余
        val now = clock.nowMillis()
        val resetCountdown = when {
            state == GuardState.TRIAL_RUNNING && !inForeground -> trialPausedAtMillis?.let { pausedAt ->
                (config.time.delayedSelectionRetentionMillis - (now - pausedAt)).coerceAtLeast(0L)
            }
            state == GuardState.AWAITING_SELECTION -> selectionDeadlineMillis?.let { deadline ->
                (deadline - now).coerceAtLeast(0L)
            }
            else -> null
        }
        mutableSnapshot.value = GuardSnapshot(
            packageName = packageName,
            state = state,
            overlay = currentOverlay,
            remainingMillis = remaining,
            usedTodayMillis = usedTodayMillis,
            todayRemainingMinutes = todayRemainingMinutes(),
            resetCountdownMillis = resetCountdown,
        )
    }

    private fun applyRestored(data: GuardStateData?) {
        inForeground = false
        if (data == null) {
            state = if (config.isForbiddenByQuota()) GuardState.BLACKLISTED else GuardState.INACTIVE
            return
        }
        extensionUsed = data.extensionUsed
        when (data.kind) {
            // RUNNING 重启等同退后台：会话剩余保持，宽限期从头计（智能联动按所选时长）
            GuardState.RUNNING -> {
                sessionRemainingMillis = (data.remainingMillis ?: 0L).coerceAtLeast(0L)
                sessionSelectedMillis = (data.sessionSelectedMillis ?: 0L).coerceAtLeast(0L)
                state = GuardState.GRACE
                graceRemainingMillis = config.gracePeriodMillisFor(sessionSelectedMillis)
            }
            GuardState.GRACE -> {
                sessionSelectedMillis = (data.sessionSelectedMillis ?: 0L).coerceAtLeast(0L)
                state = GuardState.GRACE
                graceRemainingMillis = (data.remainingMillis ?: 0L).coerceAtLeast(0L)
            }
            GuardState.TRIAL_RUNNING -> {
                state = GuardState.TRIAL_RUNNING
                trialRemainingMillis = (data.remainingMillis ?: 0L).coerceAtLeast(0L)
                trialPausedAtMillis = data.trialPausedAtMillis
            }
            GuardState.EXTENSION_RUNNING -> {
                state = GuardState.EXTENSION_RUNNING
                extensionRemainingMillis = (data.remainingMillis ?: 0L).coerceAtLeast(0L)
                extensionUsed = true
                pendingCooldownKind = data.pendingCooldownKind
                pendingCooldownRemainingMillis = data.pendingCooldownRemainingMillis ?: 0L
            }
            // 旧版本落盘的 TIME_UP（到期停留）：按"确定离开"语义续接满额到期冷却
            GuardState.TIME_UP -> {
                if (config.mechanismEnabled(Mechanism.EXPIRY_COOLDOWN) && config.time.expiryCooldownMillis > 0L) {
                    state = GuardState.EXPIRY_COOLDOWN
                    cooldownKind = CooldownKind.EXPIRY
                    cooldownRemainingMillis = config.time.expiryCooldownMillis
                    cooldownShared = false
                } else {
                    state = GuardState.INACTIVE
                }
            }
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> {
                val kind = if (data.kind == GuardState.EXPIRY_COOLDOWN) CooldownKind.EXPIRY else CooldownKind.ABANDON
                val mechanism = if (kind == CooldownKind.EXPIRY) Mechanism.EXPIRY_COOLDOWN else Mechanism.ABANDON_COOLDOWN
                val remaining = (data.cooldownDeadlineMillis ?: clock.nowMillis()) - clock.nowMillis()
                if (config.mechanismEnabled(mechanism) && remaining > 0L) {
                    state = data.kind
                    cooldownKind = kind
                    cooldownRemainingMillis = remaining
                } else {
                    state = GuardState.INACTIVE
                }
            }
            GuardState.BLACKLISTED -> state = if (config.isForbiddenByQuota()) GuardState.BLACKLISTED else GuardState.INACTIVE
            GuardState.AWAITING_SELECTION -> {
                // 重启恢复（v1.2）：带截止的待选择 = 试探期满退出的重开窗口，按剩余续计；
                // 截止已过（停机期间超窗）直接落闲置，重开按入口语义重新计满试探期
                val deadline = data.selectionDeadlineMillis
                if (deadline != null && clock.nowMillis() >= deadline) {
                    state = GuardState.INACTIVE
                } else {
                    state = GuardState.AWAITING_SELECTION
                    selectionDeadlineMillis = deadline
                    awaitingFromTrialExpiry = deadline != null
                }
            }
            else -> state = data.kind
        }
    }

    override fun exportState(): GuardStateData = when (state) {
        GuardState.RUNNING -> GuardStateData(
            kind = GuardState.RUNNING,
            remainingMillis = sessionRemainingMillis,
            extensionUsed = extensionUsed,
            sessionSelectedMillis = sessionSelectedMillis,
        )
        GuardState.GRACE -> GuardStateData(
            kind = GuardState.GRACE,
            remainingMillis = graceRemainingMillis,
            extensionUsed = extensionUsed,
            sessionSelectedMillis = sessionSelectedMillis,
        )
        GuardState.TRIAL_RUNNING -> GuardStateData(
            kind = GuardState.TRIAL_RUNNING,
            remainingMillis = trialRemainingMillis,
            extensionUsed = extensionUsed,
            trialPausedAtMillis = trialPausedAtMillis,
        )
        GuardState.EXTENSION_RUNNING -> GuardStateData(
            kind = state,
            remainingMillis = extensionRemainingMillis,
            extensionUsed = extensionUsed,
            pendingCooldownKind = pendingCooldownKind,
            pendingCooldownRemainingMillis = pendingCooldownKind?.let { pendingCooldownRemainingMillis },
        )
        GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> GuardStateData(
            kind = state,
            cooldownDeadlineMillis = clock.nowMillis() + cooldownRemainingMillis,
            extensionUsed = extensionUsed,
        )
        GuardState.AWAITING_SELECTION -> GuardStateData(
            kind = state,
            extensionUsed = extensionUsed,
            selectionDeadlineMillis = selectionDeadlineMillis,
        )
        else -> GuardStateData(kind = state, extensionUsed = extensionUsed)
    }

    /**
     * 调试注入（仅调试工具调用，正常事件流不走此路径）：强制切换到 [state]，
     * [remainingMillis] 作为该状态的活动剩余（宽限/冷却/会话/延时/试探；状态无倒计时时忽略）。
     */
    override fun debugInject(state: GuardState, remainingMillis: Long?) {
        lastProcessedMillis = clock.nowMillis()
        when (state) {
            GuardState.INACTIVE -> {
                this.state = GuardState.INACTIVE
                cooldownRemainingMillis = 0L
                cooldownShared = false
                currentOverlay = null
                inForeground = false
                selectionDeadlineMillis = null
                awaitingFromTrialExpiry = false
            }
            GuardState.RUNNING -> {
                this.state = GuardState.RUNNING
                sessionRemainingMillis = (remainingMillis ?: 0L).coerceAtLeast(0L)
                sessionSelectedMillis = sessionRemainingMillis
                firedReminderMarks.clear()
                deferredUsageToasts.clear()
            }
            GuardState.TRIAL_RUNNING -> {
                this.state = GuardState.TRIAL_RUNNING
                trialRemainingMillis = remainingMillis?.coerceAtLeast(0L)
                    ?: config.time.delayedSelectionMinutes * 60_000L
                trialPausedAtMillis = null
            }
            GuardState.GRACE -> {
                this.state = GuardState.GRACE
                graceRemainingMillis = (remainingMillis ?: 0L).coerceAtLeast(0L)
            }
            GuardState.EXTENSION_RUNNING -> {
                this.state = GuardState.EXTENSION_RUNNING
                extensionRemainingMillis = (remainingMillis ?: 0L).coerceAtLeast(0L)
                extensionUsed = true
                pendingCooldownKind = null
                pendingCooldownRemainingMillis = 0L
            }
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> {
                this.state = state
                cooldownKind = if (state == GuardState.EXPIRY_COOLDOWN) CooldownKind.EXPIRY else CooldownKind.ABANDON
                cooldownRemainingMillis = (remainingMillis ?: 0L).coerceAtLeast(0L)
                cooldownShared = false
            }
            GuardState.AWAITING_SELECTION -> {
                this.state = GuardState.AWAITING_SELECTION
                currentOverlay = timeSelectionOverlay()
                selectionDeadlineMillis = null
                awaitingFromTrialExpiry = false
            }
            else -> Unit
        }
        publishSnapshot()
    }

    override fun applySharedCooldown(kind: CooldownKind, durationMillis: Long) {
        // 仅闲置/待选择接收：不打断进行中的会话、宽限、延时或已有冷却
        if (state != GuardState.INACTIVE && state != GuardState.AWAITING_SELECTION) return
        if (durationMillis <= 0L) return
        lastProcessedMillis = clock.nowMillis()
        // 接收共享冷却即脱离待选择流程：清重开窗口语义
        selectionDeadlineMillis = null
        awaitingFromTrialExpiry = false
        state = if (kind == CooldownKind.EXPIRY) GuardState.EXPIRY_COOLDOWN else GuardState.ABANDON_COOLDOWN
        cooldownKind = kind
        cooldownRemainingMillis = durationMillis
        cooldownShared = true
        if (inForeground) {
            currentOverlay = cooldownOverlay()
        } else {
            currentOverlay = null
        }
        publishSnapshot()
    }

    override fun onConfigUpdated(newConfig: GroupConfig) {
        config = newConfig
        if (newConfig.isForbiddenByQuota()) {
            if (state != GuardState.BLACKLISTED) {
                // 分组语义切换为平禁（每日限额 = -1）：黑名单无会话/冷却语义，全部作废
                state = GuardState.BLACKLISTED
                sessionRemainingMillis = 0L
                sessionSelectedMillis = 0L
                graceRemainingMillis = 0L
                extensionRemainingMillis = 0L
                trialRemainingMillis = 0L
                trialPausedAtMillis = null
                selectionDeadlineMillis = null
                awaitingFromTrialExpiry = false
                cooldownRemainingMillis = 0L
                cooldownShared = false
                pendingCooldownKind = null
                usageElapsedMillis = 0L
                firedReminderMarks.clear()
                deferredUsageToasts.clear()
                currentOverlay = if (inForeground) OverlaySpec.Forbidden else null
            }
        } else {
            when {
                state == GuardState.BLACKLISTED -> {
                    // 黑名单语义被解除（防御路径，正常换组由协调器重建实例）
                    state = GuardState.INACTIVE
                    currentOverlay = null
                }
                state == GuardState.RUNNING || state == GuardState.EXTENSION_RUNNING || state == GuardState.DIRECT_RUNNING || state == GuardState.TRIAL_RUNNING -> {
                    val quota = newConfig.remainingQuotaMillis(usedTodayMillis)
                    if (quota != null && quota <= 0L) {
                        // 热更新使当日额度立即超限：前台消耗态即刻中断
                        state = GuardState.QUOTA_EXHAUSTED
                        sessionRemainingMillis = 0L
                        extensionRemainingMillis = 0L
                        trialRemainingMillis = 0L
                        pendingCooldownKind = null
                        currentOverlay = if (inForeground) quotaOverlay() else null
                    }
                }
            }
        }
        // 刷新当前浮层的配置派生载荷（快照按值去重，无变化不发射）
        if (currentOverlay != null) {
            currentOverlay = when (state) {
                GuardState.AWAITING_SELECTION -> timeSelectionOverlay()
                GuardState.QUOTA_EXHAUSTED -> quotaOverlay()
                GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> cooldownOverlay()
                else -> currentOverlay
            }
        }
        publishSnapshot()
    }

    /**
     * 配置保存后的运行状态重置（v1.2）：会话/试探/冷却/宽限/待选择窗口全清，回到闲置；
     * 当日累计与额度禁用语义保留（重置后仍超限则落额度用尽，次日零点解除）。
     * 与 [onConfigUpdated] 的区别：后者只换参数不清状态（进行中的会话不打断），
     * 本方法由协调器在分组配置保存时调用——用户要求更改立刻生效、不留状态残留。
     */
    override fun resetRuntimeState() {
        lastProcessedMillis = clock.nowMillis()
        sessionRemainingMillis = 0L
        sessionSelectedMillis = 0L
        graceRemainingMillis = 0L
        extensionRemainingMillis = 0L
        extensionUsed = false
        extensionPrewarnFired = false
        trialRemainingMillis = 0L
        trialPausedAtMillis = null
        selectionDeadlineMillis = null
        awaitingFromTrialExpiry = false
        cooldownRemainingMillis = 0L
        cooldownShared = false
        pendingCooldownKind = null
        pendingCooldownRemainingMillis = 0L
        usageElapsedMillis = 0L
        firedReminderMarks.clear()
        deferredUsageToasts.clear()
        currentOverlay = null
        inForeground = false
        state = when {
            config.isForbiddenByQuota() -> GuardState.BLACKLISTED
            config.dailyLimitMinutes > 0 && config.remainingQuotaMillis(usedTodayMillis) == 0L ->
                GuardState.QUOTA_EXHAUSTED
            else -> GuardState.INACTIVE
        }
        publishSnapshot()
    }
}
