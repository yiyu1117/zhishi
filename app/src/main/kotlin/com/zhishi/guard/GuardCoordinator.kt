package com.zhishi.guard

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zhishi.core.machine.DefaultGuardStateMachine
import com.zhishi.core.machine.GuardEffect
import com.zhishi.core.machine.GuardEvent
import com.zhishi.core.machine.GuardSnapshot
import com.zhishi.core.machine.GuardState
import com.zhishi.core.machine.GuardStateData
import com.zhishi.core.machine.GuardStateMachine
import com.zhishi.core.machine.ToastEvent
import com.zhishi.core.model.DetectionMode
import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.isForbiddenByQuota
import com.zhishi.core.time.AppClock
import com.zhishi.data.AppCatalog
import com.zhishi.data.GroupConfigUpdate
import com.zhishi.data.GroupIds
import com.zhishi.data.GroupRepository
import com.zhishi.data.GroupSnapshot
import com.zhishi.data.SettingsRepository
import com.zhishi.data.UsageLedger
import com.zhishi.detect.ForegroundApp
import com.zhishi.detect.ForegroundMonitor
import com.zhishi.detect.PollingForegroundDetector
import com.zhishi.detect.PollingForegroundService
import com.zhishi.detect.ZhishiAccessibilityService
import com.zhishi.overlay.DebugOverlayController
import com.zhishi.overlay.MachineDebugEntry
import com.zhishi.overlay.OverlayInteractions
import com.zhishi.overlay.OverlayManager
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 常驻通知的用量载荷：当前前台应用的活动使用剩余（会话/延时），文案由展示方（轮询服务）组装。
 */
data class NotificationUsage(val appName: String, val remainingMillis: Long)

/**
 * 守护协调器（单例，由 Application 持有；进程内常驻）：
 * - 维护 packageName → GuardStateMachine 映射；
 * - 1 秒 ticker 派发 Tick，检测自然日切换派发 DayChanged；
 * - 前台事件来自 [ForegroundMonitor]，以"前台机器集合"建模（分屏/可见窗口下可多个）：
 *   焦点离开走退后台挂起确认（瞬态系统窗口如音量条不构成真实切换，ADR 0003），
 *   无障碍窗口扫描的可见机器按"可见即前台"进入/退出；
 * - 效果翻译：ShowOverlay/HideOverlay → 浮层、ShowToast → Toast、GoHome → 退出动作、FlushState → 记账落盘；
 * - 浮层可见期间经 [MediaSilencer] 持有音频焦点（被拦应用不出声）；
 * - 依据分组规则快照重建/热更新状态机；实现 [OverlayInteractions] 接收浮层按钮回调并派发状态机事件；
 * - 记账与恢复：启动时从 [UsageLedger] 载入当日累计与 GuardStateData（ticket 06/10），
 *   周期节流落盘 + FlushState 即时落盘；
 * - 检测模式：跟随 [SettingsRepository.detectionMode] 在无障碍 provider 与轮询服务间切换（ticket 09）；
 * - 发布常驻通知剩余用量（[notificationUsage]）与调试悬浮窗状态（[debugStates]，设置开关显隐）。
 */
class GuardCoordinator(
    private val appContext: Context,
    private val monitor: ForegroundMonitor,
    private val overlayManager: OverlayManager,
    private val exitAction: ExitAction,
    private val repository: GroupRepository,
    private val catalog: AppCatalog,
    private val ledger: UsageLedger,
    private val settings: SettingsRepository,
) : OverlayInteractions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 拦截浮层可见期间的媒体静音器：持有音频焦点，被拦应用暂停出声。 */
    private val mediaSilencer = MediaSilencer(appContext)

    init {
        // 浮层可见 ⇔ 持有音频焦点：开屏即播的媒体应用被暂停；对着浮层按返回刷出的
        // 新视频重新请求焦点时被立即夺回，杜绝"没画面但有声音"。
        overlayManager.onVisibilityChanged = { _, visible ->
            if (visible) mediaSilencer.acquire() else mediaSilencer.release()
        }
    }

    private val machines = LinkedHashMap<String, GuardStateMachine>()
    private val machineConfigs = HashMap<String, GroupConfig>()
    private val machineJobs = HashMap<String, Job>()

    private var rulesSnapshot: GroupSnapshot? = null
    private var currentDay: LocalDate? = null
    private var running = false
    private var jobs: List<Job> = emptyList()

    /** 最近一次监视器报告的前台包名（可能是瞬态窗口；机器归属以 [foregroundMachines] 为准）。 */
    private var lastForeground: String? = null

    /** 机器视角的前台集合：已派发 ForegroundEntered、尚未派发 Backgrounded 的包（分屏/可见窗口下可有多个）。 */
    private val foregroundMachines = mutableSetOf<String>()

    /** 退后台挂起确认任务（按机器包名）：异包报告可能是瞬态系统窗口（音量条等）伪装成切换，证实后才退后台。 */
    private val pendingLeaves = HashMap<String, Job>()

    /** 无障碍窗口扫描的可见机器包（"可见即前台"，ADR 0003）；轮询模式恒为空。 */
    private var visibleMachinePkgs: Set<String> = emptySet()

    /** 常驻通知订阅流：当前前台应用有活动使用剩余时非空（轮询服务收集并刷新通知）。 */
    private val _notificationUsage = MutableStateFlow<NotificationUsage?>(null)
    val notificationUsage: StateFlow<NotificationUsage?> = _notificationUsage.asStateFlow()

    /** 调试悬浮窗数据流：全部状态机的实时状态（含闲置），设置页「调试模式」开关控制显隐。 */
    private val _debugStates = MutableStateFlow<List<MachineDebugEntry>>(emptyList())
    val debugStates: StateFlow<List<MachineDebugEntry>> = _debugStates.asStateFlow()
    private val debugEntries = LinkedHashMap<String, MachineDebugEntry>()

    /** 调试小悬浮窗（设置开关显隐，不可触摸、不影响操作）。 */
    private val debugOverlay = DebugOverlayController(appContext, debugStates)

    // region 记账与恢复状态

    /** 启动时从数据库载入的可恢复状态（createMachine 消费一次）。 */
    private var restoredStates: Map<String, GuardStateData> = emptyMap()

    /** 当日累计缓存（应用 → 毫秒）：机器构造时注入，落盘后回写。 */
    private val usedTodayCache = HashMap<String, Long>()

    /** 恢复数据是否已载入；载入前不创建状态机（否则会丢恢复状态）。 */
    @Volatile
    private var bootstrapped = false

    // endregion

    // region 启停

    fun start() {
        if (running) return
        running = true
        currentDay = LocalDate.now()
        monitor.start()
        jobs = listOf(
            scope.launch { tickLoop() },
            scope.launch { monitor.state.collect { onForegroundApp(it) } },
            scope.launch { monitor.visiblePackages.collect { onVisiblePackages(it) } },
            scope.launch { repository.groupSnapshot.collect { onRulesChanged(it) } },
            scope.launch { repository.configUpdates.collect { onConfigUpdate(it) } },
            scope.launch { catalog.apps.collect { rulesSnapshot?.let(::onRulesChanged) } },
            scope.launch { settings.detectionMode.collect { switchMode(it) } },
            scope.launch { settings.debugMode.collect { debugOverlay.setVisible(it) } },
        )
        // 先载入记账与恢复数据，再对账创建状态机
        scope.launch {
            val day = todayIso()
            // 跨日恢复：旧自然日落盘的 QUOTA_EXHAUSTED 等同 DayChanged 重置（丢弃，按 INACTIVE 重建）——
            // 启动时 currentDay 已是今天，DayChanged 不会再触发，不丢弃会把用户按昨日额度禁用一整天。
            // 冷却的 deadline 为绝对时刻、其余状态在进程内本就跨零点保留，均照常恢复。
            restoredStates = ledger.loadAllGuardStates()
                .filterValues { rec -> rec.savedDay == day || rec.data.kind != GuardState.QUOTA_EXHAUSTED }
                .mapValues { it.value.data }
            ledger.dayTotals(day).forEach { (pkg, used) -> usedTodayCache[pkg] = used }
            bootstrapped = true
            rulesSnapshot?.let(::onRulesChanged)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        flushAll()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        monitor.stop()
        overlayManager.hideAll()
        machines.clear()
        machineConfigs.clear()
        machineJobs.values.forEach { it.cancel() }
        machineJobs.clear()
        lastForeground = null
        rulesSnapshot = null
        _notificationUsage.value = null
        pendingLeaves.values.forEach { it.cancel() }
        pendingLeaves.clear()
        foregroundMachines.clear()
        visibleMachinePkgs = emptySet()
        debugEntries.clear()
        _debugStates.value = emptyList()
        debugOverlay.setVisible(false)
        // 复位记账/恢复引导状态：再次 start() 时重新载入，避免沿用本进程上个会话的残留缓存
        bootstrapped = false
        restoredStates = emptyMap()
        usedTodayCache.clear()
    }

    // endregion

    // region 时间驱动

    private suspend fun tickLoop() {
        var tick = 0L
        while (true) {
            delay(TICK_MILLIS)
            val today = LocalDate.now() // 系统时区自然日
            if (today != currentDay) {
                currentDay = today
                // 自然日切换：状态机清当日累计/解除禁用，随后以新记账日落盘
                dispatchToAll(GuardEvent.DayChanged)
                flushAll()
            }
            dispatchToAll(GuardEvent.Tick)
            if (++tick % FLUSH_EVERY_TICKS == 0L) flushAll()
        }
    }

    private fun dispatchToAll(event: GuardEvent) {
        for ((pkg, machine) in machines) {
            translate(pkg, machine.dispatch(event))
        }
    }

    // endregion

    // region 前台事件

    private fun onForegroundApp(app: ForegroundApp?) {
        val next = app?.packageName
        if (next == null) {
            // 息屏/监视器复位：无瞬态混淆空间，全部机器立即退后台
            pendingLeaves.values.forEach { it.cancel() }
            pendingLeaves.clear()
            for (pkg in foregroundMachines.toList()) dispatchLeave(pkg, force = true)
            return
        }
        // 原应用窗口重新夺回焦点（瞬态窗口消失后的回声）：确认作废，机器保持前台
        pendingLeaves.remove(next)?.cancel()
        if (next in foregroundMachines) return // 同包重报：去重
        Log.d(TAG, "dispatch ForegroundEntered pkg=$next machine=${machines.containsKey(next)}")
        machines[next]?.let {
            foregroundMachines.add(next)
            translate(next, it.dispatch(GuardEvent.ForegroundEntered))
        }
        // 焦点离开的旧机器：挂起确认——异包报告可能是瞬态系统窗口（音量条等）伪装成切换，
        // 它没有 UsageStats 记录；证实确有新前台事件才派发 Backgrounded（ADR 0003）。
        val prev = lastForeground
        if (prev != null && prev != next && prev in foregroundMachines && prev !in pendingLeaves && machines.containsKey(prev)) {
            pendLeave(prev)
        }
        lastForeground = next
        refreshNotificationUsage()
        // 非守护应用（无状态机）绝不弹浮层
    }

    /** 可见窗口集合变化（无障碍扫描）：被管理应用的窗口可见即视为前台（分屏/自由窗口拦截）。 */
    private fun onVisiblePackages(packages: Set<String>) {
        val withMachines = packages.filterTo(HashSet()) { machines.containsKey(it) }
        val previous = visibleMachinePkgs
        visibleMachinePkgs = withMachines
        for (pkg in withMachines) {
            if (pkg in foregroundMachines) continue
            Log.d(TAG, "dispatch ForegroundEntered (visible window) pkg=$pkg")
            machines[pkg]?.let {
                foregroundMachines.add(pkg)
                translate(pkg, it.dispatch(GuardEvent.ForegroundEntered))
            }
        }
        for (pkg in previous) {
            // 窗口消失是退后台的直接证据，不走挂起确认（连带取消在途确认）
            if (pkg !in withMachines && pkg in foregroundMachines) {
                Log.d(TAG, "dispatch Backgrounded (window gone) pkg=$pkg")
                dispatchLeave(pkg, force = true)
            }
        }
        refreshNotificationUsage()
    }

    /**
     * 退后台挂起确认：短候轮询 UsageStats，出现"新应用前台事件 + 原应用暂停记录"即证实
     * 真实切换；仅见新应用事件而原应用始终无暂停记录（被强杀/崩溃）超过安全窗后强制放行；
     * 两者皆无（音量条等瞬态窗口）则视为误报丢弃，浮层与计时如常。真实切换的代价是
     * 退后台判定晚约 1–2 秒，会话/冷却误差可忽略。
     */
    private fun pendLeave(pkg: String) {
        Log.d(TAG, "leave pending (await usage evidence) pkg=$pkg")
        pendingLeaves[pkg]?.cancel()
        // 回看窗回溯 2 秒：容纳事件落账延迟，又排除挂起前与本切换无关的旧事件
        val since = System.currentTimeMillis() - LEAVE_CONFIRM_STEP_MILLIS - LEAVE_EVIDENCE_BACKDATE_MILLIS
        pendingLeaves[pkg] = scope.launch {
            var sawForeignResume = false
            var waited = 0L
            while (waited < LEAVE_CONFIRM_FORCE_MILLIS) {
                delay(LEAVE_CONFIRM_STEP_MILLIS)
                waited += LEAVE_CONFIRM_STEP_MILLIS
                val evidence = PollingForegroundDetector.foregroundSwitchEvidence(appContext, pkg, since)
                if (evidence.foreignResumed) sawForeignResume = true
                if (evidence.confirmed) {
                    Log.d(TAG, "leave confirmed pkg=$pkg")
                    pendingLeaves.remove(pkg)
                    dispatchLeave(pkg, force = false)
                    return@launch
                }
            }
            pendingLeaves.remove(pkg)
            if (sawForeignResume) {
                Log.d(TAG, "leave force-accepted (no pause record) pkg=$pkg")
                dispatchLeave(pkg, force = false)
            } else {
                Log.d(TAG, "leave dropped (transient window) pkg=$pkg")
            }
        }
    }

    /** 派发退后台；[force] = false 时若窗口仍可见（分屏/自由窗口）则抑制。 */
    private fun dispatchLeave(pkg: String, force: Boolean) {
        if (!force && pkg in visibleMachinePkgs) {
            Log.d(TAG, "leave suppressed (window still visible) pkg=$pkg")
            return
        }
        pendingLeaves.remove(pkg)?.cancel()
        if (!foregroundMachines.remove(pkg)) return
        machines[pkg]?.let { translate(pkg, it.dispatch(GuardEvent.Backgrounded)) }
        refreshNotificationUsage()
    }

    /**
     * 刷新通知用量载荷：优先取焦点所在机器，其余前台机器兜底——发布首个处于
     * 会话中（RUNNING）/ 延时中（EXTENSION_RUNNING）且剩余非空者的活动剩余。
     * StateFlow 按值去重，调用方无需判变。
     */
    private fun refreshNotificationUsage() {
        val candidates = buildList {
            lastForeground?.let { if (it in foregroundMachines) add(it) }
            foregroundMachines.forEach { if (it !in this) add(it) }
        }
        for (pkg in candidates) {
            val snapshot = machines[pkg]?.snapshot?.value ?: continue
            val remaining = snapshot.remainingMillis
            if ((snapshot.state == GuardState.RUNNING || snapshot.state == GuardState.EXTENSION_RUNNING) &&
                remaining != null && remaining > 0L
            ) {
                _notificationUsage.value = NotificationUsage(catalog.appName(pkg), remaining)
                return
            }
        }
        _notificationUsage.value = null
    }

    // endregion

    // region 浮层交互回调（OverlayInteractions）

    override fun onDurationSelected(packageName: String, minutes: Int) {
        machines[packageName]?.let { translate(packageName, it.dispatch(GuardEvent.DurationSelected(minutes))) }
    }

    override fun onConfirmExit(packageName: String) {
        machines[packageName]?.let { translate(packageName, it.dispatch(GuardEvent.ConfirmExit)) }
    }

    override fun onExtensionRequested(packageName: String) {
        machines[packageName]?.let { translate(packageName, it.dispatch(GuardEvent.ExtensionRequested)) }
    }

    /**
     * 浮层「刷新」自检：探测当前真实前台，若并非 [packageName]（误弹/ROM 异常导致的
     * 残留浮层），按退后台处理并关闭浮层（返回 false）；确认仍在前台则保留（返回 true，
     * 由浮层内联提示）。前台探测双源：UsageStats 最近使用启发式优先（监视器本身可能
     * 是卡住的源头），失败回退监视器实时判定。
     */
    override fun onRefreshRequested(packageName: String): Boolean {
        val actual = PollingForegroundDetector.latestUsedPackage(appContext)
            ?: monitor.state.value?.packageName
        if (actual == packageName) return true
        machines[packageName]?.let { translate(packageName, it.dispatch(GuardEvent.Backgrounded)) }
        overlayManager.hide(packageName)
        return false
    }

    // endregion

    // region 效果翻译

    private fun translate(packageName: String, effects: List<GuardEffect>) {
        for (effect in effects) {
            when (effect) {
                is GuardEffect.ShowOverlay ->
                    overlayManager.show(packageName, catalog.appName(packageName), effect.spec)
                GuardEffect.HideOverlay -> overlayManager.hide(packageName)
                is GuardEffect.ShowToast -> showToast(effect.toast)
                GuardEffect.GoHome -> {
                    // 主动回桌面：机器即刻退出前台态（随后不会也不必再有退后台事件），
                    // 此处兜底关闭浮层（额度用尽的确定路径无 HideOverlay 效果）
                    foregroundMachines.remove(packageName)
                    pendingLeaves.remove(packageName)?.cancel()
                    overlayManager.hide(packageName)
                    exitAction.goToHome()
                }
                GuardEffect.FlushState -> flush(packageName)
            }
        }
    }

    private fun showToast(toast: ToastEvent) {
        val message = when (toast) {
            is ToastEvent.UsagePeriod -> "本次已使用 ${toast.elapsedMinutes} 分钟"
            is ToastEvent.TimeRemaining -> "距离结束还有 ${toast.remainingMinutes} 分钟"
            is ToastEvent.ExtensionPrewarn -> "延时还剩 ${toast.secondsLeft} 秒，结束后将锁定"
        }
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region 记账落盘（ticket 06/10）

    /** 立即落盘单个应用的当日累计与可恢复状态。 */
    private fun flush(packageName: String) {
        val machine = machines[packageName] ?: return
        val day = todayIso()
        val usedToday = machine.snapshot.value.usedTodayMillis
        val state = machine.exportState()
        usedTodayCache[packageName] = usedToday // 同步回写：后续 createMachine 读到的是最新值
        scope.launch {
            ledger.setUsage(packageName, day, usedToday)
            ledger.saveGuardState(packageName, state)
        }
    }

    /** 周期节流落盘全部活跃机器。 */
    private fun flushAll() {
        for (pkg in machines.keys.toList()) flush(pkg)
    }

    private fun todayIso(): String =
        UsageLedger.localDayIso(System.currentTimeMillis())

    // endregion

    // region 检测模式切换（ticket 09）

    private fun switchMode(mode: DetectionMode) {
        // 「↻ 刷新」逃生门仅轮询模式暴露：无障碍模式下事件源与前台判定本就一致，
        // 且 UsageStats 落账有约 1 秒延迟——刚打开应用时点刷新会误判关闭浮层，反成绕过口子。
        overlayManager.setRefreshEnabled(mode == DetectionMode.POLLING)
        when (mode) {
            DetectionMode.POLLING -> {
                // 有使用情况访问权限才拉起轮询服务；未授权时由设置页引导卡走授权流程
                if (PollingForegroundDetector.hasUsageAccess(appContext)) {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, PollingForegroundService::class.java),
                    )
                }
            }
            DetectionMode.ACCESSIBILITY -> {
                // 先停轮询服务（onDestroy 自行拆线），再把无障碍 provider 接回监视器
                appContext.stopService(Intent(appContext, PollingForegroundService::class.java))
                ZhishiAccessibilityService.boundInstance()?.let { monitor.attach(it) }
            }
        }
    }

    // endregion

    // region 分组规则 → 状态机映射（ticket 02/03 接线）

    private fun onRulesChanged(new: GroupSnapshot) {
        val old = rulesSnapshot
        rulesSnapshot = new
        if (!bootstrapped) return // 恢复数据未载入，等 bootstrap 完成后重新对账
        val newGroups = new.groups.associateBy { it.id }
        val oldGroups = old?.groups?.associateBy { it.id } ?: emptyMap()
        val packages = buildSet {
            old?.assignments?.let { addAll(it.keys) }
            addAll(new.assignments.keys)
            addAll(catalog.userPackages())
        }
        for (pkg in packages) {
            val oldGid = old?.assignments?.get(pkg) ?: GroupIds.DEFAULT
            val newGid = new.assignments[pkg] ?: GroupIds.DEFAULT
            val oldGroup = oldGroups[oldGid]
            val newGroup = newGroups[newGid] ?: newGroups[GroupIds.DEFAULT] ?: continue
            val sameGroup = old != null && oldGroup != null && oldGroup.id == newGroup.id
            if (sameGroup) {
                val machine = machines[pkg]
                when {
                    machine == null && needsMachine(newGroup) -> createMachine(pkg, newGroup.config)
                    machine != null && !needsMachine(newGroup) -> removeMachine(pkg)
                    machine != null && machineConfigs[pkg] != newGroup.config -> {
                        // 配置变化但不换组：热更新，不清状态
                        machine.onConfigUpdated(newGroup.config)
                        machineConfigs[pkg] = newGroup.config
                    }
                }
            } else {
                // 换组即清状态（spec：更换分组即清空该应用的会话与冷却状态）
                removeMachine(pkg)
                if (needsMachine(newGroup)) createMachine(pkg, newGroup.config)
            }
        }
        // 防御性清理：已不在规则范围内的机器（如数据残留）
        (machines.keys.toSet() - packages).forEach(::removeMachine)
    }

    /** 配置级更新回调（repository 暴露）：不换组时调用机器 onConfigUpdated，不清状态。 */
    private fun onConfigUpdate(update: GroupConfigUpdate) {
        val snapshot = rulesSnapshot ?: return
        val assigned = snapshot.assignments
        val members = buildList {
            assigned.filterValues { it == update.groupId }.keys.forEach(::add)
            if (update.groupId == GroupIds.DEFAULT) {
                // 未分配的用户应用逻辑上属于默认组
                catalog.userPackages().filter { it !in assigned }.forEach(::add)
            }
        }
        for (pkg in members) {
            val machine = machines[pkg] ?: continue
            if (machineConfigs[pkg] != update.config) {
                machine.onConfigUpdated(update.config)
                machineConfigs[pkg] = update.config
            }
        }
    }

    /**
     * 需要状态机的分组：黑名单（每日限额 -1）、任一机制开启、或每日限额 > 0（直接记账 + 限额中断）。
     * 白名单完全豁免；机制全关且无限额不建实例（默认组兜底语义）。
     */
    private fun needsMachine(group: Group): Boolean =
        group.kind != GroupKind.WHITELIST && (
            group.config.isForbiddenByQuota() ||
                group.config.mechanisms.isNotEmpty() ||
                group.config.dailyLimitMinutes > 0
            )

    private fun createMachine(pkg: String, config: GroupConfig) {
        if (machines.containsKey(pkg)) return
        Log.d(TAG, "machine+ pkg=$pkg mechanisms=${config.mechanisms} dailyLimit=${config.dailyLimitMinutes}")
        val restored = restoredStates[pkg] // 恢复数据消费一次：换组重建不得复用旧状态
        if (restored != null) restoredStates = restoredStates - pkg
        val machine = DefaultGuardStateMachine(
            packageName = pkg,
            clock = AppClock { System.currentTimeMillis() },
            config = config,
            todayUsedMillis = usedTodayCache[pkg] ?: 0L,
            restored = restored,
        )
        machines[pkg] = machine
        machineConfigs[pkg] = config
        machineJobs[pkg] = scope.launch {
            machine.snapshot.collect { snapshot -> syncOverlayFromSnapshot(pkg, snapshot) }
        }
        // 规则热更新时该应用可能已在前台（焦点或可见窗口）：补发进入前台事件
        if (monitor.state.value?.packageName == pkg || pkg in visibleMachinePkgs) {
            foregroundMachines.add(pkg)
            translate(pkg, machine.dispatch(GuardEvent.ForegroundEntered))
        }
    }

    private fun removeMachine(pkg: String) {
        val machine = machines[pkg] ?: return
        Log.d(TAG, "machine- pkg=$pkg")
        // 摘除前先落盘当日累计（分组切换语义下随后清除会话/冷却状态）
        val day = todayIso()
        val usedToday = machine.snapshot.value.usedTodayMillis
        machineJobs.remove(pkg)?.cancel()
        machines.remove(pkg)
        machineConfigs.remove(pkg)
        pendingLeaves.remove(pkg)?.cancel()
        foregroundMachines.remove(pkg)
        debugEntries.remove(pkg)
        publishDebugStates()
        overlayManager.hide(pkg)
        usedTodayCache[pkg] = usedToday // 同步回写：换组紧接 createMachine 时当日累计不回退
        scope.launch {
            ledger.setUsage(pkg, day, usedToday)
            ledger.clearGuardState(pkg)
        }
    }

    /** 浮层内容以状态机快照收敛（效果路径之外的兜底，同时驱动冷却倒计时等刷新）。 */
    private fun syncOverlayFromSnapshot(pkg: String, snapshot: GuardSnapshot) {
        overlayManager.syncFromMachine(pkg, catalog.appName(pkg), snapshot.overlay, snapshot.remainingMillis)
        refreshNotificationUsage() // 会话/延时剩余逐秒推进：常驻通知随快照刷新
        debugEntries[pkg] = MachineDebugEntry(
            appName = catalog.appName(pkg),
            state = snapshot.state,
            remainingMillis = snapshot.remainingMillis,
            usedTodayMillis = snapshot.usedTodayMillis,
        )
        publishDebugStates()
    }

    /** 发布调试数据：活动状态在前（剩余升序），闲置在后，应用名次序稳定。 */
    private fun publishDebugStates() {
        _debugStates.value = debugEntries.values
            .sortedWith(
                compareBy({ it.state == GuardState.INACTIVE }, { it.remainingMillis ?: Long.MAX_VALUE }, { it.appName }),
            )
    }

    // endregion

    private companion object {
        const val TAG = "ZhishiGuard"
        const val TICK_MILLIS = 1_000L

        /** 记账节流周期（tick 数）：约每 10 秒全量落盘一次，状态切换时另有即时落盘。 */
        const val FLUSH_EVERY_TICKS = 10L

        /** 退后台挂起确认的轮询步长（毫秒）。 */
        const val LEAVE_CONFIRM_STEP_MILLIS = 500L

        /** 退后台挂起确认的强制放行安全窗（毫秒）：应用被强杀永远等不到暂停记录（与轮询检测一致）。 */
        const val LEAVE_CONFIRM_FORCE_MILLIS = 10_000L

        /** 切换证据回看窗的回溯量（毫秒）：容纳 UsageStats 落账延迟，排除挂起前的无关旧事件。 */
        const val LEAVE_EVIDENCE_BACKDATE_MILLIS = 2_000L
    }
}
