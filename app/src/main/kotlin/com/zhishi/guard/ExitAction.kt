package com.zhishi.guard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.zhishi.detect.ZhishiAccessibilityService

/**
 * 退出动作：把用户送回桌面。
 * 无障碍已连接 → GLOBAL_ACTION_HOME；否则尝试启动 CATEGORY_HOME 桌面；失败则提示手动返回。
 * 独立成类以便 ticket 09 兼容模式复用。
 */
class ExitAction(private val appContext: Context) {

    /** @return 是否成功触发回桌面动作。 */
    fun goToHome(): Boolean {
        if (ZhishiAccessibilityService.isConnected && ZhishiAccessibilityService.performGlobalHome()) {
            return true
        }
        return try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(home)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(appContext, "请手动返回桌面", Toast.LENGTH_LONG).show()
            false
        }
    }
}
