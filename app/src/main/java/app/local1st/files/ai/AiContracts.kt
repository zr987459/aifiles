package app.local1st.files.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/*
 * AI 文件管理器共享合同（由主控维护）。成员只实现，不改这个文件；需要改请在回复【问题】里提出。
 * 所有路径都是绝对路径字符串（如 /storage/emulated/0/Download/a.pdf），文件 I/O 统一走
 * Graph.fsRegistry.forScheme(XId.SCHEME_FILE)（可自动用 Shizuku 进入 Android/data）。
 */

// ---------- 分类 ----------
enum class Category(val zh: String) {
    DOC("文档"), IMAGE("图片"), SCREENSHOT("截图"), VIDEO("视频"),
    AUDIO("音频"), APK("安装包"), ARCHIVE("压缩包"), OTHER("其他"),
}

data class Classification(
    val category: Category,
    /** 二级分类英文 ID，如 doc.receipt / shot.chat；界面用 [subCategoryZh] 显示。 */
    val subCategory: String? = null,
    /** 来源，如 wechat / qq / telegram / camera / download / screenshot / bluetooth。 */
    val sourceApp: String? = null,
    val tags: List<String> = emptyList(),
)

interface Classifier {
    /** 纯规则、无 I/O、要快：扫描每个文件时都会调用。 */
    fun classifyFast(path: String, name: String, ext: String, mime: String?, size: Long): Classification
    fun subCategoryZh(id: String): String
    fun sourceAppZh(id: String): String
}

// ---------- 索引 ----------
data class IndexedFile(
    val id: Long,
    val path: String,
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val mime: String?,
    val category: Category,
    val subCategory: String?,
    val sourceApp: String?,
    val tags: List<String>,
    /** 大小 + 头尾 64KB 的快速哈希，可能为空。 */
    val quickHash: String?,
)

data class CategoryStat(val count: Int, val bytes: Long)

data class IndexStats(
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val byCategory: Map<Category, CategoryStat> = emptyMap(),
    val lastScanAt: Long = 0,
    val scanning: Boolean = false,
    val scanProgress: String = "",
)

enum class Sort { MTIME_DESC, MTIME_ASC, SIZE_DESC, NAME_ASC }

data class FileQuery(
    /** 文件名 / 标签全文搜索。 */
    val text: String? = null,
    val categories: Set<Category> = emptySet(),
    val subCategory: String? = null,
    val sourceApp: String? = null,
    val pathPrefix: String? = null,
    val exts: Set<String> = emptySet(),
    val minSize: Long? = null,
    val maxSize: Long? = null,
    /** mtime 早于 now - olderThanMs。 */
    val olderThanMs: Long? = null,
    val newerThanMs: Long? = null,
    val sort: Sort = Sort.MTIME_DESC,
    val limit: Int = 500,
)

interface FileIndex {
    val stats: StateFlow<IndexStats>
    /** 后台扫描所有已挂载卷（含 Shizuku 可见的 Android/data），增量：目录 mtime 未变可跳过。 */
    fun startScan(full: Boolean = false)
    suspend fun query(q: FileQuery): List<IndexedFile>
    suspend fun get(id: Long): IndexedFile?
    suspend fun getByPath(path: String): IndexedFile?
    suspend fun updateClassification(id: Long, c: Classification)
    /** 完整内容哈希（SHA-1 hex），会缓存。 */
    suspend fun fullHash(id: Long): String?
    suspend fun onMoved(oldPath: String, newPath: String)
    suspend fun onRemoved(path: String)
    suspend fun onAdded(path: String)
    /** 来源 → 文件数。 */
    suspend fun sourceApps(): Map<String, Int>
    /** 某一级分类下 二级ID → 文件数。 */
    suspend fun subCategories(category: Category): Map<String, Int>
    /** 所有被索引的空目录。 */
    suspend fun emptyDirs(): List<String>
    /** 每个目录（含子目录）下已索引文件的总数与总大小。默认实现较慢，索引成员可覆盖。 */
    suspend fun sizeUnder(dirs: List<String>): Map<String, CategoryStat> =
        dirs.associateWith { d ->
            val files = query(FileQuery(pathPrefix = d.trimEnd('/') + "/", limit = Int.MAX_VALUE))
            CategoryStat(files.size, files.sumOf { it.size })
        }
    /** parent 的直接子目录按占用从大到小（用于“哪个文件夹最占空间”）。默认实现较慢，索引成员可覆盖。 */
    suspend fun largestChildDirs(parent: String, limit: Int = 20): List<Pair<String, CategoryStat>> {
        val p = parent.trimEnd('/') + "/"
        return query(FileQuery(pathPrefix = p, limit = Int.MAX_VALUE))
            .groupBy { p + it.path.removePrefix(p).substringBefore('/') }
            .filterKeys { k -> k != p }
            .map { (k, v) -> k to CategoryStat(v.size, v.sumOf { it.size }) }
            .sortedByDescending { it.second.bytes }
            .take(limit)
    }
}

// ---------- 计划与执行 ----------
enum class ActionType { MOVE, RENAME, MKDIR, TRASH }
enum class Risk { SAFE, CONFIRM, KEEP }
enum class PlanKind { ORGANIZE, CLEAN, MANUAL }

data class PlanAction(
    val actionId: Int,
    val type: ActionType,
    /** 源文件绝对路径（MKDIR 时为空）。AI 只能引用索引里已有的文件。 */
    val srcPath: String?,
    /** MOVE / MKDIR 的目标目录。 */
    val destDir: String? = null,
    /** RENAME / MOVE 的目标文件名；MOVE 为空表示沿用原名。 */
    val destName: String? = null,
    val size: Long = 0,
    val reason: String = "",
    val confidence: Float = 1f,
    val risk: Risk = Risk.CONFIRM,
    val selected: Boolean = true,
    /** 校验或安全检查标红的动作（如用户没提到的目录）。 */
    val flagged: Boolean = false,
    val flagReason: String? = null,
)

data class Plan(
    val planId: String,
    val kind: PlanKind,
    val title: String,
    val instruction: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val actions: List<PlanAction>,
)

enum class PlanStatus { RUNNING, DONE, PARTIAL, FAILED, UNDONE, INTERRUPTED }

data class PlanRecord(
    val planId: String,
    val kind: PlanKind,
    val title: String,
    val createdAt: Long,
    val status: PlanStatus,
    val total: Int,
    val done: Int,
    val failed: Int,
    val bytes: Long,
)

data class ValidationIssue(val actionId: Int, val message: String, val blocking: Boolean)

data class ExecProgress(
    val planId: String,
    val done: Int,
    val total: Int,
    val current: String = "",
    val finished: Boolean = false,
    val failed: Int = 0,
    val error: String? = null,
)

interface PlanExecutor {
    /** 检查：源存在且未变、路径白名单、受保护目录、重名（预先算好 (1) 后缀）、跨卷、空间。返回修正后的计划与问题。 */
    suspend fun validate(plan: Plan): Pair<Plan, List<ValidationIssue>>
    /** 只执行 selected 且未被 blocking 问题拦下的动作。先写日志再动文件；TRASH 走 Graph.trash。 */
    fun execute(plan: Plan): Flow<ExecProgress>
    /** 整批撤销。 */
    fun undo(planId: String): Flow<ExecProgress>
    /** 进程被杀后未完成的计划。 */
    suspend fun interrupted(): List<PlanRecord>
    fun resume(planId: String): Flow<ExecProgress>
    suspend fun history(limit: Int = 100): List<PlanRecord>
    suspend fun actionsOf(planId: String): List<PlanAction>
}

// ---------- 清理 ----------
enum class CleanKind(val zh: String) {
    DUPLICATE("重复文件"), SIMILAR_IMAGE("相似图片"), BLURRY_IMAGE("模糊图片"), DARK_IMAGE("暗光图片"),
    INSTALLED_APK("已安装的安装包"), OLD_DOWNLOAD("旧下载"), SCREENSHOT("截图"), CHAT_MEDIA("聊天媒体"),
    LARGE_FILE("大文件"), SHORT_VIDEO("无用短视频"), RESIDUAL_DIR("卸载残留"), CACHE("缓存与缩略图"), EMPTY_DIR("空文件夹"),
}

data class CleanItem(
    val path: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean = false,
    /** 重复组里自动保留的那份。 */
    val keep: Boolean = false,
    val reason: String = "",
)

data class CleanGroup(
    val groupId: String,
    val kind: CleanKind,
    val title: String,
    val risk: Risk,
    val items: List<CleanItem>,
    /** ≤40 字：是什么 + 为什么能删 + 删了会怎样。 */
    val reason: String,
    /** rule / algo / ai */
    val source: String = "rule",
) {
    val reclaimBytes: Long get() = items.filterNot { it.keep }.sumOf { it.size }
}

data class CleanScanState(
    val progress: String = "",
    val groups: List<CleanGroup> = emptyList(),
    val finished: Boolean = false,
)

interface CleanupScanner {
    /** 基于索引扫描，逐步发出结果。 */
    fun scan(): Flow<CleanScanState>
    /** AI 只复核规则判断不了的大文件组，只能把风险调高。LLM 未开启时原样返回。 */
    suspend fun aiReview(groups: List<CleanGroup>): List<CleanGroup>
    /** 把用户勾选的条目转成 TRASH 计划（KEEP 风险和 keep=true 的一律不进）。 */
    fun buildPlan(groups: List<CleanGroup>, selectedPaths: Set<String>): Plan
}

// ---------- 整理 ----------
data class SmartView(
    val key: String,
    val title: String,
    /** category / sub / source / month */
    val type: String,
    val count: Int,
    val bytes: Long,
    val query: FileQuery,
)

enum class IntentAction { FIND, ORGANIZE, CLEAN, TRASH }

data class CommandIntent(
    val action: IntentAction,
    val query: FileQuery,
    /** “我理解为”的条件标签，如 位置：下载 / 类型：APK / 时间：30 天前 / 动作：删除。 */
    val chips: List<String>,
    val destDir: String? = null,
    val raw: String,
    /** 不确定时为 true，界面标黄。 */
    val uncertain: Boolean = false,
)

data class OrganizeRule(
    val id: String,
    val name: String,
    val query: FileQuery,
    val destDir: String,
    val enabled: Boolean = true,
    /** 新规则前 7 天只建议不自动。 */
    val auto: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

interface Organizer {
    suspend fun smartViews(): List<SmartView>
    /** 用 LLM 细分“其他/不确定”的文件，返回更新数。LLM 未开启返回 0。 */
    suspend fun refineWithAi(limit: Int = 200): Int
    /** 生成物理整理计划（MOVE 到 Documents/AI整理/{一级}/{二级}），不碰聊天 App 目录。 */
    suspend fun proposeOrganize(scopePath: String?, instruction: String?): Plan
    /** 一句话 → 条件。LLM 未开启时用本地规则解析。 */
    suspend fun parseCommand(text: String): CommandIntent
    /** 把用户确认过的计划记入学习库；同类确认满 3 次返回一条建议规则。 */
    suspend fun recordConfirmed(plan: Plan): OrganizeRule?
    suspend fun rules(): List<OrganizeRule>
    suspend fun saveRule(rule: OrganizeRule)
    suspend fun deleteRule(id: String)
    /** 运行已启用的规则，返回计划（auto 规则由调用方直接执行）。 */
    suspend fun runRules(): Plan?
}

// ---------- 大模型 ----------
data class LlmSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val hasKey: Boolean = false,
    /** 允许发送文件内容摘要（OCR/文档片段）。默认只发元数据。 */
    val allowContent: Boolean = false,
    val dailyRequestLimit: Int = 200,
    /** 永不发送给 AI 的文件夹。 */
    val excludedPaths: List<String> = emptyList(),
)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface LlmClient {
    val settings: StateFlow<LlmSettings>
    suspend fun update(settings: LlmSettings)
    /** Key 用 Android Keystore 加密保存；传 null 清除。 */
    suspend fun setApiKey(key: String?)
    fun isReady(): Boolean
    /** OpenAI 兼容 /chat/completions，要求返回 JSON 对象。失败抛 [LlmException]。 */
    suspend fun chatJson(system: String, user: String, maxTokens: Int = 2000): JSONObject
    suspend fun test(): Result<String>
    fun requestsToday(): Int
    /** 把文件名/内容包进数据分隔块，并去掉控制字符，防提示注入。 */
    fun fence(label: String, text: String): String
    /** 路径是否允许发给 AI（不在 excludedPaths、不是证件/银行等敏感名）。 */
    fun allowed(path: String): Boolean
}
