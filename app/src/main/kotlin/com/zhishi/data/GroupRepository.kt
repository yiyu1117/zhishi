package com.zhishi.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.groupDataStore by preferencesDataStore(name = "zhishi_groups")

/** 内置分组 id：default / blacklist / whitelist，三者不可删除。 */
object GroupIds {
    const val DEFAULT = "default"
    const val BLACKLIST = "blacklist"
    const val WHITELIST = "whitelist"
    val BUILT_IN = setOf(DEFAULT, BLACKLIST, WHITELIST)
}

/** 分组规则快照：协调器据此重建/更新状态机映射。 */
data class GroupSnapshot(
    val groups: List<Group>,
    val assignments: Map<String, String>,
)

/** 配置级更新（不换组）：协调器据此调用状态机 onConfigUpdated 热更新。 */
data class GroupConfigUpdate(
    val groupId: String,
    val config: GroupConfig,
)

/**
 * 分组仓库（DataStore Preferences 持久化）：
 * - 内置组 default（机制全关、限额 0）/ blacklist（限额 -1 平禁）/ whitelist（豁免），不可删除；
 * - 自定义组自由创建删除，删除时成员回归默认组；
 * - app→组分配采用移动语义：每应用一个分配键，未分配即默认组兜底（不物理存储）。
 */
class GroupRepository(private val appContext: Context) {

    private val dataStore = appContext.groupDataStore

    val groups: Flow<List<Group>> = dataStore.data.map(::decodeGroups)

    /** package → groupId；只存非默认分配，未分配 = 默认组。 */
    val assignments: Flow<Map<String, String>> = dataStore.data.map(::decodeAssignments)

    val groupSnapshot: Flow<GroupSnapshot> =
        combine(groups, assignments, ::GroupSnapshot)

    private val _configUpdates = MutableSharedFlow<GroupConfigUpdate>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 配置级更新回调：协调器热更新状态机（不换组、不清状态）。 */
    val configUpdates: SharedFlow<GroupConfigUpdate> = _configUpdates.asSharedFlow()

    /** 单次查询某应用所属分组（含默认组兜底）。 */
    suspend fun groupOf(packageName: String): Group {
        val prefs = dataStore.data.first()
        val gid = prefs[assignKey(packageName)] ?: GroupIds.DEFAULT
        val all = decodeGroups(prefs)
        return all.firstOrNull { it.id == gid } ?: all.first()
    }

    /**
     * 新组由设置中的全局默认参数播种（时间参数 + 新组默认每日限额）；
     * 七项机制默认全开（分组配置新建即管控，避免「加了应用却不拦截」的空配置组），
     * 唯延迟选时与开屏时间选择互斥——播种时关掉延迟选时，保持开屏即选的默认体验。
     */
    suspend fun createGroup(
        name: String,
        seedTime: TimeParams = TimeParams(),
        seedDailyLimitMinutes: Int = 0,
    ): Group {
        val id = "custom-" + UUID.randomUUID().toString().substring(0, 8)
        val seedConfig = GroupConfig(
            mechanisms = Mechanism.entries.filter { it != Mechanism.DELAYED_SELECTION }.toSet(),
            dailyLimitMinutes = seedDailyLimitMinutes,
            time = seedTime,
        )
        dataStore.edit { prefs ->
            val order = (prefs[CUSTOM_ORDER_KEY]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()) + id
            prefs[CUSTOM_ORDER_KEY] = order.joinToString(",")
            prefs[nameKey(id)] = name
            prefs[timeKey(id)] = TimeParamsFormat.encode(seedConfig.time)
            prefs[limitKey(id)] = seedConfig.dailyLimitMinutes
            prefs[mechKey(id)] = seedConfig.mechanisms.joinToString(",") { it.ordinal.toString() }
        }
        return Group(id, name, GroupKind.CUSTOM, seedConfig)
    }

    /** 新建自定义分组；内置组拒绝。成员回归默认组（移除分配键即回到兜底）。 */
    suspend fun deleteGroup(groupId: String) {
        if (groupId in GroupIds.BUILT_IN) return
        dataStore.edit { prefs ->
            val order = prefs[CUSTOM_ORDER_KEY]
                ?.split(',')?.filter { it.isNotBlank() && it != groupId }
                ?: emptyList()
            prefs[CUSTOM_ORDER_KEY] = order.joinToString(",")
            prefs.remove(nameKey(groupId))
            prefs.remove(mechKey(groupId))
            prefs.remove(limitKey(groupId))
            prefs.remove(timeKey(groupId))
            prefs.asMap().keys
                .filterIsInstance<Preferences.Key<String>>()
                .filter { it.name.startsWith(ASSIGN_PREFIX) && prefs[it] == groupId }
                .forEach { prefs.remove(it) }
        }
    }

    /** 重命名自定义分组（仅自定义组可改名；内置组名称为固定展示文案）。 */
    suspend fun renameGroup(groupId: String, newName: String) {
        if (groupId in GroupIds.BUILT_IN) return
        val name = newName.trim()
        if (name.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[nameKey(groupId)] = name
        }
    }

    /** 移动语义：每应用一个分配键，天然"从原组移出"；分配到默认组即移除键。 */
    suspend fun assignApp(packageName: String, groupId: String) {
        dataStore.edit { prefs ->
            val key = assignKey(packageName)
            if (groupId == GroupIds.DEFAULT) prefs.remove(key) else prefs[key] = groupId
        }
    }

    suspend fun updateConfig(groupId: String, config: GroupConfig) {
        // 黑名单语义即每日限额 -1，强制保持
        val effective = if (groupId == GroupIds.BLACKLIST) config.copy(dailyLimitMinutes = -1) else config
        dataStore.edit { prefs ->
            prefs[mechKey(groupId)] = effective.mechanisms.joinToString(",") { it.ordinal.toString() }
            prefs[limitKey(groupId)] = effective.dailyLimitMinutes
            prefs[timeKey(groupId)] = TimeParamsFormat.encode(effective.time)
        }
        _configUpdates.tryEmit(GroupConfigUpdate(groupId, effective))
    }

    // region 解码 / 编码

    private fun decodeGroups(prefs: Preferences): List<Group> = buildList {
        add(
            Group(
                GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT,
                decodeConfig(prefs, GroupIds.DEFAULT, GroupConfig()),
            ),
        )
        add(
            Group(
                GroupIds.BLACKLIST, "黑名单组", GroupKind.BLACKLIST,
                decodeConfig(prefs, GroupIds.BLACKLIST, GroupConfig(dailyLimitMinutes = -1)),
            ),
        )
        add(
            Group(
                GroupIds.WHITELIST, "白名单组", GroupKind.WHITELIST,
                decodeConfig(prefs, GroupIds.WHITELIST, GroupConfig()),
            ),
        )
        val order = prefs[CUSTOM_ORDER_KEY]?.split(',') ?: emptyList()
        for (id in order) {
            if (id.isBlank()) continue
            val name = prefs[nameKey(id)] ?: continue
            add(Group(id, name, GroupKind.CUSTOM, decodeConfig(prefs, id, GroupConfig())))
        }
    }

    private fun decodeAssignments(prefs: Preferences): Map<String, String> = prefs.asMap().keys
        .filterIsInstance<Preferences.Key<String>>()
        .filter { it.name.startsWith(ASSIGN_PREFIX) }
        .mapNotNull { key -> prefs[key]?.let { key.name.removePrefix(ASSIGN_PREFIX) to it } }
        .toMap()

    private fun decodeConfig(prefs: Preferences, id: String, seed: GroupConfig): GroupConfig {
        val mechanisms = prefs[mechKey(id)]
            ?.splitToSequence(',')
            ?.mapNotNull(String::toIntOrNull)
            ?.mapNotNull { ordinal -> Mechanism.entries.getOrNull(ordinal) }
            ?.toSet()
            ?: seed.mechanisms
        val limit = prefs[limitKey(id)] ?: seed.dailyLimitMinutes
        val time = prefs[timeKey(id)]?.let(TimeParamsFormat::decode) ?: seed.time
        return GroupConfig(mechanisms, limit, time)
    }

    // endregion

    private companion object {
        const val ASSIGN_PREFIX = "assign."
        const val GROUP_PREFIX = "group."

        fun nameKey(id: String) = stringPreferencesKey("$GROUP_PREFIX$id.name")
        fun mechKey(id: String) = stringPreferencesKey("$GROUP_PREFIX$id.mechanisms")
        fun limitKey(id: String) = intPreferencesKey("$GROUP_PREFIX$id.dailyLimit")
        fun timeKey(id: String) = stringPreferencesKey("$GROUP_PREFIX$id.time")
        fun assignKey(pkg: String) = stringPreferencesKey(ASSIGN_PREFIX + pkg)
    }
}

private val CUSTOM_ORDER_KEY = stringPreferencesKey("groups.custom.order")
