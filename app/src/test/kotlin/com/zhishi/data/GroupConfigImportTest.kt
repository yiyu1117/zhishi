package com.zhishi.data

import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupConfig
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入合并策略单测（纯函数，无 Android 依赖）：只断言外部行为——
 * 给定文件载荷与本机现状，得到合并后的目标状态与确认框概况。
 * 同 id 覆盖、新 id 新建、未提及保留、分配逐条覆盖（默认组即移除键），
 * 以及唯一需要对照本机现状才能发现的悬空分配整体拒绝。
 */
class GroupConfigImportTest {

    private fun group(id: String, name: String, kind: GroupKind, config: GroupConfig = GroupConfig()) =
        Group(id, name, kind, config)

    /** 本机现状：内置三组 + 一个自定义组。 */
    private val localGroups = listOf(
        group(GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT, GroupConfig(mechanisms = setOf(Mechanism.USAGE_TOAST))),
        group(GroupIds.BLACKLIST, "黑名单组", GroupKind.BLACKLIST, GroupConfig(dailyLimitMinutes = -1)),
        group(GroupIds.WHITELIST, "白名单组", GroupKind.WHITELIST),
        group("custom-local1", "游戏", GroupKind.CUSTOM, GroupConfig(dailyLimitMinutes = 60)),
    )
    private val localAssignments = mapOf(
        "com.a" to "custom-local1",
        "com.b" to GroupIds.BLACKLIST,
        "com.d" to GroupIds.WHITELIST,
    )

    /** 文件：覆盖 default 与 custom-local1，新建 custom-new1/custom-new2；blacklist/whitelist 未提及。 */
    private val fileGroups = listOf(
        group(GroupIds.DEFAULT, "默认组", GroupKind.DEFAULT, GroupConfig(mechanisms = setOf(Mechanism.TIME_SELECTION), dailyLimitMinutes = 15)),
        group("custom-local1", "游戏改", GroupKind.CUSTOM, GroupConfig(mechanisms = setOf(Mechanism.EXPIRY_COOLDOWN), dailyLimitMinutes = 90, time = TimeParams(extensionMinutes = 5))),
        group("custom-new1", "学习", GroupKind.CUSTOM, GroupConfig(dailyLimitMinutes = 30)),
        group("custom-new2", "阅读", GroupKind.CUSTOM, GroupConfig()),
    )

    private fun planOf(
        fileAssignments: Map<String, String> = emptyMap(),
        fileGroups: List<Group> = this.fileGroups,
        scope: ExportScope = ExportScope.GROUPS,
        global: ExportGlobalSettings? = null,
    ): GroupImportPlan {
        val payload = GroupConfigExport.buildPayload(
            scope = scope,
            groups = fileGroups,
            assignments = fileAssignments,
            global = global,
            exportedAtMillis = 1_700_000_000_000,
        )
        val result = GroupConfigImport.planMerge(payload, localGroups, localAssignments)
        return (result as? ImportMergeResult.Ok)?.plan
            ?: error("应合并成功：$result")
    }

    @Test
    fun `同 id 覆盖名称与配置，未提及的分组原样保留，新 id 按文件顺序追加`() {
        val plan = planOf()

        // 顺序：本机既有顺序保持（内置三组在前），文件新组按文件顺序追加
        assertEquals(
            listOf(GroupIds.DEFAULT, GroupIds.BLACKLIST, GroupIds.WHITELIST, "custom-local1", "custom-new1", "custom-new2"),
            plan.groups.map { it.id },
        )
        // 同 id 覆盖：名称与配置均取文件
        val overwrittenDefault = plan.groups.first { it.id == GroupIds.DEFAULT }
        assertEquals(GroupConfig(mechanisms = setOf(Mechanism.TIME_SELECTION), dailyLimitMinutes = 15), overwrittenDefault.config)
        val overwrittenCustom = plan.groups.first { it.id == "custom-local1" }
        assertEquals("游戏改", overwrittenCustom.name)
        assertEquals(90, overwrittenCustom.config.dailyLimitMinutes)
        assertEquals(TimeParams(extensionMinutes = 5), overwrittenCustom.config.time)
        // 新建的组身份与配置取文件
        val created = plan.groups.first { it.id == "custom-new1" }
        assertEquals("学习", created.name)
        assertEquals(GroupKind.CUSTOM, created.kind)
        // 本机未在文件中的分组原样保留（同一对象，零改动）
        val localBlacklist = localGroups.first { it.id == GroupIds.BLACKLIST }
        assertTrue(plan.groups.contains(localBlacklist))
        assertTrue(plan.groups.contains(localGroups.first { it.id == GroupIds.WHITELIST }))
        // 概况：覆盖/新建分组列表
        assertEquals(listOf(GroupIds.DEFAULT, "custom-local1"), plan.overwrittenGroups.map { it.id })
        assertEquals(listOf("custom-new1", "custom-new2"), plan.createdGroups.map { it.id })
    }

    @Test
    fun `分配逐条覆盖，文件分配到默认组即移除键，本机未提及的应用保留原分配`() {
        val plan = planOf(
            fileAssignments = mapOf(
                // com.a 从 custom-local1 移到新组；com.b 从黑名单移回默认组（移除键）；com.c 本机未安装
                "com.a" to "custom-new1",
                "com.b" to GroupIds.DEFAULT,
                "com.c" to "custom-new2",
            ),
        )

        assertEquals(
            mapOf(
                "com.a" to "custom-new1",
                "com.d" to GroupIds.WHITELIST, // 文件未提及，原样保留
                "com.c" to "custom-new2",      // 本机未安装的应用也被记录
            ),
            plan.assignments,
        )
        assertNull("分配到默认组必须移除分配键", plan.assignments["com.b"])
        assertEquals(3, plan.fileAssignmentCount)
    }

    @Test
    fun `分配目标组既不在文件也不在本机时整体拒绝`() {
        val payload = GroupConfigExport.buildPayload(
            scope = ExportScope.GROUPS,
            groups = fileGroups.take(1),
            assignments = mapOf("com.x" to "custom-ghost"),
            global = null,
            exportedAtMillis = 1_700_000_000_000,
        )

        val result = GroupConfigImport.planMerge(payload, localGroups, localAssignments)

        assertTrue("悬空分配应整体拒绝", result is ImportMergeResult.Rejected)
    }

    @Test
    fun `黑名单组每日限额强制保持禁用语义`() {
        val plan = planOf(
            fileGroups = listOf(
                group(GroupIds.BLACKLIST, "黑名单组", GroupKind.BLACKLIST, GroupConfig(dailyLimitMinutes = 0)),
            ),
        )

        assertEquals(-1, plan.groups.first { it.id == GroupIds.BLACKLIST }.config.dailyLimitMinutes)
    }

    @Test
    fun `full 档全局设置原样进入执行计划`() {
        val global = ExportGlobalSettings(
            detectionMode = com.zhishi.core.model.DetectionMode.POLLING,
            defaultTimeParams = TimeParams(extensionMinutes = 7),
            newGroupDailyLimitMinutes = 45,
            disciplineLock = com.zhishi.core.model.DisciplineLockConfig(enabled = true, startMinuteOfDay = 480, endMinuteOfDay = 1320),
            notificationUsageVisible = true,
        )
        val plan = planOf(scope = ExportScope.FULL, global = global)

        assertEquals(ExportScope.FULL, plan.scope)
        assertEquals(global, plan.global)
    }

    @Test
    fun `groups 档计划不含全局设置`() {
        val plan = planOf()

        assertEquals(ExportScope.GROUPS, plan.scope)
        assertNull(plan.global)
    }
}
