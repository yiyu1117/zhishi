package com.zhishi.data

import com.zhishi.core.model.DetectionMode
import com.zhishi.core.model.DisciplineLockConfig
import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams

/** 导入文件解析结果：合法载荷，或整体拒绝（reason 为面向用户的中文文案）。 */
sealed class ImportParseResult {
    data class Ok(val payload: ExportPayload) : ImportParseResult()
    data class Rejected(val reason: String) : ImportParseResult()
}

/** 导入合并结果：可执行计划，或需对照本机现状才能发现的问题（整体拒绝）。 */
sealed class ImportMergeResult {
    data class Ok(val plan: GroupImportPlan) : ImportMergeResult()
    data class Rejected(val reason: String) : ImportMergeResult()
}

/**
 * 导入执行计划：文件载荷与本机现状合并后的完整目标状态。
 * [groups]/[assignments] 即一次性写入 DataStore 的目标全集（文件未提及的分组与分配原样保留在列）；
 * [global] 非空时由 UI 经 SettingsRepository 各 setter 逐项应用，检测模式等服务联动自然发生；
 * 概况字段供确认框展示。
 */
data class GroupImportPlan(
    val scope: ExportScope,
    val global: ExportGlobalSettings?,
    /** 合并后完整分组列表：本机既有顺序保持（内置三组在前），文件新组按文件顺序追加。 */
    val groups: List<Group>,
    /** 合并后完整分配表（不含默认组条目，缺省即默认组）。 */
    val assignments: Map<String, String>,
    /** 同 id 覆盖的分组（含文件侧新配置；写入后据此发配置更新，成员运行状态确定性重置）。 */
    val overwrittenGroups: List<Group>,
    /** 文件新带来的分组（本机无此 id）。 */
    val createdGroups: List<Group>,
    /** 文件携带的 app→组分配条数（概况展示）。 */
    val fileAssignmentCount: Int,
)

/**
 * 分组配置导入（v1.2.1）：解析与合并均为纯 Kotlin，无任何 Android 依赖。
 *
 * 与导出侧 [GroupConfigExport] 共用同一 schema；文件校验采取「全量解析、整体拒绝」——
 * 任何一项不合规（损坏、非本应用、schema/scope 不符、枚举或时间参数非法）都在
 * 触碰任何现有配置之前以 [ImportParseResult.Rejected] 收场，绝不写一半。
 */
object GroupConfigImport {

    /** 解析并全量校验导入文件；任何不合规都整体拒绝，绝不抛出异常。 */
    fun parse(json: String): ImportParseResult =
        runCatching { ImportParseResult.Ok(decodePayload(json)) }
            .getOrElse { ImportParseResult.Rejected(it.message ?: "文件无法解析") }

    /**
     * 按分组 id 合并覆盖：同 id 覆盖名称与配置，文件新 id 新建，本机未在文件中的分组原样保留；
     * app→组分配逐条覆盖（分配到默认组即移除分配键，沿用 [GroupRepository] 移动语义），
     * 本机未安装应用的分配关系一并记录。分配目标组必须存在于合并结果中，否则整体拒绝。
     */
    fun planMerge(
        payload: ExportPayload,
        localGroups: List<Group>,
        localAssignments: Map<String, String>,
    ): ImportMergeResult {
        val localById = localGroups.associateBy { it.id }
        val mergedIds = localById.keys + payload.groups.map { it.id }.toSet()
        payload.assignments.values.firstOrNull { it !in mergedIds }?.let { ghost ->
            return ImportMergeResult.Rejected("文件中的应用分配指向不存在的分组：$ghost")
        }
        // 同 id 覆盖以文件为准；黑名单组每日限额强制保持 -1 禁用语义（与仓库 updateConfig 一致）
        val overridden = payload.groups.associate { fg ->
            val effective = if (fg.id == GroupIds.BLACKLIST) fg.config.copy(dailyLimitMinutes = -1) else fg.config
            fg.id to Group(fg.id, fg.name, fg.kind, effective)
        }
        val groups = localGroups.map { overridden[it.id] ?: it } +
            payload.groups.filter { it.id !in localById }.mapNotNull { overridden[it.id] }
        // 分配逐条覆盖：文件条目覆盖同包本机条目，默认组条目即移除键
        val assignments = localAssignments.toMutableMap()
        payload.assignments.forEach { (pkg, gid) ->
            if (gid == GroupIds.DEFAULT) assignments.remove(pkg) else assignments[pkg] = gid
        }
        return ImportMergeResult.Ok(
            GroupImportPlan(
                scope = payload.scope,
                global = payload.global,
                groups = groups,
                assignments = assignments,
                overwrittenGroups = payload.groups.filter { it.id in localById }
                    .mapNotNull { overridden[it.id] },
                createdGroups = payload.groups.filter { it.id !in localById }
                    .mapNotNull { overridden[it.id] },
                fileAssignmentCount = payload.assignments.size,
            ),
        )
    }

    // region 载荷解码（非法即 fail，由 parse 统一转成整体拒绝）

    private fun decodePayload(json: String): ExportPayload {
        val root = parseJson(json.removePrefix("\uFEFF")) as? JsonNode.Obj
            ?: fail("文件顶层必须是 JSON 对象")

        val app = str(root, "app")
        require(app == GroupConfigExport.APP_MARKER) { "不是「知时」导出的配置文件" }
        val schema = int(root, "schema")
        require(schema == GroupConfigExport.SCHEMA_VERSION) {
            "文件 schema 版本不符（$schema，本应用支持 ${GroupConfigExport.SCHEMA_VERSION}）"
        }
        val scopeName = str(root, "scope")
        val scope = ExportScope.entries.firstOrNull { it.jsonName == scopeName }
            ?: fail("scope 字段非法：$scopeName")

        val groups = arr(root, "groups").map { decodeGroup(it) }
        require(groups.map { it.id }.toSet().size == groups.size) { "文件中存在重复的分组 id" }
        val assignments = decodeAssignments(obj(root, "assignments"))

        val global = when (scope) {
            ExportScope.FULL -> decodeGlobal(obj(root, "global"))
            ExportScope.GROUPS -> {
                require("global" !in root.fields) { "仅分组配置档不应包含 global 设置" }
                null
            }
        }
        // exportedAt 仅为元信息（不参与合并），缺失或损坏不影响导入
        val exportedAt = (root.fields["exportedAt"] as? JsonNode.Num)?.raw?.toLongOrNull() ?: 0L
        return ExportPayload(schema, scope, exportedAt, groups, assignments, global)
    }

    private fun decodeGroup(node: JsonNode): ExportGroup {
        val o = node as? JsonNode.Obj ?: fail("分组必须为 JSON 对象")
        val id = str(o, "id").trim()
        require(id.isNotEmpty()) { "分组 id 不能为空" }
        val name = str(o, "name").trim()
        require(name.isNotEmpty()) { "分组「$id」名称不能为空" }
        val kindName = str(o, "kind")
        val kind = GroupKind.entries.firstOrNull { it.name == kindName }
            ?: fail("分组「$id」kind 非法：$kindName")
        // kind 与 id 必须相符：内置三组身份由 id 决定，其余 id 只可能是自定义组
        val expectedKind = when (id) {
            GroupIds.DEFAULT -> GroupKind.DEFAULT
            GroupIds.BLACKLIST -> GroupKind.BLACKLIST
            GroupIds.WHITELIST -> GroupKind.WHITELIST
            else -> GroupKind.CUSTOM
        }
        require(kind == expectedKind) { "分组「$id」的 kind（$kind）与 id 不符" }

        val configO = obj(o, "config")
        val config = GroupConfig(
            mechanisms = decodeMechanisms(configO, id),
            dailyLimitMinutes = int(configO, "dailyLimitMinutes"),
            time = decodeTimeStrictly(str(configO, "time"))
                ?: fail("分组「$id」时间参数无法解析"),
        )
        return ExportGroup(id, name, kind, config)
    }

    private fun decodeMechanisms(o: JsonNode.Obj, groupId: String): Set<Mechanism> =
        arr(o, "mechanisms").map { item ->
            val name = (item as? JsonNode.Str)?.value
                ?: fail("分组「$groupId」的 mechanisms 含非字符串项")
            Mechanism.entries.firstOrNull { it.name == name }
                ?: fail("分组「$groupId」含未知机制：$name")
        }.toSet()

    private fun decodeAssignments(o: JsonNode.Obj): Map<String, String> =
        o.fields.mapValues { (pkg, value) ->
            require(pkg.isNotBlank()) { "应用分配键不能为空" }
            (value as? JsonNode.Str)?.value ?: fail("应用「$pkg」的分配值必须为分组 id 字符串")
        }

    private fun decodeGlobal(o: JsonNode.Obj): ExportGlobalSettings {
        val modeName = str(o, "detectionMode")
        val mode = DetectionMode.entries.firstOrNull { it.name == modeName }
            ?: fail("detectionMode 非法：$modeName")
        val params = decodeTimeStrictly(str(o, "defaultTimeParams"))
            ?: fail("默认时间参数无法解析")
        val lockO = obj(o, "disciplineLock")
        val lock = DisciplineLockConfig(
            enabled = bool(lockO, "enabled"),
            startMinuteOfDay = int(lockO, "startMinuteOfDay"),
            endMinuteOfDay = int(lockO, "endMinuteOfDay"),
        )
        return ExportGlobalSettings(
            detectionMode = mode,
            defaultTimeParams = params,
            newGroupDailyLimitMinutes = int(o, "newGroupDailyLimitMinutes"),
            disciplineLock = lock,
            notificationUsageVisible = bool(o, "notificationUsageVisible"),
        )
    }

    /**
     * time 串的导入侧严格解码：20 段分号分隔，各段形状与 [TimeParamsFormat] 一致
     * （数值段必须可解析、布尔段为 0/1、快选档 6 个正整数、提醒刻度可整段为空）。
     * 存储侧解码器对损坏段静默回退默认值——导入必须整体拒绝，不能带病写入。
     */
    private fun decodeTimeStrictly(encoded: String): TimeParams? {
        val parts = encoded.split(';')
        if (parts.size != 20) return null
        // 段 0：快选档位 = 6 个正整数
        val quick = parts[0].split(',')
        if (quick.size != 6 || quick.any { (it.toIntOrNull() ?: 0) <= 0 }) return null
        // 段 1/2/3/6/7/8/19：毫秒值（可为任意整数，语义钳制沿用 TimeParamsFormat）
        listOf(1, 2, 3, 6, 7, 8, 19).forEach { idx ->
            if (parts[idx].toLongOrNull() == null) return null
        }
        // 段 4 延时分钟 / 段 9 提示间隔 / 段 11 自定义上限 / 段 18 试探期：正整数
        listOf(4, 9, 11, 18).forEach { idx ->
            if ((parts[idx].toIntOrNull() ?: 0) <= 0) return null
        }
        if (parts[5].toIntOrNull() == null) return null // 段 5 预告秒（钳制 0–600）
        // 段 10：提醒刻度——可整段为空（用户删光刻度的合法状态），否则每段均为正整数
        if (parts[10].isNotEmpty() &&
            parts[10].split(',').any { (it.toIntOrNull() ?: 0) <= 0 }
        ) return null
        // 段 12/16：布尔（0/1）；段 13/14/15/17：比例整数（钳制 0–100）
        listOf(12, 16).forEach { idx ->
            if (parts[idx] != "0" && parts[idx] != "1") return null
        }
        listOf(13, 14, 15, 17).forEach { idx ->
            if (parts[idx].toIntOrNull() == null) return null
        }
        return TimeParamsFormat.decode(encoded)
    }

    // endregion

    // region 最小严格 JSON 解析器（schema 简单可控，app 模块 JVM 单测无 JSON 库可用）

    private fun parseJson(text: String): JsonNode {
        val parser = JsonParser(text)
        val node = parser.parseValue()
        parser.expectEnd()
        return node
    }

    /** 文件内 JSON 树（仅本文件可见；映射时的类型不符即整体拒绝）。 */
    private sealed class JsonNode {
        data object Null : JsonNode()
        data class Bool(val value: Boolean) : JsonNode()
        data class Num(val raw: String) : JsonNode()
        data class Str(val value: String) : JsonNode()
        data class Arr(val items: List<JsonNode>) : JsonNode()
        data class Obj(val fields: Map<String, JsonNode>) : JsonNode()
    }

    private class JsonParser(private val s: String) {
        private var i = 0

        fun expectEnd() {
            skipWs()
            require(i == s.length) { "JSON 结束后有多余内容" }
        }

        fun parseValue(): JsonNode {
            skipWs()
            require(i < s.length) { "文件内容缺失" }
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonNode.Str(parseString())
                't' -> literal("true", JsonNode.Bool(true))
                'f' -> literal("false", JsonNode.Bool(false))
                'n' -> literal("null", JsonNode.Null)
                else -> parseNumber()
            }
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun literal(word: String, node: JsonNode): JsonNode {
            require(s.regionMatches(i, word, 0, word.length)) { "非法字面量（应为 $word）" }
            i += word.length
            return node
        }

        private fun parseObject(): JsonNode.Obj {
            i++ // {
            val fields = LinkedHashMap<String, JsonNode>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return JsonNode.Obj(fields)
            }
            while (true) {
                skipWs()
                require(i < s.length && s[i] == '"') { "对象缺少字符串键" }
                val key = parseString()
                skipWs()
                require(i < s.length && s[i] == ':') { "对象缺少冒号" }
                i++
                val value = parseValue()
                require(fields.put(key, value) == null) { "文件存在重复字段：$key" }
                skipWs()
                require(i < s.length) { "对象未闭合" }
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonNode.Obj(fields)
                    }
                    else -> throw IllegalArgumentException("对象内缺少逗号或右花括号")
                }
            }
        }

        private fun parseArray(): JsonNode.Arr {
            i++ // [
            val items = mutableListOf<JsonNode>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return JsonNode.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWs()
                require(i < s.length) { "数组未闭合" }
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonNode.Arr(items)
                    }
                    else -> throw IllegalArgumentException("数组内缺少逗号或右括号")
                }
            }
        }

        private fun parseString(): String {
            require(s[i] == '"') { "应为字符串" }
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "字符串未闭合" }
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        require(i < s.length) { "转义序列不完整" }
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 < s.length) { "\\u 转义不完整" }
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("非法 \\u 转义：$hex")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("非法转义字符：\\$e")
                        }
                        i++
                    }
                    else -> {
                        require(c >= ' ') { "字符串含未转义控制字符" }
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun parseNumber(): JsonNode.Num {
            val start = i
            if (i < s.length && s[i] == '-') i++
            var digits = false
            while (i < s.length && s[i] in '0'..'9') {
                i++
                digits = true
            }
            require(digits) { "非法数字" }
            if (i < s.length && s[i] == '.') {
                i++
                var frac = false
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                    frac = true
                }
                require(frac) { "非法数字" }
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                var exp = false
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                    exp = true
                }
                require(exp) { "非法数字" }
            }
            return JsonNode.Num(s.substring(start, i))
        }
    }

    // endregion

    // region 字段取值（缺失或类型不符即整体拒绝）

    private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)

    private fun str(o: JsonNode.Obj, key: String): String {
        val v = o.fields[key] ?: fail("缺少字段：$key")
        return (v as? JsonNode.Str)?.value ?: fail("字段 $key 必须为字符串")
    }

    private fun int(o: JsonNode.Obj, key: String): Int {
        val v = o.fields[key] ?: fail("缺少字段：$key")
        return (v as? JsonNode.Num)?.raw?.toIntOrNull() ?: fail("字段 $key 必须为整数")
    }

    private fun bool(o: JsonNode.Obj, key: String): Boolean {
        val v = o.fields[key] ?: fail("缺少字段：$key")
        return (v as? JsonNode.Bool)?.value ?: fail("字段 $key 必须为布尔值")
    }

    private fun obj(o: JsonNode.Obj, key: String): JsonNode.Obj {
        val v = o.fields[key] ?: fail("缺少字段：$key")
        return v as? JsonNode.Obj ?: fail("字段 $key 必须为对象")
    }

    private fun arr(o: JsonNode.Obj, key: String): List<JsonNode> {
        val v = o.fields[key] ?: fail("缺少字段：$key")
        return (v as? JsonNode.Arr)?.items ?: fail("字段 $key 必须为数组")
    }

    // endregion
}
