# 前台应用检测：无障碍服务为主，UsageStats 轮询为兼容模式

防沉迷拦截要求在目标 APP 内容出现的同时弹出选择浮层，无障碍服务（AccessibilityService 监听 `TYPE_WINDOW_STATE_CHANGED`）能毫秒级感知前台切换，是唯一满足实时性要求的方案；代价是用户须手动开启无障碍权限。部分设备/用户场景下无障碍服务可能被 ROM 限制或被拒绝授权，故同时实现基于 UsageStatsManager 轮询的兼容模式（约 1~2 秒延迟，拦截前目标 APP 会短暂闪现），在设置中可切换。

## Considered Options

- **纯无障碍服务**：实时性最好，但权限被拒即完全不可用。
- **纯 UsageStats 轮询**：权限授予门槛低，但拦截有延迟、部分国产 ROM 限制后台轮询，可靠性不足。
- **两者结合（选定）**：无障碍为默认模式，UsageStats 为兼容模式，开发量约翻倍。

## Consequences

- 检测层需要抽象出统一接口，两种模式实现同一契约，设置页可切换。
- 若未来上架 Google Play，无障碍模式将违反其政策，只能走 UsageStats 模式（本项目定位为开源侧载分发，见 README）。
