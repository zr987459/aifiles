package app.local1st.files.ai.exec

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.FileIndex
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanAction
import app.local1st.files.ai.PlanExecutor
import app.local1st.files.ai.PlanRecord
import app.local1st.files.ai.PlanStatus
import app.local1st.files.ai.ValidationIssue
import app.local1st.files.core.fs.TrashRecord
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * 计划执行器（M1）。
 *
 * - 日志：SQLite（[ExecDb]），每个动作先写 pending 再动文件，成功改 done，失败记原因。
 * - 执行在应用级协程里跑，返回的 Flow 只是观察进度：界面离开不会把执行打断一半。
 * - 同一时刻只跑一个计划（全局互斥），避免两个计划搬同一批文件。
 * - 删除只走 Graph.trash；跨卷移动 = 复制 → 校验大小 → 改名 → 原文件进回收站。
 */
class DefaultPlanExecutor(private val context: Context) : PlanExecutor {

    private val db by lazy { ExecDb(context) }
    private val fs: XFileSystem get() = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()

    /** 正在跑的计划 → 进度。同一计划重复调用 execute/resume/undo 时复用。 */
    private val active = HashMap<String, MutableStateFlow<ExecProgress>>()

    // =====================================================================================
    // validate
    // =====================================================================================

    override suspend fun validate(plan: Plan): Pair<Plan, List<ValidationIssue>> = withContext(Dispatchers.IO) {
        validateBlocking(plan)
    }

    private suspend fun validateBlocking(plan: Plan): Pair<Plan, List<ValidationIssue>> {
        val roots = volumeRoots()
        val issues = ArrayList<ValidationIssue>()
        val out = ArrayList<PlanAction>(plan.actions.size)
        /** 目录（小写）→ 已占用名字（小写），含本批已分配的名字。 */
        val taken = HashMap<String, MutableSet<String>>()
        fun takenIn(dir: String): MutableSet<String> = taken.getOrPut(dir.lowercase()) { listNamesLower(dir) }
        /** 跨卷移动需要的空间：目标卷 → 字节；以及对应的动作。 */
        val spaceNeed = HashMap<String, Long>()
        val spaceActions = HashMap<String, MutableList<Int>>()

        val selectedDirSources = plan.actions
            .filter { it.selected && it.type != ActionType.MKDIR }
            .mapNotNull { a -> PathGuard.normalize(a.srcPath)?.let { p -> a.actionId to p } }
            .filter { (_, p) -> fs.stat(XId.file(p))?.isDir == true }

        for (a in plan.actions) {
            if (!a.selected) {
                out += a
                continue
            }
            val blocking = ArrayList<String>()
            val notes = ArrayList<String>()
            var fixed = a

            // ---- 源
            var src: String? = null
            var srcEntry: XEntry? = null
            var srcVol: String? = null
            if (a.type != ActionType.MKDIR) {
                src = PathGuard.normalize(a.srcPath)
                when {
                    src == null -> blocking += "源路径无效（不能含 .. 或相对路径）"
                    PathGuard.isSystemPath(src) -> blocking += "系统分区不能操作"
                    else -> {
                        srcVol = PathGuard.volumeOf(src, roots)
                        if (srcVol == null) {
                            blocking += "不在已挂载的存储卷内"
                        } else {
                            PathGuard.protectedReason(src, srcVol, asSource = true)?.let { blocking += it }
                            if (blocking.isEmpty() && PathGuard.hasSymlink(src, srcVol)) blocking += "路径含符号链接"
                        }
                    }
                }
                if (blocking.isEmpty() && src != null && srcVol != null) {
                    srcEntry = fs.stat(XId.file(src))
                    if (srcEntry == null) {
                        blocking += "源文件不存在"
                    } else {
                        if (!srcEntry.isDir && a.size > 0 && srcEntry.size >= 0 && srcEntry.size != a.size) {
                            blocking += "文件大小已变化"
                        }
                        val indexed = runCatching { AiGraph.index.getByPath(src) }.getOrNull()
                        if (indexed != null && !srcEntry.isDir && srcEntry.mtime > 0 && indexed.mtime > 0 &&
                            abs(indexed.mtime - srcEntry.mtime) > MTIME_TOLERANCE_MS
                        ) {
                            blocking += "文件修改时间已变化，请重新扫描"
                        }
                        if (inGitRepo(src, srcVol)) blocking += "位于 Git 仓库内，不动"
                        if (srcEntry.isDir) {
                            val scan = scanTree(srcEntry)
                            scan.problem?.let { blocking += it }
                            if (fixed.size <= 0) fixed = fixed.copy(size = scan.bytes)
                        } else if (fixed.size <= 0 && srcEntry.size > 0) {
                            fixed = fixed.copy(size = srcEntry.size)
                        }
                        val outer = selectedDirSources.firstOrNull { (id, p) ->
                            id != a.actionId && p != src && PathGuard.isInside(src, p)
                        }
                        if (outer != null) blocking += "已包含在本批另一个动作的文件夹里"
                        if (a.type == ActionType.TRASH && blocking.isEmpty() &&
                            !runCatching { Graph.trash.canTrash(srcEntry) }.getOrDefault(false)
                        ) {
                            blocking += "这个位置不能放入回收站"
                        }
                    }
                }
            }

            // ---- 目标
            when (a.type) {
                ActionType.MOVE -> {
                    val destDir = PathGuard.normalize(a.destDir)
                    val destVol = destDir?.let { PathGuard.volumeOf(it, roots) }
                    when {
                        destDir == null -> blocking += "目标目录无效"
                        PathGuard.isSystemPath(destDir) -> blocking += "不能移到系统分区"
                        destVol == null -> blocking += "目标不在已挂载的存储卷内"
                        else -> {
                            PathGuard.protectedReason(destDir, destVol, asSource = false)?.let { blocking += it }
                            if (PathGuard.hasSymlink(destDir, destVol)) blocking += "目标路径含符号链接"
                            val existing = fs.stat(XId.file(destDir))
                            if (existing != null && !existing.isDir) blocking += "目标位置是一个文件，不是文件夹"
                            if (src != null && srcEntry?.isDir == true && PathGuard.isInside(destDir, src)) {
                                blocking += "不能把文件夹移进它自己里面"
                            }
                        }
                    }
                    if (src != null && destDir != null && blocking.isEmpty()) {
                        val wanted = a.destName?.takeIf { it.isNotBlank() } ?: PathGuard.nameOf(src)
                        if (!PathGuard.isSafeName(wanted)) {
                            blocking += "目标文件名无效"
                        } else if (srcEntry?.isDir != true && PathGuard.isProtectedFileName(wanted)) {
                            blocking += "不能改成受保护的扩展名"
                        } else if (PathGuard.parentOf(src) == destDir && wanted == PathGuard.nameOf(src)) {
                            blocking += "已经在目标位置"
                        } else {
                            val set = takenIn(destDir)
                            val name = PathGuard.pickName(wanted, srcEntry?.isDir == true, set)
                            set += name.lowercase()
                            if (name != wanted) notes += "目标重名，改名为「$name」"
                            fixed = fixed.copy(destDir = destDir, destName = name)
                            val sv = srcVol
                            if (sv != null && destVol != null && sv != destVol) {
                                notes += "跨存储卷：复制后校验，再把原文件放入回收站"
                                spaceNeed[destVol] = (spaceNeed[destVol] ?: 0L) + fixed.size
                                spaceActions.getOrPut(destVol) { ArrayList() } += a.actionId
                            }
                        }
                    }
                }
                ActionType.RENAME -> {
                    if (src != null && blocking.isEmpty()) {
                        val wanted = a.destName
                        val dir = PathGuard.parentOf(src)
                        when {
                            !PathGuard.isSafeName(wanted) -> blocking += "新文件名无效"
                            srcEntry?.isDir != true && PathGuard.isProtectedFileName(wanted!!) ->
                                blocking += "不能改成受保护的扩展名"
                            wanted == PathGuard.nameOf(src) -> blocking += "新名字和原名相同"
                            else -> {
                                val set = takenIn(dir)
                                val caseOnly = wanted!!.equals(PathGuard.nameOf(src), ignoreCase = true)
                                val name = if (caseOnly) wanted else PathGuard.pickName(wanted, srcEntry?.isDir == true, set)
                                set += name.lowercase()
                                if (name != wanted) notes += "重名，改名为「$name」"
                                fixed = fixed.copy(destName = name, destDir = null)
                            }
                        }
                    }
                }
                ActionType.MKDIR -> {
                    val dir = PathGuard.normalize(a.destDir)
                    val vol = dir?.let { PathGuard.volumeOf(it, roots) }
                    when {
                        dir == null -> blocking += "目录路径无效"
                        PathGuard.isSystemPath(dir) -> blocking += "不能在系统分区建目录"
                        vol == null -> blocking += "不在已挂载的存储卷内"
                        else -> {
                            PathGuard.protectedReason(dir, vol, asSource = false)?.let { blocking += it }
                            if (PathGuard.hasSymlink(dir, vol)) blocking += "路径含符号链接"
                            if (blocking.isEmpty()) {
                                val e = fs.stat(XId.file(dir))
                                if (e != null && !e.isDir) blocking += "同名文件已存在"
                                else if (e != null) notes += "文件夹已存在，将直接使用"
                                takenIn(PathGuard.parentOf(dir)) += PathGuard.nameOf(dir).lowercase()
                                fixed = fixed.copy(destDir = dir)
                            }
                        }
                    }
                }
                ActionType.TRASH -> Unit
            }

            blocking.forEach { issues += ValidationIssue(a.actionId, it, blocking = true) }
            notes.forEach { issues += ValidationIssue(a.actionId, it, blocking = false) }
            if (blocking.isNotEmpty()) {
                fixed = fixed.copy(flagged = true, flagReason = blocking.joinToString("；"))
            }
            out += fixed
        }

        // ---- 空间：只有跨卷移动需要额外空间
        for ((vol, need) in spaceNeed) {
            val free = runCatching { StatFs(vol).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (need + SPACE_MARGIN > free) {
                val ids = spaceActions[vol].orEmpty().toSet()
                val msg = "目标存储空间不足（需要 ${fmt(need)}，可用 ${fmt(free)}）"
                for (i in out.indices) {
                    val a = out[i]
                    if (a.actionId in ids) {
                        issues += ValidationIssue(a.actionId, msg, blocking = true)
                        out[i] = a.copy(flagged = true, flagReason = listOfNotNull(a.flagReason, msg).joinToString("；"))
                    }
                }
            }
        }
        return plan.copy(actions = out) to issues
    }

    // =====================================================================================
    // execute / resume / undo：在应用级协程里跑，Flow 只负责观察
    // =====================================================================================

    override fun execute(plan: Plan): Flow<ExecProgress> = launchJob(plan.planId) { state ->
        if (db.planExists(plan.planId)) {
            val rec = db.plan(plan.planId)
            val msg = if (rec?.status == PlanStatus.RUNNING) "计划上次被中断，请用继续执行" else "这个计划已经执行过"
            state.value = ExecProgress(plan.planId, rec?.done ?: 0, rec?.total ?: 0, finished = true, error = msg)
            return@launchJob
        }
        val (fixed, issues) = validateBlocking(plan)
        val blockingById = issues.filter { it.blocking }.groupBy { it.actionId }
        val roots = volumeRoots()
        val rows = fixed.actions.mapIndexed { i, a -> toRow(fixed.planId, i, a, blockingById[a.actionId], roots) }
        val total = rows.count { it.status != RowStatus.SKIPPED }
        db.insertPlan(fixed.planId, fixed.kind, fixed.title, fixed.instruction, fixed.createdAt, total, rows)
        runRows(fixed.planId, rows, state)
    }

    override fun resume(planId: String): Flow<ExecProgress> = launchJob(planId) { state ->
        val rec = db.plan(planId)
        if (rec == null || rec.status != PlanStatus.RUNNING) {
            state.value = ExecProgress(planId, rec?.done ?: 0, rec?.total ?: 0, finished = true,
                error = if (rec == null) "找不到这个计划" else "这个计划没有中断，不需要继续")
            return@launchJob
        }
        val rows = db.rows(planId)
        reconcile(rows)
        runRows(planId, rows, state)
    }

    override fun undo(planId: String): Flow<ExecProgress> = launchJob(planId) { state ->
        val rec = db.plan(planId)
        if (rec == null) {
            state.value = ExecProgress(planId, 0, 0, finished = true, error = "找不到这个计划")
            return@launchJob
        }
        if (rec.status == PlanStatus.UNDONE) {
            state.value = ExecProgress(planId, 0, 0, finished = true, error = "这个计划已经撤销过")
            return@launchJob
        }
        val rows = db.rows(planId)
        // 中断的计划先对账，把实际已完成的动作认出来，再撤销
        if (rec.status == PlanStatus.RUNNING) {
            reconcile(rows)
            rows.filter { it.status == RowStatus.PENDING }.forEach {
                it.status = RowStatus.SKIPPED
                it.error = "中断后未执行"
                db.updateRow(it)
            }
            db.refreshCounts(planId)
        }
        runUndo(planId, rows, state)
    }

    override suspend fun interrupted(): List<PlanRecord> = withContext(Dispatchers.IO) {
        db.plans(PlanStatus.RUNNING, 1000)
            .filter { !isActive(it.planId) }
            .map { it.copy(status = PlanStatus.INTERRUPTED) }
    }

    override suspend fun history(limit: Int): List<PlanRecord> = withContext(Dispatchers.IO) {
        db.plans(null, limit).map {
            if (it.status == PlanStatus.RUNNING && !isActive(it.planId)) it.copy(status = PlanStatus.INTERRUPTED) else it
        }
    }

    override suspend fun actionsOf(planId: String): List<PlanAction> = withContext(Dispatchers.IO) {
        db.rows(planId).map { r ->
            val failed = r.status == RowStatus.FAILED || (r.status == RowStatus.SKIPPED && r.selected)
            PlanAction(
                actionId = r.actionId,
                type = r.type,
                srcPath = r.srcPath,
                destDir = when (r.type) {
                    ActionType.MOVE, ActionType.MKDIR -> r.destDir
                    else -> null
                },
                destName = when (r.type) {
                    ActionType.MOVE, ActionType.RENAME -> r.destName
                    else -> null
                },
                size = r.size,
                reason = r.reason,
                confidence = r.confidence,
                risk = r.risk,
                selected = r.selected,
                flagged = failed || r.flagged,
                flagReason = r.error?.let { statusZh(r.status) + "：" + it } ?: statusZh(r.status),
            )
        }
    }

    private fun isActive(planId: String): Boolean = synchronized(active) { planId in active }

    private fun launchJob(
        planId: String,
        block: suspend (MutableStateFlow<ExecProgress>) -> Unit,
    ): Flow<ExecProgress> {
        val state: MutableStateFlow<ExecProgress>
        synchronized(active) {
            val running = active[planId]
            if (running != null) {
                return flowOf(ExecProgress(planId, running.value.done, running.value.total, finished = true,
                    error = "这个计划正在处理中"))
            }
            state = MutableStateFlow(ExecProgress(planId, 0, 0, current = "准备中…"))
            active[planId] = state
        }
        scope.launch {
            try {
                runLock.withLock { block(state) }
                if (!state.value.finished) state.value = state.value.copy(finished = true)
            } catch (e: Throwable) {
                Log.w(TAG, "plan $planId failed", e)
                state.value = state.value.copy(finished = true, error = e.message ?: e.javaClass.simpleName)
            } finally {
                synchronized(active) { active.remove(planId) }
            }
        }
        return state.transformWhile { p ->
            emit(p)
            !p.finished
        }
    }

    // =====================================================================================
    // 执行
    // =====================================================================================

    private fun toRow(planId: String, seq: Int, a: PlanAction, blocking: List<ValidationIssue>?, roots: List<String>): ActRow {
        val src = PathGuard.normalize(a.srcPath)
        val entry = src?.let { fs.stat(XId.file(it)) }
        val dest = when (a.type) {
            ActionType.MOVE -> {
                val d = PathGuard.normalize(a.destDir)
                val n = a.destName ?: src?.let { PathGuard.nameOf(it) }
                if (d != null && n != null) PathGuard.join(d, n) else null
            }
            ActionType.RENAME -> if (src != null && a.destName != null) PathGuard.join(PathGuard.parentOf(src), a.destName) else null
            ActionType.MKDIR -> PathGuard.normalize(a.destDir)
            ActionType.TRASH -> null
        }
        val cross = a.type == ActionType.MOVE && src != null && dest != null &&
            PathGuard.volumeOf(src, roots) != PathGuard.volumeOf(dest, roots)
        val skipped = !a.selected || !blocking.isNullOrEmpty()
        return ActRow(
            planId = planId,
            seq = seq,
            actionId = a.actionId,
            type = a.type,
            srcPath = src ?: a.srcPath,
            destPath = dest,
            destDir = if (a.type == ActionType.RENAME) src?.let { PathGuard.parentOf(it) } else PathGuard.normalize(a.destDir),
            destName = when (a.type) {
                ActionType.MOVE -> dest?.let { PathGuard.nameOf(it) }
                ActionType.RENAME -> a.destName
                else -> null
            },
            size = if (entry != null && !entry.isDir && entry.size >= 0) entry.size else a.size,
            mtime = entry?.mtime ?: 0L,
            isDir = entry?.isDir ?: (a.type == ActionType.MKDIR),
            status = if (skipped) RowStatus.SKIPPED else RowStatus.PENDING,
            error = when {
                !a.selected -> "未勾选"
                !blocking.isNullOrEmpty() -> blocking.joinToString("；") { it.message }
                else -> null
            },
            crossVolume = cross,
            reason = a.reason,
            confidence = a.confidence,
            risk = a.risk,
            selected = a.selected,
            flagged = a.flagged,
        )
    }

    private suspend fun runRows(planId: String, rows: List<ActRow>, state: MutableStateFlow<ExecProgress>) {
        val counted = rows.filter { it.status != RowStatus.SKIPPED }
        val total = counted.size
        var done = counted.count { it.status == RowStatus.DONE || it.status == RowStatus.UNDONE }
        var failed = counted.count { it.status == RowStatus.FAILED }
        state.value = ExecProgress(planId, done, total, failed = failed)
        db.setPlanStatus(planId, PlanStatus.RUNNING)
        for (r in rows) {
            if (r.status != RowStatus.PENDING) continue
            val label = r.srcPath?.let { PathGuard.nameOf(it) } ?: r.destPath?.let { PathGuard.nameOf(it) } ?: ""
            state.value = ExecProgress(planId, done, total, current = label, failed = failed)
            try {
                runRow(r)
                r.status = RowStatus.DONE
                r.error = null
                done++
            } catch (e: Exception) {
                Log.w(TAG, "action ${r.actionId} failed", e)
                r.status = RowStatus.FAILED
                r.error = e.message ?: e.javaClass.simpleName
                failed++
            }
            db.updateRow(r)
            db.refreshCounts(planId)
            state.value = ExecProgress(planId, done, total, current = label, failed = failed)
        }
        val status = when {
            failed == 0 -> PlanStatus.DONE
            done == 0 -> PlanStatus.FAILED
            else -> PlanStatus.PARTIAL
        }
        db.refreshCounts(planId)
        db.setPlanStatus(planId, status, finished = true)
        state.value = ExecProgress(planId, done, total, finished = true, failed = failed,
            error = if (failed > 0) "$failed 项失败" else null)
    }

    private suspend fun runRow(r: ActRow) {
        when (r.type) {
            ActionType.MKDIR -> {
                val dir = r.destPath ?: throw IOException("目录路径无效")
                val existing = fs.stat(XId.file(dir))
                if (existing != null) {
                    if (!existing.isDir) throw IOException("同名文件已存在")
                    r.preExisted = true
                    return
                }
                val made = ensureDirs(dir)
                r.madeDirs = made.joinToString("\n").ifEmpty { null }
                index { onAdded(dir) }
            }
            ActionType.RENAME -> {
                val src = r.srcPath ?: throw IOException("源路径无效")
                val entry = requireUnchanged(r, src)
                var name = r.destName ?: throw IOException("新文件名无效")
                val dir = PathGuard.parentOf(src)
                if (!name.equals(entry.name, ignoreCase = true) && fs.stat(XId.file(PathGuard.join(dir, name))) != null) {
                    name = PathGuard.pickName(name, entry.isDir, listNamesLower(dir))
                    r.destName = name
                }
                r.destPath = PathGuard.join(dir, name)
                db.updateRow(r)
                val renamed = fs.rename(entry, name)
                r.destMtime = renamed.mtime
                index { onMoved(src, r.destPath!!) }
            }
            ActionType.MOVE -> {
                val src = r.srcPath ?: throw IOException("源路径无效")
                val destDir = r.destDir ?: throw IOException("目标目录无效")
                val entry = requireUnchanged(r, src)
                val made = ensureDirs(destDir)
                if (made.isNotEmpty()) r.madeDirs = made.joinToString("\n")
                var name = r.destName ?: entry.name
                if (fs.stat(XId.file(PathGuard.join(destDir, name))) != null) {
                    name = PathGuard.pickName(name, entry.isDir, listNamesLower(destDir))
                }
                r.destName = name
                r.destPath = PathGuard.join(destDir, name)
                db.updateRow(r) // 先把最终目标和新建目录落盘，崩溃后能对账
                val dest = r.destPath!!
                val renamed = !r.crossVolume && File(src).renameTo(File(dest))
                if (!renamed) {
                    copyThenTrash(r, entry, destDir, name)
                }
                r.destMtime = fs.stat(XId.file(dest))?.mtime ?: 0L
                index { onMoved(src, dest) }
            }
            ActionType.TRASH -> {
                val src = r.srcPath ?: throw IOException("源路径无效")
                val entry = requireUnchanged(r, src)
                val rec = Graph.trash.trash(entry)
                r.trashId = rec.id
                r.trashPath = rec.storedPath
                index { onRemoved(src) }
            }
        }
    }

    /** 源存在、大小和 mtime 与校验时一致，返回最新条目。 */
    private fun requireUnchanged(r: ActRow, src: String): XEntry {
        val e = fs.stat(XId.file(src)) ?: throw IOException("源文件不存在")
        if (!e.isDir) {
            if (r.size > 0 && e.size >= 0 && e.size != r.size) throw IOException("文件大小已变化")
            if (r.mtime > 0 && e.mtime > 0 && abs(e.mtime - r.mtime) > MTIME_TOLERANCE_MS) throw IOException("文件已被修改")
        }
        return e
    }

    /**
     * 跨卷（或同卷 rename 失败时）：复制到临时名 → 校验大小 → 改成正式名 → 原文件进回收站。
     * 回收站失败时把复制品也放回收站，保持“原样未动”。
     */
    private fun copyThenTrash(r: ActRow, src: XEntry, destDir: String, name: String) {
        val parent = fs.stat(XId.file(destDir)) ?: throw IOException("目标目录不存在")
        val tmpName = tmpNameFor(r)
        fs.stat(XId.file(PathGuard.join(destDir, tmpName)))?.let { stale -> runCatching { Graph.trash.trash(stale) } }
        val expected = if (src.isDir) scanTree(src).bytes else src.size
        val copied: Long
        try {
            copied = copyTree(src, parent, tmpName)
        } catch (e: Exception) {
            fs.stat(XId.file(PathGuard.join(destDir, tmpName)))?.let { runCatching { fs.delete(it) } }
            throw IOException("复制失败：${e.message}", e)
        }
        val tmp = fs.stat(XId.file(PathGuard.join(destDir, tmpName)))
            ?: throw IOException("复制后找不到临时文件")
        val actual = if (tmp.isDir) scanTree(tmp).bytes else tmp.size
        if (copied != expected || (expected >= 0 && actual != expected)) {
            runCatching { fs.delete(tmp) }
            throw IOException("复制后大小不一致（应为 $expected，实际 $actual）")
        }
        val finalEntry = fs.rename(tmp, name)
        try {
            val rec = Graph.trash.trash(src)
            r.trashId = rec.id
            r.trashPath = rec.storedPath
        } catch (e: Exception) {
            runCatching { Graph.trash.trash(finalEntry) }
            throw IOException("已复制，但原文件无法放入回收站，已撤回复制品：${e.message}", e)
        }
    }

    private fun tmpNameFor(r: ActRow) = ".aimove-${r.planId.hashCode().toUInt()}-${r.id}.part"

    /** 复制文件或整棵目录，返回复制的字节数。 */
    private fun copyTree(src: XEntry, destParent: XEntry, name: String): Long {
        if (src.isDir) {
            val dir = fs.mkdir(destParent, name)
            var sum = 0L
            for (child in fs.list(src)) sum += copyTree(child, dir, child.name)
            return sum
        }
        var n = 0L
        fs.openIn(src).use { input ->
            fs.openOut(destParent, name).use { output ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val k = input.read(buf)
                    if (k < 0) break
                    output.write(buf, 0, k)
                    n += k
                }
            }
        }
        return n
    }

    /** 逐层建目录，返回新建的目录（从浅到深）。 */
    private fun ensureDirs(dir: String): List<String> {
        val existing = fs.stat(XId.file(dir))
        if (existing != null) {
            if (!existing.isDir) throw IOException("${PathGuard.nameOf(dir)} 是文件，不是文件夹")
            return emptyList()
        }
        val roots = volumeRoots()
        val vol = PathGuard.volumeOf(dir, roots) ?: throw IOException("不在已挂载的存储卷内")
        val made = ArrayList<String>()
        var cur = vol
        var curEntry = fs.stat(XId.file(vol)) ?: throw IOException("存储卷不可用")
        for (seg in PathGuard.relative(dir, vol).split('/')) {
            if (seg.isEmpty()) continue
            val next = PathGuard.join(cur, seg)
            val e = fs.stat(XId.file(next))
            curEntry = when {
                e == null -> fs.mkdir(curEntry, seg).also { made += next }
                e.isDir -> e
                else -> throw IOException("$seg 是文件，不是文件夹")
            }
            cur = next
        }
        return made
    }

    // =====================================================================================
    // 中断对账
    // =====================================================================================

    /**
     * 把 pending 行和磁盘对一遍：源在目标无=未做；源无目标在=已做；都在=目标是半成品（跨卷且大小一致时只差把源放回收站）。
     */
    private suspend fun reconcile(rows: List<ActRow>) {
        for (r in rows) {
            if (r.status != RowStatus.PENDING) continue
            try {
                reconcileRow(r)
            } catch (e: Exception) {
                r.status = RowStatus.FAILED
                r.error = "对账失败：${e.message}"
            }
            db.updateRow(r)
        }
        rows.firstOrNull()?.let { db.refreshCounts(it.planId) }
    }

    private suspend fun reconcileRow(r: ActRow) {
        val src = r.srcPath
        val dest = r.destPath
        when (r.type) {
            ActionType.MKDIR -> {
                val e = dest?.let { fs.stat(XId.file(it)) }
                if (e != null && e.isDir) markDone(r, "中断前已完成")
            }
            ActionType.RENAME, ActionType.MOVE -> {
                if (src == null || dest == null) return
                // 跨卷复制的临时文件一律放回收站，重新开始
                if (r.type == ActionType.MOVE && r.destDir != null) {
                    fs.stat(XId.file(PathGuard.join(r.destDir, tmpNameFor(r))))?.let { Graph.trash.trash(it) }
                }
                val s = fs.stat(XId.file(src))
                val d = fs.stat(XId.file(dest))
                when {
                    s != null && d == null -> Unit // 未做
                    s == null && d != null -> {
                        r.destMtime = d.mtime
                        if (r.crossVolume && r.trashId == null) findTrashRecordFor(src)?.let {
                            r.trashId = it.id
                            r.trashPath = it.storedPath
                        }
                        markDone(r, "中断前已完成")
                        index { onMoved(src, dest) }
                    }
                    s != null && d != null -> {
                        val complete = r.type == ActionType.MOVE && sizeOf(s) == sizeOf(d) && sizeOf(s) >= 0
                        if (complete && (r.crossVolume || s.isDir == d.isDir)) {
                            // 复制已校验并改了正式名，只差把源放进回收站
                            val rec = Graph.trash.trash(s)
                            r.trashId = rec.id
                            r.trashPath = rec.storedPath
                            r.crossVolume = true
                            r.destMtime = d.mtime
                            markDone(r, "中断前已复制，补完放入回收站")
                            index { onMoved(src, dest) }
                        } else if (r.type == ActionType.MOVE) {
                            Graph.trash.trash(d) // 删目标半成品（进回收站），再重做
                        } else {
                            r.status = RowStatus.FAILED
                            r.error = "原文件和新名字的文件都在，未处理"
                        }
                    }
                    else -> {
                        r.status = RowStatus.FAILED
                        r.error = "原文件和目标都不存在"
                    }
                }
            }
            ActionType.TRASH -> {
                if (src == null) return
                if (fs.stat(XId.file(src)) != null) return
                findTrashRecordFor(src)?.let {
                    r.trashId = it.id
                    r.trashPath = it.storedPath
                }
                markDone(r, if (r.trashId != null) "中断前已放入回收站" else "中断前已移除（没找到回收站记录）")
                index { onRemoved(src) }
            }
        }
    }

    private fun markDone(r: ActRow, note: String) {
        r.status = RowStatus.DONE
        r.error = note
    }

    private fun sizeOf(e: XEntry): Long = if (e.isDir) scanTree(e).bytes else e.size

    /** 原路径对应的、最近一次放入回收站的记录。 */
    private fun findTrashRecordFor(path: String): TrashRecord? {
        val vol = PathGuard.volumeOf(path, volumeRoots()) ?: return null
        val rel = PathGuard.relative(path, vol)
        return runCatching {
            Graph.trash.list()
                .filter { !it.orphan && it.volumeRoot.trimEnd('/') == vol && it.originalRelativePath == rel }
                .maxByOrNull { it.deletedAt }
        }.getOrNull()
    }

    // =====================================================================================
    // 撤销
    // =====================================================================================

    private suspend fun runUndo(planId: String, rows: List<ActRow>, state: MutableStateFlow<ExecProgress>) {
        val todo = rows.filter { it.status == RowStatus.DONE }.sortedByDescending { it.seq }
        val total = todo.size
        var done = 0
        var failed = 0
        var seq = db.nextUndoSeq(planId)
        val madeDirs = LinkedHashSet<String>()
        state.value = ExecProgress(planId, 0, total, current = "准备撤销…")
        for (r in todo) {
            val label = (r.destPath ?: r.srcPath)?.let { PathGuard.nameOf(it) } ?: ""
            state.value = ExecProgress(planId, done, total, current = label, failed = failed)
            val u = ActRow(
                planId = planId, seq = seq++, phase = Phase.UNDO, refId = r.id, actionId = r.actionId, type = r.type,
                srcPath = r.destPath, destPath = r.srcPath, destDir = r.srcPath?.let { PathGuard.parentOf(it) },
                destName = r.srcPath?.let { PathGuard.nameOf(it) }, size = r.size, mtime = r.destMtime, isDir = r.isDir,
                status = RowStatus.PENDING, trashId = r.trashId, trashPath = r.trashPath, crossVolume = r.crossVolume,
            )
            db.insertRow(u)
            try {
                val result = undoRow(r, u)
                if (result == null) {
                    u.status = RowStatus.DONE
                    r.status = RowStatus.UNDONE
                    r.madeDirs?.split('\n')?.filter { it.isNotBlank() }?.let(madeDirs::addAll)
                    done++
                } else {
                    u.status = RowStatus.SKIPPED
                    u.error = result
                    failed++
                }
            } catch (e: Exception) {
                Log.w(TAG, "undo ${r.actionId} failed", e)
                u.status = RowStatus.FAILED
                u.error = e.message ?: e.javaClass.simpleName
                failed++
            }
            db.updateRow(u)
            db.updateRow(r)
            state.value = ExecProgress(planId, done, total, current = label, failed = failed)
        }
        // 执行时顺带新建的目录：空了就放回收站（从深到浅）
        for (dir in madeDirs.sortedByDescending { it.count { c -> c == '/' } }) {
            runCatching {
                val e = fs.stat(XId.file(dir)) ?: return@runCatching
                if (e.isDir && fs.list(e).isEmpty()) {
                    Graph.trash.trash(e)
                    index { onRemoved(dir) }
                }
            }
        }
        db.refreshCounts(planId)
        db.setPlanStatus(planId, if (failed == 0) PlanStatus.UNDONE else PlanStatus.PARTIAL, undone = true)
        state.value = ExecProgress(planId, done, total, finished = true, failed = failed,
            error = if (failed > 0) "$failed 项没能撤销" else null)
    }

    /** 撤销一个已完成的动作。返回 null 表示成功，返回字符串表示跳过的原因。 */
    private suspend fun undoRow(r: ActRow, u: ActRow): String? {
        when (r.type) {
            ActionType.MKDIR -> {
                if (r.preExisted) return null
                val dir = r.destPath ?: return null
                val e = fs.stat(XId.file(dir)) ?: return null
                if (!e.isDir) return "目标已不是文件夹"
                if (fs.list(e).isNotEmpty()) return "文件夹里已有文件，保留"
                Graph.trash.trash(e)
                u.trashId = null
                index { onRemoved(dir) }
                return null
            }
            ActionType.RENAME, ActionType.MOVE -> {
                val src = r.srcPath ?: return "缺少原路径"
                val dest = r.destPath ?: return "缺少新路径"
                val d = fs.stat(XId.file(dest)) ?: return "文件已不在新位置"
                if (changedSince(r, d)) return "文件在执行后被修改过，跳过"
                val backDir = PathGuard.parentOf(src)
                val wantName = PathGuard.nameOf(src)
                if (r.type == ActionType.MOVE && r.trashId != null) {
                    // 跨卷：从回收站还原原文件，再把复制品放回收站
                    val rec = trashRecord(r)
                    if (rec != null) {
                        val parent = Graph.trash.plannedParent(rec).absolutePath
                        val name = PathGuard.pickName(rec.name, rec.storedIsDir, listNamesLower(parent))
                        val restored = Graph.trash.restore(rec, name)
                        u.destPath = restored.absolutePath
                        Graph.trash.trash(d)
                        index { onRemoved(dest) }
                        index { onAdded(restored.absolutePath) }
                        return null
                    }
                    // 回收站里已没有原文件：把复制品搬回去
                }
                ensureDirs(backDir)
                val takenBack = listNamesLower(backDir)
                if (PathGuard.parentOf(dest) == backDir) takenBack -= d.name.lowercase()
                val name = PathGuard.pickName(wantName, d.isDir, takenBack)
                val back = PathGuard.join(backDir, name)
                u.destPath = back
                db.updateRow(u)
                if (r.type == ActionType.RENAME || PathGuard.parentOf(dest) == backDir) {
                    fs.rename(d, name)
                } else if (!File(dest).renameTo(File(back))) {
                    val tmpRow = ActRow(planId = r.planId, seq = 0, actionId = r.actionId, type = ActionType.MOVE,
                        srcPath = dest, destPath = back, destDir = backDir, destName = name,
                        size = d.size, mtime = d.mtime, status = RowStatus.PENDING).also { it.id = u.id }
                    copyThenTrash(tmpRow, d, backDir, name)
                }
                index { onMoved(dest, back) }
                return null
            }
            ActionType.TRASH -> {
                val rec = trashRecord(r) ?: return "回收站里找不到这个文件（可能已清空）"
                val parent = Graph.trash.plannedParent(rec).absolutePath
                val name = PathGuard.pickName(rec.name, rec.storedIsDir, listNamesLower(parent))
                val restored = Graph.trash.restore(rec, name)
                u.destPath = restored.absolutePath
                index { onAdded(restored.absolutePath) }
                return null
            }
        }
    }

    private fun changedSince(r: ActRow, d: XEntry): Boolean {
        if (d.isDir) return false
        if (r.size > 0 && d.size >= 0 && d.size != r.size) return true
        if (r.destMtime > 0 && d.mtime > 0 && abs(d.mtime - r.destMtime) > MTIME_TOLERANCE_MS) return true
        return false
    }

    private fun trashRecord(r: ActRow): TrashRecord? {
        val path = r.trashPath
        val byPath = path?.let { runCatching { Graph.trash.lookup(it) }.getOrNull() }
        if (byPath != null) return byPath
        val id = r.trashId ?: return null
        return runCatching { Graph.trash.list().firstOrNull { it.id == id } }.getOrNull()
    }

    // =====================================================================================
    // 工具
    // =====================================================================================

    private class TreeScan(val bytes: Long, val problem: String?)

    /** 统计目录大小，同时找出 .git、受保护文件、符号链接；超过上限直接拒绝。 */
    private fun scanTree(root: XEntry): TreeScan {
        var bytes = 0L
        var count = 0
        val stack = ArrayDeque<XEntry>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try {
                fs.list(dir)
            } catch (e: IOException) {
                return TreeScan(bytes, "文件夹里有读不了的内容")
            }
            for (c in children) {
                if (++count > MAX_TREE_NODES) return TreeScan(bytes, "文件夹内文件太多（超过 $MAX_TREE_NODES 个）")
                val lower = c.name.lowercase()
                if (lower == ".git") return TreeScan(bytes, "文件夹包含 Git 仓库，不动")
                if (lower == ".xfiles-trash") return TreeScan(bytes, "文件夹包含回收站")
                val p = c.localPath ?: c.path
                if (runCatching { java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(p)) }.getOrDefault(false)) {
                    return TreeScan(bytes, "文件夹里有符号链接")
                }
                if (c.isDir) {
                    if (lower in setOf("databases", "database", "shared_prefs", "backup", "backups")) {
                        return TreeScan(bytes, "文件夹包含应用数据库或备份")
                    }
                    stack.addLast(c)
                } else {
                    if (PathGuard.isProtectedFileName(c.name)) return TreeScan(bytes, "文件夹包含数据库、密钥或备份文件（${c.name}）")
                    if (c.size > 0) bytes += c.size
                }
            }
        }
        return TreeScan(bytes, null)
    }

    /** 从父目录往上到卷根，任何一层有 .git 就算在仓库里。 */
    private fun inGitRepo(path: String, volumeRoot: String): Boolean {
        var cur = PathGuard.parentOf(path)
        while (PathGuard.isInside(cur, volumeRoot)) {
            if (fs.stat(XId.file(PathGuard.join(cur, ".git"))) != null) return true
            if (cur == volumeRoot.trimEnd('/')) break
            cur = PathGuard.parentOf(cur)
        }
        return false
    }

    private fun listNamesLower(dir: String): MutableSet<String> {
        val e = fs.stat(XId.file(dir)) ?: return HashSet()
        if (!e.isDir) return HashSet()
        return runCatching { fs.list(e).mapTo(HashSet()) { it.name.lowercase() } }.getOrDefault(HashSet())
    }

    private fun volumeRoots(): List<String> {
        val roots = runCatching { Graph.freshMountedVolumePaths() }.getOrDefault(emptyList())
            .ifEmpty { runCatching { Graph.mountedVolumePaths() }.getOrDefault(emptyList()) }
        if (roots.isNotEmpty()) return roots.map { it.trimEnd('/') }
        @Suppress("DEPRECATION")
        return listOf(Environment.getExternalStorageDirectory().absolutePath.trimEnd('/'))
    }

    /** 通知索引；索引失败不影响文件操作。 */
    private suspend fun index(block: suspend FileIndex.() -> Unit) {
        try {
            AiGraph.index.block()
        } catch (e: Exception) {
            Log.w(TAG, "index update failed", e)
        }
    }

    private fun statusZh(s: String) = when (s) {
        RowStatus.PENDING -> "未完成"
        RowStatus.DONE -> "已完成"
        RowStatus.FAILED -> "失败"
        RowStatus.UNDONE -> "已撤销"
        RowStatus.SKIPPED -> "未执行"
        else -> s
    }

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private companion object {
        const val TAG = "AiPlanExecutor"
        const val MTIME_TOLERANCE_MS = 2_000L
        const val SPACE_MARGIN = 50L * 1024 * 1024
        const val MAX_TREE_NODES = 100_000
        const val COPY_BUFFER = 256 * 1024
    }
}
