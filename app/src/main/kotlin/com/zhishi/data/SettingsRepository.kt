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
        dataStore.edit { it[TIME_PARAMS_KEY] = encodeTimeParams(params) }
    }

    /** 基于当前存储值转换后保存（读-改-写在同一次 edit 内，无竞态）。 */
    suspend fun updateDefaultTimeParams(transform: (TimeParams) -> TimeParams) {
        dataStore.edit { prefs ->
            prefs[TIME_PARAMS_KEY] = encodeTimeParams(transform(decodeTimeParams(prefs)))
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

    // region 解码 / 编码

    private fun decodeLock(prefs: Preferences): DisciplineLockConfig = DisciplineLockConfig(
        enabled = prefs[LOCK_ENABLED_KEY] ?: false,
        startMinuteOfDay = prefs[LOCK_START_KEY] ?: DEFAULT_LOCK_START,
        endMinuteOfDay = prefs[LOCK_END_KEY] ?: DEFAULT_LOCK_END,
    )

    private fun decodeTimeParams(prefs: Preferences): TimeParams {
        val encoded = prefs[TIME_PARAMS_KEY] ?: return TimeParams()
        return parseTimeParams(encoded) ?: TimeParams()
    }

    /** 与 GroupRepository 的 TimeParams 编码格式一致（12 段分号分隔），便于主控统一处理。 */
    private fun parseTimeParams(encoded: String): TimeParams? = runCatching {
        val parts = encoded.split(';')
        val seed = TimeParams()
        TimeParams(
            quickOptionsMinutes = parts.getOrNull(0)
                ?.split(',')?.mapNotNull(String::toIntOrNull)?.filter { it > 0 }
                ?.takeIf { it.size == QUICK_OPTION_COUNT }
                ?: seed.quickOptionsMinutes,
            quickProtectMillis = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.quickProtectMillis,
            confirmProtectMillis = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.confirmProtectMillis,
            extensionProtectMillis = parts.getOrNull(3)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.extensionProtectMillis,
            extensionMinutes = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 }
                ?: seed.extensionMinutes,
            extensionPrewarnSeconds = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 600)
                ?: seed.extensionPrewarnSeconds,
            gracePeriodMillis = parts.getOrNull(6)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.gracePeriodMillis,
            abandonCooldownMillis = parts.getOrNull(7)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.abandonCooldownMillis,
            expiryCooldownMillis = parts.getOrNull(8)?.toLongOrNull()?.coerceAtLeast(0)
                ?: seed.expiryCooldownMillis,
            usageToastIntervalMillis = parts.getOrNull(9)?.toLongOrNull()?.takeIf { it > 0 }
                ?: seed.usageToastIntervalMillis,
            // 空串 = 用户删光刻度（合法状态，不回退到默认 5/2/1）
            reminderMarksMinutes = parts.getOrNull(10)
                ?.split(',')?.mapNotNull(String::toIntOrNull)?.filter { it > 0 }
                ?.distinct()?.sortedDescending()
                ?: seed.reminderMarksMinutes,
            customMaxMinutes = parts.getOrNull(11)?.toIntOrNull()?.takeIf { it > 0 }
                ?: seed.customMaxMinutes,
        )
    }.getOrNull()

    private fun encodeTimeParams(t: TimeParams): String = listOf(
        t.quickOptionsMinutes.joinToString(","),
        t.quickProtectMillis,
        t.confirmProtectMillis,
        t.extensionProtectMillis,
        t.extensionMinutes,
        t.extensionPrewarnSeconds,
        t.gracePeriodMillis,
        t.abandonCooldownMillis,
        t.expiryCooldownMillis,
        t.usageToastIntervalMillis,
        t.reminderMarksMinutes.joinToString(","),
        t.customMaxMinutes,
    ).joinToString(";")

    // endregion

    private companion object {
        const val QUICK_OPTION_COUNT = 6
        const val DEFAULT_NEW_GROUP_DAILY_LIMIT = 0
        const val DEFAULT_LOCK_START = 22 * 60
        const val DEFAULT_LOCK_END = 23 * 60

        val TIME_PARAMS_KEY = stringPreferencesKey("defaults.timeParams")
        val NEW_GROUP_DAILY_LIMIT_KEY = intPreferencesKey("defaults.newGroupDailyLimit")
        val LOCK_ENABLED_KEY = booleanPreferencesKey("disciplineLock.enabled")
        val LOCK_START_KEY = intPreferencesKey("disciplineLock.startMinuteOfDay")
        val LOCK_END_KEY = intPreferencesKey("disciplineLock.endMinuteOfDay")
        val DETECTION_MODE_KEY = stringPreferencesKey("detection.mode")
    }
}
