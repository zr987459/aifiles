package app.local1st.files.ai.index

import app.local1st.files.core.fs.TrashPaths

/** 扫描时跳过哪些目录 / 文件（纯字符串判断，无 I/O）。 */
internal object IndexRules {

    /** 永不索引的扩展名：密钥、数据库、媒体屏蔽标记。 */
    private val SKIP_EXTS = setOf("db", "db-wal", "db-shm", "db-journal", "key", "keystore", "jks", "pem", "kdbx", "nomedia")

    /** 微信 MicroMsg 下只允许这几个目录。 */
    private val WECHAT_ALLOWED = setOf("Download", "video", "image2", "File")

    private const val WECHAT_ROOT = "/Android/data/com.tencent.mm/MicroMsg"

    fun skipDir(path: String, name: String): Boolean {
        if (name == TrashPaths.DIR_NAME || name == ".thumbnails") return true
        if (name.startsWith(".xfiles-")) return true
        if (path.endsWith("/Android/obb") || path.contains("/Android/obb/")) return true
        // 各 App 的数据库 / 配置目录
        if (name == "databases" || name == "shared_prefs") {
            if (path.contains("/Android/data/")) return true
        }
        val rel = wechatRel(path) ?: return false
        val segs = rel.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return false
        if (segs[0] in WECHAT_ALLOWED) return false
        // MicroMsg/<账号哈希>/ 本身可以进去（只为找到下面允许的目录），再往下只认白名单
        if (segs.size == 1) return false
        return segs[1] !in WECHAT_ALLOWED
    }

    fun skipFile(parent: String, name: String, ext: String): Boolean {
        if (ext in SKIP_EXTS) return true
        if (name.contains(".xfiles-tmp") || name.endsWith(".xfiles-ready")) return true
        val rel = wechatRel(parent) ?: return false
        val segs = rel.split('/').filter { it.isNotEmpty() }
        // MicroMsg/ 和 MicroMsg/<账号>/ 直接下面的文件多是数据库和配置，不索引
        if (segs.isEmpty()) return true
        if (segs[0] in WECHAT_ALLOWED) return false
        return segs.size < 2 || segs[1] !in WECHAT_ALLOWED
    }

    /** 路径在 MicroMsg 下时返回 MicroMsg 之后的相对部分（"" 表示就是 MicroMsg），否则 null。 */
    private fun wechatRel(path: String): String? {
        val i = path.indexOf(WECHAT_ROOT)
        if (i < 0) return null
        val rest = path.substring(i + WECHAT_ROOT.length)
        if (rest.isNotEmpty() && !rest.startsWith("/")) return null
        return rest.trimStart('/')
    }
}
