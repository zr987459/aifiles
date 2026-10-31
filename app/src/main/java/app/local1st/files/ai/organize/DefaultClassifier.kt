package app.local1st.files.ai.organize

import app.local1st.files.ai.Category
import app.local1st.files.ai.Classification
import app.local1st.files.ai.Classifier
import java.util.Locale

/**
 * M4：纯规则快速分类（无 I/O）。
 *
 * - 一级：按扩展名 / MIME 判 8 类；图片在截图目录或文件名像截图时归为 [Category.SCREENSHOT]。
 * - 二级：只用 [SUB_IDS] 白名单里的英文 ID；判不出来时为 null（交给 AI 细分）。
 * - 来源：按路径识别聊天 App、相机、截图、下载、蓝牙、录音等。
 */
class DefaultClassifier : Classifier {

    companion object {
        /** 二级分类白名单（ID → 中文）。AI 细分也只能从这里选。 */
        val SUB_IDS: LinkedHashMap<String, String> = linkedMapOf(
            "doc.work" to "工作文档",
            "doc.study" to "学习资料",
            "doc.receipt" to "票据账单",
            "doc.id" to "证件",
            "doc.ebook" to "电子书",
            "doc.other" to "其他文档",
            "img.photo" to "照片",
            "img.saved" to "保存的图片",
            "shot.chat" to "聊天截图",
            "shot.shop" to "购物截图",
            "shot.code" to "代码截图",
            "shot.other" to "其他截图",
            "video.record" to "拍摄录制",
            "video.saved" to "保存的视频",
            "audio.music" to "音乐",
            "audio.voice" to "语音",
            "audio.record" to "录音",
        )

        val SOURCE_ZH: Map<String, String> = linkedMapOf(
            "wechat" to "微信",
            "qq" to "QQ",
            "wework" to "企业微信",
            "telegram" to "Telegram",
            "whatsapp" to "WhatsApp",
            "douyin" to "抖音",
            "kuaishou" to "快手",
            "weibo" to "微博",
            "xiaohongshu" to "小红书",
            "dingtalk" to "钉钉",
            "feishu" to "飞书",
            "bilibili" to "哔哩哔哩",
            "camera" to "相机",
            "screenshot" to "截图",
            "screenrecord" to "录屏",
            "download" to "下载",
            "bluetooth" to "蓝牙",
            "recordings" to "录音",
            "browser" to "浏览器",
        )

        /** 聊天 / 社交类来源（截图归 shot.chat、音频归 audio.voice、整理时不搬它们目录里的文件）。 */
        val CHAT_SOURCES = setOf("wechat", "qq", "wework", "telegram", "whatsapp", "dingtalk", "feishu")

        /** 二级 ID 对应的一级分类。 */
        fun categoryOfSub(id: String): Category? = when (id.substringBefore('.')) {
            "doc" -> Category.DOC
            "img" -> Category.IMAGE
            "shot" -> Category.SCREENSHOT
            "video" -> Category.VIDEO
            "audio" -> Category.AUDIO
            else -> null
        }

        val DOC_EXT = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "odt", "ods", "odp", "csv",
            "wps", "et", "dps", "pages", "numbers", "key", "epub", "mobi", "azw", "azw3", "fb2", "djvu", "chm",
            "ofd", "xps", "tex", "json", "xml", "html", "htm", "log",
        )
        val EBOOK_EXT = setOf("epub", "mobi", "azw", "azw3", "fb2", "djvu", "chm")
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "dng", "raw", "cr2", "nef", "arw", "avif", "ico")
        val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "wmv", "flv", "3gp", "m4v", "webm", "ts", "mpg", "mpeg", "rmvb", "rm")
        val AUDIO_EXT = setOf("mp3", "flac", "ape", "wav", "ogg", "aac", "m4a", "wma", "opus", "amr", "silk", "mid", "midi", "3ga", "slk")
        val MUSIC_EXT = setOf("mp3", "flac", "ape", "wav", "ogg", "aac", "m4a", "wma", "mid", "midi")
        val VOICE_EXT = setOf("amr", "silk", "slk", "opus")
        val APK_EXT = setOf("apk", "apks", "xapk", "apkm")
        val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "lzma", "iso", "cab")

        // 截图文件名里常带前台 App 的包名（Screenshot_20240101_120000_com.tencent.mm.jpg）
        private val SHOT_CHAT_KEYS = listOf(
            "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework", "org.telegram", "com.whatsapp",
            "com.alibaba.android.rimet", "com.ss.android.lark", "wechat", "weixin", "微信", "telegram", "whatsapp", "聊天",
        )
        private val SHOT_SHOP_KEYS = listOf(
            "com.taobao", "com.tmall", "com.jingdong", "com.xunmeng.pinduoduo", "com.sankuai", "me.ele", "com.amazon",
            "com.alibaba.wireless", "com.achievo.vipshop", "com.xingin.xhs", "taobao", "tmall", "jd.com", "pinduoduo", "淘宝", "京东", "拼多多", "美团", "订单",
        )
        private val SHOT_CODE_KEYS = listOf(
            "com.github", "com.termux", "com.aide", "com.jetbrains", "com.microsoft.vscode", "stackoverflow", "github", "termux", "code", "代码",
        )

        private val RECEIPT_KEYS = listOf("发票", "收据", "账单", "小票", "订单", "报销", "流水", "回单", "invoice", "receipt", "bill", "statement", "order")
        private val ID_KEYS = listOf("身份证", "护照", "驾驶证", "驾照", "行驶证", "户口", "社保卡", "银行卡", "学生证", "工作证", "签证", "passport", "idcard", "id_card", "license", "visa")
        private val STUDY_KEYS = listOf("作业", "课件", "论文", "试卷", "试题", "笔记", "讲义", "教材", "复习", "考试", "学习", "习题", "答案", "课程", "homework", "lecture", "thesis", "exam", "course", "notes", "paper", "chapter")
        private val WORK_KEYS = listOf("合同", "报告", "会议", "方案", "简历", "周报", "月报", "日报", "项目", "报价", "汇报", "纪要", "需求", "说明书", "协议", "预算", "contract", "report", "meeting", "resume", "proposal", "quotation", "budget", "project")

        private val CAMERA_NAME = Regex("^(img|pxl|dsc|dscn|dsc_|mvimg|vid|p|pano|burst)[_-]?\\d{6,}.*", RegexOption.IGNORE_CASE)
        private val SHOT_NAME = Regex("(screenshot|screen_shot|screencap|截屏|截图|屏幕截图|snapshot)", RegexOption.IGNORE_CASE)
        private val RECORD_NAME = Regex("(screenrecord|screen_record|screen-record|录屏|屏幕录制|record|录音|通话)", RegexOption.IGNORE_CASE)
    }

    override fun classifyFast(path: String, name: String, ext: String, mime: String?, size: Long): Classification {
        val p = path.replace('\\', '/').lowercase(Locale.ROOT)
        val n = name.lowercase(Locale.ROOT)
        val e = ext.lowercase(Locale.ROOT).removePrefix(".").ifEmpty { n.substringAfterLast('.', "") }
        val m = mime?.lowercase(Locale.ROOT)
        val source = detectSource(p, n)

        var category = when {
            e in IMAGE_EXT -> Category.IMAGE
            e in VIDEO_EXT -> Category.VIDEO
            e in AUDIO_EXT -> Category.AUDIO
            e in APK_EXT -> Category.APK
            e in ARCHIVE_EXT -> Category.ARCHIVE
            e in DOC_EXT -> Category.DOC
            m == null -> Category.OTHER
            m.startsWith("image/") -> Category.IMAGE
            m.startsWith("video/") -> Category.VIDEO
            m.startsWith("audio/") -> Category.AUDIO
            m == "application/vnd.android.package-archive" -> Category.APK
            m.startsWith("text/") || m == "application/pdf" || m.contains("document") || m.contains("msword") || m.contains("spreadsheet") || m.contains("presentation") -> Category.DOC
            m.contains("zip") || m.contains("rar") || m.contains("7z") || m.contains("tar") || m.contains("compressed") -> Category.ARCHIVE
            else -> Category.OTHER
        }
        if (category == Category.IMAGE && (source == "screenshot" || SHOT_NAME.containsMatchIn(n))) category = Category.SCREENSHOT

        val sub: String? = when (category) {
            Category.DOC -> docSub(n, e)
            Category.IMAGE -> when {
                source == "camera" -> "img.photo"
                source != null && source != "camera" -> "img.saved"
                CAMERA_NAME.matches(n) && p.contains("/dcim/") -> "img.photo"
                else -> "img.saved"
            }
            Category.SCREENSHOT -> when {
                SHOT_CHAT_KEYS.any { n.contains(it) } -> "shot.chat"
                SHOT_SHOP_KEYS.any { n.contains(it) } -> "shot.shop"
                SHOT_CODE_KEYS.any { n.contains(it) } -> "shot.code"
                else -> "shot.other"
            }
            Category.VIDEO -> when {
                source == "camera" || source == "screenrecord" || source == "recordings" -> "video.record"
                p.contains("/dcim/") && source == null -> "video.record"
                RECORD_NAME.containsMatchIn(n) && source !in CHAT_SOURCES -> "video.record"
                else -> "video.saved"
            }
            Category.AUDIO -> when {
                source == "recordings" || RECORD_NAME.containsMatchIn(n) || p.contains("/record") -> "audio.record"
                e in VOICE_EXT || source in CHAT_SOURCES -> "audio.voice"
                e in MUSIC_EXT -> "audio.music"
                else -> null
            }
            else -> null
        }
        val tags = ArrayList<String>(2)
        if (source != null) tags.add(source)
        return Classification(category, sub, source, tags)
    }

    private fun docSub(n: String, e: String): String? = when {
        e in EBOOK_EXT -> "doc.ebook"
        ID_KEYS.any { n.contains(it) } -> "doc.id"
        RECEIPT_KEYS.any { n.contains(it) } -> "doc.receipt"
        STUDY_KEYS.any { n.contains(it) } -> "doc.study"
        WORK_KEYS.any { n.contains(it) } -> "doc.work"
        else -> null
    }

    /** p、n 已转小写。顺序：具体的聊天 App → 截图/录屏 → 相机 → 蓝牙/录音 → 下载。 */
    private fun detectSource(p: String, n: String): String? = when {
        p.contains("/android/data/com.tencent.mm/") || p.contains("/android/media/com.tencent.mm/") || p.contains("/tencent/micromsg/") ||
            p.contains("/pictures/weixin") || p.contains("/download/weixin") || p.contains("/weixin/") || p.contains("/wechat/") ||
            n.startsWith("mmexport") || n.startsWith("wx_camera_") -> "wechat"
        p.contains("/android/data/com.tencent.wework/") || p.contains("/wxwork/") || p.contains("/pictures/wework") -> "wework"
        p.contains("/android/data/com.tencent.mobileqq/") || p.contains("/android/data/com.tencent.tim/") || p.contains("/tencent/qq_") ||
            p.contains("/tencent/qqfile_recv") || p.contains("/tencent/tim") || p.contains("/pictures/qq") || p.contains("/download/qq") ||
            p.contains("/tencent/mobileqq") -> "qq"
        p.contains("org.telegram") || p.contains("/telegram/") || p.contains("/telegram x/") -> "telegram"
        p.contains("com.whatsapp") || p.contains("/whatsapp/") -> "whatsapp"
        p.contains("com.ss.android.ugc.aweme") || p.contains("/douyin/") || p.contains("/dcim/douyin") || p.contains("/抖音/") -> "douyin"
        p.contains("com.smile.gifmaker") || p.contains("com.kuaishou") || p.contains("/kuaishou/") || p.contains("/快手/") -> "kuaishou"
        p.contains("com.sina.weibo") || p.contains("/weibo/") || p.contains("/sina/") -> "weibo"
        p.contains("com.xingin.xhs") || p.contains("/xhs/") || p.contains("/xiaohongshu/") || p.contains("/小红书/") -> "xiaohongshu"
        p.contains("com.alibaba.android.rimet") || p.contains("/dingtalk/") || p.contains("/钉钉/") -> "dingtalk"
        p.contains("com.ss.android.lark") || p.contains("/feishu/") || p.contains("/lark/") || p.contains("/飞书/") -> "feishu"
        p.contains("tv.danmaku.bili") || p.contains("/bilibili/") -> "bilibili"
        p.contains("/screenshots/") || p.contains("/screenshot/") || p.contains("/截屏/") || p.contains("/截图/") -> "screenshot"
        p.contains("/screenrecorder") || p.contains("/screenrecord") || p.contains("/screen_record") || p.contains("/录屏") -> "screenrecord"
        p.contains("/dcim/camera/") || p.contains("/dcim/100andro/") || p.contains("/dcim/100media/") || p.contains("/dcim/opencamera/") -> "camera"
        p.contains("/bluetooth/") || p.contains("/download/bluetooth") -> "bluetooth"
        p.contains("/recordings/") || p.contains("/recorder/") || p.contains("/sound_recorder/") || p.contains("/soundrecorder/") ||
            p.contains("/callrecord") || p.contains("/call_record") || p.contains("/录音/") || p.contains("/record/") -> "recordings"
        p.contains("/download/") || p.contains("/downloads/") -> "download"
        p.contains("/browser/") || p.contains("/ucdownloads/") || p.contains("/quark/") || p.contains("/qqbrowser/") -> "browser"
        else -> null
    }

    override fun subCategoryZh(id: String): String = SUB_IDS[id] ?: id

    override fun sourceAppZh(id: String): String = SOURCE_ZH[id] ?: id
}
