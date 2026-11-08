package com.zhishi.data

import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入文件解析单测（无 Android 依赖，JVM 运行）：以手写 JSON 字面量为独立真值源
 * （与票 02 [GroupConfigExportJsonTest] 钉死的线格式互为镜像），断言解析与整体校验行为——
 * 合法则得到载荷，损坏/非本应用/schema 或 scope 不符/枚举或时间参数非法则整体拒绝。
 * 合并语义（覆盖/新建/保留/分配移动）见 [GroupConfigImportTest]。
 */
class GroupConfigImportJsonTest {

    /** TimeParams 默认值的 TimeParamsFormat 编码（人工核对：20 段，分号分隔）。 */
    private val defaultTime = "5,10,15,20,30,60;5000;5000;10000;2;30;120000;300000;480000;600000;5,2,1;120;1;25;17;17;1;67;10;600000"

    @Test
    fun `groups 档 JSON 解析出分组配置与分配`() {
        val json = "{\"app\":\"zhishi\",\"schema\":1,\"scope\":\"groups\",\"exportedAt\":1700000000000," +
            "\"groups\":[" +
            "{\"id\":\"default\",\"name\":\"默认组\",\"kind\":\"DEFAULT\",\"config\":{\"mechanisms\":[],\"dailyLimitMinutes\":0,\"time\":\"$defaultTime\"}}," +
            "{\"id\":\"custom-aaaa1111\",\"name\":\"游戏\",\"kind\":\"CUSTOM\",\"config\":{\"mechanisms\":[\"TIME_SELECTION\",\"EXPIRY_COOLDOWN\"],\"dailyLimitMinutes\":60,\"time\":\"$defaultTime\"}}" +
            "]," +
            "\"assignments\":{\"com.example.game\":\"custom-aaaa1111\"}}"

        val result = GroupConfigImport.parse(json)

        val payload = (result as? ImportParseResult.Ok)?.payload
            ?: error("应解析成功：$result")
        assertEquals(1, payload.schemaVersion)
        assertEquals(ExportScope.GROUPS, payload.scope)
        assertEquals(2, payload.groups.size)
        val game = payload.groups.first { it.id == "custom-aaaa1111" }
        assertEquals("游戏", game.name)
        assertEquals(GroupKind.CUSTOM, game.kind)
        assertEquals(setOf(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), game.config.mechanisms)
        assertEquals(60, game.config.dailyLimitMinutes)
        assertEquals(mapOf("com.example.game" to "custom-aaaa1111"), payload.assignments)
    }

    @Test
    fun `导出再导入往返载荷一致`() {
        val groups = listOf(
            com.zhishi.core.model.Group(
                "custom-bbbb2222", "学习", GroupKind.CUSTOM,
                com.zhishi.core.model.GroupConfig(
                    mechanisms = setOf(Mechanism.DELAYED_SELECTION),
                    dailyLimitMinutes = 30,
                    time = com.zhishi.core.model.TimeParams(extensionMinutes = 5, quickProtectMillis = 8_000),
                ),
            ),
        )
        val assignments = mapOf("com.example.tool" to "custom-bbbb2222")
        val json = GroupConfigExport.encode(
            scope = ExportScope.GROUPS,
            groups = groups,
            assignments = assignments,
            global = null,
            exportedAtMillis = 1_700_000_000_000,
        )

        val result = GroupConfigImport.parse(json)

        val payload = (result as? ImportParseResult.Ok)?.payload
            ?: error("应解析成功：$result")
        assertEquals(ExportScope.GROUPS, payload.scope)
        assertEquals(1, payload.groups.size)
        assertEquals("学习", payload.groups[0].name)
        assertEquals(groups[0].config, payload.groups[0].config)
        assertEquals(assignments, payload.assignments)
    }

    @Test
    fun `损坏 JSON 整体拒绝`() {
        listOf(
            "" to "空串",
            "not json" to "非 JSON",
            "{\"app\":\"zhishi\"" to "截断",
            "{\"app\":\"zhishi\",\"schema\":1,\"scope\":\"groups\",\"groups\":[],\"assignments\":{}}多余" to "尾随内容",
        ).forEach { (json, label) ->
            val result = GroupConfigImport.parse(json)
            assertTrue("[$label] 应整体拒绝：$result", result is ImportParseResult.Rejected)
        }
    }

    // ---- 测试辅助：按导出线格式拼文档 ----

    private fun groupJson(
        id: String,
        name: String = "组",
        kind: String = if (id in setOf("default", "blacklist", "whitelist")) kindOf(id) else "CUSTOM",
        mechanisms: String = "",
        limit: Int = 0,
        time: String = defaultTime,
    ): String =
        "{\"id\":\"$id\",\"name\":\"$name\",\"kind\":\"$kind\"," +
            "\"config\":{\"mechanisms\":[$mechanisms],\"dailyLimitMinutes\":$limit,\"time\":\"$time\"}}"

    private fun kindOf(id: String): String = when (id) {
        "default" -> "DEFAULT"
        "blacklist" -> "BLACKLIST"
        "whitelist" -> "WHITELIST"
        else -> "CUSTOM"
    }

    private fun fullGlobal(detectionMode: String = "POLLING", time: String = defaultTime): String =
        "{\"detectionMode\":\"$detectionMode\",\"defaultTimeParams\":\"$time\"," +
            "\"newGroupDailyLimitMinutes\":45," +
            "\"disciplineLock\":{\"enabled\":true,\"startMinuteOfDay\":480,\"endMinuteOfDay\":1320}," +
            "\"notificationUsageVisible\":true}"

    private fun doc(scope: String, groups: String, global: String? = null): String =
        "{\"app\":\"zhishi\",\"schema\":1,\"scope\":\"$scope\",\"exportedAt\":1700000000000," +
            "\"groups\":[$groups],\"assignments\":{}" +
            (global?.let { ",\"global\":$it" } ?: "") + "}"

    @Test
    fun `full 档解析出五项全局设置`() {
        val json = doc("full", groupJson("default"), global = fullGlobal())

        val result = GroupConfigImport.parse(json)

        val payload = (result as? ImportParseResult.Ok)?.payload
            ?: error("应解析成功：$result")
        assertEquals(ExportScope.FULL, payload.scope)
        val global = payload.global ?: error("full 档必须携带 global")
        assertEquals(com.zhishi.core.model.DetectionMode.POLLING, global.detectionMode)
        assertEquals(45, global.newGroupDailyLimitMinutes)
        assertEquals(
            com.zhishi.core.model.DisciplineLockConfig(enabled = true, startMinuteOfDay = 480, endMinuteOfDay = 1320),
            global.disciplineLock,
        )
        assertEquals(true, global.notificationUsageVisible)
        assertEquals(com.zhishi.core.model.TimeParams(), global.defaultTimeParams)
    }

    @Test
    fun `非本应用或 schema 不符的文件整体拒绝`() {
        val base = doc("groups", groupJson("default"))
        listOf(
            base.replace("\"app\":\"zhishi\"", "\"app\":\"other\"") to "app 非 zhishi",
            base.replace("\"schema\":1", "\"schema\":2") to "schema 不符",
            base.replace("\"app\":\"zhishi\"", "\"schema_other\":1") to "缺 app 字段",
        ).forEach { (json, label) ->
            assertTrue("[$label] 应整体拒绝", GroupConfigImport.parse(json) is ImportParseResult.Rejected)
        }
    }

    @Test
    fun `scope 非法或与 global 不一致的文件整体拒绝`() {
        listOf(
            doc("unknown", groupJson("default")) to "scope 非法",
            doc("full", groupJson("default")) to "full 档缺 global",
            doc("groups", groupJson("default"), global = fullGlobal()) to "groups 档带 global",
        ).forEach { (json, label) ->
            assertTrue("[$label] 应整体拒绝", GroupConfigImport.parse(json) is ImportParseResult.Rejected)
        }
    }

    @Test
    fun `分组内容非法的文件整体拒绝`() {
        val unknownMechanism = doc("groups", groupJson("default", mechanisms = "\"NO_SUCH_MECHANISM\""))
        val badKind = doc("groups", groupJson("default", kind = "CUSTOM"))
        val customWithBuiltinKind = doc("groups", groupJson("custom-x1", kind = "DEFAULT"))
        val dupIds = doc("groups", "${groupJson("default")},${groupJson("default")}")
        val blankId = doc("groups", groupJson("  "))
        val blankName = doc("groups", groupJson("custom-x1", name = "  "))
        val nonIntLimit = doc(
            "groups",
            groupJson("default").replace("\"dailyLimitMinutes\":0", "\"dailyLimitMinutes\":x"),
        )
        val assignmentValueNotString = "{\"app\":\"zhishi\",\"schema\":1,\"scope\":\"groups\"," +
            "\"groups\":[],\"assignments\":{\"com.a\":1}}"
        listOf(
            unknownMechanism to "未知机制枚举名",
            badKind to "内置组 kind 不符",
            customWithBuiltinKind to "自定义组冒充内置 kind",
            dupIds to "重复分组 id",
            blankId to "空白分组 id",
            blankName to "空白分组名",
            nonIntLimit to "限额非整数",
            assignmentValueNotString to "分配值非字符串",
        ).forEach { (json, label) ->
            assertTrue("[$label] 应整体拒绝", GroupConfigImport.parse(json) is ImportParseResult.Rejected)
        }
    }

    @Test
    fun `time 串损坏的文件整体拒绝`() {
        val truncated = defaultTime.split(';').dropLast(1).joinToString(";")
        val junk = defaultTime.replace("5000;5000", "junk;5000")
        val negativeQuick = defaultTime.replace("5,10,15,20,30,60;", "5,-10,15,20,30,60;")
        val fiveOptions = defaultTime.replace("5,10,15,20,30,60;", "5,10,15,20,30;")
        val booleanSegment = defaultTime.replace(";1;25;", ";true;25;")
        listOf(
            truncated to "段数不足",
            junk to "数字段含垃圾",
            negativeQuick to "快选档含非正数",
            fiveOptions to "快选档不足 6 个",
            booleanSegment to "布尔段非 0/1",
        ).forEach { (time, label) ->
            val json = doc("groups", groupJson("default", time = time))
            assertTrue("[$label] 应整体拒绝", GroupConfigImport.parse(json) is ImportParseResult.Rejected)
        }
        // 提醒刻度整段为空是合法状态（用户删光刻度），不得拒绝
        val noMarks = doc("groups", groupJson("default", time = defaultTime.replace(";5,2,1;", ";;")))
        assertTrue("空提醒刻度应解析成功", GroupConfigImport.parse(noMarks) is ImportParseResult.Ok)
    }

    @Test
    fun `global 内容非法的文件整体拒绝`() {
        listOf(
            fullGlobal(detectionMode = "MAGIC") to "检测模式枚举名未知",
            fullGlobal(time = "junk") to "默认时间参数损坏",
        ).forEach { (global, label) ->
            val json = doc("full", groupJson("default"), global = global)
            assertTrue("[$label] 应整体拒绝", GroupConfigImport.parse(json) is ImportParseResult.Rejected)
        }
    }
}
