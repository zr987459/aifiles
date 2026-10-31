package app.local1st.files.ai.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 文件索引数据库。
 * - files：每个文件一行（path 唯一），seen 是最近一次扫描的代号，manual=1 表示分类被手动/AI 改过，重扫不覆盖。
 * - dirs：每个目录一行（mtime 用于增量跳过，empty 标记空目录）。
 * - files_fts：FTS4（name + tags），docid = files.id。
 */
internal class IndexDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL UNIQUE,
                parent TEXT NOT NULL,
                name TEXT NOT NULL,
                ext TEXT NOT NULL,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                mime TEXT,
                category TEXT NOT NULL,
                sub TEXT,
                source TEXT,
                tags TEXT NOT NULL DEFAULT '',
                quick_hash TEXT,
                full_hash TEXT,
                full_hash_mtime INTEGER NOT NULL DEFAULT 0,
                manual INTEGER NOT NULL DEFAULT 0,
                seen INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX files_parent ON files(parent)")
        db.execSQL("CREATE INDEX files_cat ON files(category, sub)")
        db.execSQL("CREATE INDEX files_source ON files(source)")
        db.execSQL("CREATE INDEX files_mtime ON files(mtime)")
        db.execSQL("CREATE INDEX files_size ON files(size)")
        db.execSQL("CREATE INDEX files_ext ON files(ext)")
        db.execSQL("CREATE INDEX files_seen ON files(seen)")
        db.execSQL(
            """CREATE TABLE dirs (
                path TEXT PRIMARY KEY,
                mtime INTEGER NOT NULL,
                empty INTEGER NOT NULL DEFAULT 0,
                seen INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX dirs_empty ON dirs(empty)")
        db.execSQL("CREATE VIRTUAL TABLE files_fts USING fts4(name, tags)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS files")
        db.execSQL("DROP TABLE IF EXISTS dirs")
        db.execSQL("DROP TABLE IF EXISTS files_fts")
        onCreate(db)
    }

    companion object {
        const val NAME = "ai_file_index.db"
        const val VERSION = 1
    }
}

/** 每 [size] 条写操作提交一次事务。只能在同一个线程里用（SQLite 事务绑定线程）。 */
internal class BatchWriter(private val db: SQLiteDatabase, private val size: Int = 500) {
    private var pending = 0

    fun op(block: (SQLiteDatabase) -> Unit) {
        if (pending == 0) db.beginTransaction()
        try {
            block(db)
        } catch (t: Throwable) {
            // 单条失败不拖垮整批
        }
        pending++
        if (pending >= size) flush()
    }

    fun flush() {
        if (pending == 0) return
        try {
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            pending = 0
        }
    }
}
