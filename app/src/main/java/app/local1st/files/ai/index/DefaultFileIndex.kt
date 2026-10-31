package app.local1st.files.ai.index

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * 文件索引（M2）。SQLite + FTS4，后台扫描所有已挂载卷，增量维护。
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

    init {
        Graph.appScope.launch(Dispatchers.IO) {
            runCatching {
                val last = prefs.getLong(KEY_LAST_SCAN, 0L)
                publishStats(scanning = false, progress = "", lastScanAt = last)
                if (System.currentTimeMillis() - last > AUTO_SCAN_INTERVAL_MS) startScan(false)
            }.onFailure { Log.w(TAG, "index init failed", it) }
        }
    }

    // ------------------------------------------------------------------ 扫描

    override fun startScan(full: Boolean) {
        synchronized(scanLock) {
            if (scanJob?.isActive == true) return
            scanJob = Graph.appScope.launch(Dispatchers.IO) {
                val job = coroutineContext[Job]
                try {
                    runScan(full) { job?.isActive != false }
                } catch (t: Throwable) {
                    Log.w(TAG, "scan failed", t)
                    publishStats(scanning = false, progress = "扫描出错：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private class ScanCtx(
        val fs: XFileSystem,
        val gen: Long,
        val full: Boolean,
        val writer: BatchWriter,
        val alive: () -> Boolean,
    ) {
        var files = 0
        var lastProgressAt = 0L
        var lastStatsAt = 0L
    }

    /** 阻塞执行，不能挂起（SQLite 事务绑定线程）。 */
    private fun runScan(full: Boolean, alive: () -> Boolean) {
        val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
        val gen = System.currentTimeMillis()
        val volumes = runCatching { Graph.roots.currentMountedVolumes() }.getOrDefault(emptyList())
        publishStats(scanning = true, progress = "准备扫描…")
        val writer = BatchWriter(db)
        val ctx = ScanCtx(fs, gen, full, writer, alive)
        for (v in volumes) {
            if (!alive()) break
            val root = fs.stat(XId.file(v.path)) ?: XEntry(id = XId.file(v.path), name = v.label, isDir = true)
            walk(ctx, root, isVolumeRoot = true)
        }
        writer.flush()
        if (!alive()) {
            publishStats(scanning = false, progress = "扫描已取消")
            return
        }
        // 删掉这次没见到的（已不存在 / 卷已卸载）
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM files WHERE seen < ?", arrayOf<Any>(gen))
            db.execSQL("DELETE FROM dirs WHERE seen < ?", arrayOf<Any>(gen))
            db.execSQL("DELETE FROM files_fts WHERE docid NOT IN (SELECT id FROM files)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        computeQuickHashes(alive)
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SCAN, now).apply()
        publishStats(scanning = false, progress = "", lastScanAt = now)
    }

    /** 迭代遍历一个卷（显式栈，避免深目录爆栈）。 */
    private fun walk(ctx: ScanCtx, root: XEntry, isVolumeRoot: Boolean) {
        val stack = ArrayDeque<Pair<XEntry, Int>>()
        stack.addLast(root to 0)
        var first = isVolumeRoot
        while (stack.isNotEmpty()) {
            if (!ctx.alive()) return
            val (dir, depth) = stack.removeLast()
            val path = dir.path
            val children: List<XEntry>? = try {
                ctx.fs.list(dir)
            } catch (t: Throwable) {
                null
            }
            // 卷根列不出 / 列出空（多半是没权限）：保留库里原有记录，不当作被删
            if (children == null || (first && children.isEmpty())) {
                keepSubtree(ctx, path)
                first = false
                continue
            }
            first = false
            progress(ctx, path)

            val oldDir = queryDir(path)
            val dirMtime = dir.mtime
            val unchanged = !ctx.full && oldDir != null && dirMtime > 0 && oldDir == dirMtime
            val realChildren = children.filter { it.name != ".nomedia" }
            val empty = realChildren.isEmpty()
            ctx.writer.op { d ->
                val cv = ContentValues()
                cv.put("path", path)
                cv.put("mtime", dirMtime)
                cv.put("empty", if (empty) 1 else 0)
                cv.put("seen", ctx.gen)
                d.insertWithOnConflict("dirs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            if (unchanged) {
                ctx.writer.op { d -> d.execSQL("UPDATE files SET seen = ? WHERE parent = ?", arrayOf<Any>(ctx.gen, path)) }
                for (c in children) {
                    if (c.isDir) {
                        if (!IndexRules.skipDir(c.path, c.name) && depth < MAX_DEPTH) stack.addLast(c to depth + 1)
                    } else {
                        ctx.files++
                    }
                }
                continue
            }

            val existing = existingIn(path)
            for (c in children) {
                if (c.isDir) {
                    if (!IndexRules.skipDir(c.path, c.name) && depth < MAX_DEPTH) stack.addLast(c to depth + 1)
                    continue
                }
                val ext = c.extension
                if (IndexRules.skipFile(path, c.name, ext)) continue
                ctx.files++
                val old = existing[c.path]
                val size = if (c.size < 0) 0L else c.size
                if (old != null && !ctx.full && old.size == size && old.mtime == c.mtime) {
                    ctx.writer.op { d -> d.execSQL("UPDATE files SET seen = ? WHERE id = ?", arrayOf<Any>(ctx.gen, old.id)) }
                    continue
                }
                upsertFile(ctx.writer, c.path, path, c.name, ext, size, c.mtime, c.mime, old, ctx.gen)
            }
        }
    }

    private class OldRow(val id: Long, val size: Long, val mtime: Long, val manual: Boolean, val name: String)

    private fun existingIn(parent: String): Map<String, OldRow> {
        val out = HashMap<String, OldRow>()
        db.rawQuery("SELECT id, path, size, mtime, manual, name FROM files WHERE parent = ?", arrayOf(parent)).use { c ->
            while (c.moveToNext()) out[c.getString(1)] = OldRow(c.getLong(0), c.getLong(2), c.getLong(3), c.getInt(4) != 0, c.getString(5))
        }
        return out
    }

    private fun queryDir(path: String): Long? =
        db.rawQuery("SELECT mtime FROM dirs WHERE path = ?", arrayOf(path)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** 列不出的目录：把它下面库里已有的行都标成“本次见过”，避免误删。 */
    private fun keepSubtree(ctx: ScanCtx, path: String) {
        val like = likePrefix(path)
        ctx.writer.op { d -> d.execSQL("UPDATE files SET seen = ? WHERE path LIKE ? ESCAPE '\\'", arrayOf<Any>(ctx.gen, like)) }
        ctx.writer.op { d -> d.execSQL("UPDATE dirs SET seen = ? WHERE path = ? OR path LIKE ? ESCAPE '\\'", arrayOf<Any>(ctx.gen, path, like)) }
    }

    private fun classify(path: String, name: String, ext: String, mime: String?, size: Long): Classification =
        try {
            AiGraph.classifier.classifyFast(path, name, ext, mime, size)
        } catch (t: Throwable) {
            Classification(Category.OTHER)
        }

    private fun mimeOf(ext: String, mime: String?): String? =
        mime ?: if (ext.isEmpty()) null else runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()

    /** 写入或更新一个文件行（含 FTS）。manual 分类的行只更新元数据。 */
    private fun upsertFile(
        writer: BatchWriter, path: String, parent: String, name: String, ext: String,
        size: Long, mtime: Long, mime0: String?, old: OldRow?, gen: Long,
    ) {
        val mime = mimeOf(ext, mime0)
        val keepClass = old?.manual == true && old.name == name
        val c = if (keepClass) null else classify(path, name, ext, mime, size)
        writer.op { d ->
            val cv = ContentValues()
            cv.put("path", path)
            cv.put("parent", parent)
            cv.put("name", name)
            cv.put("ext", ext)
            cv.put("size", size)
            cv.put("mtime", mtime)
            cv.put("mime", mime)
            cv.put("seen", gen)
            if (old == null || old.size != size || old.mtime != mtime) {
                cv.putNull("quick_hash")
                cv.putNull("full_hash")
                cv.put("full_hash_mtime", 0)
            }
            if (c != null) {
                cv.put("category", c.category.name)
                cv.put("sub", c.subCategory)
                cv.put("source", c.sourceApp)
                cv.put("tags", joinTags(c.tags))
                cv.put("manual", 0)
            }
            if (old == null) {
                if (c == null) return@op
                val id = d.insertWithOnConflict("files", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (id > 0) {
                    d.execSQL("INSERT INTO files_fts(docid, name, tags) VALUES (?, ?, ?)", arrayOf<Any>(id, ftsName(name), ftsTags(c.tags)))
                } else {
                    // 同一路径已存在（并发 onAdded）：转为更新
                    d.update("files", cv, "path = ?", arrayOf(path))
                }
            } else {
                d.update("files", cv, "id = ?", arrayOf(old.id.toString()))
                if (c != null) {
                    d.execSQL("DELETE FROM files_fts WHERE docid = ?", arrayOf<Any>(old.id))
                    d.execSQL("INSERT INTO files_fts(docid, name, tags) VALUES (?, ?, ?)", arrayOf<Any>(old.id, ftsName(name), ftsTags(c.tags)))
                }
            }
        }
    }

    private fun progress(ctx: ScanCtx, dir: String) {
        val now = SystemClock.uptimeMillis()
        if (now - ctx.lastProgressAt < 200) return
        ctx.lastProgressAt = now
        val text = "已扫描 ${ctx.files} 个文件 · $dir"
        if (now - ctx.lastStatsAt > 3000) {
            ctx.lastStatsAt = now
            ctx.writer.flush()
            publishStats(scanning = true, progress = text)
        } else {
            _stats.update { it.copy(scanning = true, scanProgress = text) }
        }
    }

    // ------------------------------------------------------------------ 统计

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

    // ------------------------------------------------------------------ 哈希

    /** 只对 >1MB 且同大小有多个的文件算快速哈希。 */
    private fun computeQuickHashes(alive: () -> Boolean) {
        val todo = ArrayList<Triple<Long, String, Long>>()
        db.rawQuery(
            "SELECT id, path, size FROM files WHERE size > ? AND quick_hash IS NULL AND size IN " +
                "(SELECT size FROM files WHERE size > ? GROUP BY size HAVING COUNT(*) > 1)",
            arrayOf(QUICK_MIN_SIZE.toString(), QUICK_MIN_SIZE.toString()),
        ).use { c -> while (c.moveToNext()) todo += Triple(c.getLong(0), c.getString(1), c.getLong(2)) }
        if (todo.isEmpty()) return
        val writer = BatchWriter(db)
        var lastAt = 0L
        for ((i, row) in todo.withIndex()) {
            if (!alive()) break
            val now = SystemClock.uptimeMillis()
            if (now - lastAt > 300) {
                lastAt = now
                _stats.update { it.copy(scanning = true, scanProgress = "计算快速哈希 ${i + 1}/${todo.size}") }
            }
            val h = runCatching { quickHashOf(row.second, row.third) }.getOrNull() ?: continue
            writer.op { d -> d.execSQL("UPDATE files SET quick_hash = ? WHERE id = ?", arrayOf<Any>(h, row.first)) }
        }
        writer.flush()
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
        // 只能走文件系统（如 Shizuku 进 Android/data）：顺序读，跳过中间
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
            if (c.moveToFirst()) arrayOf(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3)) else null
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

    // ------------------------------------------------------------------ 查询

    override suspend fun query(q: FileQuery): List<IndexedFile> = withContext(Dispatchers.IO) {
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
            where += "(path = ? OR path LIKE ? ESCAPE '\\')"
            args += p; args += likePrefix(p)
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
        val order = when (q.sort) {
            Sort.MTIME_DESC -> "mtime DESC"
            Sort.MTIME_ASC -> "mtime ASC"
            Sort.SIZE_DESC -> "size DESC"
            Sort.NAME_ASC -> "name COLLATE NOCASE ASC"
        }
        val limit = q.limit.coerceIn(1, MAX_LIMIT)
        val sql = "SELECT $COLS FROM files" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY $order LIMIT $limit"
        db.rawQuery(sql, args.toTypedArray()).use { c -> readRows(c) }
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
            db.beginTransaction()
            try {
                val cv = ContentValues()
                cv.put("category", c.category.name)
                cv.put("sub", c.subCategory)
                cv.put("source", c.sourceApp)
                cv.put("tags", joinTags(c.tags))
                cv.put("manual", 1)
                db.update("files", cv, "id = ?", arrayOf(id.toString()))
                db.execSQL("DELETE FROM files_fts WHERE docid = ?", arrayOf<Any>(id))
                db.execSQL("INSERT INTO files_fts(docid, name, tags) VALUES (?, ?, ?)", arrayOf<Any>(id, ftsName(name), ftsTags(c.tags)))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            publishStats(_stats.value.scanning, _stats.value.scanProgress)
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

    // ------------------------------------------------------------------ 增量维护

    override suspend fun onMoved(oldPath: String, newPath: String) {
        withContext(Dispatchers.IO) {
            val o = oldPath.trimEnd('/')
            val n = newPath.trimEnd('/')
            if (o.isEmpty() || n.isEmpty() || o == n) return@withContext
            val fileRow = db.rawQuery("SELECT id, size, mtime, manual, name FROM files WHERE path = ?", arrayOf(o)).use { c ->
                if (c.moveToFirst()) OldRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0, c.getString(4)) else null
            }
            if (fileRow != null) {
                db.execSQL("DELETE FROM files WHERE path = ?", arrayOf<Any>(n))
                db.execSQL("DELETE FROM files_fts WHERE docid NOT IN (SELECT id FROM files)")
                val name = n.substringAfterLast('/')
                val ext = extOf(name)
                val writer = BatchWriter(db)
                val mime = db.rawQuery("SELECT mime FROM files WHERE id = ?", arrayOf(fileRow.id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                // 移动后来源/分类可能变化：重分类（手动分类且名字没变的保留）
                upsertFile(writer, n, parentOf(n), name, ext, fileRow.size, fileRow.mtime, mime,
                    OldRow(fileRow.id, fileRow.size, fileRow.mtime, fileRow.manual, if (fileRow.name == name) name else ""), 0)
                writer.flush()
                db.execSQL("UPDATE files SET seen = (SELECT MAX(seen) FROM files) WHERE id = ?", arrayOf<Any>(fileRow.id))
                markDirNonEmpty(parentOf(n))
                refreshEmpty(parentOf(o))
            } else {
                moveSubtree(o, n)
                markDirNonEmpty(parentOf(n))
                refreshEmpty(parentOf(o))
            }
            publishStats(_stats.value.scanning, _stats.value.scanProgress)
        }
    }

    /** 目录改名/移动：批量改前缀，再按新路径重分类（来源可能变了）。 */
    private fun moveSubtree(o: String, n: String) {
        val likeO = likePrefix(o)
        val cut = o.length + 1
        db.beginTransaction()
        try {
            // 目标位置原有的记录先清掉，避免 UNIQUE 冲突
            db.execSQL("DELETE FROM files WHERE path LIKE ? ESCAPE '\\'", arrayOf<Any>(likePrefix(n)))
            db.execSQL("DELETE FROM dirs WHERE path = ? OR path LIKE ? ESCAPE '\\'", arrayOf<Any>(n, likePrefix(n)))
            db.execSQL(
                "UPDATE files SET path = ? || substr(path, ?), parent = ? || substr(parent, ?) WHERE path LIKE ? ESCAPE '\\'",
                arrayOf<Any>(n, cut, n, cut, likeO),
            )
            db.execSQL(
                "UPDATE dirs SET path = ? || substr(path, ?) WHERE path = ? OR path LIKE ? ESCAPE '\\'",
                arrayOf<Any>(n, cut, o, likeO),
            )
            db.execSQL("DELETE FROM files_fts WHERE docid NOT IN (SELECT id FROM files)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // 重分类（manual 的不动）
        val rows = ArrayList<Array<Any?>>()
        db.rawQuery("SELECT id, path, name, ext, mime, size FROM files WHERE manual = 0 AND path LIKE ? ESCAPE '\\'", arrayOf(likePrefix(n))).use { c ->
            while (c.moveToNext()) rows += arrayOf(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))
        }
        val writer = BatchWriter(db)
        for (r in rows) {
            val cls = classify(r[1] as String, r[2] as String, r[3] as String, r[4] as String?, r[5] as Long)
            writer.op { d ->
                val cv = ContentValues()
                cv.put("category", cls.category.name)
                cv.put("sub", cls.subCategory)
                cv.put("source", cls.sourceApp)
                cv.put("tags", joinTags(cls.tags))
                d.update("files", cv, "id = ?", arrayOf(r[0].toString()))
                d.execSQL("DELETE FROM files_fts WHERE docid = ?", arrayOf<Any>(r[0] as Long))
                d.execSQL("INSERT INTO files_fts(docid, name, tags) VALUES (?, ?, ?)", arrayOf<Any>(r[0] as Long, ftsName(r[2] as String), ftsTags(cls.tags)))
            }
        }
        writer.flush()
    }

    override suspend fun onRemoved(path: String) {
        withContext(Dispatchers.IO) {
            val p = path.trimEnd('/')
            if (p.isEmpty()) return@withContext
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM files WHERE path = ? OR path LIKE ? ESCAPE '\\'", arrayOf<Any>(p, likePrefix(p)))
                db.execSQL("DELETE FROM dirs WHERE path = ? OR path LIKE ? ESCAPE '\\'", arrayOf<Any>(p, likePrefix(p)))
                db.execSQL("DELETE FROM files_fts WHERE docid NOT IN (SELECT id FROM files)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            refreshEmpty(parentOf(p))
            publishStats(_stats.value.scanning, _stats.value.scanProgress)
        }
    }

    override suspend fun onAdded(path: String) {
        withContext(Dispatchers.IO) {
            val p = path.trimEnd('/')
            if (p.isEmpty()) return@withContext
            val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
            val entry = runCatching { fs.stat(XId.file(p)) }.getOrNull() ?: return@withContext
            val parent = parentOf(p)
            val gen = currentGen()
            if (entry.isDir) {
                if (IndexRules.skipDir(entry.path, entry.name)) return@withContext
                val writer = BatchWriter(db)
                val ctx = ScanCtx(fs, gen, true, writer) { true }
                walk(ctx, entry, isVolumeRoot = false)
                writer.flush()
            } else {
                val ext = entry.extension
                if (IndexRules.skipFile(parent, entry.name, ext)) return@withContext
                val old = db.rawQuery("SELECT id, size, mtime, manual, name FROM files WHERE path = ?", arrayOf(p)).use { c ->
                    if (c.moveToFirst()) OldRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0, c.getString(4)) else null
                }
                val writer = BatchWriter(db)
                upsertFile(writer, p, parent, entry.name, ext, maxOf(0L, entry.size), entry.mtime, entry.mime, old, gen)
                writer.flush()
            }
            markDirNonEmpty(parent)
            publishStats(_stats.value.scanning, _stats.value.scanProgress)
        }
    }

    /** 增量写入时沿用最近一次扫描代号，避免下次扫描前被当作“没见过”。 */
    private fun currentGen(): Long =
        db.rawQuery("SELECT MAX(seen) FROM dirs", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
            .let { if (it > 0) it else System.currentTimeMillis() }

    private fun markDirNonEmpty(dir: String) {
        db.execSQL("UPDATE dirs SET empty = 0 WHERE path = ?", arrayOf<Any>(dir))
    }

    /** 删除/移走后父目录可能变空：重新看一眼。 */
    private fun refreshEmpty(dir: String) {
        val known = db.rawQuery("SELECT 1 FROM dirs WHERE path = ?", arrayOf(dir)).use { it.moveToFirst() }
        if (!known) return
        val empty = runCatching {
            val f = File(dir)
            val names = f.list()
            if (names != null) {
                names.none { it != ".nomedia" }
            } else {
                val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
                val e = fs.stat(XId.file(dir)) ?: return@runCatching null
                fs.list(e).none { it.name != ".nomedia" }
            }
        }.getOrNull() ?: return
        db.execSQL("UPDATE dirs SET empty = ? WHERE path = ?", arrayOf<Any>(if (empty) 1 else 0, dir))
    }

    // ------------------------------------------------------------------ 工具

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
        private const val MAX_DEPTH = 64
        private const val MAX_LIMIT = 100_000
        private const val QUICK_MIN_SIZE = 1024L * 1024
        private const val QUICK_CHUNK = 64 * 1024
        private const val COLS = "id, path, name, ext, size, mtime, mime, category, sub, source, tags, quick_hash"

        /** App 启动后调用一次：建立索引实例；距上次扫描超过 30 分钟会自动增量扫描。 */
        fun ensureStarted() {
            runCatching { AiGraph.index }
        }

        internal fun parseCategory(s: String?): Category =
            runCatching { Category.valueOf(s ?: "") }.getOrDefault(Category.OTHER)

        internal fun joinTags(tags: List<String>): String =
            tags.map { it.replace(',', ' ').trim() }.filter { it.isNotEmpty() }.joinToString(",")

        internal fun splitTags(s: String?): List<String> =
            if (s.isNullOrEmpty()) emptyList() else s.split(',').filter { it.isNotEmpty() }

        /** 文件名拆成词给 FTS：分隔符变空格，便于 “2024”、“IMG” 等前缀命中。 */
        internal fun ftsName(name: String): String =
            name.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        internal fun ftsTags(tags: List<String>): String =
            tags.joinToString(" ") { ftsName(it) }

        /** 一个搜索词 → FTS 前缀查询；没有可用字符返回 null。 */
        internal fun ftsQuery(token: String): String? {
            val words = token.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "$it*" }
        }

        internal fun escapeLike(s: String): String =
            s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** “目录下所有路径”的 LIKE 模式。 */
        internal fun likePrefix(dir: String): String = escapeLike(dir.trimEnd('/')) + "/%"

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
