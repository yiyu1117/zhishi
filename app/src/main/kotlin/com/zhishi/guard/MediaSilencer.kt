package com.zhishi.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * 拦截浮层期间的媒体终止器（模拟耳机暂停键方案，v1.1 重写）：
 *
 * 拦截浮层是 FLAG_NOT_FOCUSABLE 的悬浮窗，被拦应用仍在前台照常播放——开屏即播的
 * 短视频应用画面被浮层盖住、视频照放。v1.0 的音频焦点方案实测对目标应用无效，弃用。
 *
 * 现方案以 **模拟耳机媒体键**（`KEYCODE_MEDIA_PAUSE` 经 [AudioManager.dispatchMediaKeyEvent]，
 * 无需任何权限）为唯一执行手段：注册了媒体会话的主流播放器都会响应暂停。
 * 音频焦点降级为 **探测器**：浮层可见期间持有焦点，监听到焦点被应用抢走
 * （= 有东西开始播放了，如对浮层按返回穿透刷出的新视频）即补发暂停键，500ms 节流。
 *
 * 触发覆盖所有拦截时刻：每次浮层变为可见（开屏选时 / 到期冷却 / 延时结束回落 /
 * 额度用尽 / 黑名单禁止）都派发一次暂停，由协调器经 onVisibilityChanged 接线。
 *
 * 已知边界（尽力而为）：暂停键作用于"当前媒体会话"，浮层期间用户在其它应用
 * 刻意播放的音频也会被暂停；个别不注册媒体会话的应用可能不响应。
 *
 * 线程模型：焦点回调派发到主线程（[focusRequest] 显式指定 handler），
 * acquire/release 由协调器在主线程调用。
 */
class MediaSilencer(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否处于"浮层可见、监控中"状态。 */
    private var active = false

    /** 上次派发暂停键的单调时刻：限制补发节奏，防拉锯演成热循环。 */
    private var lastPauseElapsed = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!active || change == AudioManager.AUDIOFOCUS_GAIN) return@OnAudioFocusChangeListener
        // 焦点被抢 = 有东西开始播放：补发暂停键并重新持有焦点（探测器再武装）
        val sinceLast = SystemClock.elapsedRealtime() - lastPauseElapsed
        if (sinceLast >= REDISPATCH_MIN_INTERVAL_MILLIS) {
            dispatchPause()
            requestFocus()
        } else {
            mainHandler.postDelayed({
                if (!active) return@postDelayed
                dispatchPause()
                requestFocus()
            }, REDISPATCH_MIN_INTERVAL_MILLIS - sinceLast)
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener(focusListener, mainHandler)
        .build()

    /** 浮层出现：派发暂停键终止当前播放，并持有焦点作为"再次播放"探测器。 */
    fun acquire() {
        if (active) return
        active = true
        dispatchPause()
        requestFocus()
    }

    /** 浮层关闭：放弃焦点，停止监控（已暂停的播放不主动恢复，由应用自身策略决定）。 */
    fun release() {
        if (!active) return
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "abandon audio focus failed", e)
        }
    }

    /** 模拟耳机暂停键（按下 + 抬起）。 */
    private fun dispatchPause() {
        val manager = audioManager ?: return
        lastPauseElapsed = SystemClock.elapsedRealtime()
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
        runCatching {
            manager.dispatchMediaKeyEvent(down)
            manager.dispatchMediaKeyEvent(up)
        }.onFailure { Log.w(TAG, "dispatch media pause failed", it) }
    }

    /** 请求焦点（探测器武装；应用随后请求焦点时触发补发）。 */
    private fun requestFocus() {
        val manager = audioManager ?: return
        val result = manager.requestAudioFocus(focusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "media monitor focus not granted: $result")
        }
    }

    private companion object {
        const val TAG = "ZhishiGuard"

        /** 补发暂停键的最小间隔（毫秒）。 */
        const val REDISPATCH_MIN_INTERVAL_MILLIS = 500L
    }
}
