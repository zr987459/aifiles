package app.local1st.files.ai.exec

import app.local1st.files.core.fs.TrashPaths
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 执行层的路径安全规则（纯函数，便于测试）。
 * 规则偏保守：拿不准的一律拒绝，宁可少整理也不碰用户的数据库、聊天记录和系统目录。
 */
internal object PathGuard {

    /** 永不移动/改名/删除的扩展名（数据库、密钥、证书、密码库、备份）。 */
    val PROTECTED_EXT = setOf(
        "db", "db-journal", "db-wal", "db-shm", "sqlite", "sqlite3", "sqlitedb",
        "key", "keystore", "jks", "bks", "p12", "pfx", "pem", "kdbx", "kdb",
        "nomedia", "bak", "backup",
    )

    /** 系统分区与非存储卷的根前缀。 */
    private val SYSTEM_PREFIXES = listOf(
        "/system", "/vendor", "/product", "/system_ext", "/odm", "/apex", "/proc",
        "/sys", "/dev", "/data", "/cache", "/metadata", "/oem", "/acct", "/config", "/mnt/vendor",
    )

    /** 存储卷第一层的标准目录：可以当目标，但本身不能被搬走/改名/删除。 */
    private val STANDARD_TOP = setOf(
        "android", "dcim", "download", "documents", "pictures", "movies", "music",
        "alarms", "notifications", "podcasts", "ringtones", "recordings", "audiobooks",
    )

    /** 任何位置出现都视为应用私有数据的目录名（小写）。 */
    private val PROTECTED_SEGMENTS = setOf(
        "databases", "database", "shared_prefs", "no_backup", "app_webview", "msflogs",
        "backup", "backups", ".git",
    )

    /** 微信 MicroMsg/ 下允许处理的子目录（MicroMsg/X 或 MicroMsg/{账号}/X）。 */
    private val MICROMSG_ALLOWED = setOf("download", "video", "image2", "file")

    /** QQ/TIM 应用目录里只允许处理这些用户文件目录。 */
    private val QQ_PACKAGES = setOf("com.tencent.mobileqq", "com.tencent.tim", "com.tencent.qqlite")
    private val QQ_ALLOWED = setOf("qqfile_recv", "timfile_recv", "qq_images", "qq_video", "download")

    /**
     * 规范化绝对路径：合并重复斜杠、去掉末尾斜杠。含 `.`、`..`、反斜杠、空字符时返回 null（直接拒绝而不是解析）。
     */
    fun normalize(path: String?): String? {
        if (path.isNullOrBlank() || !path.startsWith("/")) return null
        if (path.contains('\\') || path.contains('\u0000')) return null
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (segs.any { it == "." || it == ".." }) return null
        return "/" + segs.joinToString("/")
    }

    fun isSafeName(name: String?): Boolean =
        !name.isNullOrEmpty() && name != "." && name != ".." && !name.contains('/') &&
            !name.contains('\\') && !name.contains('\u0000') && name.trim().isNotEmpty() && name.length <= 255

    fun isSystemPath(path: String): Boolean =
        SYSTEM_PREFIXES.any { path == it || path.startsWith("$it/") }

    /** 包含 [path] 的存储卷根（最长前缀），不在任何卷内返回 null。 */
    fun volumeOf(path: String, roots: List<String>): String? =
        roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }
            .filter { path == it || path.startsWith("$it/") }
            .maxByOrNull { it.length }

    fun relative(path: String, root: String): String =
        path.removePrefix(root.trimEnd('/')).trimStart('/')

    fun ext(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    fun isProtectedFileName(name: String): Boolean {
        val lower = name.lowercase()
        if (ext(lower) in PROTECTED_EXT) return true
        // a.db-journal 这类 ext() 拿不全
        if (lower.endsWith(".db-journal") || lower.endsWith(".db-wal") || lower.endsWith(".db-shm")) return true
        if (lower.endsWith("~")) return true
        if (lower.contains("备份")) return true
        return false
    }

    /**
     * 受保护原因；null 表示允许。[asSource] 为 true 时检查“这个路径本身能不能被动”，
     * false 时检查“能不能往这个目录里放东西 / 在这里建目录”。
     */
    fun protectedReason(path: String, volumeRoot: String, asSource: Boolean): String? {
        val rel = relative(path, volumeRoot)
        if (rel.isEmpty()) return if (asSource) "不能操作存储卷根目录" else null
        val segs = rel.split('/')
        val lower = segs.map { it.lowercase() }
        if (TrashPaths.isUnderTrash(path) || lower.any { it == ".xfiles-trash" }) return "回收站内部的文件不能直接操作"
        if (asSource && lower.size == 1 && lower[0] in STANDARD_TOP) return "系统标准目录不能移动或删除"
        if (lower.any { it in PROTECTED_SEGMENTS || it.contains("备份") }) return "应用数据库、备份或 Git 目录受保护"
        if (asSource && isProtectedFileName(segs.last())) return "数据库、密钥、证书或备份文件受保护"
        val mm = lower.indexOf("micromsg")
        if (mm >= 0) {
            val n1 = lower.getOrNull(mm + 1)
            val n2 = lower.getOrNull(mm + 2)
            val ok = (n1 != null && n1 in MICROMSG_ALLOWED && (asSource.not() || lower.size > mm + 2)) ||
                (n2 != null && n2 in MICROMSG_ALLOWED && (asSource.not() || lower.size > mm + 3))
            if (!ok) return "微信聊天记录目录受保护"
        }
        if (lower[0] == "android") {
            if (lower.size >= 2 && lower[1] in setOf("data", "obb", "media")) {
                if (!asSource) {
                    // 目标只能是应用目录里允许的用户文件目录（极少用到），默认不往应用目录里放东西
                    return "不能把文件放进应用数据目录"
                }
                if (lower.size <= 3) return "应用数据目录本身不能移动或删除"
                if (lower[1] == "obb") return "游戏数据包目录受保护"
                val pkg = lower[2]
                if (pkg in QQ_PACKAGES && lower.drop(3).none { it in QQ_ALLOWED }) return "QQ 聊天记录目录受保护"
            } else if (asSource) {
                return "Android 系统目录受保护"
            } else {
                return "不能把文件放进 Android 系统目录"
            }
        }
        return null
    }

    /** 从卷根往下，[path] 的任何一层（含自身）是符号链接时为 true。读不到的层按非链接处理。 */
    fun hasSymlink(path: String, volumeRoot: String): Boolean {
        val rel = relative(path, volumeRoot)
        if (rel.isEmpty()) return false
        var cur = volumeRoot.trimEnd('/')
        for (seg in rel.split('/')) {
            cur = "$cur/$seg"
            val link = runCatching { Files.isSymbolicLink(Paths.get(cur)) }.getOrDefault(false)
            if (link) return true
        }
        return false
    }

    fun parentOf(path: String): String {
        val p = path.trimEnd('/').substringBeforeLast('/', "")
        return p.ifEmpty { "/" }
    }

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun join(dir: String, name: String): String =
        if (dir == "/" || dir.isEmpty()) "/$name" else dir.trimEnd('/') + "/" + name

    fun isInside(child: String, ancestor: String): Boolean =
        child == ancestor || child.startsWith(ancestor.trimEnd('/') + "/")

    /** “名字 (1).ext”；[takenLower] 是已占用的小写名字集合（存储多数不区分大小写）。 */
    fun pickName(name: String, isDir: Boolean, takenLower: Set<String>): String {
        if (name.lowercase() !in takenLower) return name
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..9999) {
            val c = "$base ($i)$ext"
            if (c.lowercase() !in takenLower) return c
        }
        return "$base (${System.currentTimeMillis()})$ext"
    }
}
