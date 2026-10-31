package app.local1st.files.ai.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 文件索引数据库。
 * - files：每个文件一行（path 唯一）。manual=1 表示分类被手动/AI 改过，重扫不覆盖。
 * - dirs：每个目录一行（mtime 用于增量，empty 标记空目录）。
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
        db.execSQL(
            """CREATE TABLE dirs (
                path TEXT PRIMARY KEY,
                mtime INTEGER NOT NULL,
                empty INTEGER NOT NULL DEFAULT 0,
                seen INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE VIRTUAL TABLE files_fts USING fts4(name, tags)")
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            db.execSQL("DROP TABLE IF EXISTS files")
            db.execSQL("DROP TABLE IF EXISTS dirs")
            db.execSQL("DROP TABLE IF EXISTS files_fts")
            onCreate(db)
            return
        }
        // v1 → v2 → v3：只换索引，保留数据
        db.execSQL("DROP INDEX IF EXISTS files_seen")
        db.execSQL("DROP INDEX IF EXISTS files_cat")
        createIndexes(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS files")
        db.execSQL("DROP TABLE IF EXISTS dirs")
        db.execSQL("DROP TABLE IF EXISTS files_fts")
        onCreate(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        // path 已有 UNIQUE 索引（也服务于前缀 LIKE / 精确查找）
        db.execSQL("CREATE INDEX IF NOT EXISTS files_parent ON files(parent)")
        // 覆盖索引：sizeUnder / largestChildDirs 只读索引不回表
        db.execSQL("CREATE INDEX IF NOT EXISTS files_path_size ON files(path, size)")
        db.execSQL("CREATE INDEX IF NOT EXISTS files_category ON files(category, mtime)")
        db.execSQL("CREATE INDEX IF NOT EXISTS files_sub ON files(sub)")
        db.execSQL("CREATE INDEX IF NOT EXISTS files_source ON files(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS files_mtime ON files(mtime)")
        db.execSQL("CREATE INDEX IF NOT EXISTS files_size ON files(size)")
        db.execSQL("CREATE INDEX IF NOT EXISTS files_ext ON files(ext)")
        db.execSQL("CREATE INDEX IF NOT EXISTS dirs_empty ON dirs(empty)")
    }

    companion object {
        const val NAME = "ai_file_index.db"
        const val VERSION = 3
    }
}

/** 在一个事务里执行。 */
internal inline fun <T> SQLiteDatabase.inTx(block: (SQLiteDatabase) -> T): T {
    beginTransaction()
    try {
        val r = block(this)
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}
