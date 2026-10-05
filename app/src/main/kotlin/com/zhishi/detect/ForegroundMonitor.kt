package com.zhishi.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 当前前台应用快照。 */
data class ForegroundApp(
    val packageName: String,
    val sinceMillis: Long,
)

/**
 * 前台应用监视器：各检测实现（无障碍 / ticket 09 轮询）把原始事件喂到这里，
 * 由它统一做去重、本 APP 排除与息屏处理，并经 [state] 向协调器暴露当前前台应用。
 */
class ForegroundMonitor(private val appContext: Context) {

    private val _state = MutableStateFlow<ForegroundApp?>(null)

    /** 当前前台应用；null = 退到桌面之外（息屏/锁屏）或尚无数据。 */
    val state: StateFlow<ForegroundApp?> = _state.asStateFlow()

    private var detector: ForegroundDetector? = null
    private var screenOn: Boolean = true
    private var receiverRegistered = false

    /** 瞬态窗口：系统 UI（锁屏/音量条）与输入法弹出不代表前台切换，事件直接丢弃。 */
    @Volatile
    private var transientPackages: Set<String> = setOf(SYSTEM_UI_PACKAGE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // 输入法包名集合需要查一次 PackageManager，后台加载避免拖慢首帧
        scope.launch { transientPackages = loadTransientPackages() }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    _state.value = null // 息屏视同退后台
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true // 保持 null，等待解锁后的窗口事件
                }
            }
        }
    }

    /** 注册息屏广播并复位息屏状态；由协调器在启动检测时调用，可重复调用。 */
    fun start() {
        screenOn = appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (receiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(appContext, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    fun stop() {
        if (receiverRegistered) {
            appContext.unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        _state.value = null
    }

    /** 注入检测实现（无障碍 provider 由服务连接时注入；ticket 09 在此替换为轮询 provider）。 */
    fun attach(detector: ForegroundDetector) {
        detach()
        this.detector = detector
        detector.start(listener)
    }

    fun detach() {
        detector?.stop()
        detector = null
    }

    /** 当前已接入的检测实现；无障碍服务 onUnbind 据此判断自己是否仍是属主（避免误拆轮询 provider）。 */
    fun currentDetector(): ForegroundDetector? = detector

    /** 清空前台判定（如无障碍断开且无替代 provider）：经状态流触发协调器的退后台派发。 */
    fun reset() {
        _state.value = null
    }

    private val listener = ForegroundListener { onDetectorEvent(it) }

    private fun onDetectorEvent(packageName: String?) {
        if (!screenOn) return
        if (packageName == null) {
            // 检测实现显式报告退后台（轮询模式语义）
            _state.value = null
            return
        }
        if (packageName == appContext.packageName) return // 本 APP 自身（含浮层窗口）不改变前台判定
        if (packageName in transientPackages) return // 系统 UI / 输入法瞬态窗口
        if (_state.value?.packageName == packageName) return // 去重
        _state.value = ForegroundApp(packageName, System.currentTimeMillis())
    }

    private fun loadTransientPackages(): Set<String> {
        val set = mutableSetOf(SYSTEM_UI_PACKAGE)
        runCatching {
            val pm = appContext.packageManager
            // 输入法服务（android.view.InputMethod）：被拦住就无法输入
            pm.queryIntentServices(Intent(IME_SERVICE_ACTION), 0)
                .forEach { set.add(it.serviceInfo.packageName) }
        }
        return set
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val IME_SERVICE_ACTION = "android.view.InputMethod"
    }
}
