package com.zhishi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.zhishi.ui.ZhishiRootUi

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
        // 协调器随进程常驻（幂等）：无障碍/轮询服务各自也会拉起，这里是设置页切模式时的兜底入口
        AppGraph.coordinator.start()
    }
}
