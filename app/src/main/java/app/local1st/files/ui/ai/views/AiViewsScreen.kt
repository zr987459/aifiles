package app.local1st.files.ui.ai.views

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import app.local1st.files.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.Category
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IndexedFile
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanAction
import app.local1st.files.ai.PlanKind
import app.local1st.files.ai.Risk
import app.local1st.files.ai.SmartView
import app.local1st.files.core.thumb.VideoThumb
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.main.AppScreen
import coil3.compose.AsyncImage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.CircularProgressIndicator
import app.local1st.files.ai.IndexStats

private const val DEFAULT_ORGANIZE_DIR = "/storage/emulated/0/Documents/AI整理"

/** 每页条数：先显示前 60 个，滚到底再加载下一页。 */
private const val PAGE_SIZE = 60

/** 扫描进行中时自动刷新的间隔。 */
private const val SCAN_REFRESH_MS = 3000L

/** 只按一级分类筛选（没有其他条件）时，返回这些分类；否则 null。 */
private fun pureCategories(q: FileQuery): Set<Category>? =
    q.categories.takeIf {
        it.isNotEmpty() && q.text.isNullOrBlank() && q.subCategory == null && q.sourceApp == null &&
            q.pathPrefix == null && q.exts.isEmpty() && q.minSize == null && q.maxSize == null &&
            q.olderThanMs == null && q.newerThanMs == null
    }

/** 用索引统计里的真实总数（不受 limit 截断）。 */
private fun statTotal(stats: IndexStats, cats: Set<Category>): Pair<Int, Long> {
    var n = 0
    var b = 0L
    cats.forEach { c -> stats.byCategory[c]?.let { n += it.count; b += it.bytes } }
    return n to b
}

private sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

private fun Throwable.zhMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

private fun safeLlmReady(): Boolean = runCatching { AiGraph.llm.isReady() }.getOrDefault(false)

/**
 * 智能分类视图（F3）。
 * - viewKey 和 query 都为空：分组列出 smartViews。
 * - 有 query（或能按 viewKey 找到视图）：文件列表 / 网格、排序、多选、生成计划。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiViewsScreen(nav: AiNav, viewKey: String?, query: FileQuery?, title: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloadTick by remember { mutableIntStateOf(0) }
    var refining by remember { mutableStateOf(false) }
    val llmReady = remember { safeLlmReady() }

    // viewKey 有值但 query 为空时，从 smartViews 里按 key 找回 query。
    var resolvedQuery by remember(viewKey, query) { mutableStateOf(query) }
    var resolvedTitle by remember(viewKey, title) { mutableStateOf(title) }
    var resolving by remember(viewKey, query) { mutableStateOf(query == null && viewKey != null) }
    LaunchedEffect(viewKey, query) {
        if (query == null && viewKey != null) {
            val v = try {
                withContext(Dispatchers.IO) { AiGraph.organizer.smartViews() }.firstOrNull { it.key == viewKey }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            resolvedQuery = v?.query
            if (resolvedTitle == null) resolvedTitle = v?.title
            resolving = false
        }
    }

    val fileMode = resolvedQuery != null || viewKey != null
    val screenTitle = resolvedTitle ?: if (fileMode) "文件" else "智能分类"

    fun refine() {
        if (refining) return
        refining = true
        scope.launch {
            val msg = try {
                val n = withContext(Dispatchers.IO) { AiGraph.organizer.refineWithAi() }
                if (n > 0) "AI 已细分 $n 个文件" else "没有需要细分的文件"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "AI 细分失败：${e.zhMessage()}"
            }
            refining = false
            reloadTick++
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    if (fileMode) {
        val q = resolvedQuery
        when {
            resolving -> SimpleScaffold(nav, screenTitle) { AiLoading() }
            q == null -> SimpleScaffold(nav, screenTitle) {
                AiMessage(Icons.Outlined.ErrorOutline, "找不到这个分类", "分类可能已变化，请返回重新进入。", isError = true)
            }
            else -> FileListMode(
                nav = nav,
                title = screenTitle,
                viewKey = viewKey,
                baseQuery = q,
                reloadTick = reloadTick,
                llmReady = llmReady,
                refining = refining,
                onRefine = ::refine,
            )
        }
    } else {
        SmartViewsMode(nav, screenTitle, reloadTick, llmReady, refining, ::refine) { reloadTick++ }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScaffold(nav: AiNav, title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    },
                )
            },
        ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content() } }
    }
}

@Composable
private fun RefineAction(llmReady: Boolean, refining: Boolean, onRefine: () -> Unit) {
    if (!llmReady) return
    if (refining) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(Modifier.width(56.dp))
        }
    } else {
        TextButton(onClick = onRefine) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("AI 细分")
        }
    }
}

// ---------------------------------------------------------------- 分组列表

private fun groupTitleOf(type: String): String = when (type) {
    "category", "sub" -> "按类型"
    "source" -> "按来源 App"
    "month" -> "按月份"
    else -> "其他"
}

private val GROUP_ORDER = listOf("按类型", "按来源 App", "按月份", "其他")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartViewsMode(
    nav: AiNav,
    title: String,
    reloadTick: Int,
    llmReady: Boolean,
    refining: Boolean,
    onRefine: () -> Unit,
    onReload: () -> Unit,
) {
    val stats by AiGraph.index.stats.collectAsState()
    var state by remember { mutableStateOf<LoadState<List<SmartView>>>(LoadState.Loading) }
    var autoTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(stats.scanning) {
        while (stats.scanning) {
            delay(SCAN_REFRESH_MS)
            autoTick++
        }
    }
    LaunchedEffect(reloadTick, stats.lastScanAt, autoTick) {
        if (state !is LoadState.Ready) state = LoadState.Loading
        state = try {
            LoadState.Ready(withContext(Dispatchers.IO) { AiGraph.organizer.smartViews() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LoadState.Failed(e.zhMessage())
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    },
                    actions = {
                        RefineAction(llmReady, refining, onRefine)
                        IconButton(onClick = onReload) { Icon(Icons.Outlined.Refresh, "刷新") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (stats.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "正在索引，已找到 ${stats.totalFiles} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (val s = state) {
                    LoadState.Loading -> AiLoading()
                    is LoadState.Failed -> AiMessage(
                        Icons.Outlined.ErrorOutline, "加载失败", s.message, isError = true,
                        actionLabel = "重试", onAction = onReload,
                    )
                    is LoadState.Ready -> {
                        val views = s.data.filter { it.count > 0 }
                        if (views.isEmpty()) {
                            AiMessage(
                                Icons.Outlined.FolderOpen,
                                if (stats.scanning) "正在建立索引" else "还没有分类结果",
                                if (stats.scanning) "扫描完成后会自动显示分类。" else "先扫描一次手机里的文件，就能按类型、来源和月份查看。",
                                actionLabel = if (stats.scanning) null else "开始扫描",
                                onAction = if (stats.scanning) null else ({ runCatching { AiGraph.index.startScan() }; Unit }),
                            )
                        } else {
                            val grouped = views.groupBy { groupTitleOf(it.type) }
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                                GROUP_ORDER.forEach { group ->
                                    val groupViews = grouped[group].orEmpty()
                                    if (groupViews.isNotEmpty()) {
                                        item(key = "h_$group") {
                                            Text(
                                                group,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                            )
                                        }
                                        items(groupViews, key = { "v_${group}_${it.key}" }) { v ->
                                            ListItem(
                                                headlineContent = { Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                supportingContent = { Text("${v.count} 个 · ${formatBytes(v.bytes)}") },
                                                leadingContent = { Icon(iconForView(v), null) },
                                                modifier = Modifier.combinedClickableCompat(
                                                    onClick = {
                                                        nav.open(AppScreen.AiViews(viewKey = v.key, query = v.query, title = v.title))
                                                    },
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun iconForView(v: SmartView) = when {
    v.query.categories.any { it == Category.IMAGE || it == Category.SCREENSHOT } -> Icons.Outlined.Image
    v.query.categories.contains(Category.VIDEO) -> Icons.Outlined.Movie
    v.type == "source" || v.type == "month" -> Icons.Outlined.Folder
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

// ---------------------------------------------------------------- 文件列表

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileListMode(
    nav: AiNav,
    title: String,
    viewKey: String?,
    baseQuery: FileQuery,
    reloadTick: Int,
    llmReady: Boolean,
    refining: Boolean,
    onRefine: () -> Unit,
) {
    var sortOpt by rememberSaveable { mutableStateOf(AiSortOption.of(baseQuery.sort)) }
    var grid by rememberSaveable { mutableStateOf(baseQuery.categories.isNotEmpty() && baseQuery.categories.all { it == Category.IMAGE || it == Category.SCREENSHOT || it == Category.VIDEO }) }
    var localTick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoadState<List<IndexedFile>>>(LoadState.Loading) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 选择模式：长按任一文件或点右上角“选择”进入；选空或按返回退出。
    var selectMode by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    var showMoveDialog by remember { mutableStateOf(false) }

    val stats by AiGraph.index.stats.collectAsState()
    var limit by remember(baseQuery, sortOpt) { mutableIntStateOf(PAGE_SIZE) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var autoTick by remember { mutableIntStateOf(0) }
    var viewTotal by remember(viewKey) { mutableStateOf<Pair<Int, Long>?>(null) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // 扫描还在进行：每隔几秒自动刷新（保留已显示的内容，不闪加载圈）。
    LaunchedEffect(stats.scanning) {
        while (stats.scanning) {
            delay(SCAN_REFRESH_MS)
            autoTick++
        }
    }

    LaunchedEffect(baseQuery, sortOpt, reloadTick, localTick, limit, autoTick, stats.lastScanAt) {
        val first = state !is LoadState.Ready
        if (first) state = LoadState.Loading else loadingMore = true
        try {
            val want = limit
            val effective = baseQuery.copy(sort = sortOpt.sort, limit = want)
            val list = withContext(Dispatchers.IO) { AiGraph.index.query(effective) }
            hasMore = list.size >= want
            state = LoadState.Ready(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (first) state = LoadState.Failed(e.zhMessage())
        } finally {
            loadingMore = false
        }
        // 去掉已不在结果里的选择。
        (state as? LoadState.Ready)?.data?.let { list ->
            val paths = list.mapTo(HashSet()) { it.path }
            selected = selected.filterTo(HashSet()) { it in paths }
        }
    }

    // 非纯分类视图：用 smartViews 里该视图的真实数量。
    val pure = remember(baseQuery) { pureCategories(baseQuery) }
    LaunchedEffect(viewKey, pure, stats.lastScanAt, autoTick) {
        if (pure != null || viewKey == null) return@LaunchedEffect
        viewTotal = try {
            withContext(Dispatchers.IO) { AiGraph.organizer.smartViews() }
                .firstOrNull { it.key == viewKey }?.let { it.count to it.bytes }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            viewTotal
        }
    }

    // 滚到接近底部时加载下一页。
    LaunchedEffect(listState, gridState) {
        snapshotFlow {
            val nearEnd = if (grid) {
                val li = gridState.layoutInfo
                li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 1 - 12
            } else {
                val li = listState.layoutInfo
                li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 1 - 8
            }
            nearEnd && hasMore && !loadingMore
        }.distinctUntilChanged().filter { it }.collect { limit += PAGE_SIZE }
    }

    val files = (state as? LoadState.Ready)?.data.orEmpty()
    val total: Pair<Int, Long>? = when {
        pure != null -> statTotal(stats, pure).takeIf { it.first > 0 || !hasMore }
        viewTotal != null -> viewTotal
        !hasMore -> files.size to files.sumOf { it.size }
        else -> null
    }?.let { t -> if (t.first < files.size) files.size to files.sumOf { it.size } else t }
    val selecting = selectMode || selected.isNotEmpty()
    val selectedFiles = files.filter { it.path in selected }
    val allSelected = files.isNotEmpty() && selected.size >= files.size

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
        selectMode = selected.isNotEmpty()
    }

    fun startSelect(path: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        toggle(path)
    }

    fun exitSelect() {
        selected = emptySet()
        selectMode = false
    }

    fun openPlan(plan: Plan) {
        exitSelect()
        nav.open(AppScreen.AiPlanReview(plan))
    }

    BackHandler(enabled = selecting) { exitSelect() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                selected.isNotEmpty() -> stringResource(R.string.aiflow_selected_count, selected.size)
                                selecting -> stringResource(R.string.aiflow_select_files)
                                else -> title
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (selecting) {
                            IconButton(onClick = ::exitSelect) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.aiflow_exit_select))
                            }
                        } else {
                            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                        }
                    },
                    actions = {
                        if (selecting) {
                            if (selectedFiles.size == 1) {
                                IconButton(onClick = { nav.reveal(selectedFiles.first().path) }) {
                                    Icon(Icons.Outlined.FolderOpen, stringResource(R.string.aiflow_reveal_in_folder))
                                }
                            }
                            TextButton(onClick = {
                                if (allSelected) exitSelect()
                                else {
                                    selected = files.mapTo(HashSet()) { it.path }
                                    selectMode = true
                                }
                            }) {
                                Text(stringResource(if (allSelected) R.string.aiflow_select_none else R.string.aiflow_select_all_loaded))
                            }
                        } else {
                            RefineAction(llmReady, refining, onRefine)
                            if (files.isNotEmpty()) {
                                IconButton(onClick = { selectMode = true }) {
                                    Icon(Icons.Outlined.Checklist, stringResource(R.string.aiflow_select))
                                }
                            }
                            IconButton(onClick = { grid = !grid }) {
                                if (grid) Icon(Icons.AutoMirrored.Outlined.List, "列表")
                                else Icon(Icons.Outlined.GridView, "网格")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (selecting) {
                    BottomAppBar {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = { openPlan(buildTrashPlan(selectedFiles)) },
                                enabled = selectedFiles.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("移入回收站")
                            }
                            FilledTonalButton(
                                onClick = { showMoveDialog = true },
                                enabled = selectedFiles.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("整理到…")
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                AiSortChips(
                    selected = sortOpt,
                    onSelect = { sortOpt = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    trailing = if (files.isNotEmpty()) {
                        {
                            Text(
                                total?.let { "${it.first} 个 · ${formatBytes(it.second)}" } ?: "已加载 ${files.size} 个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    } else null,
                )
                if (files.isNotEmpty()) {
                    Text(
                        stringResource(if (selecting) R.string.aiflow_select_hint_active else R.string.aiflow_select_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    )
                }
                HorizontalDivider()
                if (stats.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "正在索引，已找到 ${total?.first ?: stats.totalFiles} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                when (val s = state) {
                    LoadState.Loading -> AiLoading()
                    is LoadState.Failed -> AiMessage(
                        Icons.Outlined.ErrorOutline, "加载失败", s.message, isError = true,
                        actionLabel = "重试", onAction = { localTick++ },
                    )
                    is LoadState.Ready -> if (s.data.isEmpty()) {
                        if (stats.scanning) AiLoading("正在索引，已找到 ${stats.totalFiles} 个")
                        else AiMessage(Icons.Outlined.FolderOpen, "这里还没有文件", "索引里没有符合条件的文件。")
                    } else if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(s.data, key = { it.path }) { f ->
                                FileGridCell(
                                    f, selected = f.path in selected, selecting = selecting,
                                    onClick = { if (selecting) toggle(f.path) else nav.openInList(s.data, f.path) },
                                    onLongClick = { startSelect(f.path) },
                                )
                            }
                            if (hasMore) {
                                item(key = "more", span = { GridItemSpan(maxLineSpan) }) { LoadMoreFooter() }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(s.data, key = { it.path }) { f ->
                                FileRow(
                                    f, selected = f.path in selected, selecting = selecting,
                                    onClick = { if (selecting) toggle(f.path) else nav.openInList(s.data, f.path) },
                                    onLongClick = { startSelect(f.path) },
                                )
                            }
                            if (hasMore) item(key = "more") { LoadMoreFooter() }
                        }
                    }
                }
            }
        }
    }

    if (showMoveDialog) {
        MoveTargetDialog(
            count = selectedFiles.size,
            onDismiss = { showMoveDialog = false },
            onConfirm = { dir ->
                showMoveDialog = false
                openPlan(buildMovePlan(selectedFiles, dir))
            },
        )
    }
}

@Composable
private fun MoveTargetDialog(count: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var dir by rememberSaveable { mutableStateOf(DEFAULT_ORGANIZE_DIR) }
    val valid = dir.trim().startsWith("/") && dir.trim().length > 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整理到…") },
        text = {
            Column {
                Text("把选中的 $count 个文件移到这个文件夹（不存在会自动新建）：", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = dir,
                    onValueChange = { dir = it },
                    singleLine = true,
                    label = { Text("目标文件夹路径") },
                    isError = !valid,
                    supportingText = { if (!valid) Text("请输入以 / 开头的完整路径") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(dir.trim().trimEnd('/')) }) { Text("生成计划") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun buildTrashPlan(files: List<IndexedFile>): Plan = Plan(
    planId = UUID.randomUUID().toString(),
    kind = PlanKind.MANUAL,
    title = "移入回收站 ${files.size} 项",
    actions = files.mapIndexed { i, f ->
        PlanAction(
            actionId = i + 1,
            type = ActionType.TRASH,
            srcPath = f.path,
            size = f.size,
            reason = "手动选择",
            confidence = 1f,
            risk = Risk.CONFIRM,
        )
    },
)

private fun buildMovePlan(files: List<IndexedFile>, destDir: String): Plan = Plan(
    planId = UUID.randomUUID().toString(),
    kind = PlanKind.MANUAL,
    title = "整理 ${files.size} 项到 ${nameOf(destDir).ifEmpty { destDir }}",
    actions = files.mapIndexed { i, f ->
        PlanAction(
            actionId = i + 1,
            type = ActionType.MOVE,
            srcPath = f.path,
            destDir = destDir,
            size = f.size,
            reason = "手动选择",
            confidence = 1f,
            risk = Risk.CONFIRM,
        )
    },
)

// ---------------------------------------------------------------- 文件单元

/** 点开列表里的一个文件：整列表传给查看器，图片/视频可左右滑动到同列表的同类文件。 */
private fun AiNav.openInList(list: List<IndexedFile>, path: String) {
    val paths = list.map { it.path }
    openFiles(paths, paths.indexOf(path).coerceAtLeast(0))
}

private fun isVisual(f: IndexedFile) =
    f.category == Category.IMAGE || f.category == Category.SCREENSHOT || f.category == Category.VIDEO

@Composable
private fun LoadMoreFooter() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Thumb(f: IndexedFile, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp = 24.dp) {
    AiFileThumb(path = f.path, mtime = f.mtime, size = f.size, mime = f.mime, modifier = modifier, iconSize = iconSize)
}

@Composable
private fun FileRow(
    f: IndexedFile,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${formatBytes(f.size)} · ${formatTime(f.mtime)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box {
                Thumb(f, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            }
        },
        trailingContent = {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() })
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier.combinedClickableCompat(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun FileGridCell(
    f: IndexedFile,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick),
    ) {
        Thumb(f, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)), iconSize = 36.dp)
        if (!isVisual(f)) {
            Text(
                f.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        AiTileSelectionMark(selected = selected, visible = selecting)
    }
}
