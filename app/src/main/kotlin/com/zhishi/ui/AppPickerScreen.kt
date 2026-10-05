@file:OptIn(ExperimentalMaterial3Api::class)

package com.zhishi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhishi.AppGraph
import com.zhishi.data.GroupIds
import kotlinx.coroutines.launch

/**
 * 应用选择器：固定排除应用永不出现；默认隐藏系统应用（可切换）。
 * 点选即加入当前组（自动从原组移出），再次点选移回默认组。
 */
@Composable
internal fun AppPickerScreen(groupId: String, onBack: () -> Unit) {
    val catalog = AppGraph.appCatalog
    val repo = AppGraph.groupRepository
    val scope = rememberCoroutineScope()

    val apps by catalog.apps.collectAsStateWithLifecycle(initialValue = emptyList())
    val assignments by repo.assignments.collectAsStateWithLifecycle(initialValue = emptyMap())
    val groups by repo.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val showSystem by catalog.systemAppsVisible.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { catalog.ensureLoaded() }

    var query by remember { mutableStateOf("") }
    val groupNameById = groups.associate { it.id to it.name }

    val visibleApps = apps.filter { app ->
        (showSystem || !app.isSystem) &&
            (query.isBlank() || app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择应用 → ${groupNameById[groupId] ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", fontSize = 28.sp)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "显示系统应用",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showSystem,
                    onCheckedChange = { catalog.setSystemAppsVisible(it) },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visibleApps, key = { it.packageName }) { app ->
                    val currentGid = assignments[app.packageName] ?: GroupIds.DEFAULT
                    val inThisGroup = currentGid == groupId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    repo.assignApp(
                                        app.packageName,
                                        if (inThisGroup) GroupIds.DEFAULT else groupId,
                                    )
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        app.icon?.let {
                            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp))
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
                        if (!inThisGroup && currentGid != GroupIds.DEFAULT) {
                            Text(
                                "已属于 ${groupNameById[currentGid] ?: currentGid}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = inThisGroup,
                            onCheckedChange = { on ->
                                scope.launch {
                                    repo.assignApp(
                                        app.packageName,
                                        if (on) groupId else GroupIds.DEFAULT,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
