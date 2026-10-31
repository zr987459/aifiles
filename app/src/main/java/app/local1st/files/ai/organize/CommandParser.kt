package app.local1st.files.ai.organize

import app.local1st.files.ai.Category
import app.local1st.files.ai.CommandIntent
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IntentAction
import app.local1st.files.ai.Sort
import java.util.Calendar

/**
 * M4：一句话 → 查询条件的本地规则解析（无 I/O、纯函数，便于单测）。
 * 例：“下载里 30 天前的安装包删掉”“上个月的截图”“微信里的 PDF 整理一下”“大于 100M 的视频”。
 * 至少识别出一个条件（位置 / 来源 / 类型 / 时间 / 大小）才算 [Result.recognized]，否则交给 AI。
 */
object CommandParser {

    data class Result(val intent: CommandIntent, val recognized: Boolean)

    private const val ROOT = DefaultOrganizer.ROOT
    private const val DAY = 86_400_000L

    fun actionZh(a: IntentAction): String = when (a) {
        IntentAction.FIND -> "查找"
        IntentAction.ORGANIZE -> "整理"
        IntentAction.CLEAN -> "清理"
        IntentAction.TRASH -> "删除"
    }

    private class Loc(val keys: List<String>, val chip: String, val pathPrefix: String? = null, val source: String? = null)

    private val LOCATIONS = listOf(
        Loc(listOf("微信", "wechat", "weixin"), "来源：微信", source = "wechat"),
        Loc(listOf("企业微信", "企微"), "来源：企业微信", source = "wework"),
        Loc(listOf("qq"), "来源：QQ", source = "qq"),
        Loc(listOf("telegram", "电报", "tg"), "来源：Telegram", source = "telegram"),
        Loc(listOf("whatsapp"), "来源：WhatsApp", source = "whatsapp"),
        Loc(listOf("抖音"), "来源：抖音", source = "douyin"),
        Loc(listOf("钉钉"), "来源：钉钉", source = "dingtalk"),
        Loc(listOf("飞书"), "来源：飞书", source = "feishu"),
        Loc(listOf("蓝牙"), "来源：蓝牙", source = "bluetooth"),
        Loc(listOf("相机", "相册", "拍的", "拍摄"), "来源：相机", source = "camera"),
        Loc(listOf("下载"), "位置：下载", pathPrefix = "$ROOT/Download"),
        Loc(listOf("文档目录", "documents"), "位置：文档", pathPrefix = "$ROOT/Documents"),
        Loc(listOf("音乐目录"), "位置：音乐", pathPrefix = "$ROOT/Music"),
        Loc(listOf("movies"), "位置：影片", pathPrefix = "$ROOT/Movies"),
    )

    private class Type(val keys: List<String>, val chip: String, val cats: Set<Category> = emptySet(), val sub: String? = null, val exts: Set<String> = emptySet())

    // 顺序：越具体越靠前（“聊天截图”先于“截图”，“PDF”先于“文档”）
    private val TYPES = listOf(
        Type(listOf("聊天截图"), "类型：聊天截图", setOf(Category.SCREENSHOT), "shot.chat"),
        Type(listOf("购物截图"), "类型：购物截图", setOf(Category.SCREENSHOT), "shot.shop"),
        Type(listOf("截图", "截屏"), "类型：截图", setOf(Category.SCREENSHOT)),
        Type(listOf("安装包", "apk"), "类型：安装包", setOf(Category.APK)),
        Type(listOf("压缩包", "zip", "rar", "7z"), "类型：压缩包", setOf(Category.ARCHIVE)),
        Type(listOf("pdf"), "类型：PDF", exts = setOf("pdf")),
        Type(listOf("word", "docx"), "类型：Word", exts = setOf("doc", "docx", "wps")),
        Type(listOf("excel", "表格", "xlsx"), "类型：表格", exts = setOf("xls", "xlsx", "csv", "et")),
        Type(listOf("ppt", "幻灯片", "演示文稿"), "类型：PPT", exts = setOf("ppt", "pptx", "dps")),
        Type(listOf("电子书"), "类型：电子书", setOf(Category.DOC), "doc.ebook"),
        Type(listOf("发票", "票据", "账单", "收据"), "类型：票据账单", setOf(Category.DOC), "doc.receipt"),
        Type(listOf("证件", "身份证", "护照"), "类型：证件", setOf(Category.DOC), "doc.id"),
        Type(listOf("文档", "文件资料"), "类型：文档", setOf(Category.DOC)),
        Type(listOf("照片"), "类型：照片", setOf(Category.IMAGE), "img.photo"),
        Type(listOf("图片", "表情包"), "类型：图片", setOf(Category.IMAGE)),
        Type(listOf("录屏"), "类型：录屏", setOf(Category.VIDEO), "video.record"),
        Type(listOf("视频"), "类型：视频", setOf(Category.VIDEO)),
        Type(listOf("语音"), "类型：语音", setOf(Category.AUDIO), "audio.voice"),
        Type(listOf("录音"), "类型：录音", setOf(Category.AUDIO), "audio.record"),
        Type(listOf("音乐", "歌曲"), "类型：音乐", setOf(Category.AUDIO), "audio.music"),
        Type(listOf("音频"), "类型：音频", setOf(Category.AUDIO)),
    )

    private val NUM = "(\\d+|[零一二两三四五六七八九十百半]+)"
    private val AGO = Regex("$NUM\\s*(天|日|周|星期|个月|月|年)(以前|之前|前)")
    private val RECENT = Regex("(最近|近|过去)\\s*$NUM\\s*(天|日|周|星期|个月|月|年)")
    private val SIZE = Regex("(大于|超过|多于|高于|小于|低于|不到|不超过|少于)?\\s*(\\d+(?:\\.\\d+)?)\\s*(k|kb|m|mb|兆|g|gb|t|tb)\\s*(以上|以下|以内)?", RegexOption.IGNORE_CASE)

    fun parse(raw: String, now: Long): Result {
        val text = raw.trim()
        val t = text.lowercase()
        val chips = ArrayList<String>()
        var recognized = false

        val action = when {
            listOf("删掉", "删了", "删除", "删", "扔掉", "丢掉", "移到回收站", "回收站").any { t.contains(it) } -> IntentAction.TRASH
            listOf("清理", "清除", "清空", "释放空间").any { t.contains(it) } -> IntentAction.CLEAN
            listOf("整理", "归类", "归档", "分类", "收拾", "移到", "移动到", "放到", "挪到").any { t.contains(it) } -> IntentAction.ORGANIZE
            else -> IntentAction.FIND
        }

        var pathPrefix: String? = null
        var source: String? = null
        for (l in LOCATIONS) {
            if (l.keys.none { k -> containsWord(t, k) }) continue
            if (l.pathPrefix != null && pathPrefix == null) { pathPrefix = l.pathPrefix; chips.add(l.chip); recognized = true }
            if (l.source != null && source == null) { source = l.source; chips.add(l.chip); recognized = true }
        }

        val cats = LinkedHashSet<Category>()
        var sub: String? = null
        val exts = LinkedHashSet<String>()
        var tt = t
        for (ty in TYPES) {
            val hit = ty.keys.firstOrNull { k -> containsWord(tt, k) } ?: continue
            // 已命中更具体的类型时跳过同组的泛类（“聊天截图”后不再加“截图”）
            if (ty.sub == null && ty.cats.isNotEmpty() && cats.containsAll(ty.cats) && sub != null) { tt = tt.replace(hit, " "); continue }
            cats.addAll(ty.cats)
            if (ty.sub != null && sub == null) sub = ty.sub
            exts.addAll(ty.exts)
            chips.add(ty.chip)
            recognized = true
            tt = tt.replace(hit, " ")
        }
        // 只给了扩展名时，类别由扩展名决定，不再限制一级
        if (exts.isNotEmpty() && sub == null) cats.removeAll { it == Category.DOC }

        var older: Long? = null
        var newer: Long? = null
        AGO.find(t)?.let { m ->
            val n = cnNum(m.groupValues[1])
            if (n != null && n > 0) { older = (n * unitMs(m.groupValues[2])).toLong(); chips.add("时间：${fmtNum(n)} ${unitZh(m.groupValues[2])}前"); recognized = true }
        }
        if (older == null) RECENT.find(t)?.let { m ->
            val n = cnNum(m.groupValues[2])
            if (n != null && n > 0) { newer = (n * unitMs(m.groupValues[3])).toLong(); chips.add("时间：最近 ${fmtNum(n)} ${unitZh(m.groupValues[3])}"); recognized = true }
        }
        if (older == null && newer == null) {
            val w = namedWindow(t, now)
            if (w != null) { newer = w.newer; older = w.older; chips.add("时间：${w.label}"); recognized = true }
        }

        var minSize: Long? = null
        var maxSize: Long? = null
        SIZE.findAll(t).forEach { m ->
            val cmp = m.groupValues[1]
            val suffix = m.groupValues[4]
            val bytes = (m.groupValues[2].toDouble() * sizeUnit(m.groupValues[3])).toLong()
            val isMax = cmp in setOf("小于", "低于", "不到", "不超过", "少于") || suffix == "以下" || suffix == "以内"
            val isMin = cmp in setOf("大于", "超过", "多于", "高于") || suffix == "以上"
            val label = m.groupValues[2] + " " + sizeLabel(m.groupValues[3])
            if (isMax && maxSize == null) { maxSize = bytes; chips.add("大小：小于 $label"); recognized = true }
            else if (isMin && minSize == null) { minSize = bytes; chips.add("大小：大于 $label"); recognized = true }
        }
        if (minSize == null && maxSize == null && listOf("大文件", "占空间", "很大的").any { t.contains(it) }) {
            minSize = 100L * 1024 * 1024; chips.add("大小：大于 100 MB"); recognized = true
        }

        chips.add("动作：${actionZh(action)}")
        val sort = when {
            minSize != null || t.contains("最大") -> Sort.SIZE_DESC
            older != null || t.contains("最早") || t.contains("最旧") -> Sort.MTIME_ASC
            else -> Sort.MTIME_DESC
        }
        val query = FileQuery(
            text = if (recognized) null else text.ifEmpty { null },
            categories = cats,
            subCategory = sub,
            sourceApp = source,
            pathPrefix = pathPrefix,
            exts = exts,
            minSize = minSize,
            maxSize = maxSize,
            olderThanMs = older,
            newerThanMs = newer,
            sort = sort,
        )
        if (!recognized) chips.add(0, "搜索：$text")
        // 删除/清理却没有任何类型或位置限制时标黄
        val uncertain = !recognized || ((action == IntentAction.TRASH || action == IntentAction.CLEAN) && cats.isEmpty() && exts.isEmpty() && pathPrefix == null && source == null)
        return Result(CommandIntent(action, query, chips, destDir = null, raw = raw, uncertain = uncertain), recognized)
    }

    /** 英文关键字按词边界匹配（避免 “tg” 命中 “settings”），中文直接包含。 */
    private fun containsWord(t: String, k: String): Boolean {
        if (k.any { it.code > 0x7f }) return t.contains(k)
        return Regex("(?<![a-z0-9])" + Regex.escape(k) + "(?![a-z0-9])").containsMatchIn(t)
    }

    private class Window(val newer: Long?, val older: Long?, val label: String)

    private fun startOfDay(now: Long, daysBack: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now; add(Calendar.DAY_OF_YEAR, -daysBack)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfMonth(now: Long, monthsBack: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -monthsBack)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfWeek(now: Long, weeksBack: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        val dow = (get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一=0
        add(Calendar.DAY_OF_YEAR, -dow - 7 * weeksBack)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfYear(now: Long, yearsBack: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.DAY_OF_YEAR, 1); add(Calendar.YEAR, -yearsBack)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun namedWindow(t: String, now: Long): Window? {
        fun between(start: Long, end: Long?, label: String) = Window(now - start, end?.let { now - it }, label)
        return when {
            t.contains("今天") -> between(startOfDay(now, 0), null, "今天")
            t.contains("昨天") -> between(startOfDay(now, 1), startOfDay(now, 0), "昨天")
            t.contains("前天") -> between(startOfDay(now, 2), startOfDay(now, 1), "前天")
            t.contains("上周") || t.contains("上星期") -> between(startOfWeek(now, 1), startOfWeek(now, 0), "上周")
            t.contains("本周") || t.contains("这周") || t.contains("这星期") -> between(startOfWeek(now, 0), null, "本周")
            t.contains("上个月") || t.contains("上月") -> between(startOfMonth(now, 1), startOfMonth(now, 0), "上个月")
            t.contains("本月") || t.contains("这个月") -> between(startOfMonth(now, 0), null, "本月")
            t.contains("去年") -> between(startOfYear(now, 1), startOfYear(now, 0), "去年")
            t.contains("今年") -> between(startOfYear(now, 0), null, "今年")
            t.contains("最近") || t.contains("近期") -> Window(7 * DAY, null, "最近 7 天")
            t.contains("很久") || t.contains("好久") || t.contains("老的") || t.contains("旧的") -> Window(null, 180 * DAY, "半年前")
            else -> null
        }
    }

    private fun unitMs(u: String): Double = when (u) {
        "天", "日" -> DAY.toDouble()
        "周", "星期" -> 7.0 * DAY
        "个月", "月" -> 30.0 * DAY
        "年" -> 365.0 * DAY
        else -> DAY.toDouble()
    }

    private fun unitZh(u: String): String = when (u) {
        "天", "日" -> "天"
        "周", "星期" -> "周"
        "个月", "月" -> "个月"
        "年" -> "年"
        else -> u
    }

    private fun fmtNum(n: Double): String = if (n == Math.floor(n)) n.toLong().toString() else n.toString()

    private fun sizeUnit(u: String): Double = when (u.lowercase()) {
        "k", "kb" -> 1024.0
        "m", "mb", "兆" -> 1024.0 * 1024
        "g", "gb" -> 1024.0 * 1024 * 1024
        "t", "tb" -> 1024.0 * 1024 * 1024 * 1024
        else -> 1.0
    }

    private fun sizeLabel(u: String): String = when (u.lowercase()) {
        "k", "kb" -> "KB"
        "m", "mb", "兆" -> "MB"
        "g", "gb" -> "GB"
        "t", "tb" -> "TB"
        else -> u
    }

    /** 阿拉伯数字或简单中文数字（一 ~ 九百九十九、两、半）。 */
    fun cnNum(s: String): Double? {
        s.toDoubleOrNull()?.let { return it }
        if (s == "半") return 0.5
        val d = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var cur = 0
        for (c in s) {
            when (c) {
                in d.keys -> cur = d[c]!!
                '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                '半' -> return null
                else -> return null
            }
        }
        val v = total + cur
        return if (v > 0) v.toDouble() else null
    }
}
