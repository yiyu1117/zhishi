package com.zhishi

import android.app.Application
import com.zhishi.detect.DetectionModeSource
import com.zhishi.detect.DetectionModeSourceProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ZhishiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // 开机广播无法使用挂起函数：绑定阻塞式模式读取源（fail-safe 默认无障碍模式）
        DetectionModeSourceProvider.bind(DetectionModeSource {
            runBlocking { AppGraph.settingsRepository.detectionMode.first() }
        })
        // 后台预加载应用目录：应用选择器与浮层应用名可直接命中缓存
        AppGraph.appScope.launch { AppGraph.appCatalog.ensureLoaded() }
    }
}
