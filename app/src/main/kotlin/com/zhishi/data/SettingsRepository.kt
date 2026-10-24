package com.zhishi.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhishi.core.model.DetectionMode
import com.zhishi.core.model.DisciplineLockConfig
import com.zhishi.core.model.TimeParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "zhishi_settings")

/** 全局设置快照：主控接线用（新建分组播种、检测模式选择、自律锁校验等）。 */
data class SettingsSnapshot(
    val defaultTimeParams: TimeParams,
    val newGroupDailyLimitMinutes: Int,
    val disciplineLock: DisciplineLockConfig,
    val detectionMode: DetectionMode,
)

/**
 * 全局设置仓库（DataStore Preferences 持久化，重启后保持）：
 * - 全局默认 TimeParams（新建分组播种用；已存在分组各自内嵌配置，互不影响）；
 * - 新建分组默认每日限额（-1 禁用 / 0 不限 / N 分钟）；
 * - 自律锁配置（默认关，时段语义见 [DisciplineLockConfig.isUnlockedAt]，支持跨零点）；
 * - 检测模式（默认 ACCESSIBILITY；仅存储与暴露，模式切换的服务接线由协调器处理）。
 */
class SettingsRepository(private val appContext: Context) {

    private val dataStore = appContext.settingsDataStore

    val defaultTimeParams: Flow<TimeParams> = dataStore.data.map(::decodeTimeParams)

    /** 新建分组的默认每日限额（分钟）：-1 禁用 / 0 不限 / N 限时。 */
    val newGroupDailyLimitMinutes: Flow<Int> =
        dataStore.data.map { it[NEW_GROUP_DAILY_LIMIT_KEY] ?: DEFAULT_NEW_GROUP_DAILY_LIMIT }

    val disciplineLock: Flow<DisciplineLockConfig> = dataStore.data.map(::decodeLock)

    val detectionMode: Flow<DetectionMode> = dataStore.data.map { prefs ->
        prefs[DETECTION_MODE_KEY]
            ?.let { name -> runCatching { DetectionMode.valueOf(name) }.getOrNull() }
            ?: DetectionMode.ACCESSIBILITY
    }

    /** 用户已关闭「后台保活引导」卡（HyperOS/MIUI 冻结防护提示）。 */
    val keepAliveGuideDismissed: Flow<Boolean> =
        dataStore.data.map { it[KEEP_ALIVE_DISMISSED_KEY] ?: false }

    /** 调试模式：以小悬浮窗显示全部状态机实时状态（排查拦截/计时问题用），默认关。 */
    val debugMode: Flow<Boolean> = dataStore.data.map { it[DEBUG_MODE_KEY] ?: false }

    /** 调试工具悬浮窗（重置计时/注入状态/查看日志），默认关。 */
    val debugToolsEnabled: Flow<Boolean> = dataStore.data.map { it[DEBUG_TOOLS_KEY] ?: false }

    /**
     * 常驻通知显示剩余时长（v1.2）：开启后常驻通知随会话/延时展示当前应用剩余时间；
     * 两种检测模式均可开启（无障碍模式下会额外启动一条常驻通知服务），默认关。
     */
    val notificationUsageVisible: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_USAGE_VISIBLE_KEY] ?: false }

    val settings: Flow<SettingsSnapshot> = combine(
        defaultTimeParams,
        newGroupDailyLimitMinutes,
        disciplineLock,
        detectionMode,
    ) { params, limit, lock, mode ->
        SettingsSnapshot(params, limit, lock, mode)
    }

    /** 整体替换默认参数；单字段编辑建议用 [updateDefaultTimeParams]。 */
    suspend fun setDefaultTimeParams(params: TimeParams) {
        dataStore.edit { it[TIME_PARAMS_KEY] = TimeParamsFormat.encode(params) }
    }

    /** 基于当前存储值转换后保存（读-改-写在同一次 edit 内，无竞态）。 */
    suspend fun updateDefaultTimeParams(transform: (TimeParams) -> TimeParams) {
        dataStore.edit { prefs ->
            prefs[TIME_PARAMS_KEY] = TimeParamsFormat.encode(transform(decodeTimeParams(prefs)))
        }
    }

    suspend fun setNewGroupDailyLimitMinutes(minutes: Int) {
        dataStore.edit { it[NEW_GROUP_DAILY_LIMIT_KEY] = minutes }
    }

    suspend fun setDisciplineLock(config: DisciplineLockConfig) {
        dataStore.edit { prefs ->
            prefs[LOCK_ENABLED_KEY] = config.enabled
            prefs[LOCK_START_KEY] = config.startMinuteOfDay
            prefs[LOCK_END_KEY] = config.endMinuteOfDay
        }
    }

    suspend fun setDisciplineLockEnabled(enabled: Boolean) {
        dataStore.edit { it[LOCK_ENABLED_KEY] = enabled }
    }

    /** 起止为当日分钟数；start > end 视为跨零点时段。 */
    suspend fun setDisciplineLockWindow(startMinuteOfDay: Int, endMinuteOfDay: Int) {
        dataStore.edit { prefs ->
            prefs[LOCK_START_KEY] = startMinuteOfDay
            prefs[LOCK_END_KEY] = endMinuteOfDay
        }
    }

    suspend fun setDetectionMode(mode: DetectionMode) {
        dataStore.edit { it[DETECTION_MODE_KEY] = mode.name }
    }

    suspend fun setKeepAliveGuideDismissed(dismissed: Boolean) {
        dataStore.edit { it[KEEP_ALIVE_DISMISSED_KEY] = dismissed }
    }

    suspend fun setDebugMode(enabled: Boolean) {
        dataStore.edit { it[DEBUG_MODE_KEY] = enabled }
    }

    suspend fun setDebugToolsEnabled(enabled: Boolean) {
        dataStore.edit { it[DEBUG_TOOLS_KEY] = enabled }
    }

    suspend fun setNotificationUsageVisible(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATION_USAGE_VISIBLE_KEY] = enabled }
    }

    /**
     * v1.2 一次性迁移：全局默认参数的「智能时长联动」「同组共享冷却」置开——
     * 生产默认值已改为开，但已落盘的旧值不会自行跟随；仅执行一次（标记位防重跑），
     * 此后用户手动改回的值不会被再次覆盖。由协调器启动时调用。
     */
    suspend fun migrateV12DefaultSwitchesIfNeeded() {
        dataStore.edit { prefs ->
            if (prefs[MIGRATED_V12_KEY] == true) return@edit
            prefs[TIME_PARAMS_KEY] = TimeParamsFormat.encode(
                decodeTimeParams(prefs).copy(
                    smartTimingEnabled = true,
                    sharedCooldownEnabled = true,
                ),
            )
            prefs[MIGRATED_V12_KEY] = true
        }
    }

    // region 解码 / 编码

    private fun decodeLock(prefs: Preferences): DisciplineLockConfig = DisciplineLockConfig(
        enabled = prefs[LOCK_ENABLED_KEY] ?: false,
        startMinuteOfDay = prefs[LOCK_START_KEY] ?: DEFAULT_LOCK_START,
        endMinuteOfDay = prefs[LOCK_END_KEY] ?: DEFAULT_LOCK_END,
    )

    private fun decodeTimeParams(prefs: Preferences): TimeParams {
        val encoded = prefs[TIME_PARAMS_KEY] ?: return TimeParams()
        return TimeParamsFormat.decode(encoded) ?: TimeParams()
    }

    // endregion

    private companion object {
        const val DEFAULT_NEW_GROUP_DAILY_LIMIT = 0
        const val DEFAULT_LOCK_START = 22 * 60
        const val DEFAULT_LOCK_END = 23 * 60

        val TIME_PARAMS_KEY = stringPreferencesKey("defaults.timeParams")
        val NEW_GROUP_DAILY_LIMIT_KEY = intPreferencesKey("defaults.newGroupDailyLimit")
        val LOCK_ENABLED_KEY = booleanPreferencesKey("disciplineLock.enabled")
        val LOCK_START_KEY = intPreferencesKey("disciplineLock.startMinuteOfDay")
        val LOCK_END_KEY = intPreferencesKey("disciplineLock.endMinuteOfDay")
        val DETECTION_MODE_KEY = stringPreferencesKey("detection.mode")
        val KEEP_ALIVE_DISMISSED_KEY = booleanPreferencesKey("guide.keepAlive.dismissed")
        val DEBUG_MODE_KEY = booleanPreferencesKey("debug.overlay.enabled")
        val DEBUG_TOOLS_KEY = booleanPreferencesKey("debug.tools.enabled")
        val NOTIFICATION_USAGE_VISIBLE_KEY = booleanPreferencesKey("notification.usageVisible")
        val MIGRATED_V12_KEY = booleanPreferencesKey("migrated.v12DefaultSwitches")
    }
}
