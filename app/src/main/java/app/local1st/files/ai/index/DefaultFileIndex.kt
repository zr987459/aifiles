package app.local1st.files.ai.index

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.webkit.MimeTypeMap
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.Category
import app.local1st.files.ai.CategoryStat
import app.local1st.files.ai.Classification
import app.local1st.files.ai.FileIndex
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IndexStats
import app.local1st.files.ai.IndexedFile
import app.local1st.files.ai.Sort
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XFileSystem
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件索引（M2 / P1 提速）。
 *
 * 扫描：普通目录用 java.io.File.list + 一次 lstat（nio）读元数据；只有 File 读不了的目录
 * （Android/data 下，或 list() 返回 null）才走 Graph.fsRegistry 的特权通道（Shizuku）。
 * 每个卷的顶层目录分给 4 个协程并行，库里旧记录一次性读进内存比对，变化的才写，
 * 每 2000 条一个事务；第一遍只写元数据和分类，不读文件内容、不算哈希。
 * 所有数据库与文件 I/O 都在 Dispatchers.IO。
 */
class DefaultFileIndex(private val context: Context) : FileIndex {

    private val helper = IndexDb(context.applicationContext ?: context)
    private val db: SQLiteDatabase by lazy { helper.writableDatabase }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _stats = MutableStateFlow(IndexStats())
    override val stats: StateFlow<IndexStats> = _stats

    private val scanLock = Any()
    @Volatile
    private var scanJob: Job? = null

    /** 特权通道 list 用的线程（带超时，卡住的线程被丢弃不影响扫描）。 */
    private val privPool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "ai-index-priv").apply { isDaemon = true }
    }

    /** 串行化批量写（SQLite 本身也会串行，这里保证一个事务里只有一批）。 */
    private val writeLock = Any()

    init {
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching {
                // 先把上次扫描时间放出去（首页据此判断要不要首扫），再查分类计数
                val last = prefs.getLong(KEY_LAST_SCAN, 0L)
                _stats.update { it.copy(lastScanAt = last) }
                publishStats(scanning = false, progress = "", lastScanAt = last)
                startScan(false) // 30 分钟内扫过会自己跳过
            }.onFailure { Log.w(TAG, "index init failed", it) }
        }
    }

    // ================================================================== 扫描

    /**
     * 同一时间只跑一个扫描。full=false（自动/增量）：上次扫描结束不到 30 分钟且库里有数据时直接跳过；
     * full=true（手动“重新扫描”）：总是执行，并重写全部记录。
     */
    override fun startScan(full: Boolean) {
        synchronized(scanLock) {
            if (scanJob?.isActive == true) return
            scanJob = Graph.appScope.launch(Dispatchers.IO) {
                if (!full && recentlyScanned()) return@launch
                val job = coroutineContext[Job]
                var message = ""
                try {
                    message = runScan(full) { job?.isActive != false }
                } catch (t: Throwable) {
                    Log.w(TAG, "scan failed", t)
                    message = "扫描出错：${t.message ?: t.javaClass.simpleName}"
                } finally {
                    // 无论成功、出错还是取消，都结束 scanning 并记下时间，避免首页反复触发
                    val now = System.currentTimeMillis()
                    runCatching { prefs.edit().putLong(KEY_LAST_SCAN, now).apply() }
                    runCatching { publishStats(scanning = false, progress = message, lastScanAt = now) }
                        .onFailure { _stats.update { it.copy(scanning = false, scanProgress = message, lastScanAt = now) } }
                }
            }
        }
    }

    private fun recentlyScanned(): Boolean {
        val last = prefs.getLong(KEY_LAST_SCAN, 0L)
        if (last <= 0L || System.currentTimeMillis() - last >= AUTO_SCAN_INTERVAL_MS) return false
        val hasRows = runCatching {
            db.rawQuery("SELECT 1 FROM files LIMIT 1", null).use { it.moveToFirst() }
        }.getOrDefault(false)
        if (hasRows) _stats.update { it.copy(lastScanAt = last, scanning = false) }
        return hasRows
    }

    private class OldRow(val id: Long, val size: Long, val mtime: Long, val manual: Boolean) {
        @Volatile var seen = false
    }

    private class OldDir(val mtime: Long, val empty: Boolean) {
        @Volatile var seen = false
    }

    private class FileRow(
        val path: String, val parent: String, val name: String, val ext: String,
        val size: Long, val mtime: Long, val mime: String?,
        /** 库里已有的行；0 表示新文件。 */
        val oldId: Long,
        /** null：手动分类，保留原分类只更新元数据。 */
        val cls: Classification?,
    )

    private class DirRow(val path: String, val mtime: Long, val empty: Boolean)

    /** 一次扫描（或一次 onAdded 目录）的共享状态。 */
    private class Session(
        val fs: XFileSystem,
        val full: Boolean,
        val alive: () -> Boolean,
        val files: Map<String, OldRow>,
        val dirs: Map<String, OldDir>,
    ) {
        val counter = AtomicInteger()
        val bytes = AtomicLong()
        @Volatile var current = ""
        /** 列不出 / 超时的目录：其下旧记录保留。 */
        val failed = ConcurrentLinkedQueue<String>()
        /** 特权通道总预算截止时间（uptime ms）。 */
        @Volatile var privDeadline = Long.MAX_VALUE
        val privTimeouts = AtomicInteger()
    }

    /** 每个协程自己的写缓冲。 */
    private class Buf {
        val files = ArrayList<FileRow>(BATCH)
        val dirs = ArrayList<DirRow>()
        val size get() = files.size + dirs.size
    }

    /** 返回结束时要显示的进度文字（正常完成为空）。阻塞部分都在 IO 线程。 */
    private suspend fun runScan(full: Boolean, alive: () -> Boolean): String {
        val started = System.currentTimeMillis()
        val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
        val volumes = runCatching { Graph.roots.currentMountedVolumes() }.getOrDefault(emptyList())
        _stats.update { it.copy(scanning = true, scanProgress = "准备扫描…") }
        val s = Session(fs, full, alive, loadFiles(null), loadDirs(null))
        s.privDeadline = SystemClock.uptimeMillis() + PRIV_BUDGET_MS

        // 进度估算：上次总文件数（或库里行数）；第一次扫描用 StatFs 已用空间
        val estFiles = maxOf(prefs.getInt(KEY_LAST_TOTAL, 0), s.files.size)
        val estBytes = if (estFiles > 0) 0L else volumes.sumOf { v ->
            runCatching { StatFs(v.path).let { it.totalBytes - it.availableBytes } }.getOrDefault(0L)
        }

        val tasks = ConcurrentLinkedQueue<Pair<String, Long>>()
        val rootBuf = Buf()
        for (v in volumes) {
            if (!alive()) break
            val root = v.path.trimEnd('/').ifEmpty { "/" }
            tasks.addAll(processDir(s, root, File(root).lastModified(), rootBuf, isVolumeRoot = true))
        }
        flush(rootBuf)

        coroutineScope {
            val ticker = launch {
                var n = 0
                while (isActive) {
                    delay(STATS_INTERVAL_MS)
                    val text = progressText(s, estFiles, estBytes)
                    if (++n % 5 == 0) publishStats(scanning = true, progress = text)
                    else _stats.update { it.copy(scanning = true, scanProgress = text) }
                }
            }
            val workers = List(PARALLEL) {
                launch(Dispatchers.IO) {
                    val buf = Buf()
                    try {
                        while (alive()) {
                            val (dir, mtime) = tasks.poll() ?: break
                            walkTree(s, dir, mtime, buf)
                        }
                    } finally {
                        runCatching { flush(buf) }
                    }
                }
            }
            workers.forEach { it.join() }
            ticker.cancel()
        }

        if (!alive()) return "扫描已取消"
        _stats.update { it.copy(scanning = true, scanProgress = "99% · 整理索引…") }
        deleteUnseen(s)
        prefs.edit().putInt(KEY_LAST_TOTAL, s.counter.get()).apply()
        Log.i(TAG, "scan done: ${s.counter.get()} files in ${System.currentTimeMillis() - started} ms, skipped ${s.failed.size} dirs")
        return ""
    }

    private fun progressText(s: Session, estFiles: Int, estBytes: Long): String {
        val n = s.counter.get()
        val pct = when {
            estFiles > 0 -> (n * 100L / estFiles).toInt()
            estBytes > 0 -> (s.bytes.get() * 100 / estBytes).toInt()
            else -> -1
        }.coerceAtMost(99)
        val head = if (pct >= 0) "$pct% · " else ""
        return "${head}已扫描 $n 个文件 · ${s.current}"
    }

    /** 深度优先走完一棵子树（显式栈），阻塞执行。 */
    private fun walkTree(s: Session, rootDir: String, rootMtime: Long, buf: Buf) {
        val stack = ArrayDeque<Pair<String, Long>>()
        stack.addLast(rootDir to rootMtime)
        while (stack.isNotEmpty()) {
            if (!s.alive()) return
            val (dir, mtime) = stack.removeLast()
            if (dir.count { it == '/' } > MAX_DEPTH) continue
            for (sub in processDir(s, dir, mtime, buf, isVolumeRoot = false)) stack.addLast(sub)
        }
    }

    /**
     * 处理一个目录：写它的文件与目录行，返回要继续走的子目录 (path, mtime)。
     * 增量：目录 mtime 没变时，库里已有的文件只标记“见过”，不再 stat、不重写。
     * File 读不了时走特权通道（子树整体用特权通道走完，返回空）。
     */
    private fun processDir(s: Session, dir: String, mtime: Long, buf: Buf, isVolumeRoot: Boolean): List<Pair<String, Long>> {
        if (isRestricted(dir)) {
            walkPrivileged(s, dir, mtime, buf)
            return emptyList()
        }
        val names = File(dir).list()
        if (names == null) {
            walkPrivileged(s, dir, mtime, buf)
            return emptyList()
        }
        if (isVolumeRoot && names.isEmpty()) {
            s.failed += dir // 卷根空：多半是没权限，保留旧记录
            return emptyList()
        }
        s.current = dir
        val old = s.dirs[dir]
        val unchanged = !s.full && old != null && mtime > 0 && old.mtime == mtime
        val subdirs = ArrayList<Pair<String, Long>>()
        var empty = true
        for (name in names) {
            if (name != ".nomedia") empty = false
            val child = if (dir == "/") "/$name" else "$dir/$name"
            if (unchanged) {
                val known = s.files[child]
                if (known != null) {
                    known.seen = true
                    s.counter.incrementAndGet()
                    s.bytes.addAndGet(known.size)
                    continue
                }
            }
            val attrs = try {
                Files.readAttributes(Paths.get(child), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (t: Throwable) {
                null
            } ?: continue
            if (attrs.isSymbolicLink) continue
            if (attrs.isDirectory) {
                if (!IndexRules.skipDir(child, name)) subdirs += child to attrs.lastModifiedTime().toMillis()
            } else if (attrs.isRegularFile) {
                handleFile(s, dir, child, name, attrs.size(), attrs.lastModifiedTime().toMillis(), null, buf)
            }
        }
        handleDir(s, dir, mtime, empty, buf)
        return subdirs
    }

    /** fs.list 带超时：超时 / 出错返回 null。卡住的线程被中断后由线程池回收。 */
    private fun listWithTimeout(s: Session, entry: XEntry): List<XEntry>? {
        val fut = privPool.submit(Callable { s.fs.list(entry) })
        return try {
            fut.get(PRIV_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fut.cancel(true)
            s.privTimeouts.incrementAndGet()
            Log.w(TAG, "privileged list timed out: ${entry.path}")
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** 特权通道（Shizuku/root）走一棵子树：单目录 5 秒超时跳过，总预算用完后剩下的保留旧记录。 */
    private fun walkPrivileged(s: Session, rootDir: String, rootMtime: Long, buf: Buf) {
        val stack = ArrayDeque<XEntry>()
        stack.addLast(XEntry(id = XId.file(rootDir), name = rootDir.substringAfterLast('/'), isDir = true, mtime = rootMtime))
        while (stack.isNotEmpty()) {
            if (!s.alive()) return
            val d = stack.removeLast()
            val dir = d.path.trimEnd('/').ifEmpty { "/" }
            if (dir.count { it == '/' } > MAX_DEPTH) continue
            if (SystemClock.uptimeMillis() > s.privDeadline || s.privTimeouts.get() >= MAX_PRIV_TIMEOUTS) {
                // 特权通道太慢或不可用：剩下的目录保留旧记录，下次再扫
                s.failed += dir
                continue
            }
            val children = listWithTimeout(s, d)
            if (children == null || (children.isEmpty() && isRestricted(dir))) {
                // 列不出 / 超时，或受限目录列出空（多半是没授权）：保留旧记录，不算空目录
                s.failed += dir
                continue
            }
            s.current = dir
            var empty = true
            for (c in children) {
                if (c.name != ".nomedia") empty = false
                if (c.isDir) {
                    if (!IndexRules.skipDir(c.path, c.name)) stack.addLast(c)
                } else {
                    handleFile(s, dir, c.path, c.name, maxOf(0L, c.size), c.mtime, c.mime, buf)
                }
            }
            handleDir(s, dir, d.mtime, empty, buf)
        }
    }

    private fun handleFile(s: Session, parent: String, path: String, name: String, size: Long, mtime: Long, mime0: String?, buf: Buf) {
        val ext = extOf(name)
        if (IndexRules.skipFile(parent, name, ext)) return
        s.counter.incrementAndGet()
        s.bytes.addAndGet(size)
        val old = s.files[path]
        if (old != null) {
            old.seen = true
            if (!s.full && old.size == size && old.mtime == mtime) return
        }
        val mime = mimeOf(ext, mime0)
        val cls = if (old?.manual == true) null else classify(path, name, ext, mime, size)
        buf.files += FileRow(path, parent, name, ext, size, mtime, mime, old?.id ?: 0L, cls)
        if (buf.size >= BATCH) flush(buf)
    }

    private fun handleDir(s: Session, dir: String, mtime: Long, empty: Boolean, buf: Buf) {
        val old = s.dirs[dir]
        if (old != null) {
            old.seen = true
            if (old.mtime == mtime && old.empty == empty) return
        }
        buf.dirs += DirRow(dir, mtime, empty)
        if (buf.size >= BATCH) flush(buf)
    }

    /** 把一批行写进库（一个事务）。 */
    private fun flush(buf: Buf) {
        if (buf.size == 0) return
        synchronized(writeLock) {
            db.inTx { d ->
                val insFile = d.compileStatement(
                    "INSERT OR IGNORE INTO files(path, parent, name, ext, size, mtime, mime, category, sub, source, tags, manual) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,0)"
                )
                val updFull = d.compileStatement(
                    "UPDATE files SET size=?, mtime=?, mime=?, category=?, sub=?, source=?, tags=?, manual=0, " +
                        "quick_hash=NULL, full_hash=NULL, full_hash_mtime=0 WHERE id=?"
                )
                val updMeta = d.compileStatement(
                    "UPDATE files SET size=?, mtime=?, mime=?, quick_hash=NULL, full_hash=NULL, full_hash_mtime=0 WHERE id=?"
                )
                val delFts = d.compileStatement("DELETE FROM files_fts WHERE docid=?")
                val insFts = d.compileStatement("INSERT INTO files_fts(docid, name, tags) VALUES (?,?,?)")
                val upDir = d.compileStatement("INSERT OR REPLACE INTO dirs(path, mtime, empty) VALUES (?,?,?)")
                try {
                    for (r in buf.files) {
                        try {
                            val c = r.cls
                            if (r.oldId == 0L) {
                                if (c == null) continue
                                insFile.clearBindings()
                                insFile.bindString(1, r.path)
                                insFile.bindString(2, r.parent)
                                insFile.bindString(3, r.name)
                                insFile.bindString(4, r.ext)
                                insFile.bindLong(5, r.size)
                                insFile.bindLong(6, r.mtime)
                                bindOpt(insFile, 7, r.mime)
                                insFile.bindString(8, c.category.name)
                                bindOpt(insFile, 9, c.subCategory)
                                bindOpt(insFile, 10, c.sourceApp)
                                insFile.bindString(11, joinTags(c.tags))
                                val id = insFile.executeInsert()
                                if (id > 0) {
                                    insFts.clearBindings()
                                    insFts.bindLong(1, id)
                                    insFts.bindString(2, ftsName(r.name))
                                    insFts.bindString(3, ftsTags(c.tags))
                                    insFts.executeInsert()
                                }
                            } else if (c != null) {
                                updFull.clearBindings()
                                updFull.bindLong(1, r.size)
                                updFull.bindLong(2, r.mtime)
                                bindOpt(updFull, 3, r.mime)
                                updFull.bindString(4, c.category.name)
                                bindOpt(updFull, 5, c.subCategory)
                                bindOpt(updFull, 6, c.sourceApp)
                                updFull.bindString(7, joinTags(c.tags))
                                updFull.bindLong(8, r.oldId)
                                updFull.executeUpdateDelete()
                                delFts.bindLong(1, r.oldId)
                                delFts.executeUpdateDelete()
                                insFts.clearBindings()
                                insFts.bindLong(1, r.oldId)
                                insFts.bindString(2, ftsName(r.name))
                                insFts.bindString(3, ftsTags(c.tags))
                                insFts.executeInsert()
                            } else {
                                updMeta.clearBindings()
                                updMeta.bindLong(1, r.size)
                                updMeta.bindLong(2, r.mtime)
                                bindOpt(updMeta, 3, r.mime)
                                updMeta.bindLong(4, r.oldId)
                                updMeta.executeUpdateDelete()
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "write ${r.path} failed", t)
                        }
                    }
                    for (r in buf.dirs) {
                        upDir.bindString(1, r.path)
                        upDir.bindLong(2, r.mtime)
                        upDir.bindLong(3, if (r.empty) 1 else 0)
                        upDir.executeInsert()
                    }
                } finally {
                    insFile.close(); updFull.close(); updMeta.close(); delFts.close(); insFts.close(); upDir.close()
                }
            }
        }
        buf.files.clear()
        buf.dirs.clear()
    }

    /** 扫描结束：删掉这次没见到、也不在“列不出”目录下的记录。 */
    private fun deleteUnseen(s: Session) {
        val failed = s.failed.toList()
        fun keep(path: String) = failed.any { f -> path == f || path.startsWith("$f/") }
        val fileIds = s.files.values.filter { !it.seen }.map { it.id }.toHashSet()
        if (failed.isNotEmpty() && fileIds.isNotEmpty()) {
            for ((p, r) in s.files) if (!r.seen && keep(p)) fileIds.remove(r.id)
        }
        val dirPaths = s.dirs.filter { (p, d) -> !d.seen && !keep(p) }.keys.toList()
        if (fileIds.isEmpty() && dirPaths.isEmpty()) return
        synchronized(writeLock) {
            db.inTx { d ->
                for (chunk in fileIds.chunked(CHUNK)) {
                    val inList = chunk.joinToString(",")
                    d.execSQL("DELETE FROM files WHERE id IN ($inList)")
                    d.execSQL("DELETE FROM files_fts WHERE docid IN ($inList)")
                }
                for (chunk in dirPaths.chunked(CHUNK)) {
                    d.execSQL("DELETE FROM dirs WHERE path IN (" + chunk.joinToString(",") { "?" } + ")", chunk.toTypedArray<Any>())
                }
            }
        }
    }

    /** 一次性把旧记录读进内存（[under] 不为空时只读该目录下的）。 */
    private fun loadFiles(under: String?): Map<String, OldRow> {
        val out = ConcurrentHashMap<String, OldRow>()
        val (sql, args) = if (under == null) {
            "SELECT path, id, size, mtime, manual FROM files" to null
        } else {
            "SELECT path, id, size, mtime, manual FROM files WHERE path > ? AND path < ?" to arrayOf("$under/", "${under}0")
        }
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = OldRow(c.getLong(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0)
        }
        return out
    }

    private fun loadDirs(under: String?): Map<String, OldDir> {
        val out = ConcurrentHashMap<String, OldDir>()
        val (sql, args) = if (under == null) {
            "SELECT path, mtime, empty FROM dirs" to null
        } else {
            "SELECT path, mtime, empty FROM dirs WHERE path = ? OR (path > ? AND path < ?)" to arrayOf(under, "$under/", "${under}0")
        }
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = OldDir(c.getLong(1), c.getInt(2) != 0)
        }
        return out
    }

    private fun classify(path: String, name: String, ext: String, mime: String?, size: Long): Classification =
        try {
            AiGraph.classifier.classifyFast(path, name, ext, mime, size)
        } catch (t: Throwable) {
            Classification(Category.OTHER)
        }

    private fun mimeOf(ext: String, mime: String?): String? =
        mime ?: if (ext.isEmpty()) null else runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()

    // ================================================================== 统计

    private fun publishStats(scanning: Boolean, progress: String, lastScanAt: Long? = null) {
        val byCat = LinkedHashMap<Category, CategoryStat>()
        var total = 0
        var bytes = 0L
        runCatching {
            db.rawQuery("SELECT category, COUNT(*), COALESCE(SUM(size), 0) FROM files GROUP BY category", null).use { c ->
                while (c.moveToNext()) {
                    val cat = parseCategory(c.getString(0))
                    val n = c.getInt(1)
                    val b = c.getLong(2)
                    val prev = byCat[cat]
                    byCat[cat] = if (prev == null) CategoryStat(n, b) else CategoryStat(prev.count + n, prev.bytes + b)
                    total += n
                    bytes += b
                }
            }
        }
        _stats.update {
            it.copy(
                totalFiles = total,
                totalBytes = bytes,
                byCategory = byCat,
                lastScanAt = lastScanAt ?: it.lastScanAt,
                scanning = scanning,
                scanProgress = progress,
            )
        }
    }

    private fun refreshStatsLater() {
        val st = _stats.value
        // 扫描中由 ticker 刷新，不在这里插队
        if (!st.scanning) publishStats(false, st.scanProgress)
    }

    // ================================================================== 哈希（按需）

    /**
     * 快速哈希（大小 + 头尾 64KB 的 SHA-1），按需计算并缓存。扫描时不算。
     * 合同外的额外方法：清理扫描可用 `(AiGraph.index as? DefaultFileIndex)?.quickHash(id)`。
     */
    suspend fun quickHash(id: Long): String? = withContext(Dispatchers.IO) {
        val row = db.rawQuery("SELECT path, size, quick_hash FROM files WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getString(2)) else null
        } ?: return@withContext null
        row.third?.let { return@withContext it }
        val h = runCatching { quickHashOf(row.first, row.second) }.getOrNull() ?: return@withContext null
        db.execSQL("UPDATE files SET quick_hash = ? WHERE id = ?", arrayOf<Any>(h, id))
        h
    }

    private fun quickHashOf(path: String, size: Long): String? {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(size.toString().toByteArray())
        val f = File(path)
        if (f.isFile && f.canRead()) {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val buf = ByteArray(QUICK_CHUNK)
                val head = minOf(len, QUICK_CHUNK.toLong()).toInt()
                raf.readFully(buf, 0, head)
                md.update(buf, 0, head)
                if (len > QUICK_CHUNK) {
                    val tailStart = maxOf(QUICK_CHUNK.toLong(), len - QUICK_CHUNK)
                    val tail = (len - tailStart).toInt()
                    raf.seek(tailStart)
                    raf.readFully(buf, 0, tail)
                    md.update(buf, 0, tail)
                }
            }
            return hex(md.digest())
        }
        val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
        val entry = fs.stat(XId.file(path)) ?: return null
        fs.openIn(entry).use { input ->
            val buf = ByteArray(QUICK_CHUNK)
            val head = readUpTo(input, buf, minOf(size, QUICK_CHUNK.toLong()).toInt())
            md.update(buf, 0, head)
            if (size > QUICK_CHUNK) {
                val tailStart = maxOf(QUICK_CHUNK.toLong(), size - QUICK_CHUNK)
                skipFully(input, tailStart - head)
                val tail = readUpTo(input, buf, (size - tailStart).toInt())
                md.update(buf, 0, tail)
            }
        }
        return hex(md.digest())
    }

    override suspend fun fullHash(id: Long): String? = withContext(Dispatchers.IO) {
        val row = db.rawQuery("SELECT path, mtime, full_hash, full_hash_mtime FROM files WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) arrayOf<Any?>(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3)) else null
        } ?: return@withContext null
        val path = row[0] as String
        val mtime = row[1] as Long
        val cached = row[2] as String?
        if (cached != null && row[3] as Long == mtime) return@withContext cached
        val h = runCatching {
            val md = MessageDigest.getInstance("SHA-1")
            openStream(path)?.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            } ?: return@runCatching null
            hex(md.digest())
        }.getOrNull() ?: return@withContext null
        db.execSQL("UPDATE files SET full_hash = ?, full_hash_mtime = ? WHERE id = ?", arrayOf<Any>(h, mtime, id))
        h
    }

    private fun openStream(path: String): InputStream? {
        val f = File(path)
        if (f.isFile && f.canRead()) return f.inputStream()
        val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
        val entry = fs.stat(XId.file(path)) ?: return null
        return fs.openIn(entry)
    }

    // ================================================================== 查询

    override suspend fun query(q: FileQuery): List<IndexedFile> = queryPage(q, 0)

    /**
     * 分页查询（合同外的额外方法）：按 [FileQuery.sort] 排序，跳过 [offset] 条，取 [FileQuery.limit] 条。
     * 用法：`(AiGraph.index as? DefaultFileIndex)?.queryPage(q, offset)`。
     */
    suspend fun queryPage(q: FileQuery, offset: Int): List<IndexedFile> = withContext(Dispatchers.IO) {
        val (where, args) = buildWhere(q)
        val order = when (q.sort) {
            Sort.MTIME_DESC -> "mtime DESC, id DESC"
            Sort.MTIME_ASC -> "mtime ASC, id ASC"
            Sort.SIZE_DESC -> "size DESC, id DESC"
            Sort.NAME_ASC -> "name COLLATE NOCASE ASC, id ASC"
        }
        val limit = q.limit.coerceIn(1, MAX_LIMIT)
        val off = offset.coerceAtLeast(0)
        val sql = "SELECT $COLS FROM files$where ORDER BY $order LIMIT $limit OFFSET $off"
        db.rawQuery(sql, args).use { c -> readRows(c) }
    }

    /** 满足条件的总数（合同外的额外方法，配合分页显示总数）。 */
    suspend fun queryCount(q: FileQuery): Int = withContext(Dispatchers.IO) {
        val (where, args) = buildWhere(q)
        db.rawQuery("SELECT COUNT(*) FROM files$where", args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun buildWhere(q: FileQuery): Pair<String, Array<String>> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        q.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            for (tok in text.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(8)) {
                val like = "%" + escapeLike(tok) + "%"
                val fts = ftsQuery(tok)
                if (fts != null) {
                    where += "(id IN (SELECT docid FROM files_fts WHERE files_fts MATCH ?) OR name LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\')"
                    args += fts; args += like; args += like
                } else {
                    where += "(name LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\')"
                    args += like; args += like
                }
            }
        }
        if (q.categories.isNotEmpty()) {
            where += "category IN (" + q.categories.joinToString(",") { "?" } + ")"
            q.categories.forEach { args += it.name }
        }
        q.subCategory?.let { where += "sub = ?"; args += it }
        q.sourceApp?.let { where += "source = ?"; args += it }
        q.pathPrefix?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { p ->
            // 区间比较可以用上 path 的唯一索引（LIKE 用不上）
            where += "(path > ? AND path < ?)"
            args += "$p/"; args += "${p}0"
        }
        if (q.exts.isNotEmpty()) {
            val exts = q.exts.map { it.trim().trimStart('.').lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            if (exts.isNotEmpty()) {
                where += "ext IN (" + exts.joinToString(",") { "?" } + ")"
                args += exts
            }
        }
        q.minSize?.let { where += "size >= ?"; args += it.toString() }
        q.maxSize?.let { where += "size <= ?"; args += it.toString() }
        val now = System.currentTimeMillis()
        q.olderThanMs?.let { where += "mtime < ?"; args += (now - it).toString() }
        q.newerThanMs?.let { where += "mtime >= ?"; args += (now - it).toString() }
        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return clause to args.toTypedArray()
    }

    override suspend fun get(id: Long): IndexedFile? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $COLS FROM files WHERE id = ?", arrayOf(id.toString())).use { c -> readRows(c).firstOrNull() }
    }

    override suspend fun getByPath(path: String): IndexedFile? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT $COLS FROM files WHERE path = ?", arrayOf(path)).use { c -> readRows(c).firstOrNull() }
    }

    override suspend fun updateClassification(id: Long, c: Classification) {
        withContext(Dispatchers.IO) {
            val name = db.rawQuery("SELECT name FROM files WHERE id = ?", arrayOf(id.toString())).use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            } ?: return@withContext
            synchronized(writeLock) {
                db.inTx { d ->
                    d.execSQL(
                        "UPDATE files SET category = ?, sub = ?, source = ?, tags = ?, manual = 1 WHERE id = ?",
                        arrayOf<Any?>(c.category.name, c.subCategory, c.sourceApp, joinTags(c.tags), id),
                    )
                    d.execSQL("DELETE FROM files_fts WHERE docid = ?", arrayOf<Any>(id))
                    d.execSQL("INSERT INTO files_fts(docid, name, tags) VALUES (?, ?, ?)", arrayOf<Any>(id, ftsName(name), ftsTags(c.tags)))
                }
            }
            refreshStatsLater()
        }
    }

    override suspend fun sourceApps(): Map<String, Int> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Int>()
        db.rawQuery("SELECT source, COUNT(*) AS n FROM files WHERE source IS NOT NULL AND source != '' GROUP BY source ORDER BY n DESC", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        out
    }

    override suspend fun subCategories(category: Category): Map<String, Int> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Int>()
        db.rawQuery(
            "SELECT sub, COUNT(*) AS n FROM files WHERE category = ? AND sub IS NOT NULL AND sub != '' GROUP BY sub ORDER BY n DESC",
            arrayOf(category.name),
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) }
        out
    }

    override suspend fun emptyDirs(): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        db.rawQuery("SELECT path FROM dirs WHERE empty = 1 ORDER BY path", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }

    /** 每个目录（含子目录）下已索引文件的数量与总大小：path 区间 + 覆盖索引 (path, size)。 */
    override suspend fun sizeUnder(dirs: List<String>): Map<String, CategoryStat> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, CategoryStat>()
        for (d in dirs) {
            val p = d.trimEnd('/')
            val (lo, hi) = if (p.isEmpty()) "/" to "0" else "$p/" to "${p}0"
            out[d] = db.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(size), 0) FROM files WHERE path > ? AND path < ?",
                arrayOf(lo, hi),
            ).use { c -> if (c.moveToFirst()) CategoryStat(c.getInt(0), c.getLong(1)) else CategoryStat(0, 0) }
        }
        out
    }

    /** parent 的直接子目录按占用从大到小（只算子目录，不含 parent 里直接放的文件）。 */
    override suspend fun largestChildDirs(parent: String, limit: Int): List<Pair<String, CategoryStat>> = withContext(Dispatchers.IO) {
        val p = parent.trimEnd('/')
        val prefix = if (p.isEmpty()) "/" else "$p/"
        val hi = if (p.isEmpty()) "0" else "${p}0"
        val out = ArrayList<Pair<String, CategoryStat>>()
        db.rawQuery(
            "SELECT substr(rest, 1, instr(rest, '/') - 1) AS child, COUNT(*), COALESCE(SUM(size), 0) AS bytes " +
                "FROM (SELECT substr(path, ?) AS rest, size FROM files WHERE path > ? AND path < ?) " +
                "WHERE instr(rest, '/') > 0 GROUP BY child ORDER BY bytes DESC LIMIT ?",
            arrayOf((prefix.length + 1).toString(), prefix, hi, limit.coerceIn(1, 10_000).toString()),
        ).use { c ->
            while (c.moveToNext()) out += (prefix + c.getString(0)) to CategoryStat(c.getInt(1), c.getLong(2))
        }
        out
    }

    // ================================================================== 增量维护

    override suspend fun onMoved(oldPath: String, newPath: String) {
        withContext(Dispatchers.IO) {
            val o = oldPath.trimEnd('/')
            val n = newPath.trimEnd('/')
            if (o.isEmpty() || n.isEmpty() || o == n) return@withContext
            val row = db.rawQuery("SELECT id, manual, name, mime, size FROM files WHERE path = ?", arrayOf(o)).use { c ->
                if (c.moveToFirst()) arrayOf<Any?>(c.getLong(0), c.getInt(1) != 0, c.getString(2), c.getString(3), c.getLong(4)) else null
            }
            if (row != null) {
                val id = row[0] as Long
                val manual = row[1] as Boolean
                val oldName = row[2] as String
                val name = n.substringAfterLast('/')
                val ext = extOf(name)
                val cls = if (manual && oldName == name) null else classify(n, name, ext, row[3] as String?, row[4] as Long)
                synchronized(writeLock) {
                    db.inTx { d ->
                        val gone = d.rawQuery("SELECT id FROM files WHERE path = ?", arrayOf(n)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                        if (gone != 0L && gone != id) {
                            d.execSQL("DELETE FROM files WHERE id = ?", arrayOf<Any>(gone))
                            d.execSQL("DELETE FROM files_fts WHERE docid = ?", arrayOf<Any>(gone))
                        }
                        d.execSQL("UPDATE files SET path = ?, parent = ?, name = ?, ext = ? WHERE id = ?", arrayOf<Any>(n, parentOf(n), name, ext, id))
                        if (cls != null) {
                            d.execSQL(
                                "UPDATE files SET category = ?, sub = ?, source = ?, tags = ?, manual = 0 WHERE id = ?",
                                arrayOf<Any?>(cls.category.name, cls.subCategory, cls.sourceApp, joinTags(cls.tags), id),
                            )
                        }
                        val tags = d.rawQuery("SELECT tags FROM files WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                        d.execSQL("DELETE FROM files_fts WHERE docid = ?", arrayOf<Any>(id))
                        d.execSQL("INSERT INTO files_fts(docid, name, tags) VALUES (?, ?, ?)", arrayOf<Any>(id, ftsName(name), ftsTags(splitTags(tags))))
                    }
                }
            } else {
                moveSubtree(o, n)
            }
            markDirNonEmpty(parentOf(n))
            refreshEmpty(parentOf(o))
            refreshStatsLater()
        }
    }

    /** 目录改名/移动：批量改前缀，再按新路径重分类（来源可能变了）。 */
    private fun moveSubtree(o: String, n: String) {
        val cut = o.length + 1
        synchronized(writeLock) {
            db.inTx { d ->
                // 目标位置原有的记录先清掉，避免 UNIQUE 冲突
                d.execSQL("DELETE FROM files_fts WHERE docid IN (SELECT id FROM files WHERE path > ? AND path < ?)", arrayOf<Any>("$n/", "${n}0"))
                d.execSQL("DELETE FROM files WHERE path > ? AND path < ?", arrayOf<Any>("$n/", "${n}0"))
                d.execSQL("DELETE FROM dirs WHERE path = ? OR (path > ? AND path < ?)", arrayOf<Any>(n, "$n/", "${n}0"))
                d.execSQL(
                    "UPDATE files SET path = ? || substr(path, ?), parent = ? || substr(parent, ?) WHERE path > ? AND path < ?",
                    arrayOf<Any>(n, cut, n, cut, "$o/", "${o}0"),
                )
                d.execSQL(
                    "UPDATE dirs SET path = ? || substr(path, ?) WHERE path = ? OR (path > ? AND path < ?)",
                    arrayOf<Any>(n, cut, o, "$o/", "${o}0"),
                )
            }
        }
        val rows = ArrayList<Array<Any?>>()
        db.rawQuery("SELECT id, path, name, ext, mime, size FROM files WHERE manual = 0 AND path > ? AND path < ?", arrayOf("$n/", "${n}0")).use { c ->
            while (c.moveToNext()) rows += arrayOf(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))
        }
        for (chunk in rows.chunked(BATCH)) {
            val classified = chunk.map { r -> r to classify(r[1] as String, r[2] as String, r[3] as String, r[4] as String?, r[5] as Long) }
            synchronized(writeLock) {
                db.inTx { d ->
                    for ((r, cls) in classified) {
                        val id = r[0] as Long
                        d.execSQL(
                            "UPDATE files SET category = ?, sub = ?, source = ?, tags = ? WHERE id = ?",
                            arrayOf<Any?>(cls.category.name, cls.subCategory, cls.sourceApp, joinTags(cls.tags), id),
                        )
                        d.execSQL("DELETE FROM files_fts WHERE docid = ?", arrayOf<Any>(id))
                        d.execSQL("INSERT INTO files_fts(docid, name, tags) VALUES (?, ?, ?)", arrayOf<Any>(id, ftsName(r[2] as String), ftsTags(cls.tags)))
                    }
                }
            }
        }
    }

    override suspend fun onRemoved(path: String) {
        withContext(Dispatchers.IO) {
            val p = path.trimEnd('/')
            if (p.isEmpty()) return@withContext
            synchronized(writeLock) {
                db.inTx { d ->
                    d.execSQL(
                        "DELETE FROM files_fts WHERE docid IN (SELECT id FROM files WHERE path = ? OR (path > ? AND path < ?))",
                        arrayOf<Any>(p, "$p/", "${p}0"),
                    )
                    d.execSQL("DELETE FROM files WHERE path = ? OR (path > ? AND path < ?)", arrayOf<Any>(p, "$p/", "${p}0"))
                    d.execSQL("DELETE FROM dirs WHERE path = ? OR (path > ? AND path < ?)", arrayOf<Any>(p, "$p/", "${p}0"))
                }
            }
            refreshEmpty(parentOf(p))
            refreshStatsLater()
        }
    }

    override suspend fun onAdded(path: String) {
        withContext(Dispatchers.IO) {
            val p = path.trimEnd('/')
            if (p.isEmpty()) return@withContext
            val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
            val parent = parentOf(p)
            val f = File(p)
            val buf = Buf()
            val isDir: Boolean
            if (f.exists()) {
                isDir = f.isDirectory
                if (isDir) {
                    if (IndexRules.skipDir(p, f.name)) return@withContext
                    val s = Session(fs, false, { true }, loadFiles(p), loadDirs(p))
                    walkTree(s, p, f.lastModified(), buf)
                } else {
                    val s = Session(fs, false, { true }, singleRow(p), emptyMap())
                    handleFile(s, parent, p, f.name, f.length(), f.lastModified(), null, buf)
                }
            } else {
                val entry = runCatching { fs.stat(XId.file(p)) }.getOrNull() ?: return@withContext
                isDir = entry.isDir
                if (isDir) {
                    if (IndexRules.skipDir(p, entry.name)) return@withContext
                    val s = Session(fs, false, { true }, loadFiles(p), loadDirs(p))
                    walkPrivileged(s, p, entry.mtime, buf)
                } else {
                    val s = Session(fs, false, { true }, singleRow(p), emptyMap())
                    handleFile(s, parent, p, entry.name, maxOf(0L, entry.size), entry.mtime, entry.mime, buf)
                }
            }
            flush(buf)
            markDirNonEmpty(parent)
            refreshStatsLater()
        }
    }

    private fun singleRow(path: String): Map<String, OldRow> =
        db.rawQuery("SELECT id, size, mtime, manual FROM files WHERE path = ?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) mapOf(path to OldRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0)) else emptyMap()
        }

    private fun markDirNonEmpty(dir: String) {
        synchronized(writeLock) { db.execSQL("UPDATE dirs SET empty = 0 WHERE path = ?", arrayOf<Any>(dir)) }
    }

    /** 删除/移走后父目录可能变空：重新看一眼。 */
    private fun refreshEmpty(dir: String) {
        val known = db.rawQuery("SELECT 1 FROM dirs WHERE path = ?", arrayOf(dir)).use { it.moveToFirst() }
        if (!known) return
        val empty = runCatching {
            val names = File(dir).list()
            if (names != null && !isRestricted(dir)) {
                names.none { it != ".nomedia" }
            } else {
                val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
                val e = fs.stat(XId.file(dir)) ?: return@runCatching null
                fs.list(e).none { it.name != ".nomedia" }
            }
        }.getOrNull() ?: return
        synchronized(writeLock) { db.execSQL("UPDATE dirs SET empty = ? WHERE path = ?", arrayOf<Any>(if (empty) 1 else 0, dir)) }
    }

    // ================================================================== 工具

    private fun readRows(c: Cursor): List<IndexedFile> {
        val out = ArrayList<IndexedFile>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            out += IndexedFile(
                id = c.getLong(0),
                path = c.getString(1),
                name = c.getString(2),
                ext = c.getString(3),
                size = c.getLong(4),
                mtime = c.getLong(5),
                mime = c.getString(6),
                category = parseCategory(c.getString(7)),
                subCategory = c.getString(8),
                sourceApp = c.getString(9),
                tags = splitTags(c.getString(10)),
                quickHash = c.getString(11),
            )
        }
        return out
    }

    companion object {
        private const val TAG = "AiFileIndex"
        private const val PREFS = "ai_file_index"
        private const val KEY_LAST_SCAN = "last_scan_at"
        private const val AUTO_SCAN_INTERVAL_MS = 30 * 60 * 1000L
        private const val STATS_INTERVAL_MS = 300L
        private const val KEY_LAST_TOTAL = "last_total_files"
        private const val PRIV_TIMEOUT_MS = 5_000L
        private const val PRIV_BUDGET_MS = 120_000L
        private const val MAX_PRIV_TIMEOUTS = 6
        private const val PARALLEL = 4
        private const val BATCH = 2000
        private const val CHUNK = 400
        private const val MAX_DEPTH = 64
        private const val MAX_LIMIT = 200_000
        private const val QUICK_CHUNK = 64 * 1024
        private const val COLS = "id, path, name, ext, size, mtime, mime, category, sub, source, tags, quick_hash"
        private val RESTRICTED = Regex("/Android/data(/|$)")

        /** App 启动后调用一次：建立索引实例；距上次扫描超过 30 分钟会自动增量扫描。 */
        fun ensureStarted() {
            runCatching { AiGraph.index }
        }

        /** Android 11+ 普通 File 读不了，必须走特权通道的目录。 */
        internal fun isRestricted(dir: String): Boolean = RESTRICTED.containsMatchIn(dir)

        private fun bindOpt(st: android.database.sqlite.SQLiteStatement, i: Int, v: String?) {
            if (v == null) st.bindNull(i) else st.bindString(i, v)
        }

        internal fun parseCategory(s: String?): Category =
            runCatching { Category.valueOf(s ?: "") }.getOrDefault(Category.OTHER)

        internal fun joinTags(tags: List<String>): String =
            tags.map { it.replace(',', ' ').trim() }.filter { it.isNotEmpty() }.joinToString(",")

        internal fun splitTags(s: String?): List<String> =
            if (s.isNullOrEmpty()) emptyList() else s.split(',').filter { it.isNotEmpty() }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** 文件名拆成词给 FTS：分隔符变空格，便于 “2024”、“IMG” 等前缀命中。 */
        internal fun ftsName(name: String): String = name.replace(NON_WORD, " ").trim()

        internal fun ftsTags(tags: List<String>): String = tags.joinToString(" ") { ftsName(it) }

        /** 一个搜索词 → FTS 前缀查询；没有可用字符返回 null。 */
        internal fun ftsQuery(token: String): String? {
            val words = token.replace(NON_WORD, " ").trim().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "$it*" }
        }

        internal fun escapeLike(s: String): String =
            s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        internal fun parentOf(path: String): String {
            val p = path.trimEnd('/')
            val i = p.lastIndexOf('/')
            return if (i <= 0) "/" else p.substring(0, i)
        }

        internal fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

        internal fun hex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0f])
            }
            return sb.toString()
        }

        private fun readUpTo(input: InputStream, buf: ByteArray, want: Int): Int {
            var got = 0
            while (got < want) {
                val n = input.read(buf, got, want - got)
                if (n < 0) break
                got += n
            }
            return got
        }

        private fun skipFully(input: InputStream, count: Long) {
            var left = count
            val tmp = ByteArray(8192)
            while (left > 0) {
                val s = input.skip(left)
                if (s > 0) {
                    left -= s
                    continue
                }
                val n = input.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
                if (n < 0) return
                left -= n
            }
        }
    }
}
