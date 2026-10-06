package com.google.android.accessibility.selecttospeak

import com.zhishi.detect.ZhishiAccessibilityService

/**
 * HyperOS 兼容别名服务（功能与 [ZhishiAccessibilityService] 完全一致）。
 *
 * 背景：HyperOS 3 的「无障碍 → 已下载的应用」列表按服务类名特征过滤，
 * 自建应用的服务即使声明标准也不会出现在列表中（adb 直写启用列表的通道已被封死）。
 * 本类采用与主流工具（GKD 等）相同的类名命名模式以通过该过滤；
 * 只需在无障碍设置中启用本服务与原服务中的一个，切勿同时启用（事件会重复）。
 */
class SelectToSpeakService : ZhishiAccessibilityService()
