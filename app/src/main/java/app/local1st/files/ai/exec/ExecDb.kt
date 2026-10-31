package app.local1st.files.ai.exec

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.PlanKind
import app.local1st.files.ai.PlanRecord
import app.local1st.files.ai.PlanStatus
import app.local1st.files.ai.Risk

/** 动作日志行的状态。 */
internal object RowStatus {
    const val PENDING = "pending"
    const val DONE = "done"
    const val FAILED = "failed"
    const val UNDONE = "undone"
    /** 未勾选或被校验拦下，没有执行。 */
    const val SKIPPED = "skipped"
}

internal object Phase {
    const val EXEC = "exec"
    const val UNDO = "undo"
}

/** actions 表的一行（执行或撤销）。可变字段在执行过程中逐步回写。 */
internal class ActRow(
    var id: Long = 0,
    val planId: String,
    val seq: Int,
    val phase: String = Phase.EXEC,
    /** 撤销行指向被撤销的执行行。 */
    val refId: Long = 0,
    val actionId: Int,
    val type: ActionType,
    /** 原路径。 */
    var srcPath: String?,
    /** 新路径（MOVE/RENAME 的最终文件路径；MKDIR 的目录）。 */
    var destPath: String?,
    val destDir: String?,
    var destName: String?,
    var size: Long,
    var mtime: Long,
    var isDir: Boolean = false,
    var status: String,
    var error: String? = null,
    var trashId: String? = null,
    var trashPath: String? = null,
    /** MOVE 时顺带新建的目录（换行分隔），撤销时为空就放回收站。 */
    var madeDirs: String? = null,
    var preExisted: Boolean = false,
    var crossVolume: Boolean = false,
    /** 执行完成后目标的 mtime，撤销时据此判断文件有没有被改过。 */
    var destMtime: Long = 0,
    val reason: String = "",
    val confidence: Float = 1f,
    val risk: Risk = Risk.CONFIRM,
    val selected: Boolean = true,
    val flagged: Boolean = false,
)

internal class ExecDb(context: Context) : SQLiteOpenHelper(context, "ai_exec.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE plans(
                plan_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                instruction TEXT,
                created_at INTEGER NOT NULL,
                started_at INTEGER NOT NULL DEFAULT 0,
                finished_at INTEGER NOT NULL DEFAULT 0,
                undone_at INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                total INTEGER NOT NULL DEFAULT 0,
                done INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE actions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plan_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                phase TEXT NOT NULL,
                ref_id INTEGER NOT NULL DEFAULT 0,
                action_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                src_path TEXT,
                dest_path TEXT,
                dest_dir TEXT,
                dest_name TEXT,
                size INTEGER NOT NULL DEFAULT 0,
                mtime INTEGER NOT NULL DEFAULT 0,
                is_dir INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                error TEXT,
                trash_id TEXT,
                trash_path TEXT,
                made_dirs TEXT,
                pre_existed INTEGER NOT NULL DEFAULT 0,
                cross_volume INTEGER NOT NULL DEFAULT 0,
                dest_mtime INTEGER NOT NULL DEFAULT 0,
                reason TEXT,
                confidence REAL NOT NULL DEFAULT 1,
                risk TEXT,
                selected INTEGER NOT NULL DEFAULT 1,
                flagged INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE INDEX idx_actions_plan ON actions(plan_id, phase, seq)")
        db.execSQL("CREATE INDEX idx_plans_status ON plans(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------- plans ----------

    fun insertPlan(
        planId: String, kind: PlanKind, title: String, instruction: String?, createdAt: Long,
        total: Int, rows: List<ActRow>,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("plan_id", planId)
                put("kind", kind.name)
                put("title", title)
                put("instruction", instruction)
                put("created_at", createdAt)
                put("started_at", System.currentTimeMillis())
                put("status", PlanStatus.RUNNING.name)
                put("total", total)
            }
            db.insertOrThrow("plans", null, v)
            for (r in rows) r.id = db.insertOrThrow("actions", null, rowValues(r))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun planExists(planId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM plans WHERE plan_id=?", arrayOf(planId)).use { it.moveToFirst() }

    fun setPlanStatus(planId: String, status: PlanStatus, finished: Boolean = false, undone: Boolean = false) {
        val v = ContentValues().apply {
            put("status", status.name)
            if (finished) put("finished_at", System.currentTimeMillis())
            if (undone) put("undone_at", System.currentTimeMillis())
        }
        writableDatabase.update("plans", v, "plan_id=?", arrayOf(planId))
    }

    /** 按执行行重新统计 total/done/failed/bytes。 */
    fun refreshCounts(planId: String) {
        val db = writableDatabase
        db.rawQuery(
            """SELECT
                SUM(CASE WHEN status<>'${RowStatus.SKIPPED}' THEN 1 ELSE 0 END),
                SUM(CASE WHEN status IN ('${RowStatus.DONE}','${RowStatus.UNDONE}') THEN 1 ELSE 0 END),
                SUM(CASE WHEN status='${RowStatus.FAILED}' THEN 1 ELSE 0 END),
                SUM(CASE WHEN status IN ('${RowStatus.DONE}','${RowStatus.UNDONE}') THEN size ELSE 0 END)
               FROM actions WHERE plan_id=? AND phase='${Phase.EXEC}'""",
            arrayOf(planId),
        ).use { c ->
            if (c.moveToFirst()) {
                val v = ContentValues().apply {
                    put("total", c.getInt(0))
                    put("done", c.getInt(1))
                    put("failed", c.getInt(2))
                    put("bytes", c.getLong(3))
                }
                db.update("plans", v, "plan_id=?", arrayOf(planId))
            }
        }
    }

    fun plan(planId: String): PlanRecord? =
        readableDatabase.rawQuery("SELECT * FROM plans WHERE plan_id=?", arrayOf(planId)).use { c ->
            if (c.moveToFirst()) toRecord(c) else null
        }

    fun plans(status: PlanStatus? = null, limit: Int = 100): List<PlanRecord> {
        val sql = if (status == null) "SELECT * FROM plans ORDER BY created_at DESC, started_at DESC LIMIT ?"
        else "SELECT * FROM plans WHERE status=? ORDER BY created_at DESC LIMIT ?"
        val args = if (status == null) arrayOf(limit.toString()) else arrayOf(status.name, limit.toString())
        return readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<PlanRecord>()
            while (c.moveToNext()) out += toRecord(c)
            out
        }
    }

    private fun toRecord(c: Cursor) = PlanRecord(
        planId = c.str("plan_id")!!,
        kind = enumOr(c.str("kind"), PlanKind.MANUAL),
        title = c.str("title") ?: "",
        createdAt = c.long("created_at"),
        status = enumOr(c.str("status"), PlanStatus.FAILED),
        total = c.long("total").toInt(),
        done = c.long("done").toInt(),
        failed = c.long("failed").toInt(),
        bytes = c.long("bytes"),
    )

    // ---------- actions ----------

    fun insertRow(r: ActRow) {
        r.id = writableDatabase.insertOrThrow("actions", null, rowValues(r))
    }

    fun updateRow(r: ActRow) {
        val v = rowValues(r)
        writableDatabase.update("actions", v, "id=?", arrayOf(r.id.toString()))
    }

    fun rows(planId: String, phase: String = Phase.EXEC): List<ActRow> =
        readableDatabase.rawQuery(
            "SELECT * FROM actions WHERE plan_id=? AND phase=? ORDER BY seq ASC, id ASC",
            arrayOf(planId, phase),
        ).use { c ->
            val out = ArrayList<ActRow>()
            while (c.moveToNext()) out += toRow(c)
            out
        }

    fun nextUndoSeq(planId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(seq),0) FROM actions WHERE plan_id=? AND phase='${Phase.UNDO}'",
            arrayOf(planId),
        ).use { if (it.moveToFirst()) it.getInt(0) + 1 else 1 }

    private fun rowValues(r: ActRow) = ContentValues().apply {
        put("plan_id", r.planId)
        put("seq", r.seq)
        put("phase", r.phase)
        put("ref_id", r.refId)
        put("action_id", r.actionId)
        put("type", r.type.name)
        put("src_path", r.srcPath)
        put("dest_path", r.destPath)
        put("dest_dir", r.destDir)
        put("dest_name", r.destName)
        put("size", r.size)
        put("mtime", r.mtime)
        put("is_dir", if (r.isDir) 1 else 0)
        put("status", r.status)
        put("error", r.error)
        put("trash_id", r.trashId)
        put("trash_path", r.trashPath)
        put("made_dirs", r.madeDirs)
        put("pre_existed", if (r.preExisted) 1 else 0)
        put("cross_volume", if (r.crossVolume) 1 else 0)
        put("dest_mtime", r.destMtime)
        put("reason", r.reason)
        put("confidence", r.confidence.toDouble())
        put("risk", r.risk.name)
        put("selected", if (r.selected) 1 else 0)
        put("flagged", if (r.flagged) 1 else 0)
        put("updated_at", System.currentTimeMillis())
    }

    private fun toRow(c: Cursor) = ActRow(
        id = c.long("id"),
        planId = c.str("plan_id")!!,
        seq = c.long("seq").toInt(),
        phase = c.str("phase") ?: Phase.EXEC,
        refId = c.long("ref_id"),
        actionId = c.long("action_id").toInt(),
        type = enumOr(c.str("type"), ActionType.MOVE),
        srcPath = c.str("src_path"),
        destPath = c.str("dest_path"),
        destDir = c.str("dest_dir"),
        destName = c.str("dest_name"),
        size = c.long("size"),
        mtime = c.long("mtime"),
        isDir = c.long("is_dir") != 0L,
        status = c.str("status") ?: RowStatus.FAILED,
        error = c.str("error"),
        trashId = c.str("trash_id"),
        trashPath = c.str("trash_path"),
        madeDirs = c.str("made_dirs"),
        preExisted = c.long("pre_existed") != 0L,
        crossVolume = c.long("cross_volume") != 0L,
        destMtime = c.long("dest_mtime"),
        reason = c.str("reason") ?: "",
        confidence = c.getDouble(c.getColumnIndexOrThrow("confidence")).toFloat(),
        risk = enumOr(c.str("risk"), Risk.CONFIRM),
        selected = c.long("selected") != 0L,
        flagged = c.long("flagged") != 0L,
    )

    private fun Cursor.str(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    private fun Cursor.long(col: String): Long {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) 0L else getLong(i)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(def)
}
