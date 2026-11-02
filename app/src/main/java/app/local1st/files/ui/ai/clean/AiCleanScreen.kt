package app.local1st.files.ui.ai.clean

import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import app.local1st.files.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.Category
import app.local1st.files.ai.CleanGroup
import app.local1st.files.ai.CleanItem
import app.local1st.files.ai.CleanKind
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IndexStats
import app.local1st.files.ai.Risk
import app.local1st.files.ai.Sort
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.ai.views.AiFileThumb
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.AppScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------------- 存储分析页（A1） ---------------- */

private const val ROOT = "/storage/emulated/0"
private const val KEY_APPS = "apps"
private const val KEY_DIRS = "dirs"
private const val KEY_REDUNDANT = "redundant"

/** 一张清理卡片对应哪些 CleanKind。 */
private data class CardSpec(val key: String, val title: String, val desc: String, val kinds: Set<CleanKind>)

private val GROUP_CARDS = listOf(
    CardSpec("dup", "重复文件", "内容完全相同的文件，保留一份即可", setOf(CleanKind.DUPLICATE)),
    CardSpec("large", "大文件", "体积较大的视频、安装包、压缩包等，确认不需要再删", setOf(CleanKind.LARGE_FILE)),
    CardSpec(KEY_REDUNDANT, "冗余文件", "临时文件、日志、空文件、空文件夹和缓存缩略图", setOf(CleanKind.CACHE, CleanKind.EMPTY_DIR)),
    CardSpec("olddl", "旧下载", "下载目录里很久没动过的文件", setOf(CleanKind.OLD_DOWNLOAD)),
    CardSpec("shot", "截图", "按月份归档的截图，过期的可以清掉", setOf(CleanKind.SCREENSHOT)),
    CardSpec("chat", "聊天媒体", "聊天 App 收到的图片、视频和文件", setOf(CleanKind.CHAT_MEDIA)),
    CardSpec("apk", "已安装的安装包", "对应应用已经装好，安装包可以删除", setOf(CleanKind.INSTALLED_APK)),
    CardSpec("photo", "模糊/相似图片", "模糊、暗光和连拍相似的照片", setOf(CleanKind.BLURRY_IMAGE, CleanKind.SIMILAR_IMAGE, CleanKind.DARK_IMAGE)),
    CardSpec("residual", "卸载残留", "已卸载应用留下的文件夹", setOf(CleanKind.RESIDUAL_DIR)),
    CardSpec("shortvideo", "无用短视频", "只有几秒的视频片段", setOf(CleanKind.SHORT_VIDEO)),
)

/** 首页卡片顺序：分组卡片中间插入两张文件夹统计卡片。 */
private val CARD_ORDER = listOf("dup", "large", KEY_REDUNDANT, KEY_APPS, KEY_DIRS, "olddl", "shot", "chat", "apk", "photo", "residual", "shortvideo")

/** 常见应用的目录（相对内部存储根）。Android/data 下的只有 Shizuku 授权并扫描后才有数据。 */
private val APP_DIRS: List<Pair<String, List<String>>> = listOf(
    "微信" to listOf("Android/data/com.tencent.mm", "tencent/MicroMsg", "Tencent/MicroMsg", "Pictures/WeiXin", "Download/WeiXin"),
    "QQ" to listOf("Android/data/com.tencent.mobileqq", "tencent/QQfile_recv", "Tencent/QQfile_recv", "Tencent/QQ_Images", "Pictures/QQ"),
    "抖音" to listOf("Android/data/com.ss.android.ugc.aweme", "DCIM/抖音", "Movies/抖音"),
    "Telegram" to listOf("Android/data/org.telegram.messenger", "Telegram", "Pictures/Telegram", "Movies/Telegram", "Download/Telegram"),
    "快手" to listOf("Android/data/com.smile.gifmaker", "DCIM/快手"),
    "哔哩哔哩" to listOf("Android/data/tv.danmaku.bili", "Movies/bili"),
    "小红书" to listOf("Android/data/com.xingin.xhs", "Pictures/小红书"),
    "微博" to listOf("Android/data/com.sina.weibo", "sina/weibo"),
    "钉钉" to listOf("Android/data/com.alibaba.android.rimet", "DingTalk"),
    "企业微信" to listOf("Android/data/com.tencent.wework", "WeWork"),
    "淘宝" to listOf("Android/data/com.taobao.taobao"),
    "网易云音乐" to listOf("Android/data/com.netease.cloudmusic", "netease/cloudmusic"),
)

private data class AppUsage(val name: String, val bytes: Long, val count: Int, val dirs: List<Triple<String, Long, Int>>)

private data class Segment(val name: String, val color: Color, val bytes: Long)

private fun segmentsOf(stats: IndexStats, used: Long): List<Segment> {
    fun b(vararg c: Category) = c.sumOf { stats.byCategory[it]?.bytes ?: 0L }
    val list = listOf(
        Segment("图片", Color(0xFF42A5F5), b(Category.IMAGE, Category.SCREENSHOT)),
        Segment("视频", Color(0xFFAB47BC), b(Category.VIDEO)),
        Segment("音频", Color(0xFFFFA726), b(Category.AUDIO)),
        Segment("文档", Color(0xFF26A69A), b(Category.DOC)),
        Segment("安装包", Color(0xFF66BB6A), b(Category.APK)),
        Segment("压缩包", Color(0xFF8D6E63), b(Category.ARCHIVE)),
        Segment("其他文件", Color(0xFF78909C), b(Category.OTHER)),
    )
    val indexed = list.sumOf { it.bytes }
    return list + Segment("应用与系统", Color(0xFFBDBDBD), (used - indexed).coerceAtLeast(0L))
}

private fun readStorage(): Pair<Long, Long>? = runCatching {
    val path = if (java.io.File(ROOT).exists()) ROOT else Environment.getExternalStorageDirectory().path
    val fs = StatFs(path)
    val total = fs.totalBytes
    val used = (total - fs.availableBytes).coerceAtLeast(0L)
    used to total
}.getOrNull()

private val PERCENT = Regex("(\\d{1,3})\\s*%")

/** 扫描百分比：优先用索引给的百分数，否则用“已索引 / 已用空间”估算。 */
private fun indexPercent(stats: IndexStats, used: Long): Int? {
    PERCENT.find(stats.scanProgress)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.coerceIn(0, 100) }
    if (used <= 0) return null
    return (stats.totalBytes * 100 / used).toInt().coerceIn(0, 99)
}

/** 索引里的 .tmp/.log 与空文件，补成“冗余文件”组（规则判定，但风险设为建议确认）。 */
private suspend fun loadRedundantExtras(): List<CleanGroup> {
    val index = AiGraph.index
    fun ok(p: String) = !p.contains("/Android/data/") && !p.contains("/Android/obb/")
    val tmpLog = index.query(FileQuery(exts = setOf("tmp", "temp", "log"), sort = Sort.SIZE_DESC, limit = 2000))
        .filter { ok(it.path) }
    val empty = index.query(FileQuery(maxSize = 0L, sort = Sort.NAME_ASC, limit = 2000))
        .filter { ok(it.path) && it.size == 0L }
    val out = ArrayList<CleanGroup>()
    if (tmpLog.isNotEmpty()) {
        out += CleanGroup(
            groupId = "A1:tmplog",
            kind = CleanKind.CACHE,
            title = "临时文件与日志 · ${tmpLog.size} 个",
            risk = Risk.CONFIRM,
            items = tmpLog.map { CleanItem(it.path, it.size, it.mtime, reason = ".${it.ext}") },
            reason = "应用写下的临时文件和日志，通常可删；正在运行的应用可能会重新生成",
            source = "rule",
        )
    }
    if (empty.isNotEmpty()) {
        out += CleanGroup(
            groupId = "A1:empty",
            kind = CleanKind.CACHE,
            title = "空文件 · ${empty.size} 个",
            risk = Risk.CONFIRM,
            items = empty.map { CleanItem(it.path, 0L, it.mtime, reason = "0 字节") },
            reason = "大小为 0 的文件，不占空间但让目录变乱；少数应用拿它做标记",
            source = "rule",
        )
    }
    return out
}

private suspend fun loadAppUsage(): List<AppUsage> {
    val all = APP_DIRS.flatMap { (_, rel) -> rel.map { "$ROOT/$it" } }.distinct()
    val stats = AiGraph.index.sizeUnder(all)
    return APP_DIRS.mapNotNull { (name, rel) ->
        val dirs = rel.map { "$ROOT/$it" }.mapNotNull { d ->
            val s = stats[d] ?: return@mapNotNull null
            if (s.count == 0 && s.bytes == 0L) null else Triple(d, s.bytes, s.count)
        }
        if (dirs.isEmpty()) null else AppUsage(name, dirs.sumOf { it.second }, dirs.sumOf { it.third }, dirs.sortedByDescending { it.second })
    }.sortedByDescending { it.bytes }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiCleanScreen(nav: AiNav) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 索引状态
    val statsFlow: StateFlow<IndexStats> = remember {
        runCatching { AiGraph.index.stats }.getOrElse { MutableStateFlow(IndexStats()) }
    }
    val stats by statsFlow.collectAsState()
    var storage by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // 清理扫描
    var scanToken by remember { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<CleanGroup>>(emptyList()) }
    var progressText by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var extraGroups by remember { mutableStateOf<List<CleanGroup>>(emptyList()) }
    var apps by remember { mutableStateOf<List<AppUsage>>(emptyList()) }
    var bigDirs by remember { mutableStateOf<List<Triple<String, Long, Int>>>(emptyList()) }
    var dirsLoading by remember { mutableStateOf(false) }

    // 选择、详情、执行
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val seenGroups = remember { HashSet<String>() }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var executing by remember { mutableStateOf(false) }
    var execProgress by remember { mutableStateOf<ExecProgress?>(null) }
    var execLabel by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PendingClean?>(null) }

    fun startIndex(full: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AiGraph.index.startScan(full) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                snackbar.showSnackbar("无法开始扫描：${cleanErr(t)}")
            }
        }
    }

    // 执行中禁止返回；详情页里返回先回到概览。
    BackHandler(enabled = detailKey != null && !executing) { detailKey = null }
    BackHandler(enabled = executing) {}

    // 第一次进来还没建过索引：先做一次扫描。
    LaunchedEffect(Unit) {
        delay(400)
        val s = statsFlow.value
        if (s.lastScanAt == 0L && s.totalFiles == 0 && !s.scanning) startIndex(full = false)
    }

    // 索引扫描结束后重新分析。
    var wasIndexing by remember { mutableStateOf(stats.scanning) }
    LaunchedEffect(stats.scanning) {
        if (wasIndexing && !stats.scanning) scanToken++
        wasIndexing = stats.scanning
    }

    // 存储容量（StatFs）
    LaunchedEffect(scanToken, stats.scanning) {
        storage = withContext(Dispatchers.IO) { readStorage() }
    }

    // 清理分组
    LaunchedEffect(scanToken) {
        scanning = true
        scanError = null
        progressText = "准备分析…"
        groups = emptyList()
        selected = emptySet()
        seenGroups.clear()
        try {
            AiGraph.cleanup.scan().flowOn(Dispatchers.IO).collect { st ->
                progressText = st.progress
                // 新出现的组：SAFE 默认全选，CONFIRM 不选，KEEP 不可选。
                var add: Set<String> = emptySet()
                st.groups.forEach { g ->
                    if (seenGroups.add(g.groupId) && g.risk == Risk.SAFE) add = add + cleanSelectable(g).map { it.path }
                }
                groups = st.groups
                if (add.isNotEmpty()) selected = selected + add
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            scanError = cleanErr(t)
        } finally {
            scanning = false
        }
    }

    // 冗余补充、应用目录、最大文件夹（索引查询，较慢，放 IO）
    LaunchedEffect(scanToken) {
        dirsLoading = true
        try {
            extraGroups = withContext(Dispatchers.IO) { runCatching { loadRedundantExtras() }.getOrDefault(emptyList()) }
            apps = withContext(Dispatchers.IO) { runCatching { loadAppUsage() }.getOrDefault(emptyList()) }
            bigDirs = withContext(Dispatchers.IO) {
                runCatching {
                    AiGraph.index.largestChildDirs(ROOT, 20).map { (d, s) -> Triple(d, s.bytes, s.count) }
                }.getOrDefault(emptyList())
            }
        } finally {
            dirsLoading = false
        }
    }

    // 卡片数据
    val cardGroups: Map<String, List<CleanGroup>> = remember(groups, extraGroups) {
        val claimed = groups.flatMap { g -> g.items.map { it.path } }.toHashSet()
        val extras = extraGroups.mapNotNull { g ->
            val items = g.items.filterNot { it.path in claimed }
            if (items.isEmpty()) null else g.copy(items = items)
        }
        GROUP_CARDS.associate { spec ->
            val list = groups.filter { it.kind in spec.kinds } + if (spec.key == KEY_REDUNDANT) extras else emptyList()
            spec.key to list
        }
    }

    // 一键清理 = 所有 SAFE 项
    val safeSizes = remember(groups) {
        val m = LinkedHashMap<String, Long>()
        groups.filter { it.risk == Risk.SAFE }.forEach { g -> cleanSelectable(g).forEach { m[it.path] = it.size } }
        m
    }
    val safeBytes = safeSizes.values.sum()

    fun runUndo(planId: String) {
        scope.launch {
            executing = true
            execLabel = "正在撤销"
            try {
                var err: String? = null
                AiGraph.executor.undo(planId).flowOn(Dispatchers.IO).collect { execProgress = it; err = it.error }
                snackbar.showSnackbar(err?.let { "撤销未完成：$it" } ?: "已撤销")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                snackbar.showSnackbar("撤销失败：${cleanErr(t)}")
            } finally {
                executing = false
                execProgress = null
                scanToken++
            }
        }
    }

    fun runTrash(snapshot: List<CleanGroup>, paths: Set<String>) {
        scope.launch {
            executing = true
            execLabel = "正在移入回收站"
            var planId: String? = null
            var msg = ""
            try {
                val plan = withContext(Dispatchers.IO) { AiGraph.cleanup.buildPlan(snapshot, paths) }
                if (plan.actions.isEmpty()) {
                    msg = "没有可移入回收站的文件"
                } else {
                    val (checked, issues) = withContext(Dispatchers.IO) { AiGraph.executor.validate(plan) }
                    val blocked = issues.filter { it.blocking }.map { it.actionId }.toSet().size
                    var last: ExecProgress? = null
                    AiGraph.executor.execute(checked).flowOn(Dispatchers.IO).collect { execProgress = it; last = it }
                    val p = last
                    planId = checked.planId
                    msg = buildString {
                        append("已移入回收站")
                        if (p != null && p.done > 0) append(" ${p.done} 项")
                        if (p != null && p.failed > 0) append("，失败 ${p.failed} 项")
                        if (blocked > 0) append("，跳过 $blocked 项")
                        p?.error?.let { append("（$it）") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                msg = "清理失败：${cleanErr(t)}"
            } finally {
                executing = false
                execProgress = null
            }
            scanToken++
            val id = planId
            if (id != null) {
                val r = snackbar.showSnackbar(
                    message = msg,
                    actionLabel = "撤销",
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) runUndo(id)
            } else {
                snackbar.showSnackbar(msg)
            }
        }
    }

    /**
     * 一键清理总是先确认；详情页里选了“建议确认”的条目、或数量/体积很大时也先确认。
     * 只有少量、规则判定为安全的文件才直接移入回收站（之后仍可撤销）。
     */
    fun requestTrash(snapshot: List<CleanGroup>, paths: Set<String>, oneClick: Boolean = false) {
        if (paths.isEmpty() || executing) return
        val sizes = HashMap<String, Long>()
        snapshot.forEach { g -> cleanSelectable(g).forEach { sizes[it.path] = it.size } }
        val bytes = paths.sumOf { sizes[it] ?: 0L }
        val risky = cleanHasRisky(snapshot, paths)
        if (oneClick || risky || paths.size > CLEAN_CONFIRM_COUNT || bytes > CLEAN_CONFIRM_BYTES) {
            pending = PendingClean(snapshot, paths, oneClick, risky)
        } else {
            runTrash(snapshot, paths)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val key = detailKey
        val spec = GROUP_CARDS.firstOrNull { it.key == key }
        when {
            key == KEY_APPS -> CleanDirListScreen(
                title = "应用相关文件夹",
                description = "各应用目录占用（只统计已索引的文件；授权 Shizuku 后包含 Android/data）",
                rows = apps.flatMap { a ->
                    listOf(CleanDirRow(a.name, "${a.dirs.size} 个目录", a.bytes, a.count, null)) +
                        a.dirs.map { (d, b, c) -> CleanDirRow(d.removePrefix("$ROOT/"), d, b, c, d, indent = true) }
                },
                loading = dirsLoading,
                snackbar = snackbar,
                onBack = { detailKey = null },
                onReveal = nav.reveal,
            )
            key == KEY_DIRS -> CleanDirListScreen(
                title = "最占空间的文件夹",
                description = "内部存储根目录下各文件夹的占用",
                rows = bigDirs.map { (d, b, c) -> CleanDirRow(d.substringAfterLast('/'), d, b, c, d) },
                loading = dirsLoading,
                snackbar = snackbar,
                onBack = { detailKey = null },
                onReveal = nav.reveal,
            )
            spec != null -> {
                val gs = cardGroups[spec.key].orEmpty()
                CleanDetailScreen(
                    title = spec.title,
                    description = spec.desc,
                    groups = gs,
                    selected = selected,
                    executing = executing,
                    execLabel = execLabel,
                    execProgress = execProgress,
                    snackbar = snackbar,
                    onBack = { detailKey = null },
                    onToggleItem = { p -> selected = if (p in selected) selected - p else selected + p },
                    onSetGroup = { paths, on -> selected = if (on) selected + paths else selected - paths.toSet() },
                    onTrash = { paths -> requestTrash(gs, paths) },
                    onOpen = nav.openFile,
                    onReveal = nav.reveal,
                )
            }
            else -> OverviewScaffold(
                nav = nav,
                snackbar = snackbar,
                stats = stats,
                storage = storage,
                safeBytes = safeBytes,
                safeCount = safeSizes.size,
                executing = executing,
                execLabel = execLabel,
                execProgress = execProgress,
                scanning = scanning,
                progressText = progressText,
                scanError = scanError,
                cardGroups = cardGroups,
                apps = apps,
                bigDirs = bigDirs,
                dirsLoading = dirsLoading,
                onFullScan = { startIndex(full = true) },
                onRefresh = { scanToken++ },
                onOneClick = { requestTrash(groups.filter { it.risk == Risk.SAFE }, safeSizes.keys.toSet(), oneClick = true) },
                onOpenCard = { detailKey = it },
            )
        }
    }

    pending?.let { pc ->
        val rows = remember(pc) { cleanBreakdown(pc.snapshot, pc.paths) }
        CleanConfirmDialog(
            rows = rows,
            risky = pc.risky,
            oneClick = pc.oneClick,
            onConfirm = {
                pending = null
                runTrash(pc.snapshot, pc.paths)
            },
            onDismiss = { pending = null },
        )
    }
}

/** 等待用户确认的一次清理。 */
private data class PendingClean(
    val snapshot: List<CleanGroup>,
    val paths: Set<String>,
    val oneClick: Boolean,
    val risky: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverviewScaffold(
    nav: AiNav,
    snackbar: SnackbarHostState,
    stats: IndexStats,
    storage: Pair<Long, Long>?,
    safeBytes: Long,
    safeCount: Int,
    executing: Boolean,
    execLabel: String,
    execProgress: ExecProgress?,
    scanning: Boolean,
    progressText: String,
    scanError: String?,
    cardGroups: Map<String, List<CleanGroup>>,
    apps: List<AppUsage>,
    bigDirs: List<Triple<String, Long, Int>>,
    dirsLoading: Boolean,
    onFullScan: () -> Unit,
    onRefresh: () -> Unit,
    onOneClick: () -> Unit,
    onOpenCard: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("存储分析") },
                navigationIcon = {
                    TooltipIconButton("返回", Icons.AutoMirrored.Outlined.ArrowBack, enabled = !executing, onClick = nav.back)
                },
                actions = {
                    TooltipIconButton(
                        "重新分析",
                        Icons.Outlined.Refresh,
                        enabled = !scanning && !executing,
                        onClick = onRefresh,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (executing) {
                CleanTrashBar(0, 0L, true, execLabel, execProgress, false) {}
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                StorageHero(
                    stats = stats,
                    storage = storage,
                    safeBytes = safeBytes,
                    safeCount = safeCount,
                    busy = executing,
                    analyzing = scanning,
                    onOneClick = onOneClick,
                    onAi = { nav.open(AppScreen.AiAssistant("分析我的存储，哪些可以清理？")) },
                    onFullScan = onFullScan,
                )
            }
            item(key = "status") {
                val found = remember(cardGroups) {
                    val items = cardGroups.values.flatten().filter { it.risk != Risk.KEEP }
                        .flatMap { g -> g.items.filterNot { it.keep } }
                        .distinctBy { it.path }
                    items.size to items.sumOf { it.size }
                }
                CleanScanSteps(
                    stats = stats,
                    indexPercent = indexPercent(stats, storage?.first ?: 0L),
                    analyzing = scanning,
                    progressText = progressText,
                    scanError = scanError,
                    foundCount = found.first,
                    foundBytes = found.second,
                    onRetry = onRefresh,
                )
            }
            CARD_ORDER.forEach { key ->
                item(key = "card:$key") {
                    when (key) {
                        KEY_APPS -> SummaryCard(
                            title = "应用相关文件夹",
                            desc = "微信、QQ、抖音、Telegram 等应用目录的占用",
                            amount = if (apps.isEmpty()) null else "${cleanBytes(apps.sumOf { it.bytes })} · ${apps.size} 个应用",
                            loading = dirsLoading,
                            previews = apps.take(2).map { it.name to cleanBytes(it.bytes) },
                            onClick = { onOpenCard(KEY_APPS) },
                        )
                        KEY_DIRS -> SummaryCard(
                            title = "最占空间的文件夹",
                            desc = "内部存储根目录下各文件夹的占用排行",
                            amount = bigDirs.firstOrNull()?.let { "最大 ${cleanBytes(it.second)}" },
                            loading = dirsLoading,
                            previews = bigDirs.take(2).map { it.first.substringAfterLast('/') to cleanBytes(it.second) },
                            onClick = { onOpenCard(KEY_DIRS) },
                        )
                        else -> {
                            val spec = GROUP_CARDS.first { it.key == key }
                            val gs = cardGroups[key].orEmpty()
                            GroupSummaryCard(spec, gs, loading = scanning, onClick = { onOpenCard(key) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageHero(
    stats: IndexStats,
    storage: Pair<Long, Long>?,
    safeBytes: Long,
    safeCount: Int,
    busy: Boolean,
    analyzing: Boolean,
    onOneClick: () -> Unit,
    onAi: () -> Unit,
    onFullScan: () -> Unit,
) {
    val used = storage?.first ?: 0L
    val total = storage?.second ?: 0L
    val segments = segmentsOf(stats, used)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // 最显眼：一键清理
            Button(
                onClick = onOneClick,
                enabled = safeCount > 0 && !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(Icons.Outlined.CleaningServices, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        safeCount > 0 -> stringResource(R.string.aiflow_oneclick_label, cleanBytes(safeBytes))
                        analyzing -> "正在计算可释放空间…"
                        else -> "暂无可安全清理的文件"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAi, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("✨ AI 建议")
            }
            Spacer(Modifier.height(16.dp))

            Text("内部存储", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (total > 0) "已用 ${cleanBytes(used)}" else "读取中…",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (total > 0) {
                    Text(
                        " / 共 ${cleanBytes(total)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 彩色分段条：各类占用 + 剩余空间
            val base = if (total > 0) total else segments.sumOf { it.bytes }.coerceAtLeast(1L)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                var acc = 0L
                segments.filter { it.bytes > 0 }.forEach { s ->
                    val b = s.bytes.coerceAtMost(base - acc).coerceAtLeast(0L)
                    acc += b
                    if (b > 0) Box(Modifier.weight(b.toFloat() / base).fillMaxHeight().background(s.color))
                }
                val rest = base - acc
                if (rest > 0) Spacer(Modifier.weight(rest.toFloat() / base))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                segments.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(s.color, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${s.name} ${cleanBytes(s.bytes)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            stats.scanning -> stringResource(R.string.aiflow_hero_scanning)
                            stats.totalFiles > 0 -> "已索引 ${stats.totalFiles} 个文件 · ${cleanBytes(stats.totalBytes)}"
                            else -> "还没有扫描过，点“全盘扫描”开始"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (stats.scanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onFullScan, enabled = !stats.scanning && !busy) {
                    Text(if (stats.scanning) "扫描中" else "全盘扫描")
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    desc: String,
    amount: String?,
    loading: Boolean,
    previews: List<Pair<String, String>>,
    onClick: () -> Unit,
    thumbs: List<CleanItem> = emptyList(),
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    amount ?: if (loading) "统计中…" else "未发现",
                    color = if (amount != null) CleanRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (amount != null) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (previews.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                previews.forEachIndexed { i, (name, size) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        thumbs.getOrNull(i)?.let { item ->
                            AiFileThumb(
                                path = item.path,
                                mtime = item.mtime,
                                size = item.size,
                                isDir = item.isDir,
                                iconSize = 18.dp,
                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "详情 →",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun GroupSummaryCard(spec: CardSpec, groups: List<CleanGroup>, loading: Boolean, onClick: () -> Unit) {
    val live = groups.filter { it.risk != Risk.KEEP }
    val items = live.flatMap { g -> g.items.filterNot { it.keep } }.distinctBy { it.path }
    val bytes = items.sumOf { it.size }
    val keepOnly = groups.sumOf { it.items.size }
    val amount = when {
        items.isNotEmpty() -> "${cleanBytes(bytes)} · ${items.size} 项"
        keepOnly > 0 -> "建议保留 $keepOnly 项"
        else -> null
    }
    val preview = (items.sortedByDescending { it.size }.take(2)).ifEmpty {
        groups.flatMap { it.items }.take(2)
    }
    SummaryCard(
        title = spec.title,
        desc = spec.desc,
        amount = amount,
        loading = loading,
        previews = preview.map { it.path.substringAfterLast('/') to cleanBytes(it.size) },
        thumbs = preview,
        onClick = onClick,
    )
}
