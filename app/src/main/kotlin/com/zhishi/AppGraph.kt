package com.zhishi

import android.content.Context
import com.zhishi.data.AppCatalog
import com.zhishi.data.GroupRepository
import com.zhishi.detect.ForegroundMonitor
import com.zhishi.guard.ExitAction
import com.zhishi.guard.GuardCoordinator
import com.zhishi.overlay.OverlayInteractions
import com.zhishi.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 手写单例图（无 Hilt）：进程内共享的对象图。 */
object AppGraph {

    @Volatile
    private var appContextRef: Context? = null

    /** 应用级协程作用域（生命周期同进程）。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appContext: Context
        get() = requireNotNull(appContextRef) { "AppGraph 未初始化：应在 Application.onCreate 调用 init()" }

    /** 幂等初始化；Application.onCreate 调用，Activity/Service 中的重复调用是安全兜底。 */
    fun init(context: Context) {
        if (appContextRef == null) appContextRef = context.applicationContext
    }

    val groupRepository: GroupRepository by lazy { GroupRepository(appContext) }
    val appCatalog: AppCatalog by lazy { AppCatalog(appContext) }
    val exitAction: ExitAction by lazy { ExitAction(appContext) }

    /**
     * 浮层交互转发枢纽：OverlayManager 构造早于协调器，先以空实现兜底；
     * 协调器构造完成时绑定自身（GuardCoordinator 实现了 OverlayInteractions）。
     */
    private val overlayInteractionsHub = object : OverlayInteractions {
        @Volatile
        private var delegate: OverlayInteractions = OverlayInteractions.NONE

        fun bind(target: OverlayInteractions) {
            delegate = target
        }

        override fun onDurationSelected(packageName: String, minutes: Int) =
            delegate.onDurationSelected(packageName, minutes)

        override fun onConfirmExit(packageName: String) = delegate.onConfirmExit(packageName)

        override fun onExtensionRequested(packageName: String) = delegate.onExtensionRequested(packageName)
    }

    val overlayManager: OverlayManager by lazy { OverlayManager(appContext, overlayInteractionsHub) }
    val foregroundMonitor: ForegroundMonitor by lazy { ForegroundMonitor(appContext) }

    val coordinator: GuardCoordinator by lazy {
        GuardCoordinator(
            appContext = appContext,
            monitor = foregroundMonitor,
            overlayManager = overlayManager,
            exitAction = exitAction,
            repository = groupRepository,
            catalog = appCatalog,
        ).also { overlayInteractionsHub.bind(it) }
    }
}
