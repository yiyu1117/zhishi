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
}
