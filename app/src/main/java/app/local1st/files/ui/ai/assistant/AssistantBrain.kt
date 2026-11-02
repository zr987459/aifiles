package app.local1st.files.ui.ai.assistant

import androidx.compose.runtime.mutableStateListOf
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.Category
import app.local1st.files.ai.CategoryStat
import app.local1st.files.ai.CleanGroup
import app.local1st.files.ai.CommandIntent
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IndexedFile
import app.local1st.files.ai.IntentAction
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanAction
import app.local1st.files.ai.PlanKind
import app.local1st.files.ai.Risk
import app.local1st.files.ai.llm.DefaultLlmClient
import app.local1st.files.ui.main.AppScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/*
 * AI 助手的“大脑”：把一句话变成一条回复（文字 + 我理解为标签 + 可点按钮）。
 * 全部是 suspend 函数，由界面放在 Dispatchers.IO 调用。
 */

internal const val ROOT = "/storage/emulated/0"

/** 按钮要做的事：直接打开页面，或先在后台算出页面（如生成计划）。 */
internal sealed interface BtnAction {
    data class Open(val screen: AppScreen) : BtnAction
    class Run(val emptyText: String, val work: suspend () -> AppScreen?) : BtnAction
}

internal data class ChatButton(val label: String, val action: BtnAction, val primary: Boolean = false)

internal data class ChatMsg(
    val id: Long,
    val user: Boolean,
    val text: String,
    val chips: List<String> = emptyList(),
    val uncertain: Boolean = false,
    val buttons: List<ChatButton> = emptyList(),
    val pending: Boolean = false,
    /** 小字备注，如“本地分析” / “AI 回答”。 */
    val note: String? = null,
    /** 出错的回复：用错误色显示。 */
    val error: Boolean = false,
    /** 出错时可“再试一次”的原问题。 */
    val retry: String? = null,
)

/** 进程内会话：从“查看文件”返回后对话还在。 */
internal object AssistantSession {
    val messages = mutableStateListOf<ChatMsg>()
    private val seq = AtomicLong(1)
    fun nextId(): Long = seq.getAndIncrement()

    @Volatile var cleanCache: Pair<Long, List<CleanGroup>>? = null
}

internal val EXAMPLES = listOf("哪些能删？", "什么最占空间？", "整理下载文件夹", "找上个月的截图")

private val SETTINGS_BTN = ChatButton("开通 AI（更聪明）", BtnAction.Open(AppScreen.AiSettings))
private val CLEAN_BTN = ChatButton("去清理页", BtnAction.Open(AppScreen.AiClean))

// ================================================================= 入口

internal suspend fun answer(text: String): ChatMsg {
    if (isStorageQuestion(text)) return storageAnswer(text)
    val intent = AiGraph.organizer.parseCommand(text)
    return intentReply(intent)
}

/** “哪些能删”“什么最占空间”这类问题。 */
internal fun isStorageQuestion(t: String): Boolean {
    val s = t.replace(" ", "")
    val keys = listOf(
        "能删", "可以删", "该删", "删什么", "删哪", "删掉什么", "占空间", "占用", "最占", "空间不够", "空间不足",
        "内存不够", "内存不足", "存储", "满了", "瘦身", "清理建议", "怎么清理", "释放空间", "腾空间", "腾出",
    )
    return keys.any { s.contains(it) } || (s.contains("最大") && !s.contains("大于"))
}

// ================================================================= 指令

private suspend fun intentReply(intent: CommandIntent): ChatMsg {
    val ready = AiGraph.llm.isReady()
    val q = intent.query
    val cond = hasCondition(q)
    val chips = intent.chips.ifEmpty { listOf("内容：" + intent.raw.take(20)) }
    val title = intentTitle(intent)
    val view = ChatButton("查看文件", BtnAction.Open(AppScreen.AiViews(query = q, title = title)), primary = true)

    // 本地模式下只在“没听懂 / 不太确定”时提示开通，避免每条回复都挂同一个按钮
    fun extra(list: List<ChatButton>) = if (ready || (cond && !intent.uncertain)) list else list + SETTINGS_BTN

    return when (intent.action) {
        IntentAction.FIND -> {
            if (!cond) {
                ChatMsg(
                    0, false,
                    "我没完全听懂。可以说说位置、类型或时间，比如“找上个月的截图”。也可以直接按文件名搜索。",
                    buttons = extra(
                        listOf(ChatButton("按文件名搜「${intent.raw.take(10)}」", BtnAction.Open(AppScreen.AiViews(query = FileQuery(text = intent.raw), title = "搜索：" + intent.raw.take(20))))),
                    ),
                    uncertain = true,
                )
            } else {
                val (n, bytes) = countFiles(q)
                val text = if (n == 0) "没有找到符合条件的文件。条件可能太严，换个说法试试。" else "找到 ${countText(n)} 个文件，共 ${fmt(bytes)}。"
                ChatMsg(0, false, text, chips, intent.uncertain, extra(if (n == 0) emptyList() else listOf(view)))
            }
        }

        IntentAction.ORGANIZE -> {
            val organize = ChatButton(
                "生成整理计划",
                BtnAction.Run("没有需要整理的文件") {
                    val plan = AiGraph.organizer.proposeOrganize(q.pathPrefix, intent.raw)
                    if (plan.actions.isEmpty()) null else AppScreen.AiPlanReview(plan)
                },
                primary = true,
            )
            val where = q.pathPrefix?.let { "「" + displayDir(it) + "」" } ?: "这些文件"
            ChatMsg(
                0, false,
                "好的，我会把${where}按类型归到 Documents/AI整理 下。先生成移动计划给你预览，确认后才动，整批可撤销。",
                chips, intent.uncertain,
                extra(listOfNotNull(organize, if (cond) view.copy(primary = false) else null)),
            )
        }

        IntentAction.CLEAN, IntentAction.TRASH -> {
            if (!cond) {
                ChatMsg(
                    0, false,
                    "删除要先说清楚位置、类型、时间或大小，比如“删除下载里 30 天前的安装包”。也可以去清理页，让规则帮你找出能删的。",
                    chips, true, extra(listOf(CLEAN_BTN)),
                )
            } else {
                val (n, bytes) = countFiles(q)
                if (n == 0) {
                    ChatMsg(0, false, "没有找到符合条件的文件，不需要删除。", chips, intent.uncertain, extra(emptyList()))
                } else {
                    val trash = ChatButton(
                        "生成删除计划",
                        BtnAction.Run("没有找到符合条件的文件") {
                            val files = AiGraph.index.query(q)
                            if (files.isEmpty()) null else AppScreen.AiPlanReview(trashPlan(intent, files))
                        },
                        primary = true,
                    )
                    ChatMsg(
                        0, false,
                        "符合条件的有 ${countText(n)} 个文件，共 ${fmt(bytes)}。生成计划后逐个确认，删除只进回收站，可整批撤销。",
                        chips, intent.uncertain, extra(listOf(view.copy(primary = false), trash)),
                    )
                }
            }
        }
    }
}

private suspend fun countFiles(q: FileQuery): Pair<Int, Long> {
    val files = AiGraph.index.query(q.copy(limit = 1000))
    return files.size to files.sumOf { it.size }
}

private fun countText(n: Int): String = if (n >= 1000) "1000+" else n.toString()

// ================================================================= 存储问题

private class StorageFacts(
    val totalFiles: Int,
    val totalBytes: Long,
    val cats: List<Pair<Category, CategoryStat>>,
    val dirs: List<Pair<String, CategoryStat>>,
    val groups: List<CleanGroup>,
    val cleanComplete: Boolean,
) {
    val safe get() = groups.filter { it.risk == Risk.SAFE && it.reclaimBytes > 0 }
    val confirm get() = groups.filter { it.risk == Risk.CONFIRM && it.reclaimBytes > 0 }
    val safeBytes get() = safe.sumOf { it.reclaimBytes }
    val confirmBytes get() = confirm.sumOf { it.reclaimBytes }
}

private suspend fun storageAnswer(text: String): ChatMsg {
    val index = AiGraph.index
    val stats = index.stats.value
    if (stats.totalFiles == 0) {
        runCatching { index.startScan(false) }
        return ChatMsg(
            0, false,
            if (stats.scanning) "还在建立文件索引（${stats.scanProgress.ifBlank { "扫描中" }}），稍等一会儿再问我。"
            else "文件索引还没建好，我已开始扫描。稍等一会儿再问我，或先去清理页看看。",
            buttons = listOf(CLEAN_BTN),
        )
    }
    val dirs = try {
        withTimeoutOrNull(15_000) { index.largestChildDirs(ROOT, 8) } ?: emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }
    val (groups, complete) = cleanGroups()
    val facts = StorageFacts(
        stats.totalFiles, stats.totalBytes,
        stats.byCategory.entries.map { it.key to it.value }.filter { it.second.bytes > 0 }.sortedByDescending { it.second.bytes },
        dirs.filter { it.second.bytes > 0 },
        groups, complete,
    )
    val local = localButtons(facts)
    val llm = AiGraph.llm
    if (!llm.isReady()) {
        return ChatMsg(0, false, localText(text, facts), buttons = local + SETTINGS_BTN, note = "本地分析 · 开通 AI 可得到更具体的建议")
    }
    return try {
        val (answerText, btns) = askLlm(text, facts)
        ChatMsg(0, false, answerText, buttons = btns.ifEmpty { local }, note = "AI 建议 · 只看了类别、大小和文件夹名")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ChatMsg(
            0, false, localText(text, facts), buttons = local,
            note = "AI 暂时不可用（" + (e.message ?: e.javaClass.simpleName).take(60) + "），先用本地分析回答",
        )
    }
}

private suspend fun cleanGroups(): Pair<List<CleanGroup>, Boolean> {
    AssistantSession.cleanCache?.let { (at, g) -> if (System.currentTimeMillis() - at < 5 * 60_000) return g to true }
    var latest: List<CleanGroup> = emptyList()
    var done = false
    try {
        withTimeoutOrNull(30_000) {
            AiGraph.cleanup.scan().collect {
                latest = it.groups
                if (it.finished) done = true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
    }
    if (done) AssistantSession.cleanCache = System.currentTimeMillis() to latest
    return latest to done
}

private fun wantsDelete(t: String) = listOf("删", "清理", "释放", "腾", "瘦身", "满了", "不够", "不足").any { t.contains(it) }

private fun localText(question: String, f: StorageFacts): String {
    val del = buildString {
        if (f.safeBytes + f.confirmBytes <= 0) {
            append(if (f.cleanComplete) "按规则没找到明显可删的文件。" else "清理扫描还没跑完，暂时没找到明显可删的文件。")
        } else {
            append("按规则大约能腾出 ${fmt(f.safeBytes + f.confirmBytes)}")
            if (!f.cleanComplete) append("（扫描未完成，可能更多）")
            append("：")
            val parts = mutableListOf<String>()
            if (f.safeBytes > 0) parts += "放心删 ${fmt(f.safeBytes)}（" + f.safe.take(3).joinToString("、") { it.kind.zh } + "）"
            if (f.confirmBytes > 0) parts += "要你看一眼 ${fmt(f.confirmBytes)}（" + f.confirm.sortedByDescending { it.reclaimBytes }.take(3).joinToString("、") { it.kind.zh } + "）"
            append(parts.joinToString("；"))
            append("。")
        }
    }
    val space = buildString {
        append("共 ${f.totalFiles} 个文件 ${fmt(f.totalBytes)}")
        if (f.cats.isNotEmpty()) append("，最占空间的是 " + f.cats.take(3).joinToString("、") { "${it.first.zh} ${fmt(it.second.bytes)}" })
        if (f.dirs.isNotEmpty()) append("；文件夹里 " + f.dirs.take(3).joinToString("、") { "${displayDir(it.first)} ${fmt(it.second.bytes)}" } + " 最大")
        append("。")
    }
    val tail = "删除只进回收站，可整批撤销。"
    return if (wantsDelete(question)) "$del$space$tail" else "$space$del$tail"
}

private fun safeButton(f: StorageFacts): ChatButton? {
    if (f.safeBytes <= 0) return null
    val groups = f.safe
    return ChatButton(
        "一键清理安全项（${fmt(f.safeBytes)}）",
        BtnAction.Run("没有可清理的安全项") {
            val paths = groups.flatMap { g -> g.items.filterNot { it.keep }.map { it.path } }.toSet()
            val plan = AiGraph.cleanup.buildPlan(groups, paths)
            if (plan.actions.isEmpty()) null else AppScreen.AiPlanReview(plan)
        },
        primary = true,
    )
}

private fun catButton(c: Category) = ChatButton("看看${c.zh}", BtnAction.Open(AppScreen.AiViews(viewKey = "cat:" + c.name, title = c.zh)))

private fun dirButton(d: String) =
    ChatButton("打开 " + displayDir(d).take(12), BtnAction.Open(AppScreen.AiViews(query = FileQuery(pathPrefix = d, sort = app.local1st.files.ai.Sort.SIZE_DESC), title = displayDir(d))))

private fun localButtons(f: StorageFacts): List<ChatButton> = listOfNotNull(
    safeButton(f),
    CLEAN_BTN,
    f.cats.firstOrNull()?.let { catButton(it.first) },
    f.dirs.firstOrNull()?.let { dirButton(it.first) },
)

/** 只发汇总（类别/大小/目录名），不发文件名和内容。 */
private suspend fun askLlm(question: String, f: StorageFacts): Pair<String, List<ChatButton>> {
    val llm = AiGraph.llm
    val allowedDirs = f.dirs.filter { llm.allowed(it.first) }.take(8)
    val summary = buildString {
        appendLine("总计：${f.totalFiles} 个文件，${fmt(f.totalBytes)}")
        appendLine("按类别：" + f.cats.joinToString("；") { "${it.first.name}(${it.first.zh}) ${it.second.count} 个 ${fmt(it.second.bytes)}" })
        appendLine("可清理（规则判断，" + (if (f.cleanComplete) "扫描完成" else "扫描未完成") + "）：")
        val gs = (f.safe + f.confirm).sortedByDescending { it.reclaimBytes }.take(12)
        if (gs.isEmpty()) appendLine("无")
        gs.forEach { appendLine("- ${it.kind.zh} 风险=${if (it.risk == Risk.SAFE) "安全" else "需确认"} ${it.items.count { i -> !i.keep }} 项 ${fmt(it.reclaimBytes)}") }
        appendLine("内部存储下最大的文件夹：")
        allowedDirs.forEachIndexed { i, (d, s) -> appendLine("${i + 1}. ${d.removePrefix("$ROOT/")} ${s.count} 个 ${fmt(s.bytes)}") }
    }
    val system = """你是手机文件管理器里的助手。只依据给出的存储汇总，用简洁友好的中文回答用户的问题（不超过 150 字），给出具体建议和数字，不要编造汇总里没有的数字或文件夹。
删除只会进回收站，并且要用户逐项确认。${DefaultLlmClient.DATA_RULE}。
只输出 JSON 对象：{"answer":"中文回答","buttons":[{"label":"不超过12字","do":"..."}]}
buttons 最多 4 个，do 只能是：clean_safe（一键清理安全项）、open_clean（打开清理页）、category:类别英文名（DOC IMAGE SCREENSHOT VIDEO AUDIO APK ARCHIVE OTHER）、dir:编号（汇总里文件夹的编号）。"""
    val user = llm.fence("storage_summary", summary) + "\n用户的问题：\n" + llm.fence("question", question)
    val o = llm.chatJson(system, user, 800)
    val text = o.optString("answer").trim()
    if (text.isEmpty()) throw IllegalStateException("模型没有给出回答")
    val out = ArrayList<ChatButton>()
    val arr = o.optJSONArray("buttons")
    if (arr != null) for (i in 0 until minOf(arr.length(), 6)) {
        val b = arr.optJSONObject(i) ?: continue
        val label = b.optString("label").trim().take(14)
        val d = b.optString("do").trim()
        val btn: ChatButton? = when {
            d == "clean_safe" -> safeButton(f)
            d == "open_clean" -> CLEAN_BTN
            d.startsWith("category:") -> runCatching { Category.valueOf(d.substringAfter(':').trim().uppercase(Locale.ROOT)) }.getOrNull()?.let { catButton(it) }
            d.startsWith("dir:") -> d.substringAfter(':').trim().toIntOrNull()?.let { allowedDirs.getOrNull(it - 1) }?.let { dirButton(it.first) }
            else -> null
        }
        if (btn != null && out.none { it.action == btn.action || it.label == btn.label }) {
            // clean_safe 的按钮文字带大小，保持本地文字更可靠
            out += if (label.isNotEmpty() && d != "clean_safe") btn.copy(label = label) else btn
        }
        if (out.size >= 4) break
    }
    return text.take(600) to out
}

// ================================================================= 工具

internal fun hasCondition(q: FileQuery): Boolean =
    !q.text.isNullOrBlank() || q.categories.isNotEmpty() || q.subCategory != null || q.sourceApp != null ||
        !q.pathPrefix.isNullOrBlank() || q.exts.isNotEmpty() || q.minSize != null || q.maxSize != null ||
        q.olderThanMs != null || q.newerThanMs != null

private fun intentTitle(intent: CommandIntent): String =
    intent.chips.filterNot { it.startsWith("动作：") }.joinToString(" · ").ifBlank { intent.raw }.take(40)

private fun trashPlan(intent: CommandIntent, files: List<IndexedFile>): Plan = Plan(
    planId = UUID.randomUUID().toString(),
    kind = PlanKind.CLEAN,
    title = "删除：" + intentTitle(intent),
    instruction = intent.raw,
    actions = files.mapIndexed { i, f ->
        PlanAction(
            actionId = i + 1,
            type = ActionType.TRASH,
            srcPath = f.path,
            size = f.size,
            reason = "符合指令：" + intent.raw.take(40),
            risk = Risk.CONFIRM,
            selected = !intent.uncertain,
        )
    },
)

internal fun displayDir(path: String): String {
    val p = path.trimEnd('/')
    return when {
        p == ROOT -> "内部存储"
        p.startsWith("$ROOT/") -> p.removePrefix("$ROOT/")
        else -> p.substringAfterLast('/').ifEmpty { p }
    }
}

internal fun fmt(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (v >= 100) "%.0f %s".format(Locale.ROOT, v, units[i]) else "%.1f %s".format(Locale.ROOT, v, units[i])
}
