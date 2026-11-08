package com.zhishi.data

import com.zhishi.core.model.DetectionMode
import com.zhishi.core.model.DisciplineLockConfig
import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出 JSON 线格式单测：用已知字面量钉死对外 schema（票 03 导入侧按此解码）。
 * TimeParams 编解码复用 DataStore 的 TimeParamsFormat 串（20 段分号分隔），
 * 测试中的 TIME 字面量为该格式的默认值，人工核对得出，非实现重算。
 */
class GroupConfigExportJsonTest {

    /** TimeParams 默认值的 TimeParamsFormat 编码（人工核对：20 段，分号分隔）。 */
    private val defaultTime = "5,10,15,20,30,60;5000;5000;10000;2;30;120000;300000;480000;600000;5,2,1;120;1;25;17;17;1;67;10;600000"

    @Test
    fun `groups 档 JSON 线格式固定（顶层字段、分组对象与分配映射）`() {
        val groups = listOf(
            Group(GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT, GroupConfig()),
            Group(GroupIds.BLACKLIST, "黑名单组", GroupKind.BLACKLIST, GroupConfig(dailyLimitMinutes = -1)),
            Group(GroupIds.WHITELIST, "白名单组", GroupKind.WHITELIST, GroupConfig()),
            Group(
                "custom-aaaa1111", "游戏", GroupKind.CUSTOM,
                GroupConfig(
                    mechanisms = setOf(Mechanism.EXPIRY_COOLDOWN, Mechanism.TIME_SELECTION),
                    dailyLimitMinutes = 60,
                ),
            ),
        )

        val json = GroupConfigExport.encode(
            scope = ExportScope.GROUPS,
            groups = groups,
            assignments = mapOf("com.example.game" to "custom-aaaa1111", "com.example.short" to GroupIds.BLACKLIST),
            global = null,
            exportedAtMillis = 1_700_000_000_000,
        )

        val expected = "{\"app\":\"zhishi\",\"schema\":1,\"scope\":\"groups\",\"exportedAt\":1700000000000," +
            "\"groups\":[" +
            "{\"id\":\"default\",\"name\":\"默认组\",\"kind\":\"DEFAULT\",\"config\":{\"mechanisms\":[],\"dailyLimitMinutes\":0,\"time\":\"$defaultTime\"}}," +
            "{\"id\":\"blacklist\",\"name\":\"黑名单组\",\"kind\":\"BLACKLIST\",\"config\":{\"mechanisms\":[],\"dailyLimitMinutes\":-1,\"time\":\"$defaultTime\"}}," +
            "{\"id\":\"whitelist\",\"name\":\"白名单组\",\"kind\":\"WHITELIST\",\"config\":{\"mechanisms\":[],\"dailyLimitMinutes\":0,\"time\":\"$defaultTime\"}}," +
            "{\"id\":\"custom-aaaa1111\",\"name\":\"游戏\",\"kind\":\"CUSTOM\",\"config\":{\"mechanisms\":[\"TIME_SELECTION\",\"EXPIRY_COOLDOWN\"],\"dailyLimitMinutes\":60,\"time\":\"$defaultTime\"}}" +
            "]," +
            "\"assignments\":{\"com.example.game\":\"custom-aaaa1111\",\"com.example.short\":\"blacklist\"}}"
        assertEquals(expected, json)
    }

    @Test
    fun `full 档 JSON 追加 global 对象且五项设置字段固定`() {
        val json = GroupConfigExport.encode(
            scope = ExportScope.FULL,
            groups = listOf(Group(GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT, GroupConfig())),
            assignments = emptyMap(),
            global = ExportGlobalSettings(
                detectionMode = DetectionMode.POLLING,
                defaultTimeParams = TimeParams(),
                newGroupDailyLimitMinutes = 45,
                disciplineLock = DisciplineLockConfig(enabled = true, startMinuteOfDay = 8 * 60, endMinuteOfDay = 22 * 60),
                notificationUsageVisible = true,
            ),
            exportedAtMillis = 1_700_000_000_000,
        )

        assertTrue(json.startsWith("{\"app\":\"zhishi\",\"schema\":1,\"scope\":\"full\",\"exportedAt\":1700000000000,"))
        assertTrue(
            json.contains(
                "\"global\":{\"detectionMode\":\"POLLING\",\"defaultTimeParams\":\"$defaultTime\"," +
                    "\"newGroupDailyLimitMinutes\":45," +
                    "\"disciplineLock\":{\"enabled\":true,\"startMinuteOfDay\":480,\"endMinuteOfDay\":1320}," +
                    "\"notificationUsageVisible\":true}",
            ),
        )
        // global 位于文件末尾（full 档以 global 对象收尾，随后仅根对象闭合）
        assertTrue(json.endsWith("true}}"))
    }

    @Test
    fun `两档 JSON 均不含调试标志与保活卡关闭记忆字段`() {
        val groups = listOf(
            Group(GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT, GroupConfig()),
            Group(GroupIds.BLACKLIST, "黑名单组", GroupKind.BLACKLIST, GroupConfig(dailyLimitMinutes = -1)),
            Group(GroupIds.WHITELIST, "白名单组", GroupKind.WHITELIST, GroupConfig()),
        )
        val scopes = listOf(
            GroupConfigExport.encode(ExportScope.GROUPS, groups, emptyMap(), null, 1_700_000_000_000),
            GroupConfigExport.encode(
                ExportScope.FULL, groups, emptyMap(),
                ExportGlobalSettings(DetectionMode.ACCESSIBILITY, TimeParams(), 0, DisciplineLockConfig(), false),
                1_700_000_000_000,
            ),
        )
        scopes.forEach { json ->
            val lower = json.lowercase()
            assertFalse("调试标志字段不得出现在导出文件：$json", "debug" in lower)
            assertFalse("保活卡关闭记忆字段不得出现在导出文件：$json", "keepalive" in lower)
            assertFalse("保活卡关闭记忆字段不得出现在导出文件：$json", "dismissed" in lower)
        }
    }

    @Test
    fun `组名特殊字符按 JSON 转义`() {
        val groups = listOf(
            Group("custom-x1", "引\"号\\反斜\n换行\t制表", GroupKind.CUSTOM, GroupConfig()),
        )

        val json = GroupConfigExport.encode(
            scope = ExportScope.GROUPS,
            groups = groups,
            assignments = emptyMap(),
            global = null,
            exportedAtMillis = 1_700_000_000_000,
        )

        assertTrue(json.contains("\"name\":\"引\\\"号\\\\反斜\\n换行\\t制表\""))
    }
}
