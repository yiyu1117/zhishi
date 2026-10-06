package com.zhishi.detect

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UsageStats 轮询前台检测 provider（ADR 0001 兼容模式，ticket 09）。
 *
 * 实现与无障碍 provider 相同的 [ForegroundDetector] 契约，经 [ForegroundMonitor.attach]
 * 接入（模式切换接线由主控在协调器完成）：
 * - 协程按约 1 秒间隔轮询 `UsageStatsManager.queryEvents(now-15s..now)`，
 *   取时间最新的前台事件（ACTIVITY_RESUMED / MOVE_TO_FOREGROUND，两者常量值同为 1），
 *   维护最新前台包名；
 * - 手势预览瞬态过滤（挂起-确认）：HyperOS/MIUI 的 UsageStats 记录不可靠——「上滑
 *   悬停后取消」有的应用不给 PAUSED/RESUMED、有的只给一半，照单全收会让前台判定
 *   卡在 launcher（拦截浮层被吃掉且不恢复）。候选先挂起等当前应用的 PAUSED 确认，
 *   超宽限期未证实视为瞬态丢弃，超过安全窗强制放行防强杀场景卡死；
 * - 结合息屏广播：SCREEN_OFF 立即发射 null（息屏视同退后台），SCREEN_ON 触发一次即时轮询；
 * - 仅当最新结果与上次发射不同才回调 listener（monitor 侧另有去重兜底）。
 *
 * 延迟 1~2 秒，拦截前目标应用可能短暂闪现 —— 兼容模式的既定代价（ADR 0001）。
 */
class PollingForegroundDetector(
    private val appContext: Context,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
) : ForegroundDetector {

    private val usageStatsManager: UsageStatsManager? by lazy {
        appContext.getSystemService(UsageStatsManager::class.java)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var listener: ForegroundListener? = null
    private var receiverRegistered = false

    /** 当前判定：无前台事件刷新 / 息屏时为 null。 */
    @Volatile
    private var currentPkg: String? = null

    /** 当前前台应用是否已观察到 PAUSED 记录。 */
    @Volatile
    private var currentPkgPaused: Boolean = false

    /** 挂起中的前台候选（未获暂停确认），null = 无挂起。 */
    @Volatile
    private var pendingPkg: String? = null

    /** 挂起开始的墙钟时间，用于宽限期判定。 */
    @Volatile
    private var pendingSinceMillis: Long = 0L

    /** 首个未证实候选的墙钟时间（0 = 无）：超安全窗后强制放行，防强杀场景卡死。 */
    @Volatile
    private var firstUnconfirmedMillis: Long = 0L

    /** 上次发射值；哨兵保证首次探测必发射。 */
    @Volatile
    private var lastEmitted: String? = NEVER_EMITTED

    /** 已处理事件的最大时间戳（跨轮询去重，避免重复回放窗口内旧事件）。 */
    @Volatile
    private var lastEventTs: Long = Long.MIN_VALUE

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    currentPkg = null
                    currentPkgPaused = false
                    pendingPkg = null
                    firstUnconfirmedMillis = 0L
                    emitIfNeeded(null) // 息屏视同退后台
                }
                Intent.ACTION_SCREEN_ON -> scope.launch { pollOnce() } // 即时探测，缩短解锁后首拦延迟
            }
        }
    }

    override fun start(listener: ForegroundListener) {
        this.listener = listener
        pollJob?.cancel()
        registerScreenReceiver()
        pollJob = scope.launch {
            pollOnce() // 先立即探测一次，缩短模式切换后的首个拦截延迟
            while (isActive) {
                delay(pollIntervalMillis)
                pollOnce()
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        listener = null
        unregisterScreenReceiver()
        currentPkg = null
        currentPkgPaused = false
        pendingPkg = null
        firstUnconfirmedMillis = 0L
        lastEmitted = NEVER_EMITTED
        lastEventTs = Long.MIN_VALUE
    }

    // region 轮询

    private fun pollOnce() {
        val usm = usageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - LOOKBACK_MILLIS, now)
        } catch (_: SecurityException) {
            return // 使用情况访问被运行时撤销：保持现状，由服务层检测并停服
        } catch (_: Exception) {
            return
        }
        // 挂起候选宽限期内未获暂停确认：判定为手势预览瞬态，丢弃
        if (pendingPkg != null && now - pendingSinceMillis >= BLIP_GRACE_MILLIS) {
            pendingPkg = null
        }
        var latestEmitted: String? = null
        var consumedTs = lastEventTs
        val sink = UsageEvents.Event()
        while (true) {
            val event = readNextEvent(events, sink) ?: break
            if (event.timeStamp <= lastEventTs) continue
            consumedTs = event.timeStamp
            val pkg = event.packageName
            when {
                isForegroundEventType(event.eventType) -> {
                    if (pkg != null) {
                        val accepted = acceptForegroundCandidate(pkg, now)
                        if (accepted != null) {
                            currentPkg = accepted
                            latestEmitted = accepted
                        }
                    }
                }
                isBackgroundEventType(event.eventType) -> {
                    // 当前前台应用观察到暂停记录：真实过渡。挂起中的候选立即证实
                    // （PAUSED 与 launcher RESUMED 的落账顺序不稳定，两种顺序都得对）
                    if (pkg == currentPkg) {
                        currentPkgPaused = true
                        firstUnconfirmedMillis = 0L
                        pendingPkg?.let {
                            currentPkg = it
                            currentPkgPaused = false
                            pendingPkg = null
                            latestEmitted = it
                        }
                    }
                }
            }
        }
        lastEventTs = consumedTs
        if (latestEmitted != null) {
            emitIfNeeded(latestEmitted)
        }
    }

    /**
     * 处理「pkg 成为新前台」的候选，返回实际采纳的新前台（null = 维持现状）。
     *
     * HyperOS/MIUI 的事件记录不可靠：手势预览（上滑小白条悬停后取消）只记 launcher 的
     * RESUMED —— 有的应用不给 PAUSED（取消后前台判定若被换到 launcher 会永久卡死，
     * 拦截浮层被吃掉且不恢复），有的应用记了 PAUSED 但取消时不记 RESUMED（同理卡在
     * launcher）；而真实回桌面的 PAUSED 落账又可能晚于 launcher 的 RESUMED。
     *
     * 故策略为「挂起-确认」：当前应用尚无暂停记录的候选先挂起 [BLIP_GRACE_MILLIS]，
     * 期间等到 PAUSED 即证实采纳（真实切换/回桌面），超时未证实则丢弃（手势瞬态，
     * 前台判定维持原应用）。应用被强杀/崩溃永远等不到 PAUSED，超过安全窗
     * [FORCE_ACCEPT_NO_PAUSE_MILLIS] 后强制放行防卡死。
     */
    private fun acceptForegroundCandidate(pkg: String, now: Long): String? = when {
        pkg == currentPkg -> {
            // 应用内切换/同应用恢复
            currentPkgPaused = false
            pendingPkg = null
            firstUnconfirmedMillis = 0L
            null
        }
        currentPkg == null || currentPkgPaused -> {
            pendingPkg = null
            firstUnconfirmedMillis = 0L
            pkg
        }
        else -> {
            if (firstUnconfirmedMillis == 0L) firstUnconfirmedMillis = now
            if (now - firstUnconfirmedMillis >= FORCE_ACCEPT_NO_PAUSE_MILLIS) {
                firstUnconfirmedMillis = 0L
                pkg
            } else {
                pendingPkg = pkg
                pendingSinceMillis = now
                null
            }
        }
    }

    private fun isBackgroundEventType(type: Int): Boolean =
        // MOVE_TO_BACKGROUND(2) 与 ACTIVITY_PAUSED 同值（后者 API 29 才引入）。
        @Suppress("DEPRECATION")
        (type == UsageEvents.Event.MOVE_TO_BACKGROUND)

    private fun emitIfNeeded(candidate: String?) {
        if (candidate == lastEmitted) return
        lastEmitted = candidate
        listener?.onForegroundChanged(candidate)
    }

    private fun isForegroundEventType(type: Int): Boolean =
        // MOVE_TO_FOREGROUND(1) 与 ACTIVITY_RESUMED 同值（后者 API 29 才引入）；
        // 用旧常量保证 minSdk 26 全版本一致，弃用警告在此收敛。
        @Suppress("DEPRECATION")
        (type == UsageEvents.Event.MOVE_TO_FOREGROUND)

    /**
     * 迭代读取下一条事件并填入 [sink]。
     * API 29+ 用两参重载（SDK 36 桩已移除旧的单参重载，无法直接编译）；
     * API 26-28 运行时仍保留单参实现，经反射调用（缓存 Method，避免每次轮询查找）。
     */
    private fun readNextEvent(events: UsageEvents, sink: UsageEvents.Event): UsageEvents.Event? {
        if (!events.hasNextEvent()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            events.getNextEvent(sink)
            sink
        } else {
            val method = legacyGetNextEvent ?: return null
            runCatching { method.invoke(events) as? UsageEvents.Event }.getOrNull()
        }
    }

    // endregion

    // region 息屏广播

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(appContext, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // 竞态下可能已注销，忽略
        }
        receiverRegistered = false
    }

    // endregion

    companion object {
        /** 轮询间隔（约 1 秒，ADR 0001 兼容模式延迟预算）。 */
        const val POLL_INTERVAL_MILLIS = 1_000L

        /** 事件回看窗口：容纳一次轮询间隔与系统事件落库延迟，又不至于复活过旧的 RESUMED。 */
        private const val LOOKBACK_MILLIS = 15_000L

        /** 挂起候选的证实宽限期（毫秒）：手势取消通常 2–3 秒内完成，真实过渡的 PAUSED 落账远快于此。 */
        private const val BLIP_GRACE_MILLIS = 2_500L

        /** 无暂停记录候选的强制接受安全窗（毫秒）：应用被强杀/崩溃时永远等不到 PAUSED，超窗放行防卡死。 */
        private const val FORCE_ACCEPT_NO_PAUSE_MILLIS = 10_000L

        private const val NEVER_EMITTED = "\u0000__never__" // 包名不含 NUL，作哨兵安全

        /** API 26-28 的 UsageEvents.getNextEvent() 运行时仍存在，但高版本 SDK 桩已移除，反射兜底。 */
        private val legacyGetNextEvent: Method? by lazy {
            runCatching { UsageEvents::class.java.getMethod("getNextEvent") }.getOrNull()
        }

        /**
         * 是否已授予"使用情况访问"权限（AppOpsManager OPSTR_GET_USAGE_STATS）。
         * 引导卡、前台服务与开机接收器共用。
         */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            @Suppress("DEPRECATION") // checkOpNoThrow：minSdk 26 全版本可用，API 29+ 起标记弃用
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /**
         * 一次性前台探测（拦截浮层的「刷新」自检用）：回看窗口内 lastTimeUsed 最新的应用。
         * 启发式判定，仅供逃生门自检；失败（无权限/无数据）返回 null，由调用方回退。
         */
        fun latestUsedPackage(context: Context, lookbackMillis: Long = 5 * 60_000L): String? {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            return try {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - lookbackMillis, now)
                    ?.maxByOrNull { it.lastTimeUsed }
                    ?.takeIf { it.lastTimeUsed > 0L }
                    ?.packageName
            } catch (_: Exception) {
                null
            }
        }
    }
}
