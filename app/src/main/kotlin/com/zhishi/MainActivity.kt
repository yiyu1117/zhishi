package com.zhishi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.zhishi.core.model.DetectionMode
import com.zhishi.detect.PollingForegroundDetector
import com.zhishi.detect.ZhishiAccessibilityService
import com.zhishi.ui.ZhishiRootUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this) // 幂等（Application 已初始化）
        setContent {
            MaterialTheme {
                ZhishiRootUi()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 与根 UI 的就绪判定一致：无障碍已开，或兼容模式 + 使用情况访问已授予，才启动协调器（幂等）；
        // 条件不满足时不无条件启动（否则未授权场景会被 ON_START 绕过 UI 的 stop 门禁）。
        AppGraph.appScope.launch {
            val mode = AppGraph.settingsRepository.detectionMode.first()
            val ready = ZhishiAccessibilityService.isServiceEnabled(this@MainActivity) ||
                (mode == DetectionMode.POLLING && PollingForegroundDetector.hasUsageAccess(this@MainActivity))
            if (ready) AppGraph.coordinator.start()
        }
    }
}
