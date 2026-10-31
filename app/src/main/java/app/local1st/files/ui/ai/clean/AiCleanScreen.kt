package app.local1st.files.ui.ai.clean

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.CleanGroup
import app.local1st.files.ai.CleanItem
import app.local1st.files.ai.CleanKind
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.Risk
import app.local1st.files.core.thumb.PrivFile
import app.local1st.files.core.thumb.VideoThumb
import app.local1st.files.core.util.Format
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.components.TooltipIconButton
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------------- 常量 ---------------- */

/** 超过这个数量或体积时，执行前二次确认。 */
private const val CONFIRM_COUNT = 200
private const val CONFIRM_BYTES = 5L * 1024 * 1024 * 1024

/** 展开详情时最多渲染的条目数，避免超大组卡顿。 */
private const val MAX_DETAIL_ITEMS = 120

private val SafeColor = Color(0xFF2E7D32)
private val ConfirmColor = Color(0xFFB28704)
private val KeepColor = Color(0xFF757575)

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v", "ts", "flv", "wmv")

private fun extOf(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
private fun isImage(item: CleanItem) = !item.isDir && extOf(item.path) in IMAGE_EXTS
private fun isVideo(item: CleanItem) = !item.isDir && extOf(item.path) in VIDEO_EXTS
private fun isMedia(item: CleanItem) = isImage(item) || isVideo(item)

/** Android/data、obb 普通权限读不到，需要走特权通道取缩略图。 */
private fun needsPrivileged(path: String) = path.contains("/Android/data/") || path.contains("/Android/obb/")

private fun thumbModel(item: CleanItem): Any? {
    val priv = needsPrivileged(item.path)
    return when {
        isVideo(item) -> VideoThumb(path = item.path, mtime = item.mtime, size = item.size, privileged = priv)
        isImage(item) -> if (priv) PrivFile(item.path, item.mtime, item.size) else File(item.path)
        else -> null
    }
}

private fun sourceZh(source: String) = when (source.lowercase()) {
    "rule" -> "规则"
    "algo" -> "算法"
    "ai" -> "AI"
    else -> source
}

private fun riskLabel(risk: Risk): Pair<String, Color> = when (risk) {
    Risk.SAFE -> "可安全删除" to SafeColor
    Risk.CONFIRM -> "建议确认" to ConfirmColor
    Risk.KEEP -> "建议保留" to KeepColor
}

private fun selectableItems(g: CleanGroup): List<CleanItem> =
    if (g.risk == Risk.KEEP) emptyList() else g.items.filterNot { it.keep }

private fun errText(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

/* ---------------- 页面 ---------------- */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiCleanScreen(nav: AiNav) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 扫描状态
    var scanToken by remember { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<CleanGroup>>(emptyList()) }
    var progressText by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanFinished by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    // 选择与展开
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val seenGroups = remember { HashSet<String>() }

    // 执行与复核
    var executing by remember { mutableStateOf(false) }
    var execProgress by remember { mutableStateOf<ExecProgress?>(null) }
    var execLabel by remember { mutableStateOf("") }
    var reviewing by remember { mutableStateOf(false) }
    var confirmBig by remember { mutableStateOf(false) }

    val llmReady by produceState(false) {
        value = runCatching { withContext(Dispatchers.IO) { AiGraph.llm.isReady() } }.getOrDefault(false)
    }

    // 执行中禁止返回，避免中途取消。
    BackHandler(enabled = executing) {}

    LaunchedEffect(scanToken) {
        scanning = true
        scanFinished = false
        scanError = null
        progressText = "准备扫描…"
        groups = emptyList()
        selected = emptySet()
        expanded = emptySet()
        seenGroups.clear()
        try {
            AiGraph.cleanup.scan().flowOn(Dispatchers.IO).collect { st ->
                progressText = st.progress
                // 新出现的组：SAFE 默认全选，其余不选。
                var add: Set<String> = emptySet()
                st.groups.forEach { g ->
                    if (seenGroups.add(g.groupId) && g.risk == Risk.SAFE) {
                        add = add + selectableItems(g).map { it.path }
                    }
                }
                groups = st.groups
                if (add.isNotEmpty()) selected = selected + add
                if (st.finished) scanFinished = true
            }
            scanFinished = true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            scanError = errText(t)
        } finally {
            scanning = false
        }
    }

    // 可选条目：路径 → 大小
    val selectableSizes: Map<String, Long> = remember(groups) {
        val m = LinkedHashMap<String, Long>()
        groups.forEach { g -> selectableItems(g).forEach { m[it.path] = it.size } }
        m
    }
    val reclaimable = remember(selectableSizes) { selectableSizes.values.sum() }
    val effective = selected.filter { it in selectableSizes }
    val selCount = effective.size
    val selBytes = effective.sumOf { selectableSizes[it] ?: 0L }

    fun runUndo(planId: String) {
        scope.launch {
            executing = true
            execLabel = "正在撤销"
            try {
                var last: ExecProgress? = null
                AiGraph.executor.undo(planId).flowOn(Dispatchers.IO).collect { execProgress = it; last = it }
                val err = last?.error
                snackbar.showSnackbar(if (err != null) "撤销未完成：$err" else "已撤销")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                snackbar.showSnackbar("撤销失败：${errText(t)}")
            } finally {
                executing = false
                execProgress = null
                scanToken++
            }
        }
    }

    fun runTrash() {
        val chosen = effective.toSet()
        val snapshot = groups
        scope.launch {
            executing = true
            execLabel = "正在移入回收站"
            var planId: String? = null
            var resultMsg = ""
            try {
                val plan = withContext(Dispatchers.IO) { AiGraph.cleanup.buildPlan(snapshot, chosen) }
                if (plan.actions.isEmpty()) {
                    resultMsg = "没有可移入回收站的文件"
                } else {
                    val (checked, issues) = withContext(Dispatchers.IO) { AiGraph.executor.validate(plan) }
                    val blocked = issues.filter { it.blocking }.map { it.actionId }.toSet().size
                    var last: ExecProgress? = null
                    AiGraph.executor.execute(checked).flowOn(Dispatchers.IO).collect { execProgress = it; last = it }
                    val p = last
                    planId = checked.planId
                    resultMsg = buildString {
                        append("已移入回收站")
                        if (p != null && p.done > 0) append(" ${p.done} 个")
                        if ((p?.failed ?: 0) > 0) append("，失败 ${p?.failed} 个")
                        if (blocked > 0) append("，跳过 $blocked 个")
                        p?.error?.let { append("（$it）") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                resultMsg = "清理失败：${errText(t)}"
            } finally {
                executing = false
                execProgress = null
            }
            scanToken++
            val id = planId
            if (id != null) {
                val r = snackbar.showSnackbar(
                    message = resultMsg,
                    actionLabel = "撤销",
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) runUndo(id)
            } else {
                snackbar.showSnackbar(resultMsg)
            }
        }
    }

    fun runReview() {
        val before = groups
        scope.launch {
            reviewing = true
            try {
                val after = withContext(Dispatchers.IO) { AiGraph.cleanup.aiReview(before) }
                val oldRisk = before.associate { it.groupId to it.risk }
                // 风险被调高的组：取消勾选。
                val drop = after.filter { g -> oldRisk[g.groupId]?.let { g.risk.ordinal > it.ordinal } == true }
                    .flatMap { g -> g.items.map { it.path } }.toSet()
                groups = after
                if (drop.isNotEmpty()) selected = selected - drop
                snackbar.showSnackbar(if (drop.isEmpty()) "AI 复核完成" else "AI 复核完成，已取消 ${drop.size} 个高风险条目")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                snackbar.showSnackbar("AI 复核失败：${errText(t)}")
            } finally {
                reviewing = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("AI 清理") },
                    navigationIcon = {
                        TooltipIconButton(
                            "返回",
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            enabled = !executing,
                            onClick = nav.back,
                        )
                    },
                    actions = {
                        TooltipIconButton(
                            "重新扫描",
                            Icons.Outlined.Refresh,
                            enabled = !scanning && !executing && !reviewing,
                            onClick = { scanToken++ },
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                BottomBar(
                    count = selCount,
                    bytes = selBytes,
                    executing = executing,
                    label = execLabel,
                    progress = execProgress,
                    enabled = selCount > 0 && !executing && !reviewing,
                    onClick = {
                        if (selCount > CONFIRM_COUNT || selBytes > CONFIRM_BYTES) confirmBig = true else runTrash()
                    },
                )
            },
        ) { padding ->
            val showLoading = scanning && groups.isEmpty()
            val showError = scanError != null && groups.isEmpty()
            val showEmpty = !scanning && scanError == null && groups.isEmpty()
            when {
                showLoading -> CenterState(padding) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(progressText.ifBlank { "正在扫描…" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                showError -> CenterState(padding) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("扫描出错", style = MaterialTheme.typography.titleMedium)
                    Text(scanError.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { scanToken++ }) { Text("重试") }
                }
                showEmpty -> CenterState(padding) {
                    Icon(Icons.Outlined.CleaningServices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("没有发现可清理的文件", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "如果还没建立索引，可以先回 AI 首页扫描一次。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { scanToken++ }) { Text("重新扫描") }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 4.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "summary") {
                        SummaryCard(
                            reclaimable = reclaimable,
                            groupCount = groups.size,
                            scanning = scanning,
                            progress = progressText,
                            error = scanError,
                            showReview = llmReady && !scanning,
                            reviewing = reviewing,
                            reviewEnabled = !executing && !reviewing,
                            onReview = { runReview() },
                        )
                    }
                    items(groups, key = { it.groupId }) { g ->
                        GroupCard(
                            group = g,
                            selected = selected,
                            expanded = g.groupId in expanded,
                            enabled = !executing,
                            onToggleExpand = {
                                expanded = if (g.groupId in expanded) expanded - g.groupId else expanded + g.groupId
                            },
                            onToggleGroup = {
                                val paths = selectableItems(g).map { it.path }
                                selected = if (paths.all { it in selected }) selected - paths.toSet() else selected + paths
                            },
                            onToggleItem = { path ->
                                selected = if (path in selected) selected - path else selected + path
                            },
                            onOpen = nav.openFile,
                            onReveal = nav.reveal,
                        )
                    }
                }
            }
        }
    }

    if (confirmBig) {
        AlertDialog(
            onDismissRequest = { confirmBig = false },
            title = { Text("确认移入回收站？") },
            text = {
                Text("将把 $selCount 个文件（共 ${Format.bytes(selBytes)}）移入回收站。数量较多，请再确认一次；之后可以整批撤销。")
            },
            confirmButton = {
                TextButton(onClick = { confirmBig = false; runTrash() }) { Text("移入回收站") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBig = false }) { Text("取消") }
            },
        )
    }
}

/* ---------------- 组件 ---------------- */

@Composable
private fun CenterState(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun SummaryCard(
    reclaimable: Long,
    groupCount: Int,
    scanning: Boolean,
    progress: String,
    error: String?,
    showReview: Boolean,
    reviewing: Boolean,
    reviewEnabled: Boolean,
    onReview: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("可释放", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                Format.bytes(reclaimable).ifBlank { "0 B" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (scanning) "扫描中 · 已发现 $groupCount 组" else "共 $groupCount 组",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (scanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (progress.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        progress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text("扫描中断：$error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (showReview) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onReview, enabled = reviewEnabled) {
                    if (reviewing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (reviewing) "AI 复核中…" else "AI 复核")
                }
            }
        }
    }
}

@Composable
private fun RiskTag(risk: Risk) {
    val (text, color) = riskLabel(risk)
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    group: CleanGroup,
    selected: Set<String>,
    expanded: Boolean,
    enabled: Boolean,
    onToggleExpand: () -> Unit,
    onToggleGroup: () -> Unit,
    onToggleItem: (String) -> Unit,
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
) {
    val selectable = selectableItems(group)
    val chosen = selectable.count { it.path in selected }
    val state = when {
        selectable.isEmpty() || chosen == 0 -> ToggleableState.Off
        chosen == selectable.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Card(onClick = onToggleExpand, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                TriStateCheckbox(
                    state = state,
                    onClick = onToggleGroup,
                    enabled = enabled && selectable.isNotEmpty(),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RiskTag(group.risk)
                        Text(
                            "${group.kind.zh} · ${group.items.size} 个 · 来源：${sourceZh(group.source)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Format.bytes(group.reclaimBytes).ifBlank { "0 B" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }
            if (group.reason.isNotBlank()) {
                Text(
                    group.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, end = 16.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                GroupDetail(group, selected, enabled, onToggleItem, onOpen, onReveal)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupDetail(
    group: CleanGroup,
    selected: Set<String>,
    enabled: Boolean,
    onToggleItem: (String) -> Unit,
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
) {
    val shown = group.items.take(MAX_DETAIL_ITEMS)
    val groupLocked = group.risk == Risk.KEEP
    Column(Modifier.padding(horizontal = 12.dp)) {
        when {
            group.kind == CleanKind.DUPLICATE -> {
                // 重复组并排展示，保留的那份标“保留”。
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.forEach { item ->
                        DuplicateTile(
                            item = item,
                            checked = item.path in selected,
                            selectable = enabled && !groupLocked && !item.keep,
                            onToggle = { onToggleItem(item.path) },
                            onOpen = { onOpen(item.path) },
                            onReveal = { onReveal(item.path) },
                        )
                    }
                }
            }
            shown.isNotEmpty() && shown.all { isMedia(it) } -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    shown.forEach { item ->
                        MediaTile(
                            item = item,
                            checked = item.path in selected,
                            selectable = enabled && !groupLocked && !item.keep,
                            onToggle = { onToggleItem(item.path) },
                            onOpen = { onOpen(item.path) },
                            onReveal = { onReveal(item.path) },
                        )
                    }
                }
            }
            else -> {
                shown.forEach { item ->
                    FileRow(
                        item = item,
                        checked = item.path in selected,
                        selectable = enabled && !groupLocked && !item.keep,
                        onToggle = { onToggleItem(item.path) },
                        onOpen = { onOpen(item.path) },
                        onReveal = { onReveal(item.path) },
                    )
                }
            }
        }
        if (group.items.size > shown.size) {
            Text(
                "仅显示前 ${shown.size} 个，共 ${group.items.size} 个（勾选整组会包含全部）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            "点按打开，长按在文件夹中定位",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun iconFor(item: CleanItem): ImageVector = when {
    item.isDir -> Icons.Outlined.Folder
    isImage(item) -> Icons.Outlined.Image
    isVideo(item) -> Icons.Outlined.Movie
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun Thumb(item: CleanItem, modifier: Modifier) {
    var loaded by remember(item.path) { mutableStateOf(false) }
    val model = remember(item.path, item.mtime, item.size) { thumbModel(item) }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(iconFor(item), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = item.path.substringAfterLast('/'),
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo(item) && loaded) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun KeepBadge(modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = SafeColor, modifier = modifier) {
        Text(
            "保留",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: CleanItem,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
) {
    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onReveal),
    ) {
        Thumb(item, Modifier.fillMaxSize())
        if (item.keep) {
            KeepBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
        } else {
            Checkbox(
                checked = checked && selectable,
                onCheckedChange = { onToggle() },
                enabled = selectable,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuplicateTile(
    item: CleanItem,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
) {
    Column(
        Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onReveal)
            .padding(4.dp),
    ) {
        Box(Modifier.size(124.dp)) {
            Thumb(item, Modifier.fillMaxSize())
            if (item.keep) {
                KeepBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
            } else {
                Checkbox(
                    checked = checked && selectable,
                    onCheckedChange = { onToggle() },
                    enabled = selectable,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.path.substringBeforeLast('/').substringAfterLast('/'),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.path.substringAfterLast('/'),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(Format.bytes(item.size), Format.dateTime(item.mtime)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: CleanItem,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onReveal)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.keep) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { KeepBadge() }
        } else {
            Checkbox(checked = checked && selectable, onCheckedChange = { onToggle() }, enabled = selectable)
        }
        if (isMedia(item)) {
            Thumb(item, Modifier.size(40.dp))
        } else {
            Icon(iconFor(item), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.path.substringBeforeLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(Format.bytes(item.size), Format.dateTime(item.mtime), item.reason)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    count: Int,
    bytes: Long,
    executing: Boolean,
    label: String,
    progress: ExecProgress?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (executing) {
                val p = progress
                val total = p?.total ?: 0
                if (p != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (p.done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(label)
                        if (p != null && total > 0) append(" ${p.done}/$total")
                        if (!p?.current.isNullOrBlank()) append(" · ${p?.current?.substringAfterLast('/')}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (count == 0) "请选择要清理的文件"
                        else "移入回收站 $count 个，共 ${Format.bytes(bytes)}",
                    )
                }
            }
        }
    }
}
