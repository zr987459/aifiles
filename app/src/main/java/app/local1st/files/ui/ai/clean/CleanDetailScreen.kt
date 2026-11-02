package app.local1st.files.ui.ai.clean

import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import app.local1st.files.R
import app.local1st.files.ui.ai.views.AiSortChips
import app.local1st.files.ui.ai.views.AiSortOption
import app.local1st.files.ui.ai.views.sortedForAi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.CleanGroup
import app.local1st.files.ai.CleanKind
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.Risk
import app.local1st.files.ui.components.TooltipIconButton

private const val PAGE = 120
private const val GRID_COLS = 4

/**
 * 某一类清理项的详情：分组 + 可勾选列表 / 缩略图网格，底部“移入回收站 N 项 · X”。
 * 选择状态由 [AiCleanScreen] 持有，这里只统计本页分组里的条目。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CleanDetailScreen(
    title: String,
    description: String,
    groups: List<CleanGroup>,
    selected: Set<String>,
    executing: Boolean,
    execLabel: String,
    execProgress: ExecProgress?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onToggleItem: (String) -> Unit,
    onSetGroup: (List<String>, Boolean) -> Unit,
    onTrash: (Set<String>) -> Unit,
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
) {
    val sizes = remember(groups) {
        val m = LinkedHashMap<String, Long>()
        groups.forEach { g -> cleanSelectable(g).forEach { m[it.path] = it.size } }
        m
    }
    val chosen = selected.filter { it in sizes }
    val count = chosen.size
    val bytes = chosen.sumOf { sizes[it] ?: 0L }
    var limits by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // 与智能分类列表同一套排序；重复文件组保持“保留的那份在前”，不参与排序。
    var sortOpt by rememberSaveable { mutableStateOf(AiSortOption.SIZE) }
    val sortedGroups = remember(groups, sortOpt) {
        groups.map { g ->
            if (g.kind == CleanKind.DUPLICATE) g
            else g.copy(items = g.items.sortedForAi(sortOpt, { it.path.substringAfterLast('/') }, { it.size }, { it.mtime }))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    TooltipIconButton("返回", Icons.AutoMirrored.Outlined.ArrowBack, enabled = !executing, onClick = onBack)
                },
                actions = {
                    if (sizes.isNotEmpty()) {
                        val all = sizes.keys.all { it in selected }
                        TextButton(
                            enabled = !executing,
                            onClick = { onSetGroup(sizes.keys.toList(), !all) },
                        ) { Text(if (all) "全不选" else "全选") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            CleanTrashBar(
                count = count,
                bytes = bytes,
                executing = executing,
                label = execLabel,
                progress = execProgress,
                enabled = count > 0 && !executing,
                onClick = { onTrash(chosen.toSet()) },
            )
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("这一类没有发现可清理的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "desc") {
                Column {
                    if (description.isNotBlank()) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.aiflow_clean_detail_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AiSortChips(
                        selected = sortOpt,
                        onSelect = { sortOpt = it },
                        enabled = !executing,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            sortedGroups.forEach { g ->
                val locked = g.risk == Risk.KEEP
                val selectable = cleanSelectable(g)
                val limit = limits[g.groupId] ?: PAGE
                val shown = g.items.take(limit)
                item(key = "h:${g.groupId}") {
                    val n = selectable.count { it.path in selected }
                    val state = when {
                        selectable.isEmpty() || n == 0 -> ToggleableState.Off
                        n == selectable.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    Column(Modifier.padding(top = 10.dp)) {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(
                                state = state,
                                enabled = !executing && selectable.isNotEmpty(),
                                onClick = { onSetGroup(selectable.map { it.path }, state != ToggleableState.On) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(g.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    CleanRiskTag(g.risk)
                                    Text(
                                        "${g.kind.zh} · ${g.items.size} 项 · 来源：${cleanSourceZh(g.source)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                cleanBytes(g.reclaimBytes),
                                style = MaterialTheme.typography.titleSmall,
                                color = CleanRed,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (g.reason.isNotBlank()) {
                            Text(
                                g.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                val canPick = { keep: Boolean -> !executing && !locked && !keep }
                when {
                    g.kind == CleanKind.DUPLICATE -> item(key = "d:${g.groupId}") {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            shown.forEach { x ->
                                CleanDuplicateTile(
                                    item = x,
                                    checked = x.path in selected,
                                    selectable = canPick(x.keep),
                                    onToggle = { onToggleItem(x.path) },
                                    onOpen = { onOpen(x.path) },
                                    onReveal = { onReveal(x.path) },
                                )
                            }
                        }
                    }
                    shown.isNotEmpty() && shown.all { cleanIsMedia(it) } -> {
                        val rows = shown.chunked(GRID_COLS)
                        rows.forEachIndexed { ri, row ->
                            item(key = "g:${g.groupId}:$ri") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { x ->
                                        CleanMediaTile(
                                            item = x,
                                            checked = x.path in selected,
                                            selectable = canPick(x.keep),
                                            onToggle = { onToggleItem(x.path) },
                                            onOpen = { onOpen(x.path) },
                                            onReveal = { onReveal(x.path) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(GRID_COLS - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    else -> {
                        shown.forEachIndexed { i, x ->
                            item(key = "r:${g.groupId}:$i") {
                                CleanItemRow(
                                    item = x,
                                    checked = x.path in selected,
                                    selectable = canPick(x.keep),
                                    onToggle = { onToggleItem(x.path) },
                                    onOpen = { onOpen(x.path) },
                                    onReveal = { onReveal(x.path) },
                                )
                            }
                        }
                    }
                }
                if (g.items.size > shown.size) {
                    item(key = "m:${g.groupId}") {
                        TextButton(onClick = { limits = limits + (g.groupId to limit + PAGE) }) {
                            Text("再显示 ${minOf(PAGE, g.items.size - shown.size)} 项（共 ${g.items.size} 项，勾选整组已包含全部）")
                        }
                    }
                }
            }
        }
    }
}

/** 文件夹占用列表的一行。 */
internal data class CleanDirRow(
    val title: String,
    val subtitle: String,
    val bytes: Long,
    val count: Int,
    /** 点按后定位的目录；为空表示是标题行。 */
    val path: String?,
    val indent: Boolean = false,
)

/** 只读的文件夹占用详情（应用相关文件夹 / 最占空间的文件夹）：点按在浏览器中定位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CleanDirListScreen(
    title: String,
    description: String,
    rows: List<CleanDirRow>,
    loading: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onReveal: (String) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TooltipIconButton("返回", Icons.AutoMirrored.Outlined.ArrowBack, onClick = onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "desc") {
                Text(
                    if (loading) "$description（统计中…）" else "$description。点按在文件夹中定位，到浏览器里处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (!loading && rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有统计到数据，先做一次全盘扫描试试。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(rows.size, key = { "row:$it" }) { i ->
                val r = rows[i]
                val clickable = r.path != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (clickable) Modifier.clickable { onReveal(r.path!!) } else Modifier)
                        .padding(start = if (r.indent) 28.dp else 0.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        null,
                        tint = if (r.indent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(if (r.indent) 20.dp else 26.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.title,
                            style = if (r.indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (r.subtitle.isNotBlank()) {
                            Text(
                                r.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(cleanBytes(r.bytes), color = CleanRed, style = MaterialTheme.typography.titleSmall)
                        Text("${r.count} 个文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (clickable) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}
