@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhishi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhishi.AppGraph
import com.zhishi.core.model.Group
import com.zhishi.core.model.GroupKind
import com.zhishi.core.model.Mechanism
import com.zhishi.core.model.TimeParams
import com.zhishi.data.AppCatalog
import com.zhishi.data.GroupIds
import kotlinx.coroutines.launch

// region 分组列表

@Composable
internal fun GroupListScreen(onOpenGroup: (String) -> Unit, onOpenSettings: () -> Unit) {
    val repo = AppGraph.groupRepository
    val settings = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val assignments by repo.assignments.collectAsStateWithLifecycle(initialValue = emptyMap())
    val apps by AppGraph.appCatalog.apps.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(Unit) { AppGraph.appCatalog.ensureLoaded() }
    val lock = rememberDisciplineLockUi()
    // 新分组用设置中的全局默认参数播种
    val seedTime by settings.defaultTimeParams.collectAsStateWithLifecycle(initialValue = TimeParams())
    val seedLimit by settings.newGroupDailyLimitMinutes.collectAsStateWithLifecycle(initialValue = 0)

    var showCreateDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知时 · 应用分组") },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                },
            )
        },
        floatingActionButton = {
            // 自律锁生效中（含配置加载期 fail-closed）时不可新建分组
            FloatingActionButton(onClick = {
                if (!lock.locked) {
                    newGroupName = ""
                    showCreateDialog = true
                }
            }) {
                Text("＋", fontSize = 24.sp)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (lock.config != null && lock.locked) {
                DisciplineLockBanner(lock.windowText)
                Spacer(Modifier.height(8.dp))
            }
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(groups, key = { it.id }) { group ->
                        GroupCard(
                            group = group,
                            memberCount = memberCount(group, assignments, apps),
                            onClick = { onOpenGroup(group.id) },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newGroupName.isNotBlank() && !lock.locked,
                    onClick = {
                        val name = newGroupName.trim()
                        showCreateDialog = false
                        if (name.isNotEmpty()) scope.launch {
                            repo.createGroup(name, seedTime = seedTime, seedDailyLimitMinutes = seedLimit)
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupCard(group: Group, memberCount: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                KindBadge(group.kind)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "$memberCount 个应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KindBadge(kind: GroupKind) {
    val label = when (kind) {
        GroupKind.DEFAULT -> "默认"
        GroupKind.BLACKLIST -> "黑名单"
        GroupKind.WHITELIST -> "白名单"
        GroupKind.CUSTOM -> "自定义"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = when (kind) {
            GroupKind.BLACKLIST -> MaterialTheme.colorScheme.errorContainer
            GroupKind.WHITELIST -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 成员数：默认组兜底所有未分配的用户应用；其余按分配键统计。 */
private fun memberCount(group: Group, assignments: Map<String, String>, apps: List<AppCatalog.AppEntry>): Int =
    when (group.kind) {
        GroupKind.DEFAULT -> apps.count {
            !it.isSystem && (assignments[it.packageName] == null || assignments[it.packageName] == GroupIds.DEFAULT)
        }
        else -> assignments.values.count { it == group.id }
    }

// endregion

// region 分组详情

@Composable
internal fun GroupDetailScreen(groupId: String, onBack: () -> Unit, onPickApps: () -> Unit) {
    val repo = AppGraph.groupRepository
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val assignments by repo.assignments.collectAsStateWithLifecycle(initialValue = emptyMap())
    val apps by AppGraph.appCatalog.apps.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(Unit) { AppGraph.appCatalog.ensureLoaded() }

    val group = groups.firstOrNull { it.id == groupId }
    if (groups.isNotEmpty() && group == null) {
        // 分组已被删除：返回列表
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (group == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    // 保护提醒：待确认开启的机制（确认才写入，取消则开关回弹）
    var pendingMechanism by remember { mutableStateOf<Mechanism?>(null) }
    val lock = rememberDisciplineLockUi()
    val members = membersOf(group, assignments, apps)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", fontSize = 28.sp)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (lock.config != null && lock.locked) {
                DisciplineLockBanner(lock.windowText)
                Spacer(Modifier.height(12.dp))
            }
            when (group.kind) {
                GroupKind.BLACKLIST -> SectionCard(
                    title = "平禁说明",
                    body = "黑名单组（平禁）：组内应用完全禁止使用，打开即弹「禁止使用」浮层。" +
                        "没有时间选择、会话、延时或冷却，每日限额固定为禁用（-1）。",
                )
                GroupKind.WHITELIST -> SectionCard(
                    title = "完全豁免",
                    body = "白名单组：组内应用完全不受任何管控，机制开关不适用且不可编辑。",
                )
                else -> {
                    Text("六项机制", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "本版本仅存储配置，行为将在后续版本生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Mechanism.entries.forEach { mechanism ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                mechanismLabel(mechanism),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Switch(
                                checked = group.config.mechanismEnabled(mechanism),
                                enabled = !lock.locked,
                                onCheckedChange = { on ->
                                    if (lock.locked) return@Switch
                                    if (on) {
                                        // 保护提醒：开启前确认影响范围
                                        pendingMechanism = mechanism
                                    } else {
                                        scope.launch {
                                            val next = group.config.mechanisms - mechanism
                                            repo.updateConfig(group.id, group.config.copy(mechanisms = next))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "每日限额：" + when {
                            group.config.dailyLimitMinutes < 0 -> "禁用（平禁）"
                            group.config.dailyLimitMinutes == 0 -> "不限"
                            else -> "${group.config.dailyLimitMinutes} 分钟"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "成员应用（${members.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickApps, enabled = !lock.locked) { Text("添加应用") }
            }
            Spacer(Modifier.height(4.dp))
            if (members.isEmpty()) {
                Text(
                    "暂无成员应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            members.forEach { app ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    app.icon?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    } ?: Box(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        enabled = !lock.locked,
                        onClick = { scope.launch { repo.assignApp(app.packageName, GroupIds.DEFAULT) } },
                    ) {
                        Text("✕", fontSize = 18.sp)
                    }
                }
            }

            if (!group.isBuiltIn) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !lock.locked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("删除分组", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除分组") },
            text = { Text("「${group.name}」将被删除，其中的应用将回归默认组。") },
            confirmButton = {
                TextButton(
                    enabled = !lock.locked,
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            repo.deleteGroup(group.id)
                            onBack()
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    // 保护提醒：为分组开启任一机制前的影响范围确认；默认组额外警示覆盖面
    pendingMechanism?.let { mechanism ->
        AlertDialog(
            onDismissRequest = { pendingMechanism = null },
            title = { Text("开启「${mechanismLabel(mechanism)}」") },
            text = {
                Column {
                    Text("开启「${mechanismLabel(mechanism)}」后，本组的 ${members.size} 个应用将受到该机制约束。")
                    if (group.kind == GroupKind.DEFAULT) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "注意：默认组覆盖所有未分组的用户应用（当前共 ${members.size} 个），请确认不会误拦。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingMechanism = null
                    scope.launch {
                        repo.updateConfig(
                            group.id,
                            group.config.copy(mechanisms = group.config.mechanisms + mechanism),
                        )
                    }
                }) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { pendingMechanism = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun mechanismLabel(mechanism: Mechanism): String = when (mechanism) {
    Mechanism.TIME_SELECTION -> "开屏时间选择"
    Mechanism.USAGE_TOAST -> "周期用量提示"
    Mechanism.TIME_REMINDER -> "剩余时间提醒"
    Mechanism.EXTENSION -> "延时使用"
    Mechanism.EXPIRY_COOLDOWN -> "到期冷却"
    Mechanism.ABANDON_COOLDOWN -> "放弃冷却"
}

/** 成员解析：分组内分配的应用 + 默认组兜底的未分配用户应用；目录缺失时以包名显示。 */
private fun membersOf(
    group: Group,
    assignments: Map<String, String>,
    apps: List<AppCatalog.AppEntry>,
): List<AppCatalog.AppEntry> {
    val byPackage = apps.associateBy { it.packageName }
    val packages = when (group.kind) {
        GroupKind.DEFAULT -> buildList {
            apps.filter { !it.isSystem && assignments[it.packageName] == null }
                .forEach { add(it.packageName) }
            assignments.filterValues { it == group.id }.keys.forEach(::add)
        }
        else -> assignments.filterValues { it == group.id }.keys.toList()
    }
    return packages
        .map { pkg -> byPackage[pkg] ?: AppCatalog.AppEntry(pkg, pkg, null, isSystem = false) }
        .sortedBy { it.label.lowercase() }
}

// endregion
