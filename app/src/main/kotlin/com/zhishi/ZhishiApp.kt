package com.zhishi

import android.app.Application
import kotlinx.coroutines.launch

class ZhishiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // 后台预加载应用目录：应用选择器与浮层应用名可直接命中缓存
        AppGraph.appScope.launch { AppGraph.appCatalog.ensureLoaded() }
    }
}
