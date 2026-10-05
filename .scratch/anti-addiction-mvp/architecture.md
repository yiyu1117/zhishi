# 知时 — 架构契约（给实现 Agent 的补充说明）

本文档是 spec.md 的补充，定义模块边界与已定契约。**契约类型已写好并编译通过，不要改名、不要移动包结构**；在契约之上实现行为。

## 模块划分

- `:core`（纯 Kotlin JVM 模块，`com.zhishi.core.*`）：守护状态机、领域模型、全部可单测逻辑。**禁止依赖任何 Android 类**。测试在 `core/src/test/kotlin`。
- `:app`（Android 模块，包名 `com.zhishi`）：Compose UI、无障碍服务、浮层、Toast、存储、检测 provider。领域逻辑一律下沉 `:core`。

## 技术基线（已锁定）

- AGP 9.4.1（内置 Kotlin，勿再应用 `org.jetbrains.kotlin.android`）、Kotlin 2.4.20、compileSdk/targetSdk 36、minSdk 26
- Compose BOM 2026.09.00、material3；无 Room（SQLiteOpenHelper 直用）、无 Hilt（手写单例图）、无 Navigation 库
- 存储约定：分组/配置/自律锁/检测模式 → DataStore Preferences；「应用×自然日」记账与会话状态恢复 → SQLite（`guard.db`）
- 不申请 INTERNET 权限

## 契约类型速览（见 core/src/main/kotlin/com/zhishi/core/）

- `model/CoreModels.kt`：`GroupKind`、`Mechanism`（六项）、`TimeParams`（默认参数表，全部可改）、`GroupConfig`（机制开关 + dailyLimitMinutes 三元值 + time）、`Group`、`remainingQuotaMillis()`、`isForbiddenByQuota()`
- `model/SettingsModels.kt`：`DisciplineLockConfig.isUnlockedAt(minuteOfDay)`（支持跨零点）、`DetectionMode`
- `machine/GuardTypes.kt`：`GuardState`（11 态）、`GuardEvent`、`GuardEffect`（ShowOverlay/HideOverlay/ShowToast/GoHome/FlushState）、`OverlaySpec`（5 形态）、`ToastEvent`（3 类）、`GuardSnapshot`、`GuardStateData`、抽象类 `GuardStateMachine(packageName, clock, config)`：`snapshot: StateFlow<GuardSnapshot>`、`dispatch(event): List<GuardEffect>`、`exportState()`
- `machine/DefaultGuardStateMachine.kt`：**待实现**（ticket 03–07 的核心工作）
- `time/AppClock.kt`：`fun interface AppClock { fun nowMillis(): Long }`

## 状态机语义（唯一测试缝合点，必须全单测）

1. 每应用一个实例；所有时间语义由注入的 `AppClock` 驱动，`Tick` 事件由协调器约每秒派发。
2. `RUNNING`：仅前台推进会话计时（Tick 扣减）。退后台/息屏 → `GRACE`。
3. `GRACE`：宽限倒计时推进；回来 → `RUNNING`（会话剩余不变）；耗尽 → 会话作废，进 `ABANDON_COOLDOWN`。
4. 到时（前台耗尽）→ `TIME_UP`（"时间已到"浮层，确定按钮 5s 保护，延时按钮若可用 10s 保护）。确定 → GoHome + 进 `EXPIRY_COOLDOWN`（8min 默认）。**用户停留 TIME_UP 期间冷却未开始**；冷却中用户重开应用（停留冷却浮层）冷却冻结。
5. 延时：每会话一次；`TIME_UP` 或冷却浮层上可用；点击 → `EXTENSION_RUNNING`（2min，前台计时，冷却冻结）；结束前 30s 发 `ExtensionPrewarn` Toast；结束 → 回 `TIME_UP`（延时已用，按钮消失）；确定 → 按剩余冷却继续。冷却结束的新会话延时配额恢复。
6. 实际到期 = min(所选时长剩余, 当日剩余额度)；额度先尽 → 立即中断进 `QUOTA_EXHAUSTED`（浮层"今日 N 分钟已用完，零点重置"，确定 5s 保护、送回桌面、无延时），当天禁用；`DayChanged` 重置。
7. `BLACKLISTED`：进前台即 `Forbidden` 浮层，无会话/延时/冷却。
8. 无时间选择机制但有每日限额的分组：`DIRECT_RUNNING` 直接前台记账并执行限额，无会话/浮层（额度用尽仍中断）。
9. 提醒：`USAGE_TOAST` 按 `usageToastIntervalMillis` 会话前台累计报告；`TIME_REMINDER` 距实际到期点按 `reminderMarksMinutes`（5/2/1）提醒；**同刻撞点只弹一条，剩余提醒优先、周期提示顺延**；仅前台进行期间弹（GRACE/后台不弹）。
10. 机制关闭的语义：`TIME_SELECTION` 关 → 无时间选择浮层（配额仍执行）；`EXTENSION` 关 → 无延时按钮；`EXPIRY_COOLDOWN`/`ABANDON_COOLDOWN` 关 → 对应冷却跳过；两个 Toast 机制关 → 对应提醒不弹。
11. 快选/确定/延时的按钮保护计时在**浮层层**（:app）实现，状态机不管理倒计时锁。
12. `DayChanged`：清当日记账状态机侧缓存（协调器同时切记账日）、解除 `QUOTA_EXHAUSTED`。
13. 换组清状态由**协调器**处理（销毁重建状态机），状态机不处理。

## 恢复语义（ticket 10）

- `RUNNING` 重启 → 恢复为 `GRACE`（等同退后台，宽限期从头计）。
- 冷却中重启 → 按离开时刻起算：`GuardStateData.cooldownDeadlineMillis` 为绝对截止，停机时间计入。
- 延时已用标记、当日记账完整恢复。

## 协调器（:app，GuardCoordinator）职责

- 维护 `Map<package, GuardStateMachine>`；前台事件来自 `ForegroundMonitor`（统一契约，两个实现：无障碍 provider / UsageStats 轮询 provider，见 ADR 0001）。
- 全局 1s ticker：对所有活跃状态机派发 `Tick`；检测自然日切换派发 `DayChanged`。
- 收集各状态机 `snapshot` → 驱动浮层显隐与内容；效果翻译：ShowOverlay→OverlayManager、ShowToast→ToastSender、GoHome→退出动作、FlushState→记账落盘（节流 ~10s + 状态切换时立即）。
- 前台归属判定排除本 APP 与浮层自身窗口。

## 固定排除（永不出现在应用选择器，也不可被拦截）

本 APP（`com.zhishi`）、系统桌面（CATEGORY_HOME）、系统 UI（`com.android.systemui`）、输入法（IME 服务）。

## 分支/提交

- 主分支 `main`。**子 Agent 不做 git 操作**，由主控验证后提交。
