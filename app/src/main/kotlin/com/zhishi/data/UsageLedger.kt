package com.zhishi.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zhishi.core.machine.GuardState
import com.zhishi.core.machine.GuardStateData
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「应用 × 自然日」记账与守护状态持久化层（ticket 06 数据部分 / ticket 10 恢复数据源）。
 *
 * SQLite（`guard.db`，无 Room 约定），两表：
 * - `usage_day(package, day, used_millis)`：每日记账，`day` 为 ISO yyyy-MM-dd 本地自然日
 *   （[localDayIso] 生成，协调器在 `DayChanged` 时切换记账日）；
 * - `guard_state(package, kind, remaining_millis, cooldown_deadline_millis, extension_used, updated_at)`：
 *   每应用一行守护状态恢复数据（[GuardStateData]，`FlushState` 节流落盘写入）。
 *
 * ### GuardStateData ↔ guard_state 列映射
 * - `kind`                    ↔ [GuardStateData.kind]（[GuardState] 枚举名字符串；读回 valueOf，未知值按无记录忽略）；
 * - `remaining_millis`        ↔ [GuardStateData.remainingMillis]（可空：会话/宽限/延时剩余）；
 * - `cooldown_deadline_millis`↔ [GuardStateData.cooldownDeadlineMillis]（可空：冷却绝对截止 epoch millis）；
 * - `extension_used`          ↔ [GuardStateData.extensionUsed]（0/1）；
 * - `pending_kind` / `pending_remaining_millis` ↔ [GuardStateData.pendingCooldownKind] / [GuardStateData.pendingCooldownRemainingMillis]
 *   （可空：延时发起前被冻结的冷却，恢复后按剩余续接而非重置满额）；
 * - `session_selected_millis` ↔ [GuardStateData.sessionSelectedMillis]（可空：智能宽限期的计算基数）；
 * - `trial_paused_at_millis` ↔ [GuardStateData.trialPausedAtMillis]（可空：试探期退后台暂停时刻）；
 * - `updated_at`              ↔ 写入时刻 [System.currentTimeMillis]（跨日失效判定 + 诊断，不参与恢复）。
 *
 * ### 恢复语义（architecture.md「恢复语义」，由协调器重建状态机时执行，本层原样存取）
 * - `kind = RUNNING` 的记录恢复为 `GRACE`（等同退后台，宽限期从头计）；
 * - 冷却按 `cooldown_deadline_millis` 绝对截止恢复（停机时间计入）；
 * - 当日记账与延时已用标记完整恢复；
 * - 落盘于旧自然日的 `QUOTA_EXHAUSTED` 由协调器按 [GuardStateRecord.savedDay] 判定丢弃
 *   （重启跨零点不会再触发 `DayChanged`，不丢弃会把用户按昨日额度禁用一整天）。
 *
 * 全部 API 为 suspend 并切 [Dispatchers.IO]；不接协调器（落盘时机/恢复/切日由主控接线）。
 */
/** 守护状态恢复记录：[data] 为恢复数据本体，[savedDay] 为该行最后写入时的本地自然日（跨日失效判定用）。 */
data class GuardStateRecord(
    val data: GuardStateData,
    val savedDay: String,
)

class UsageLedger(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_USAGE_DAY)
        db.execSQL(SQL_CREATE_GUARD_STATE)
        db.execSQL(SQL_INDEX_USAGE_DAY)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2：guard_state 增加"延时发起前被冻结的冷却"两列（pending_kind / pending_remaining_millis）
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE guard_state ADD COLUMN pending_kind TEXT")
            db.execSQL("ALTER TABLE guard_state ADD COLUMN pending_remaining_millis INTEGER")
        }
        // v3：guard_state 增加 v1.1 智能宽限期基数（所选时长）与试探期中断保留时刻两列
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE guard_state ADD COLUMN session_selected_millis INTEGER")
            db.execSQL("ALTER TABLE guard_state ADD COLUMN trial_paused_at_millis INTEGER")
        }
    }

    // region 每日记账（usage_day）

    /** 某应用当日累计使用毫秒；无记录返回 0。 */
    suspend fun usedTodayMillis(packageName: String, day: String): Long = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT used_millis FROM usage_day WHERE package = ? AND day = ? LIMIT 1",
            arrayOf(packageName, day),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** 累加当日使用（upsert 累加，事务内 INSERT OR IGNORE + UPDATE，兼容 API 26 的旧 SQLite 无 upsert 语法）。 */
    suspend fun addUsage(packageName: String, day: String, deltaMillis: Long): Unit =
        withContext(Dispatchers.IO) {
            if (deltaMillis == 0L) return@withContext
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO usage_day(package, day, used_millis) VALUES (?, ?, 0)",
                    arrayOf(packageName, day),
                )
                db.execSQL(
                    "UPDATE usage_day SET used_millis = used_millis + ? WHERE package = ? AND day = ?",
                    arrayOf<Any>(deltaMillis, packageName, day),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

    /** 覆写当日累计（如恢复时以导出快照校正）。 */
    suspend fun setUsage(packageName: String, day: String, millis: Long): Unit =
        withContext(Dispatchers.IO) {
            writableDatabase.insertWithOnConflict(
                TABLE_USAGE_DAY,
                null,
                ContentValues().apply {
                    put(COL_PACKAGE, packageName)
                    put(COL_DAY, day)
                    put(COL_USED_MILLIS, millis)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            Unit
        }

    /** 某自然日所有应用的累计使用（当日用量汇总）。 */
    suspend fun dayTotals(day: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, Long>()
        readableDatabase.rawQuery(
            "SELECT package, used_millis FROM usage_day WHERE day = ?",
            arrayOf(day),
        ).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getLong(1)
        }
        result
    }

    // endregion

    // region 守护状态持久化（guard_state，ticket 10 恢复数据源）

    /** 保存/覆盖某应用的恢复数据（REPLACE 整行）。 */
    suspend fun saveGuardState(packageName: String, data: GuardStateData): Unit =
        withContext(Dispatchers.IO) {
            writableDatabase.insertWithOnConflict(
                TABLE_GUARD_STATE,
                null,
                ContentValues().apply {
                    put(COL_PACKAGE, packageName)
                    put(COL_KIND, data.kind.name)
                    put(COL_REMAINING, data.remainingMillis)
                    put(COL_COOLDOWN_DEADLINE, data.cooldownDeadlineMillis)
                    put(COL_EXTENSION_USED, if (data.extensionUsed) 1 else 0)
                    put(COL_PENDING_KIND, data.pendingCooldownKind?.name)
                    put(COL_PENDING_REMAINING, data.pendingCooldownRemainingMillis)
                    put(COL_SESSION_SELECTED, data.sessionSelectedMillis)
                    put(COL_TRIAL_PAUSED_AT, data.trialPausedAtMillis)
                    put(COL_UPDATED_AT, System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            Unit
        }

    /** 读取某应用的恢复数据；无记录或 kind 无法识别返回 null。 */
    suspend fun loadGuardState(packageName: String): GuardStateData? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT kind, remaining_millis, cooldown_deadline_millis, extension_used, pending_kind, pending_remaining_millis, " +
                "session_selected_millis, trial_paused_at_millis FROM guard_state WHERE package = ? LIMIT 1",
            arrayOf(packageName),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toGuardStateData() else null
        }
    }

    /** 读取全部应用的恢复记录（启动恢复遍历用；[GuardStateRecord.savedDay] 供协调器丢弃跨日失效的当日态）。 */
    suspend fun loadAllGuardStates(): Map<String, GuardStateRecord> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, GuardStateRecord>()
        readableDatabase.rawQuery(
            "SELECT package, kind, remaining_millis, cooldown_deadline_millis, extension_used, pending_kind, pending_remaining_millis, " +
                "session_selected_millis, trial_paused_at_millis, updated_at FROM guard_state",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val data = cursor.toGuardStateData() ?: continue
                val savedDay = localDayIso(cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)))
                result[cursor.getString(0)] = GuardStateRecord(data, savedDay)
            }
        }
        result
    }

    /** 清除某应用的恢复数据（如换组清状态后调用）。 */
    suspend fun clearGuardState(packageName: String): Unit = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_GUARD_STATE, "$COL_PACKAGE = ?", arrayOf(packageName))
        Unit
    }

    /** 清空全部恢复数据（如 DayChanged 后协调器判定全量失效时）。 */
    suspend fun clearAllGuardStates(): Unit = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_GUARD_STATE, null, null)
        Unit
    }

    // endregion

    /** 读行 → [GuardStateData]；kind 枚举名无法识别（前向兼容旧数据）返回 null。 */
    private fun Cursor.toGuardStateData(): GuardStateData? {
        val state = runCatching {
            GuardState.valueOf(getString(getColumnIndexOrThrow(COL_KIND)))
        }.getOrNull() ?: return null
        val remainingCol = getColumnIndexOrThrow(COL_REMAINING)
        val deadlineCol = getColumnIndexOrThrow(COL_COOLDOWN_DEADLINE)
        val extensionCol = getColumnIndexOrThrow(COL_EXTENSION_USED)
        val pendingKindCol = getColumnIndexOrThrow(COL_PENDING_KIND)
        val pendingRemainingCol = getColumnIndexOrThrow(COL_PENDING_REMAINING)
        val pendingKind = if (isNull(pendingKindCol)) null else runCatching {
            com.zhishi.core.model.CooldownKind.valueOf(getString(pendingKindCol))
        }.getOrNull()
        val sessionSelectedCol = getColumnIndexOrThrow(COL_SESSION_SELECTED)
        val trialPausedCol = getColumnIndexOrThrow(COL_TRIAL_PAUSED_AT)
        return GuardStateData(
            kind = state,
            remainingMillis = if (isNull(remainingCol)) null else getLong(remainingCol),
            cooldownDeadlineMillis = if (isNull(deadlineCol)) null else getLong(deadlineCol),
            extensionUsed = getInt(extensionCol) != 0,
            pendingCooldownKind = pendingKind,
            pendingCooldownRemainingMillis = if (isNull(pendingRemainingCol)) null else getLong(pendingRemainingCol),
            sessionSelectedMillis = if (isNull(sessionSelectedCol)) null else getLong(sessionSelectedCol),
            trialPausedAtMillis = if (isNull(trialPausedCol)) null else getLong(trialPausedCol),
        )
    }

    companion object {
        private const val DB_NAME = "guard.db"
        private const val DB_VERSION = 3

        private const val TABLE_USAGE_DAY = "usage_day"
        private const val TABLE_GUARD_STATE = "guard_state"
        private const val COL_PACKAGE = "package"
        private const val COL_DAY = "day"
        private const val COL_USED_MILLIS = "used_millis"
        private const val COL_KIND = "kind"
        private const val COL_REMAINING = "remaining_millis"
        private const val COL_COOLDOWN_DEADLINE = "cooldown_deadline_millis"
        private const val COL_EXTENSION_USED = "extension_used"
        private const val COL_PENDING_KIND = "pending_kind"
        private const val COL_PENDING_REMAINING = "pending_remaining_millis"
        private const val COL_SESSION_SELECTED = "session_selected_millis"
        private const val COL_TRIAL_PAUSED_AT = "trial_paused_at_millis"
        private const val COL_UPDATED_AT = "updated_at"

        private const val SQL_CREATE_USAGE_DAY =
            "CREATE TABLE usage_day(" +
                "package TEXT NOT NULL, " +
                "day TEXT NOT NULL, " +
                "used_millis INTEGER NOT NULL, " +
                "PRIMARY KEY(package, day))"

        private const val SQL_CREATE_GUARD_STATE =
            "CREATE TABLE guard_state(" +
                "package TEXT PRIMARY KEY, " +
                "kind TEXT NOT NULL, " +
                "remaining_millis INTEGER, " +
                "cooldown_deadline_millis INTEGER, " +
                "extension_used INTEGER NOT NULL DEFAULT 0, " +
                "pending_kind TEXT, " +
                "pending_remaining_millis INTEGER, " +
                "session_selected_millis INTEGER, " +
                "trial_paused_at_millis INTEGER, " +
                "updated_at INTEGER NOT NULL)"

        private const val SQL_INDEX_USAGE_DAY =
            "CREATE INDEX IF NOT EXISTS index_usage_day_day ON usage_day(day)"

        /** epoch millis → ISO yyyy-MM-dd 本地自然日（`usage_day.day` 列格式，协调器统一使用）。 */
        fun localDayIso(epochMillis: Long = System.currentTimeMillis()): String =
            Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
    }
}
