package com.zhishi.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 拦截浮层期间的媒体静音器（音频焦点方案）：
 *
 * 拦截浮层是 FLAG_NOT_FOCUSABLE 的悬浮窗，被拦应用仍在前台照常出声——开屏即播的
 * 短视频应用（如抖音）画面被浮层盖住、声音照放；用户对着浮层按返回时按键还会穿透
 * 到被拦应用，刷出新视频继续出声。本类在浮层可见期间请求并持续持有音频焦点
 * （AUDIOFOCUS_GAIN），配合焦点处理的播放器（主流媒体应用均遵守）会暂停发声；
 * 应用刷出新视频重新请求焦点时，监听到焦点丢失立即夺回，声音窗口被压到最短。
 *
 * 焦点是协作式 API，不遵守焦点协议的应用仍可能出声；进一步的全局媒体流静音会
 * 误伤音量键与其它应用，不作为默认手段。
 *
 * 线程模型：焦点回调派发到主线程（[focusRequest] 显式指定 handler），
 * acquire/release 由协调器在主线程调用。
 */
class MediaSilencer(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否处于"浮层可见、静音持有中"状态。 */
    private var active = false

    /** 上次请求焦点的单调时刻：限制夺回节奏，防拉锯演变成热循环。 */
    private var lastRequestElapsed = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!active || change == AudioManager.AUDIOFOCUS_GAIN) return@OnAudioFocusChangeListener
        // 被拦应用刷出新视频重新拿焦点：尽快夺回；距上次请求过近则稍后补一发
        val sinceLast = SystemClock.elapsedRealtime() - lastRequestElapsed
        if (sinceLast >= REREQUEST_MIN_INTERVAL_MILLIS) {
            requestNow()
        } else {
            mainHandler.postDelayed(::requestNow, REREQUEST_MIN_INTERVAL_MILLIS - sinceLast)
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

    /** 浮层出现：请求并持有音频焦点，被拦应用的媒体播放暂停。 */
    fun acquire() {
        if (active) return
        active = true
        requestNow()
    }

    /** 浮层关闭：放弃焦点，恢复正常音频行为（应用恢复播放由其自身策略决定）。 */
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

    private fun requestNow() {
        if (!active) return
        lastRequestElapsed = SystemClock.elapsedRealtime()
        val manager = audioManager ?: return
        val result = manager.requestAudioFocus(focusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "silence audio focus not granted: $result")
        }
    }

    private companion object {
        const val TAG = "ZhishiGuard"

        /** 夺回焦点的最小间隔（毫秒）。 */
        const val REREQUEST_MIN_INTERVAL_MILLIS = 500L
    }
}
