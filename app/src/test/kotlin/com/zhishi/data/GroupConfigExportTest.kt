package com.zhishi.data

import com.zhishi.core.model.DetectionMode
import com.zhishi.core.model.DisciplineLockConfig
import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分组配置导出纯函数单测（无 Android 依赖，JVM 运行）：
 * 只断言外部行为——给定分组/分配/全局设置与档位，得到内容正确的导出结果。
 * 分配完整性与两档内容差异是本组测试的核心；线格式（JSON 字符串）见 [GroupConfigExportJsonTest]。
 */
class GroupConfigExportTest {

    private fun group(id: String, name: String, kind: GroupKind, config: GroupConfig) =
        Group(id, name, kind, config)

    @Test
    fun `groups 档含内置三组配置、自定义组名称配置与顺序、全部分配，且无全局设置`() {
        val groups = listOf(
            // 自定义组故意排在内置组之前，验证规范化排序不依赖输入顺序
            group(
                "custom-aaaa1111", "游戏", GroupKind.CUSTOM,
                GroupConfig(
                    mechanisms = setOf(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN),
                    dailyLimitMinutes = 60,
                    time = TimeParams(extensionMinutes = 5, quickProtectMillis = 8_000),
                ),
            ),
            group(
                GroupIds.WHITELIST, "白名单组", GroupKind.WHITELIST,
                GroupConfig(mechanisms = setOf(Mechanism.EXTENSION), time = TimeParams(gracePeriodMillis = 90_000)),
            ),
            group(
                "custom-bbbb2222", "学习", GroupKind.CUSTOM,
                GroupConfig(mechanisms = setOf(Mechanism.DELAYED_SELECTION), dailyLimitMinutes = 30),
            ),
            group(GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT, GroupConfig(mechanisms = setOf(Mechanism.USAGE_TOAST))),
            group(GroupIds.BLACKLIST, "黑名单组", GroupKind.BLACKLIST, GroupConfig(dailyLimitMinutes = -1)),
        )
        val assignments = mapOf(
            "com.example.game" to "custom-aaaa1111",
            "com.example.short" to GroupIds.BLACKLIST,
            "com.example.tool" to "custom-bbbb2222",
        )

        val payload = GroupConfigExport.buildPayload(
            scope = ExportScope.GROUPS,
            groups = groups,
            assignments = assignments,
            global = null,
            exportedAtMillis = 1_700_000_000_000,
        )

        assertEquals(GroupConfigExport.SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(ExportScope.GROUPS, payload.scope)
        assertEquals(1_700_000_000_000, payload.exportedAtMillis)
        // 内置三组固定在最前（default/blacklist/whitelist），自定义组保持出现顺序
        assertEquals(
            listOf(GroupIds.DEFAULT, GroupIds.BLACKLIST, GroupIds.WHITELIST, "custom-aaaa1111", "custom-bbbb2222"),
            payload.groups.map { it.id },
        )
        // 配置完整：名称、种类、机制、限额与时间参数逐项核对
        val game = payload.groups.first { it.id == "custom-aaaa1111" }
        assertEquals("游戏", game.name)
        assertEquals(GroupKind.CUSTOM, game.kind)
        assertEquals(setOf(Mechanism.TIME_SELECTION, Mechanism.EXPIRY_COOLDOWN), game.config.mechanisms)
        assertEquals(60, game.config.dailyLimitMinutes)
        assertEquals(5, game.config.time.extensionMinutes)
        assertEquals(8_000L, game.config.time.quickProtectMillis)
        val blacklist = payload.groups.first { it.id == GroupIds.BLACKLIST }
        assertEquals(-1, blacklist.config.dailyLimitMinutes)
        // 分配完整：三个应用、三种目标组全部原样携带（默认组分配本就不落盘，缺省即默认组）
        assertEquals(assignments, payload.assignments)
        assertNull(payload.global)
    }

    @Test
    fun `full 档在分组内容之外携带全部五项全局设置`() {
        val groups = listOf(
            group(GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT, GroupConfig()),
            group(GroupIds.BLACKLIST, "黑名单组", GroupKind.BLACKLIST, GroupConfig(dailyLimitMinutes = -1)),
            group(GroupIds.WHITELIST, "白名单组", GroupKind.WHITELIST, GroupConfig()),
        )
        val global = ExportGlobalSettings(
            detectionMode = DetectionMode.POLLING,
            defaultTimeParams = TimeParams(extensionMinutes = 7, smartAbandonCooldownPercent = 42),
            newGroupDailyLimitMinutes = 45,
            disciplineLock = DisciplineLockConfig(enabled = true, startMinuteOfDay = 8 * 60, endMinuteOfDay = 22 * 60),
            notificationUsageVisible = true,
        )

        val payload = GroupConfigExport.buildPayload(
            scope = ExportScope.FULL,
            groups = groups,
            assignments = emptyMap(),
            global = global,
            exportedAtMillis = 1_700_000_000_001,
        )

        assertEquals(ExportScope.FULL, payload.scope)
        assertEquals(global, payload.global)
        assertEquals(listOf(GroupIds.DEFAULT, GroupIds.BLACKLIST, GroupIds.WHITELIST), payload.groups.map { it.id })
        assertEquals(0, payload.assignments.size)
    }
}
