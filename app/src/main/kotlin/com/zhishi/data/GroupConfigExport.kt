package com.zhishi.data

import com.zhishi.core.model.DetectionMode
import com.zhishi.core.model.DisciplineLockConfig
import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams

/**
 * 分组配置导出（v1.2.1）：纯 Kotlin 编解码，无任何 Android 依赖，
 * 供设置页（SAF 保存）与 JVM 单测直接调用；导入侧（按 id 合并覆盖）复用同一 schema。
 */

/** 导出档位：仅分组配置 / 含全局设置。 */
enum class ExportScope(val jsonName: String) {
    /** 仅分组配置：内置三组配置 + 全部自定义组（名称/配置/顺序）+ app→组分配。 */
    GROUPS("groups"),

    /** 含全局设置：[GROUPS] 全部内容 + 检测模式、默认参数、新建组默认限额、自律锁、常驻通知开关。 */
    FULL("full"),
}

/**
 * full 档附带的全局设置。只包含允许迁移的五项字段——
 * 调试标志（debugMode/debugToolsEnabled）与保活引导卡关闭记忆在类型上就不存在，无法被导出。
 */
data class ExportGlobalSettings(
    val detectionMode: DetectionMode,
    val defaultTimeParams: TimeParams,
    val newGroupDailyLimitMinutes: Int,
    val disciplineLock: DisciplineLockConfig,
    val notificationUsageVisible: Boolean,
)

/** 导出文件中的单个分组（id/名称/种类 + 分组内嵌配置）。 */
data class ExportGroup(
    val id: String,
    val name: String,
    val kind: GroupKind,
    val config: GroupConfig,
)

/** 导出文件顶层结构（即对外 JSON schema 的内存形态）。 */
data class ExportPayload(
    val schemaVersion: Int,
    val scope: ExportScope,
    val exportedAtMillis: Long,
    /** 数组顺序即自定义组顺序；内置三组固定在最前。 */
    val groups: List<ExportGroup>,
    /** package → groupId；默认组分配不落盘（缺省即默认组，沿用移动语义）。 */
    val assignments: Map<String, String>,
    /** 仅 full 档非空。 */
    val global: ExportGlobalSettings?,
)

object GroupConfigExport {

    /** 导出文件 schema 版本；导入侧校验此值，不匹配整体拒绝。 */
    const val SCHEMA_VERSION = 1

    /** 文件标识字段：非本应用导出的文件可据此快速拒绝。 */
    const val APP_MARKER = "zhishi"

    /**
     * 由仓库快照构建导出载荷：内置三组按固定顺序排最前，自定义组保持出现顺序（即存储顺序）。
     * [global] 仅 [ExportScope.FULL] 允许携带，两档一致性由 require 保证。
     */
    fun buildPayload(
        scope: ExportScope,
        groups: List<Group>,
        assignments: Map<String, String>,
        global: ExportGlobalSettings?,
        exportedAtMillis: Long,
    ): ExportPayload {
        require((scope == ExportScope.FULL) == (global != null)) {
            "global 设置仅 FULL 档携带（groups 档必须传 null）"
        }
        // 内置三组按 canonical 顺序排最前（与 GroupRepository 的存储序一致），自定义组保持出现顺序
        val builtins = listOf(GroupIds.DEFAULT, GroupIds.BLACKLIST, GroupIds.WHITELIST)
            .mapNotNull { id -> groups.firstOrNull { it.id == id }?.toExportGroup() }
        val customs = groups.filter { it.id !in GroupIds.BUILT_IN }.map { it.toExportGroup() }
        return ExportPayload(
            schemaVersion = SCHEMA_VERSION,
            scope = scope,
            exportedAtMillis = exportedAtMillis,
            groups = builtins + customs,
            assignments = assignments,
            global = global,
        )
    }

    /** 序列化为紧凑 JSON（键序固定：app, schema, scope, exportedAt, groups, assignments, global）。 */
    fun toJson(payload: ExportPayload): String = buildString {
        append("{\"app\":")
        appendJsonString(APP_MARKER)
        append(",\"schema\":").append(payload.schemaVersion)
        append(",\"scope\":")
        appendJsonString(payload.scope.jsonName)
        append(",\"exportedAt\":").append(payload.exportedAtMillis)
        append(",\"groups\":[")
        payload.groups.forEachIndexed { index, group ->
            if (index > 0) append(',')
            appendGroup(group)
        }
        append("],\"assignments\":{")
        payload.assignments.entries.withIndex().forEach { (index, entry) ->
            if (index > 0) append(',')
            appendJsonString(entry.key)
            append(':')
            appendJsonString(entry.value)
        }
        append('}')
        payload.global?.let { global ->
            append(",\"global\":{\"detectionMode\":")
            appendJsonString(global.detectionMode.name)
            append(",\"defaultTimeParams\":")
            appendJsonString(TimeParamsFormat.encode(global.defaultTimeParams))
            append(",\"newGroupDailyLimitMinutes\":").append(global.newGroupDailyLimitMinutes)
            append(",\"disciplineLock\":{\"enabled\":").append(global.disciplineLock.enabled)
            append(",\"startMinuteOfDay\":").append(global.disciplineLock.startMinuteOfDay)
            append(",\"endMinuteOfDay\":").append(global.disciplineLock.endMinuteOfDay)
            append("},\"notificationUsageVisible\":").append(global.notificationUsageVisible)
            append('}')
        }
        append('}')
    }

    /** 一步到位：快照 → JSON 字符串。 */
    fun encode(
        scope: ExportScope,
        groups: List<Group>,
        assignments: Map<String, String>,
        global: ExportGlobalSettings?,
        exportedAtMillis: Long,
    ): String = toJson(buildPayload(scope, groups, assignments, global, exportedAtMillis))

    private fun Group.toExportGroup() = ExportGroup(id = id, name = name, kind = kind, config = config)

    /**
     * 分组对象：{"id","name","kind","config":{"mechanisms":[名称数组，按枚举序],"dailyLimitMinutes","time"}}。
     * time 复用 DataStore 的 [TimeParamsFormat] 串（20 段分号分隔），保证与存储格式永不分叉。
     */
    private fun StringBuilder.appendGroup(group: ExportGroup) {
        append("{\"id\":")
        appendJsonString(group.id)
        append(",\"name\":")
        appendJsonString(group.name)
        append(",\"kind\":")
        appendJsonString(group.kind.name)
        append(",\"config\":{\"mechanisms\":[")
        Mechanism.entries.filter { it in group.config.mechanisms }.forEachIndexed { index, mechanism ->
            if (index > 0) append(',')
            appendJsonString(mechanism.name)
        }
        append("],\"dailyLimitMinutes\":").append(group.config.dailyLimitMinutes)
        append(",\"time\":")
        appendJsonString(TimeParamsFormat.encode(group.config.time))
        append("}}")
    }

    /** JSON 字符串字面量：转义引号、反斜杠与全部控制字符（组名为用户输入，不可信任）。 */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    if (c < ' ') {
                        append("\\u")
                        append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        append(c)
                    }
            }
        }
        append('"')
    }
}
