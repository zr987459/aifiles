package app.local1st.files.ui.ai.views

import android.widget.Toast
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

private const val DEFAULT_ORGANIZE_DIR = "/storage/emulated/0/Documents/AI整理"

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
    LaunchedEffect(reloadTick, stats.lastScanAt) {
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
                        stats.scanProgress.ifBlank { "正在扫描文件…" },
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

private enum class SortOption(val label: String, val sort: app.local1st.files.ai.Sort) {
    TIME("时间", app.local1st.files.ai.Sort.MTIME_DESC),
    SIZE("大小", app.local1st.files.ai.Sort.SIZE_DESC),
    NAME("名称", app.local1st.files.ai.Sort.NAME_ASC),
}

private fun initialSortOption(q: FileQuery): SortOption = when (q.sort) {
    app.local1st.files.ai.Sort.SIZE_DESC -> SortOption.SIZE
    app.local1st.files.ai.Sort.NAME_ASC -> SortOption.NAME
    else -> SortOption.TIME
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileListMode(
    nav: AiNav,
    title: String,
    baseQuery: FileQuery,
    reloadTick: Int,
    llmReady: Boolean,
    refining: Boolean,
    onRefine: () -> Unit,
) {
    var sortOpt by rememberSaveable { mutableStateOf(initialSortOption(baseQuery)) }
    var grid by rememberSaveable { mutableStateOf(baseQuery.categories.isNotEmpty() && baseQuery.categories.all { it == Category.IMAGE || it == Category.SCREENSHOT || it == Category.VIDEO }) }
    var localTick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoadState<List<IndexedFile>>>(LoadState.Loading) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(baseQuery, sortOpt, reloadTick, localTick) {
        state = LoadState.Loading
        state = try {
            val effective = baseQuery.copy(sort = sortOpt.sort)
            LoadState.Ready(withContext(Dispatchers.IO) { AiGraph.index.query(effective) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LoadState.Failed(e.zhMessage())
        }
        // 去掉已不在结果里的选择。
        (state as? LoadState.Ready)?.data?.let { list ->
            val paths = list.mapTo(HashSet()) { it.path }
            selected = selected.filterTo(HashSet()) { it in paths }
        }
    }

    val files = (state as? LoadState.Ready)?.data.orEmpty()
    val selecting = selected.isNotEmpty()
    val selectedFiles = files.filter { it.path in selected }

    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun openPlan(plan: Plan) {
        selected = emptySet()
        nav.open(AppScreen.AiPlanReview(plan))
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (selecting) "已选 ${selected.size} 项" else title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (selecting) {
                            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, "取消选择") }
                        } else {
                            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                        }
                    },
                    actions = {
                        if (selecting) {
                            IconButton(onClick = {
                                selected = if (selected.size == files.size) emptySet() else files.mapTo(HashSet()) { it.path }
                            }) { Icon(Icons.Outlined.SelectAll, "全选") }
                        } else {
                            RefineAction(llmReady, refining, onRefine)
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
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("移入回收站")
                            }
                            FilledTonalButton(
                                onClick = { showMoveDialog = true },
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("排序", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SortOption.entries.forEach { opt ->
                        FilterChip(
                            selected = sortOpt == opt,
                            onClick = { sortOpt = opt },
                            label = { Text(opt.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (files.isNotEmpty()) {
                        Text(
                            "${files.size} 个 · ${formatBytes(files.sumOf { it.size })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                when (val s = state) {
                    LoadState.Loading -> AiLoading()
                    is LoadState.Failed -> AiMessage(
                        Icons.Outlined.ErrorOutline, "加载失败", s.message, isError = true,
                        actionLabel = "重试", onAction = { localTick++ },
                    )
                    is LoadState.Ready -> if (s.data.isEmpty()) {
                        AiMessage(Icons.Outlined.FolderOpen, "这里还没有文件", "索引里没有符合条件的文件。")
                    } else if (grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(104.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(s.data, key = { it.path }) { f ->
                                FileGridCell(
                                    f, selected = f.path in selected, selecting = selecting,
                                    onClick = { if (selecting) toggle(f.path) else nav.openFile(f.path) },
                                    onSelect = { toggle(f.path) },
                                    onReveal = { nav.reveal(f.path) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(s.data, key = { it.path }) { f ->
                                FileRow(
                                    f, selected = f.path in selected, selecting = selecting,
                                    onClick = { if (selecting) toggle(f.path) else nav.openFile(f.path) },
                                    onSelect = { toggle(f.path) },
                                    onReveal = { nav.reveal(f.path) },
                                )
                            }
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

private fun isVisual(f: IndexedFile) =
    f.category == Category.IMAGE || f.category == Category.SCREENSHOT || f.category == Category.VIDEO

private fun thumbModel(f: IndexedFile): Any? = when (f.category) {
    Category.IMAGE, Category.SCREENSHOT -> File(f.path)
    Category.VIDEO -> VideoThumb(path = f.path, mtime = f.mtime, size = f.size)
    else -> null
}

private fun fallbackIcon(f: IndexedFile) = when (f.category) {
    Category.IMAGE, Category.SCREENSHOT -> Icons.Outlined.Image
    Category.VIDEO -> Icons.Outlined.Movie
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun Thumb(f: IndexedFile, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(fallbackIcon(f), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val model = remember(f.path, f.mtime) { thumbModel(f) }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FileRow(
    f: IndexedFile,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onReveal: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
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
                Thumb(f, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            },
            trailingContent = {
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onSelect() })
            },
            modifier = Modifier.combinedClickableCompat(onClick = onClick, onLongClick = { menu = true }),
        )
        FileMenu(expanded = menu, onDismiss = { menu = false }, selected = selected, onSelect = onSelect, onReveal = onReveal)
    }
}

@Composable
private fun FileGridCell(
    f: IndexedFile,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onReveal: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .combinedClickableCompat(onClick = onClick, onLongClick = { menu = true }),
        ) {
            Box {
                Thumb(f, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                if (selecting) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent),
                    )
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = if (selected) "已选" else "未选",
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                    )
                }
            }
            if (!isVisual(f)) {
                Text(
                    f.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        FileMenu(expanded = menu, onDismiss = { menu = false }, selected = selected, onSelect = onSelect, onReveal = onReveal)
    }
}

@Composable
private fun FileMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    selected: Boolean,
    onSelect: () -> Unit,
    onReveal: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("在文件夹中显示") },
            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
            onClick = { onDismiss(); onReveal() },
        )
        DropdownMenuItem(
            text = { Text(if (selected) "取消选择" else "选择") },
            leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
            onClick = { onDismiss(); onSelect() },
        )
    }
}
