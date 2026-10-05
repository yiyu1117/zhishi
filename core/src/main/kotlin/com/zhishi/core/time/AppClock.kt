package com.zhishi.core.time

/** 时钟源。守护状态机唯一的时间来源，测试注入假时钟。 */
fun interface AppClock {
    fun nowMillis(): Long
}
