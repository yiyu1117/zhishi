package com.zhishi.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 可启动应用目录：供应用选择器与浮层应用名展示。
 * 固定排除集（本 APP、系统桌面、系统 UI、输入法）永不出现在选择器中，也不可被拦截。
 */
class AppCatalog(private val appContext: Context) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: ImageBitmap?,
        val isSystem: Boolean,
    )

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())

    /** 全量可启动应用（已剔除固定排除），按名称排序。 */
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _systemAppsVisible = MutableStateFlow(false)

    /** 选择器默认隐藏系统应用（spec 41），开关只影响 UI 过滤。 */
    val systemAppsVisible: StateFlow<Boolean> = _systemAppsVisible.asStateFlow()

    fun setSystemAppsVisible(visible: Boolean) {
        _systemAppsVisible.value = visible
    }

    private val loaded = MutableStateFlow(false)
    private val loadMutex = Mutex()
    private val nameIndex = HashMap<String, String>()

    suspend fun ensureLoaded() {
        if (loaded.value) return
        loadMutex.withLock {
            if (loaded.value) return
            withContext(Dispatchers.IO) { loadApps() }
            loaded.value = true
        }
    }

    /** 浮层显示用应用名；未加载或未知包名时回退为包名本身。 */
    fun appName(packageName: String): String = synchronized(nameIndex) {
        nameIndex[packageName] ?: packageName
    }

    /** 用户应用包名集合（默认组成员的候选范围，系统应用默认不在其中）。 */
    fun userPackages(): Set<String> = _apps.value
        .filter { !it.isSystem }
        .map { it.packageName }
        .toSet()

    private fun loadApps() {
        val pm = appContext.packageManager
        val fixed = loadFixedExclusions(pm)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg in fixed) return@mapNotNull null
                val flags = resolveInfo.activityInfo.applicationInfo.flags
                val isSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                    flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                runCatching {
                    AppEntry(
                        packageName = pkg,
                        label = resolveInfo.loadLabel(pm).toString(),
                        icon = resolveInfo.loadIcon(pm).toImageBitmap(),
                        isSystem = isSystem,
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
        synchronized(nameIndex) {
            nameIndex.clear()
            entries.forEach { nameIndex[it.packageName] = it.label }
        }
        _apps.value = entries
    }

    /**
     * 固定排除（CONTEXT.md）：本 APP、系统桌面（CATEGORY_HOME）、系统 UI、输入法。
     * 被排除的应用不出现在选择器中，也不可加入任何分组（因此永远不会被拦截）。
     */
    private fun loadFixedExclusions(pm: PackageManager): Set<String> {
        val excluded = mutableSetOf(appContext.packageName, SYSTEM_UI_PACKAGE)
        runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_ALL,
            ).forEach { excluded.add(it.activityInfo.packageName) }
        }
        runCatching {
            pm.queryIntentServices(Intent(IME_SERVICE_ACTION), 0)
                .forEach { excluded.add(it.serviceInfo.packageName) }
        }
        return excluded
    }

    private fun Drawable.toImageBitmap(): ImageBitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: 48
        val h = intrinsicHeight.takeIf { it > 0 } ?: 48
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, w, h)
        draw(canvas)
        return bitmap.asImageBitmap()
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val IME_SERVICE_ACTION = "android.view.InputMethod"
    }
}
