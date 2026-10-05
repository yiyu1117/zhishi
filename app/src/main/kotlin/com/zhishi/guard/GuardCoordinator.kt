package com.zhishi.guard

import android.content.Context
import android.widget.Toast
import com.zhishi.core.machine.DefaultGuardStateMachine
import com.zhishi.core.machine.GuardEffect
import com.zhishi.core.machine.GuardEvent
import com.zhishi.core.machine.GuardSnapshot
import com.zhishi.core.machine.GuardStateMachine
import com.zhishi.core.machine.ToastEvent
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
import com.zhishi.detect.ForegroundApp
import com.zhishi.detect.ForegroundMonitor
import com.zhishi.overlay.OverlayManager
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 守护协调器（单例，由 Application 持有）：
 * - 维护 packageName → GuardStateMachine 映射；
 * - 1 秒 ticker 派发 Tick，检测自然日切换派发 DayChanged；
 * - 前台事件来自 [ForegroundMonitor]，进后台时向原应用状态机派发 Backgrounded；
 * - 效果翻译：ShowOverlay/HideOverlay → 浮层、ShowToast → Toast、GoHome → 退出动作、FlushState → 记账（ticket 06/10）；
 * - 依据分组规则快照重建/热更新状态机（ticket 02 接线：黑名单语义）。
 */
class GuardCoordinator(
    private val appContext: Context,
    private val monitor: ForegroundMonitor,
    private val overlayManager: OverlayManager,
    private val exitAction: ExitAction,
    private val repository: GroupRepository,
    private val catalog: AppCatalog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val machines = LinkedHashMap<String, GuardStateMachine>()
    private val machineConfigs = HashMap<String, GroupConfig>()
    private val machineJobs = HashMap<String, Job>()

    private var rulesSnapshot: GroupSnapshot? = null
    private var lastForeground: String? = null
    private var currentDay: LocalDate? = null
    private var running = false
    private var jobs: List<Job> = emptyList()

    // region 启停

    fun start() {
        if (running) return
        running = true
        currentDay = LocalDate.now()
        monitor.start()
        jobs = listOf(
            scope.launch { tickLoop() },
            scope.launch { monitor.state.collect { onForegroundApp(it) } },
            scope.launch { repository.groupSnapshot.collect { onRulesChanged(it) } },
            scope.launch { repository.configUpdates.collect { onConfigUpdate(it) } },
            scope.launch { catalog.apps.collect { rulesSnapshot?.let(::onRulesChanged) } },
        )
    }

    fun stop() {
        if (!running) return
        running = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        monitor.stop()
        overlayManager.hideAll()
        machines.keys.toList().forEach(::removeMachine)
        lastForeground = null
        rulesSnapshot = null
    }

    // endregion

    // region 时间驱动

    private suspend fun tickLoop() {
        while (true) {
            delay(TICK_MILLIS)
            val today = LocalDate.now() // 系统时区自然日
            if (today != currentDay) {
                currentDay = today
                // 自然日切换：清当日记账（记账落库见 ticket 06/10）并解除当日禁用
                dispatchToAll(GuardEvent.DayChanged)
            }
            dispatchToAll(GuardEvent.Tick)
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
        val prev = lastForeground
        val next = app?.packageName
        lastForeground = next

        if (prev != null && prev != next) {
            val machine = machines[prev]
            if (machine != null) {
                translate(prev, machine.dispatch(GuardEvent.Backgrounded))
            } else if (prev == SampleGuard.PACKAGE) {
                // ticket 01 示例守护路径：离开示例应用时关闭演示浮层
                overlayManager.hide(prev)
            }
        }

        if (next == null) return
        val machine = machines[next]
        if (machine != null) {
            translate(next, machine.dispatch(GuardEvent.ForegroundEntered))
        } else if (next == SampleGuard.PACKAGE && rulesSnapshot?.assignments?.containsKey(next) != true) {
            // ticket 01 示例守护应用（bootstrap 演示，不走状态机）；
            // 用户把示例应用分配进任意分组后（ticket 02），交由分组规则接管，演示浮层不再出现
            overlayManager.showSample(catalog.appName(next))
        }
        // 其余应用（非守护）绝不弹浮层
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
                GuardEffect.GoHome -> exitAction.goToHome()
                GuardEffect.FlushState -> Unit // 记账落盘：ticket 06/10 落地
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

    // region 分组规则 → 状态机映射（ticket 02 接线）

    private fun onRulesChanged(new: GroupSnapshot) {
        val old = rulesSnapshot
        rulesSnapshot = new
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
     * 本票只接线黑名单语义（每日限额 -1）；白名单组完全豁免。
     * 机制全关且无限额的应用不建状态机，其余机制由 ticket 03–07 的状态机落地后自动生效。
     */
    private fun needsMachine(group: Group): Boolean =
        group.kind != GroupKind.WHITELIST && group.config.isForbiddenByQuota()

    private fun createMachine(pkg: String, config: GroupConfig) {
        if (machines.containsKey(pkg)) return
        val machine = DefaultGuardStateMachine(
            packageName = pkg,
            clock = AppClock { System.currentTimeMillis() },
            config = config,
            todayUsedMillis = 0L,
        )
        machines[pkg] = machine
        machineConfigs[pkg] = config
        machineJobs[pkg] = scope.launch {
            machine.snapshot.collect { snapshot -> syncOverlayFromSnapshot(pkg, snapshot) }
        }
        // 规则热更新时该应用可能已在前台：补发进入前台事件
        if (monitor.state.value?.packageName == pkg) {
            translate(pkg, machine.dispatch(GuardEvent.ForegroundEntered))
        }
    }

    private fun removeMachine(pkg: String) {
        machines.remove(pkg)
        machineConfigs.remove(pkg)
        machineJobs.remove(pkg)?.cancel()
        overlayManager.hide(pkg)
    }

    /** 浮层内容以状态机快照收敛（效果路径之外的兜底，同时驱动冷却倒计时等刷新）。 */
    private fun syncOverlayFromSnapshot(pkg: String, snapshot: GuardSnapshot) {
        overlayManager.syncFromMachine(pkg, catalog.appName(pkg), snapshot.overlay, snapshot.remainingMillis)
    }

    // endregion

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
