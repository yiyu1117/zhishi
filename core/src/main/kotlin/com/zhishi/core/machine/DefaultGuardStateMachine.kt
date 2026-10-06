package com.zhishi.core.machine

import com.zhishi.core.model.CooldownKind
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.isForbiddenByQuota
import com.zhishi.core.time.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 守护状态机默认实现（ticket 03–07 的实现对象）。
 *
 * 设计要点：
 * - **事件补偿**：每次 [dispatch] 先按当前状态的时间规则把内部时间线推进到 now，再处理事件；
 *   内部记录 [inForeground] 与 [lastProcessedMillis]。Tick 稀疏或事件间时间跳跃均安全。
 * - **冷却推进规则**：冷却倒计时仅当用户不处于该应用内（`!inForeground`）时推进；
 *   停留冷却浮层或延时使用期间冻结。
 * - **实际到期** = min(会话剩余, 当日剩余额度)；额度先尽立即中断（同刻打平按额度耗尽处理）。
 * - **延时**：每会话一次；从冷却浮层发起时冻结冷却剩余（pending），延时结束回到 TIME_UP，
 *   之后确定/离开均按 pending 剩余继续冷却；从 TIME_UP 直接发起（冷却未开始）则退出时进满额到期冷却。
 * - **提醒**：仅前台消耗期间产生；同刻撞点剩余提醒优先；一旦剩余提醒触发过，
 *   本会话后续周期提示一律抑制（设计草稿规则）。
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
    private var graceRemainingMillis: Long = 0L
    private var extensionRemainingMillis: Long = 0L
    private var extensionUsed: Boolean = false
    private var extensionPrewarnFired: Boolean = false

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
     * 前台消耗态仅在用户处于前台时消耗；冷却与宽限仅当用户不在应用内时推进
     * （宽限的防线防止异常滞留 GRACE 时被 Tick 悄悄耗尽判成中途放弃）。
     */
    private fun compensate(now: Long): List<GuardEffect> {
        val elapsed = now - lastProcessedMillis
        if (elapsed <= 0L) return emptyList()
        return when (state) {
            GuardState.RUNNING -> consumeSession(elapsed)
            GuardState.EXTENSION_RUNNING -> consumeExtension(elapsed)
            GuardState.DIRECT_RUNNING -> consumeDirect(elapsed)
            GuardState.GRACE -> if (!inForeground) advanceGrace(elapsed) else emptyList()
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN ->
                if (!inForeground) advanceCooldown(elapsed) else emptyList()
            else -> emptyList()
        }
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
            sessionRemainingMillis <= 0L -> enterTimeUp(effects)
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
            extensionRemainingMillis <= 0L -> enterTimeUp(effects)
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

    // ------------------------------------------------------------------ 宽限与冷却推进

    /** GRACE：宽限倒计时推进；耗尽判中途放弃，过冲时间计入随后开始的放弃冷却。 */
    private fun advanceGrace(elapsed: Long): List<GuardEffect> {
        val effects = mutableListOf<GuardEffect>()
        graceRemainingMillis -= elapsed
        if (graceRemainingMillis > 0L) return effects
        val overshoot = -graceRemainingMillis
        graceRemainingMillis = 0L
        abandonSession(effects)
        if (state == GuardState.EXPIRY_COOLDOWN || state == GuardState.ABANDON_COOLDOWN) {
            cooldownRemainingMillis -= overshoot
            if (cooldownRemainingMillis <= 0L) toInactive()
        }
        return effects
    }

    /** 冷却推进（仅用户不在应用内时被调用）；自然结束回 INACTIVE，无效果。 */
    private fun advanceCooldown(elapsed: Long): List<GuardEffect> {
        cooldownRemainingMillis -= elapsed
        if (cooldownRemainingMillis <= 0L) toInactive()
        return emptyList()
    }

    private fun toInactive() {
        state = GuardState.INACTIVE
        cooldownRemainingMillis = 0L
        currentOverlay = null
    }

    // ------------------------------------------------------------------ 事件处理

    private fun onForegroundEntered(): List<GuardEffect> {
        inForeground = true
        return when (state) {
            GuardState.INACTIVE -> enterFromInactive()
            GuardState.AWAITING_SELECTION -> show(timeSelectionOverlay())
            GuardState.RUNNING, GuardState.DIRECT_RUNNING, GuardState.EXTENSION_RUNNING -> emptyList()
            GuardState.GRACE -> {
                // 宽限期内回来：会话剩余保持，继续前台计时
                state = GuardState.RUNNING
                emptyList()
            }
            GuardState.TIME_UP -> show(timeUpOverlay())
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> show(cooldownOverlay())
            GuardState.QUOTA_EXHAUSTED -> show(quotaOverlay())
            GuardState.BLACKLISTED -> show(OverlaySpec.Forbidden)
        }
    }

    private fun onBackgrounded(): List<GuardEffect> {
        inForeground = false
        return when (state) {
            GuardState.RUNNING -> {
                // 退后台/息屏：会话计时暂停，宽限倒计时开始
                state = GuardState.GRACE
                graceRemainingMillis = config.time.gracePeriodMillis
                emptyList()
            }
            GuardState.GRACE, GuardState.INACTIVE, GuardState.DIRECT_RUNNING -> emptyList()
            GuardState.EXTENSION_RUNNING -> {
                // 延时提前结束：按被冻结的冷却剩余继续（无 pending 则进满额到期冷却）
                extensionUsed = true
                extensionRemainingMillis = 0L
                val effects = mutableListOf<GuardEffect>()
                val pendingKind = pendingCooldownKind
                pendingCooldownKind = null
                if (pendingKind != null) {
                    enterCooldown(pendingKind, pendingCooldownRemainingMillis, effects, goHome = false, hideOverlay = true, flushState = false)
                } else {
                    enterCooldown(CooldownKind.EXPIRY, config.time.expiryCooldownMillis, effects, goHome = false, hideOverlay = true, flushState = false)
                }
                effects
            }
            GuardState.TIME_UP -> {
                // 用户自行离开：不 GoHome；有被冻结的冷却（延时后）按剩余续接，否则进满额到期冷却
                val effects = mutableListOf<GuardEffect>()
                val pendingKind = pendingCooldownKind
                pendingCooldownKind = null
                if (pendingKind != null) {
                    enterCooldown(pendingKind, pendingCooldownRemainingMillis, effects, goHome = false, hideOverlay = true, flushState = false)
                } else {
                    enterCooldown(CooldownKind.EXPIRY, config.time.expiryCooldownMillis, effects, goHome = false, hideOverlay = true, flushState = false)
                }
                effects
            }
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> {
                val had = currentOverlay != null
                currentOverlay = null
                if (had) listOf(GuardEffect.HideOverlay) else emptyList()
            }
            GuardState.AWAITING_SELECTION, GuardState.QUOTA_EXHAUSTED, GuardState.BLACKLISTED -> {
                val had = currentOverlay != null
                currentOverlay = null
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
        sessionRemainingMillis = minOf(minutes * 60_000L, quota ?: Long.MAX_VALUE)

        state = GuardState.RUNNING
        currentOverlay = null
        return listOf(GuardEffect.HideOverlay)
    }

    private fun onConfirmExit(): List<GuardEffect> = when (state) {
        GuardState.TIME_UP -> {
            val effects = mutableListOf<GuardEffect>()
            val pendingKind = pendingCooldownKind
            pendingCooldownKind = null
            if (pendingKind != null) {
                // 延时来自冷却浮层：按冻结剩余继续该冷却
                enterCooldown(pendingKind, pendingCooldownRemainingMillis, effects, goHome = true, hideOverlay = true, flushState = true)
            } else {
                // 冷却未开始（TIME_UP 停留/延时来自 TIME_UP）：进满额到期冷却
                enterCooldown(CooldownKind.EXPIRY, config.time.expiryCooldownMillis, effects, goHome = true, hideOverlay = true, flushState = true)
            }
            // 确定后主动送回桌面，用户已不在应用内
            inForeground = false
            effects
        }
        GuardState.QUOTA_EXHAUSTED -> {
            inForeground = false
            listOf(GuardEffect.GoHome, GuardEffect.FlushState)
        }
        GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> {
            // 冷却浮层的「返回桌面」：冷却照常推进（用户离开后不再冻结）
            inForeground = false
            currentOverlay = null
            listOf(GuardEffect.GoHome, GuardEffect.HideOverlay, GuardEffect.FlushState)
        }
        GuardState.AWAITING_SELECTION -> {
            // 时间选择浮层的「退出」：未开始会话，无冷却语义，仅送回桌面
            inForeground = false
            currentOverlay = null
            listOf(GuardEffect.GoHome, GuardEffect.HideOverlay)
        }
        else -> emptyList()
    }

    private fun onExtensionRequested(): List<GuardEffect> {
        if (!config.mechanismEnabled(Mechanism.EXTENSION)) return emptyList()
        if (extensionUsed) return emptyList()
        return when (state) {
            GuardState.TIME_UP -> {
                extensionUsed = true
                extensionPrewarnFired = false
                extensionRemainingMillis = config.time.extensionMinutes * 60_000L
                state = GuardState.EXTENSION_RUNNING
                currentOverlay = null
                listOf(GuardEffect.HideOverlay)
            }
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
        val quota = config.remainingQuotaMillis(usedTodayMillis)
        if (quota != null && quota <= 0L && config.dailyLimitMinutes > 0) {
            state = GuardState.QUOTA_EXHAUSTED
            return show(quotaOverlay())
        }
        return when {
            config.mechanismEnabled(Mechanism.TIME_SELECTION) -> {
                state = GuardState.AWAITING_SELECTION
                show(timeSelectionOverlay())
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

    /** 会话到时：用户仍停留应用内，显示"时间已到"浮层（冷却尚未开始）。 */
    private fun enterTimeUp(effects: MutableCollection<GuardEffect>) {
        state = GuardState.TIME_UP
        currentOverlay = timeUpOverlay()
        effects += GuardEffect.ShowOverlay(currentOverlay!!)
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

    /** 中途放弃：会话作废，进放弃冷却（机制关则 INACTIVE）。用户不在应用内，无浮层效果。 */
    private fun abandonSession(effects: MutableCollection<GuardEffect>) {
        sessionRemainingMillis = 0L
        usageElapsedMillis = 0L
        firedReminderMarks.clear()
        deferredUsageToasts.clear()
        pendingCooldownKind = null
        enterCooldown(CooldownKind.ABANDON, config.time.abandonCooldownMillis, effects, goHome = false, hideOverlay = false, flushState = false)
    }

    /** 进入指定冷却（机制关闭或剩余非正则回 INACTIVE）；效果顺序：GoHome → HideOverlay → FlushState。 */
    private fun enterCooldown(
        kind: CooldownKind,
        remainingMillis: Long,
        effects: MutableCollection<GuardEffect>,
        goHome: Boolean,
        hideOverlay: Boolean,
        flushState: Boolean,
    ) {
        currentOverlay = null
        val mechanism = if (kind == CooldownKind.EXPIRY) Mechanism.EXPIRY_COOLDOWN else Mechanism.ABANDON_COOLDOWN
        if (config.mechanismEnabled(mechanism) && remainingMillis > 0L) {
            state = if (kind == CooldownKind.EXPIRY) GuardState.EXPIRY_COOLDOWN else GuardState.ABANDON_COOLDOWN
            cooldownKind = kind
            cooldownRemainingMillis = remainingMillis
        } else {
            state = GuardState.INACTIVE
            cooldownRemainingMillis = 0L
        }
        if (goHome) effects += GuardEffect.GoHome
        if (hideOverlay) effects += GuardEffect.HideOverlay
        if (flushState) effects += GuardEffect.FlushState
    }

    private fun show(spec: OverlaySpec): List<GuardEffect> {
        currentOverlay = spec
        return listOf(GuardEffect.ShowOverlay(spec))
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

    private fun timeUpOverlay(): OverlaySpec = OverlaySpec.TimeUp(
        confirmProtectMillis = config.time.confirmProtectMillis,
        extensionAvailable = config.mechanismEnabled(Mechanism.EXTENSION) && !extensionUsed,
        extensionProtectMillis = config.time.extensionProtectMillis,
    )

    private fun quotaOverlay(): OverlaySpec = OverlaySpec.QuotaExhausted(
        usedLimitMinutes = config.dailyLimitMinutes,
        confirmProtectMillis = config.time.confirmProtectMillis,
    )

    private fun cooldownOverlay(): OverlaySpec = OverlaySpec.Cooldown(
        kind = cooldownKind,
        extensionAvailable = config.mechanismEnabled(Mechanism.EXTENSION) && !extensionUsed,
        extensionProtectMillis = config.time.extensionProtectMillis,
    )

    private fun todayRemainingMinutes(): Int? =
        config.remainingQuotaMillis(usedTodayMillis)?.let { (it / 60_000L).toInt() }

    // ------------------------------------------------------------------ 快照 / 恢复 / 热更新

    private fun publishSnapshot() {
        val remaining = when (state) {
            GuardState.RUNNING -> sessionRemainingMillis
            GuardState.GRACE -> graceRemainingMillis
            GuardState.EXTENSION_RUNNING -> extensionRemainingMillis
            GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> cooldownRemainingMillis
            else -> null
        }
        mutableSnapshot.value = GuardSnapshot(
            packageName = packageName,
            state = state,
            overlay = currentOverlay,
            remainingMillis = remaining,
            usedTodayMillis = usedTodayMillis,
            todayRemainingMinutes = todayRemainingMinutes(),
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
            // RUNNING 重启等同退后台：会话剩余保持，宽限期从头计
            GuardState.RUNNING -> {
                sessionRemainingMillis = (data.remainingMillis ?: 0L).coerceAtLeast(0L)
                state = GuardState.GRACE
                graceRemainingMillis = config.time.gracePeriodMillis
            }
            GuardState.GRACE -> {
                state = GuardState.GRACE
                graceRemainingMillis = (data.remainingMillis ?: 0L).coerceAtLeast(0L)
            }
            GuardState.EXTENSION_RUNNING -> {
                state = GuardState.EXTENSION_RUNNING
                extensionRemainingMillis = (data.remainingMillis ?: 0L).coerceAtLeast(0L)
                extensionUsed = true
                pendingCooldownKind = data.pendingCooldownKind
                pendingCooldownRemainingMillis = data.pendingCooldownRemainingMillis ?: 0L
            }
            GuardState.TIME_UP -> {
                state = GuardState.TIME_UP
                extensionUsed = data.extensionUsed
                pendingCooldownKind = data.pendingCooldownKind
                pendingCooldownRemainingMillis = data.pendingCooldownRemainingMillis ?: 0L
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
            else -> state = data.kind
        }
    }

    override fun exportState(): GuardStateData = when (state) {
        GuardState.RUNNING -> GuardStateData(
            kind = GuardState.RUNNING,
            remainingMillis = sessionRemainingMillis,
            extensionUsed = extensionUsed,
        )
        GuardState.GRACE -> GuardStateData(
            kind = GuardState.GRACE,
            remainingMillis = graceRemainingMillis,
            extensionUsed = extensionUsed,
        )
        GuardState.EXTENSION_RUNNING, GuardState.TIME_UP -> GuardStateData(
            kind = state,
            remainingMillis = if (state == GuardState.EXTENSION_RUNNING) extensionRemainingMillis else null,
            extensionUsed = extensionUsed,
            pendingCooldownKind = pendingCooldownKind,
            pendingCooldownRemainingMillis = pendingCooldownKind?.let { pendingCooldownRemainingMillis },
        )
        GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> GuardStateData(
            kind = state,
            cooldownDeadlineMillis = clock.nowMillis() + cooldownRemainingMillis,
            extensionUsed = extensionUsed,
        )
        else -> GuardStateData(kind = state, extensionUsed = extensionUsed)
    }

    /**
     * 调试注入（仅调试工具调用，正常事件流不走此路径）：强制切换到 [state]，
     * [remainingMillis] 作为该状态的活动剩余（宽限/冷却/会话/延时；状态无倒计时时忽略）。
     */
    override fun debugInject(state: GuardState, remainingMillis: Long?) {
        lastProcessedMillis = clock.nowMillis()
        when (state) {
            GuardState.INACTIVE -> {
                toInactive()
                inForeground = false
            }
            GuardState.RUNNING -> {
                this.state = GuardState.RUNNING
                sessionRemainingMillis = (remainingMillis ?: 0L).coerceAtLeast(0L)
                firedReminderMarks.clear()
                deferredUsageToasts.clear()
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
            }
            GuardState.TIME_UP -> {
                this.state = GuardState.TIME_UP
                currentOverlay = timeUpOverlay()
            }
            GuardState.AWAITING_SELECTION -> {
                this.state = GuardState.AWAITING_SELECTION
                currentOverlay = timeSelectionOverlay()
            }
            else -> Unit
        }
        publishSnapshot()
    }

    override fun onConfigUpdated(newConfig: GroupConfig) {        config = newConfig
        if (newConfig.isForbiddenByQuota()) {
            if (state != GuardState.BLACKLISTED) {
                // 分组语义切换为平禁（每日限额 = -1）：黑名单无会话/冷却语义，全部作废
                state = GuardState.BLACKLISTED
                sessionRemainingMillis = 0L
                graceRemainingMillis = 0L
                extensionRemainingMillis = 0L
                cooldownRemainingMillis = 0L
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
                state == GuardState.RUNNING || state == GuardState.EXTENSION_RUNNING || state == GuardState.DIRECT_RUNNING -> {
                    val quota = newConfig.remainingQuotaMillis(usedTodayMillis)
                    if (quota != null && quota <= 0L) {
                        // 热更新使当日额度立即超限：前台消耗态即刻中断
                        state = GuardState.QUOTA_EXHAUSTED
                        sessionRemainingMillis = 0L
                        extensionRemainingMillis = 0L
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
                GuardState.TIME_UP -> timeUpOverlay()
                GuardState.QUOTA_EXHAUSTED -> quotaOverlay()
                GuardState.EXPIRY_COOLDOWN, GuardState.ABANDON_COOLDOWN -> cooldownOverlay()
                else -> currentOverlay
            }
        }
        publishSnapshot()
    }
}
