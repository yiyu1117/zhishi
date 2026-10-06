package com.zhishi.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * APP 端日志（环形缓冲 + Compose 可订阅）：全链路诊断（检测事件 → 监视器 → 协调器 →
 * 状态机 → 浮层）写入此处在应用内可查，同时照常输出到 logcat。
 * 调试工具悬浮窗内可查看/复制/清空；缓冲满后丢弃最旧条目，内存占用有界。
 */
object AppLog {

    /** 单条日志。 */
    data class Entry(
        val timeMillis: Long,
        val level: Char,
        val tag: String,
        val message: String,
    )

    private const val CAPACITY = 800

    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** 当前缓冲快照（调试 UI 订阅用）。 */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = log(Log.DEBUG, 'D', tag, message)
    fun i(tag: String, message: String) = log(Log.INFO, 'I', tag, message)
    fun w(tag: String, message: String) = log(Log.WARN, 'W', tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.ERROR, 'E', tag, if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message)

    private fun log(priority: Int, level: Char, tag: String, message: String) {
        synchronized(buffer) {
            buffer.addLast(Entry(System.currentTimeMillis(), level, tag, message))
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
        Log.println(priority, tag, message)
        _entries.value = synchronized(buffer) { buffer.toList() }
    }

    /** 清空缓冲。 */
    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _entries.value = emptyList()
    }

    /** 全部日志的纯文本（复制到剪贴板用）。 */
    fun asText(): String = synchronized(buffer) {
        buffer.joinToString("\n") { entry ->
            "${FORMATTER.get().format(entry.timeMillis)} ${entry.level}/${entry.tag}: ${entry.message}"
        }
    }

    private val FORMATTER = object : ThreadLocal<java.text.SimpleDateFormat>() {
        override fun initialValue() = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    }
}
