package com.zhishi.overlay

/**
 * 拦截浮层交互回调契约：浮层按钮点击只经此回调给协调器，由协调器派发状态机事件
 * （浮层自身绝不直接执行退出/开始会话动作；示例浮层的"回桌面"是唯一例外）。
 *
 * 按钮保护计时（快选/确定/延时）在浮层层施加完毕后才允许点击，协调器可直接派发事件。
 */
interface OverlayInteractions {
    /** 时间选择浮层：用户选定本次使用时长（快选按钮或自定义假键盘确认），单位分钟。 */
    fun onDurationSelected(packageName: String, minutes: Int)

    /** "时间已到"/"额度用尽"浮层：用户点击确定（协调器应送回桌面）。 */
    fun onConfirmExit(packageName: String)

    /** "时间已到"/冷却浮层：用户点击延时按钮（仅浮层显示延时按钮时可达）。 */
    fun onExtensionRequested(packageName: String)

    companion object {
        /** 空实现：[OverlayManager] 构造函数默认值，未接线时按钮点击静默忽略，保证不接线也编译通过。 */
        val NONE: OverlayInteractions = object : OverlayInteractions {
            override fun onDurationSelected(packageName: String, minutes: Int) = Unit
            override fun onConfirmExit(packageName: String) = Unit
            override fun onExtensionRequested(packageName: String) = Unit
        }
    }
}
