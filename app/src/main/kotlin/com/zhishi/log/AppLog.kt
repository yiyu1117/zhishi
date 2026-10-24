package com.zhishi.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * APP 端日志（环形缓冲 + Compose 可订阅）：全链路诊断（检测事件 → 监视器 → 协调器 →
 * 状态机 → 浮层）写入此处在应用内可查，同时照常输出到 logcat。
 * 调试工具悬浮窗内可查看/复制/清空；缓冲满后丢弃最旧条目，内存占用有界。
 *
 * 订阅模型（v1.2）：[version] 只发布单调递增的写入序号，UI 以其为键按需调 [snapshot]
 * 拉取——写日志路径不再每次拷贝整份列表（800 条 × 高频写入是设置保存卡顿的元凶之一）。
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

    /** 单调递增的写入序号：环形缓冲满后 size 不再变化，序号保证 UI 仍能感知新写入。 */
    private var sequence = 0L
    private val _version = MutableStateFlow(0L)

    /** 当前写入序号（每次写入/清空前递增），UI 订阅用；内容经 [snapshot] 拉取。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun d(tag: String, message: String) = log(Log.DEBUG, 'D', tag, message)
    fun i(tag: String, message: String) = log(Log.INFO, 'I', tag, message)
    fun w(tag: String, message: String) = log(Log.WARN, 'W', tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.ERROR, 'E', tag, if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message)

    private fun log(priority: Int, level: Char, tag: String, message: String) {
        synchronized(buffer) {
            buffer.addLast(Entry(System.currentTimeMillis(), level, tag, message))
            while (buffer.size > CAPACITY) buffer.removeFirst()
            sequence++
            _version.value = sequence
        }
        Log.println(priority, tag, message)
    }

    /** 当前缓冲的列表快照（UI 渲染 / 复制用）。 */
    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    /** 当前缓冲条数（≤ CAPACITY，按钮文案等偶尔展示用）。 */
    val size: Int get() = synchronized(buffer) { buffer.size }

    /** 清空缓冲。 */
    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            sequence++
            _version.value = sequence
        }
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
