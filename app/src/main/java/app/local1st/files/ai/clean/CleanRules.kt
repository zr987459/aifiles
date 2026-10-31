package app.local1st.files.ai.clean

import app.local1st.files.ai.Category
import app.local1st.files.ai.IndexedFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 清理扫描用到的纯规则：安全边界、路径工具、风险与文案。无 I/O。
 *
 * 安全边界（合同“永远不碰”）：只处理 /storage 下的用户存储；不碰 Android/obb、各 App 的数据库与
 * 聊天记录目录（微信 MicroMsg/星/ 下只允许 Download、video、image2、File）、
 * .db .key .keystore .pem .kdbx .nomedia 以及备份文件。
 */
internal object CleanRules {
    const val DAY = 24L * 60 * 60 * 1000
    const val RECENT_MS = 7 * DAY
    const val OLD_DOWNLOAD_MS = 90 * DAY
    const val LARGE_BYTES = 100L * 1024 * 1024
    const val SHORT_VIDEO_MS = 3000L
    const val BLUR_VARIANCE = 50.0
    const val MAX_IMAGES = 2000
    const val MAX_VIDEOS = 2000
    const val MIN_DUP_BYTES = 1024L

    /** 永不处理的扩展名（数据库、密钥、证书、密码库、媒体屏蔽标记、备份）。 */
    private val FORBIDDEN_EXTS = setOf(
        "db", "db-wal", "db-shm", "db-journal", "sqlite", "sqlite3", "realm",
        "key", "keystore", "jks", "bks", "p12", "pfx", "pem", "kdbx", "kdb", "nomedia",
        "bak", "backup", "ab", "tibkp", "nbu", "nbf",
    )

    /** 聊天 App 包名 → 其 Android/data 目录里允许清理的子目录名。 */
    private val CHAT_ALLOWED = mapOf(
        "com.tencent.mm" to setOf("download", "video", "image2", "file"),
        "com.tencent.mobileqq" to setOf("qqfile_recv", "chatpic", "shortvideo", "qq_images", "photo"),
        "com.tencent.tim" to setOf("timfile_recv", "chatpic", "shortvideo", "photo"),
        "org.telegram.messenger" to setOf("telegram images", "telegram video", "telegram audio", "telegram documents"),
        "org.telegram.messenger.web" to setOf("telegram images", "telegram video", "telegram audio", "telegram documents"),
        "org.thunderdog.challegram" to setOf("photos", "videos", "music", "documents"),
    )

    private val VOLUME_RE = Regex("^(/storage/emulated/\\d+|/storage/[^/]+)")

    /** 卷根，如 /storage/emulated/0；不在 /storage 下返回 null。 */
    fun volumeRoot(path: String): String? = VOLUME_RE.find(path)?.value

    /** 卷根之后的相对路径段。 */
    fun relSegments(path: String): List<String> {
        val root = volumeRoot(path) ?: return emptyList()
        return path.removePrefix(root).split('/').filter { it.isNotEmpty() }
    }

    fun ext(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot < 0) "" else name.substring(dot + 1).lowercase()
    }

    /** true = 这条路径永远不能出现在任何清理分组里。 */
    fun isForbidden(path: String): Boolean {
        if (volumeRoot(path) == null) return true
        val segs = relSegments(path)
        val name = path.substringAfterLast('/')
        val lowerName = name.lowercase()
        if (lowerName == ".nomedia" || ext(path) in FORBIDDEN_EXTS) return true
        if (lowerName.contains("backup") || lowerName.contains("备份")) return true
        for (s in segs) {
            val ls = s.lowercase()
            if (ls == "backup" || ls == "backups" || ls.contains("备份") || ls == "databases" || ls == "shared_prefs") return true
        }
        if (segs.size >= 2 && segs[0].equals("Android", true)) {
            val area = segs[1].lowercase()
            if (area == "obb") return true
            if (area == "data" || area == "media") {
                if (segs.size < 3) return true // Android/data 本身
                val pkg = segs[2].lowercase()
                val allowed = CHAT_ALLOWED[pkg]
                if (allowed != null) {
                    val rest = segs.drop(3).map { it.lowercase() }
                    // 微信：MicroMsg/星/ 下只允许指定目录；MicroMsg/Download 也算
                    val mm = rest.indexOf("micromsg")
                    if (mm >= 0) {
                        val a = rest.getOrNull(mm + 1)
                        val b = rest.getOrNull(mm + 2)
                        return !(a in allowed || b in allowed)
                    }
                    return rest.none { it in allowed }
                }
            }
        }
        return false
    }

    fun isProtectedType(f: IndexedFile): Boolean = f.category == Category.DOC || f.category == Category.ARCHIVE

    fun isRecent(mtime: Long, now: Long): Boolean = mtime > 0 && now - mtime < RECENT_MS

    /** 文档、压缩包、近 7 天文件：只显示不让删（KEEP）。 */
    fun isKeepFile(f: IndexedFile, now: Long): Boolean = isProtectedType(f) || isRecent(f.mtime, now)

    fun isDcim(path: String): Boolean = relSegments(path).firstOrNull()?.equals("DCIM", true) == true

    fun isUnder(path: String, dir: String): Boolean = path.startsWith(dir.trimEnd('/') + "/")

    /** 卷根下的标准公共目录：空了也不删。 */
    private val STANDARD_DIRS = setOf(
        "dcim", "download", "downloads", "pictures", "movies", "music", "documents", "alarms",
        "ringtones", "notifications", "podcasts", "audiobooks", "recordings", "android", "screenshots",
    )

    /** 空文件夹是否可以列入：不在 Android/ 下、不是卷根或标准目录、不是受保护路径。 */
    fun emptyDirAllowed(path: String): Boolean {
        if (isForbidden(path)) return false
        val segs = relSegments(path)
        if (segs.isEmpty()) return false
        if (segs[0].equals("Android", true)) return false
        if (segs.size == 1 && segs[0].lowercase() in STANDARD_DIRS) return false
        if (segs.size == 2 && segs[0].equals("DCIM", true) && segs[1].equals("Camera", true)) return false
        return true
    }

    /** 缓存/缩略图目录名。 */
    val CACHE_SEGS = setOf(".thumbnails", "thumbnails", "cache", ".cache", "caches", "temp", ".temp", "tmp", ".tmp")

    /** 只靠名字不一定是缓存（可能是用户自己建的），里面有文档/新文件就跳过。 */
    val WEAK_CACHE_SEGS = setOf("temp", ".temp", "tmp", ".tmp", "thumbnails")

    /** 文件所属的缓存目录（最浅的一层），不是缓存返回 null。 */
    fun cacheDirOf(path: String): String? {
        val root = volumeRoot(path) ?: return null
        val segs = relSegments(path)
        // 最后一段是文件名本身，不算
        for (i in 0 until segs.size - 1) {
            val s = segs[i].lowercase()
            if (s in CACHE_SEGS) {
                return root + "/" + segs.subList(0, i + 1).joinToString("/")
            }
        }
        return null
    }

    private val PKG_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /** 看起来像包名的文件夹名（至少一个点、每段字母开头）。 */
    fun looksLikePackage(name: String): Boolean = name.length in 5..150 && PKG_RE.matches(name)

    fun month(mtime: Long): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(mtime))

    fun monthZh(mtime: Long): String = SimpleDateFormat("yyyy年M月", Locale.CHINA).format(Date(mtime))

    fun fmtSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i]) else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** reason 最多 40 字。 */
    fun clip(s: String): String = if (s.length <= 40) s else s.take(40)

    fun riskRank(r: app.local1st.files.ai.Risk): Int = when (r) {
        app.local1st.files.ai.Risk.SAFE -> 0
        app.local1st.files.ai.Risk.CONFIRM -> 1
        app.local1st.files.ai.Risk.KEEP -> 2
    }

    // ---------- 文案（≤40 字：是什么 + 为什么能删 + 删了会怎样） ----------
    const val R_DUP = "内容完全相同的副本，已自动保留一份，删除不会丢内容"
    const val R_SIMILAR = "相似的连拍照片，已保留最清晰的一张，其余删后进回收站"
    const val R_BLURRY = "画面模糊的照片，多为手抖误拍，删除后可在回收站找回"
    const val R_DARK = "几乎全黑的暗光照片，多为误拍，删除后可在回收站找回"
    const val R_APK = "对应应用已安装同版或更新版，安装包可删，不影响已装应用"
    const val R_OLD_DL = "90 天前下载且没再改动，可能已不需要，删前请确认"
    const val R_LARGE = "超过 100MB 的大文件，最占空间，删前确认是否还要用"
    const val R_SHORT = "不到 3 秒的短视频，多为误触录制，删除后可在回收站找回"
    const val R_RESIDUAL = "已卸载应用留下的文件夹，应用已不在，删前确认无重要资料"
    const val R_CACHE = "缓存和缩略图，会自动重建，删除只会让首次加载变慢"
    const val R_EMPTY = "空文件夹，里面没有任何文件，删除不影响使用"
    const val R_KEEP = "文档、压缩包或近 7 天的新文件，只显示，不建议删除"
    fun rShot(m: String) = clip("${m}的截图，多为临时记录，确认没用再删，删后进回收站")
    fun rChat(src: String, m: String) = clip("${src}${m}收到的图片视频，删后聊天里看不到原图")
}
