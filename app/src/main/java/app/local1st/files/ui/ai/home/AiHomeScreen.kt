package app.local1st.files.ui.ai.home

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.local1st.files.R
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.Category
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IndexStats
import app.local1st.files.ai.PlanRecord
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.ai.assistant.EXAMPLES
import app.local1st.files.ui.ai.settings.requestAllFilesAccess
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.AppScreen
import app.local1st.files.ui.main.hasStorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val ROOT = "/storage/emulated/0"
private const val DAY = 86_400_000L

// ================================================================= 首页

/**
 * AI 文件首页。层级（从上到下）：
 * 1. 一句话框（输入时给出“搜文件名 / 交给 AI”两个明确选项）+ 历史 + 设置
 * 2. 状态提示（权限 / 中断 / 索引进度）
 * 3. “试试对 AI 说”示例（唯一的助手入口，替代原来的 ✨ 按钮和助手卡片）
 * 4. 存储概览卡（用量 + 清理空间 + 浏览全部文件，其他存储卷并在卡内）
 * 5. 分类宫格  6. 常用位置（微信 / QQ / 下载 / 最近 7 天）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiHomeScreen(nav: AiNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val stats by AiGraph.index.stats.collectAsState()
    val llmSettings by AiGraph.llm.settings.collectAsState()
    val aiReady = remember(llmSettings) { runCatching { AiGraph.llm.isReady() }.getOrDefault(false) }

    var input by rememberSaveable { mutableStateOf("") }
    var storageGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    var interrupted by remember { mutableStateOf<List<PlanRecord>>(emptyList()) }
    var mediaCounts by remember { mutableStateOf<MediaCounts?>(null) }
    var quick by remember { mutableStateOf(QuickCounts()) }
    var volumes by remember { mutableStateOf<List<VolumeInfo>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0) }

    // 数字只来自两个“稳定”来源：MediaStore（秒出）→ 最近一次“完成”的索引快照。
    // 扫描中的半截计数不显示，避免来回跳；快照只在 scanning=false 时更新。
    val snapshot = stableSnapshot(stats)

    LifecycleResumeEffect(Unit) {
        val now = hasStorageAccess(context)
        if (now && !storageGranted) launchScan(scope)
        storageGranted = now
        refreshTick++
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        delay(600) // 等索引从磁盘读出上次扫描时间
        val s = AiGraph.index.stats.value
        if (s.lastScanAt == 0L && !s.scanning && hasStorageAccess(context)) safeIo { AiGraph.index.startScan(false) }
        interrupted = safeIo { AiGraph.executor.interrupted() }.getOrDefault(emptyList())
    }

    // 存储卷每次回到首页刷新；MediaStore 计数只在还没有完成的索引快照时查询（都在 IO 线程）
    LaunchedEffect(refreshTick, storageGranted) {
        volumes = safeIo { loadVolumes(context) }.getOrDefault(emptyList())
        if (storageGranted && (mediaCounts == null || AiGraph.index.stats.value.lastScanAt == 0L)) {
            mediaCounts = safeIo { queryMediaCounts(context) }.getOrNull() ?: mediaCounts
        }
    }

    // 常用位置：有完成的索引快照用索引，否则 MediaStore；快照不变就不重算
    LaunchedEffect(snapshot?.lastScanAt, mediaCounts) {
        val snap = snapshot
        val m = mediaCounts
        if (snap != null) {
            safeIo {
                val src = AiGraph.index.sourceApps()
                QuickCounts(
                    wechat = src["wechat"] ?: 0,
                    qq = src["qq"] ?: 0,
                    download = AiGraph.index.query(FileQuery(pathPrefix = "$ROOT/Download", limit = 100_000)).size,
                    recent = AiGraph.index.query(FileQuery(newerThanMs = 7 * DAY, limit = 100_000)).size,
                )
            }.getOrNull()?.let { quick = it }
        } else if (m != null) {
            quick = QuickCounts(m.wechat, m.qq, m.download, m.recent)
        }
    }

    fun categoryCount(c: Category): Int? {
        val snap = snapshot
        return if (snap != null) snap.byCategory[c]?.count ?: 0 else mediaCounts?.byCategory?.get(c)
    }

    fun searchByName(t: String) {
        keyboard?.hide()
        nav.open(AppScreen.AiViews(query = FileQuery(text = t), title = "搜索：$t"))
    }

    fun askAi(t: String?) {
        keyboard?.hide()
        nav.open(AppScreen.AiAssistant(t?.trim()?.ifEmpty { null }))
    }

    fun submit() {
        val t = input.trim()
        if (t.isEmpty()) return
        if (looksLikeCommand(t)) askAi(t) else searchByName(t)
    }

    fun grantStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestAllFilesAccess(context)
        else nav.open(AppScreen.AiSettings)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                // 1. 一句话框 + 历史 + 设置
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = input,
                        onValueChange = { input = it.take(300) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(stringResource(R.string.aihome_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (input.isNotEmpty()) {
                                TooltipIconButton(stringResource(R.string.aihome_clear), Icons.Outlined.Close) { input = "" }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                    )
                    TooltipIconButton(stringResource(R.string.aihome_history), Icons.Outlined.History) { nav.open(AppScreen.AiHistory) }
                    TooltipIconButton(stringResource(R.string.aihome_ai_settings), Icons.Outlined.Settings) { nav.open(AppScreen.AiSettings) }
                }

                // 输入中：两个明确的去向，不再让用户猜“回车会去哪”
                val typed = input.trim()
                if (typed.isNotEmpty()) {
                    val preferAi = looksLikeCommand(typed)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                        Column {
                            val shown = typed.take(16) + if (typed.length > 16) "…" else ""
                            val ai: @Composable () -> Unit = {
                                ChoiceRow(Icons.Outlined.AutoAwesome, stringResource(R.string.aihome_ask_ai, shown), highlight = preferAi) { askAi(typed) }
                            }
                            val name: @Composable () -> Unit = {
                                ChoiceRow(Icons.Outlined.Search, stringResource(R.string.aihome_search_name, shown), highlight = !preferAi) { searchByName(typed) }
                            }
                            if (preferAi) { ai(); HorizontalDivider(); name() } else { name(); HorizontalDivider(); ai() }
                        }
                    }
                }

                // 2. 状态提示
                if (!storageGranted) {
                    TipBar(stringResource(R.string.aihome_perm_tip), stringResource(R.string.aihome_perm_action), warn = true) { grantStorage() }
                }
                if (interrupted.isNotEmpty()) {
                    val n = interrupted.size
                    TipBar(if (n > 1) "上次操作被中断（$n 个计划）" else "上次操作被中断", "去历史", warn = true) {
                        nav.open(AppScreen.AiHistory)
                    }
                }
                if (stats.scanning) {
                    Text(
                        indexProgressText(stats.scanProgress, mediaCounts?.allFiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }

                // 3. 试试对 AI 说（唯一的助手入口）
                if (typed.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.aihome_try_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (!aiReady) {
                                TextButton(onClick = { nav.open(AppScreen.AiSettings) }) { Text(stringResource(R.string.aihome_ai_setup)) }
                            } else {
                                TextButton(onClick = { askAi(null) }) { Text(stringResource(R.string.aihome_open_assistant)) }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EXAMPLES.forEach { ex -> SuggestionChip(onClick = { askAi(ex) }, label = { Text(ex) }) }
                        }
                        if (!aiReady) {
                            Text(
                                stringResource(R.string.aihome_local_mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 4. 存储概览（含清理入口）
                if (volumes.isNotEmpty()) {
                    StorageCard(volumes, onClean = { nav.open(AppScreen.AiClean) }, onBrowse = { nav.back() })
                }

                // 5. 分类宫格
                SectionTitle(stringResource(R.string.aihome_categories))
                CategoryGrid({ categoryCount(it) }) { c -> nav.open(AppScreen.AiViews(viewKey = "cat:" + c.name, title = c.zh)) }

                // 6. 常用位置（截图已在分类里，不再重复）
                SectionTitle(stringResource(R.string.aihome_quick))
                val recentTitle = stringResource(R.string.aihome_recent)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile(Modifier.weight(1f), "微信", Icons.Outlined.Chat, Color(0xFF07C160), quick.wechat) {
                        nav.open(AppScreen.AiViews(query = FileQuery(sourceApp = "wechat"), title = "微信"))
                    }
                    QuickTile(Modifier.weight(1f), "QQ", Icons.Outlined.Forum, Color(0xFF12B7F5), quick.qq) {
                        nav.open(AppScreen.AiViews(query = FileQuery(sourceApp = "qq"), title = "QQ"))
                    }
                    QuickTile(Modifier.weight(1f), "下载", Icons.Outlined.Download, Color(0xFF1E88E5), quick.download) {
                        nav.open(AppScreen.AiViews(query = FileQuery(pathPrefix = "$ROOT/Download"), title = "下载"))
                    }
                    QuickTile(Modifier.weight(1f), stringResource(R.string.aihome_recent_short), Icons.Outlined.Schedule, Color(0xFF7E57C2), quick.recent) {
                        nav.open(AppScreen.AiViews(query = FileQuery(newerThanMs = 7 * DAY), title = recentTitle))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ChoiceRow(icon: ImageVector, text: String, highlight: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ================================================================= 存储概览

@Composable
private fun StorageCard(volumes: List<VolumeInfo>, onClean: () -> Unit, onBrowse: () -> Unit) {
    val context = LocalContext.current
    val primary = volumes.firstOrNull { it.primary } ?: volumes.first()
    val others = volumes.filter { it !== primary }
    val used = (primary.total - primary.free).coerceAtLeast(0)
    val frac = if (primary.total > 0) (used.toFloat() / primary.total).coerceIn(0f, 1f) else 0f
    val low = frac > 0.9f
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(primary.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.aihome_storage_free, fmtSize(context, primary.free)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                drawStopIndicator = {},
            )
            Text(
                if (low) stringResource(R.string.aihome_storage_low)
                else stringResource(R.string.aihome_storage_used, fmtSize(context, used), fmtSize(context, primary.total)),
                style = MaterialTheme.typography.bodySmall,
                color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onClean) { Text(stringResource(R.string.aihome_clean)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onBrowse) { Text(stringResource(R.string.aihome_browse)) }
            }
            if (others.isNotEmpty()) {
                HorizontalDivider()
                others.forEach { v -> VolumeRow(v, onBrowse) }
            }
        }
    }
}

// ================================================================= 宫格与快捷访问

private data class CatSpec(val category: Category, val label: String, val icon: ImageVector, val color: Color)

private val CATEGORY_SPECS = listOf(
    CatSpec(Category.IMAGE, "图片", Icons.Outlined.Image, Color(0xFFFF8A00)),
    CatSpec(Category.VIDEO, "视频", Icons.Outlined.Movie, Color(0xFFE53935)),
    CatSpec(Category.AUDIO, "音频", Icons.Outlined.MusicNote, Color(0xFF8E24AA)),
    CatSpec(Category.DOC, "文档", Icons.Outlined.Description, Color(0xFF1E88E5)),
    CatSpec(Category.APK, "安装包", Icons.Outlined.Android, Color(0xFF43A047)),
    CatSpec(Category.ARCHIVE, "压缩包", Icons.Outlined.FolderZip, Color(0xFF6D4C41)),
    CatSpec(Category.SCREENSHOT, "截图", Icons.Outlined.Screenshot, Color(0xFF00897B)),
    CatSpec(Category.OTHER, "其他", Icons.Outlined.MoreHoriz, Color(0xFF757575)),
)

@Composable
private fun CategoryGrid(count: (Category) -> Int?, onClick: (Category) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CATEGORY_SPECS.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { spec ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onClick(spec.category) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ColorIcon(spec.icon, spec.color, 52)
                        Text(spec.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text(
                            count(spec.category)?.let { formatCount(it) + " 项" } ?: "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorIcon(icon: ImageVector, color: Color, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp * 0.32f).dp))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((sizeDp * 0.52f).dp))
    }
}

@Composable
private fun QuickTile(modifier: Modifier, label: String, icon: ImageVector, color: Color, count: Int?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                count?.let { formatCount(it) + " 项" } ?: "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

// ================================================================= 存储卷

private data class VolumeInfo(val path: String, val label: String, val primary: Boolean, val total: Long, val free: Long)

@Composable
private fun VolumeRow(v: VolumeInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (v.primary) Icons.Outlined.PhoneAndroid else Icons.Outlined.SdStorage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(v.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "可用 ${fmtSize(context, v.free)} / 共 ${fmtSize(context, v.total)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TipBar(text: String, actionText: String, warn: Boolean, onAction: () -> Unit) {
    val bg = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = fg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

// ================================================================= 数据（全部在 IO 线程调用）

private data class QuickCounts(val wechat: Int? = null, val qq: Int? = null, val download: Int? = null, val recent: Int? = null)

private data class MediaCounts(
    val byCategory: Map<Category, Int>,
    val wechat: Int,
    val qq: Int,
    val screenshot: Int,
    val download: Int,
    val recent: Int,
    val allFiles: Int,
)

private val DOC_EXTS = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf", "csv", "wps", "et", "dps", "epub", "mobi", "azw3", "odt", "ods", "odp", "ofd")
private val APK_EXTS = listOf("apk", "apks", "xapk", "apkm")
private val ARCHIVE_EXTS = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")

private const val SHOT_LIKE = "(_data LIKE '%/Screenshots/%' OR _data LIKE '%/Screenshot/%' OR _data LIKE '%/截屏/%' OR _display_name LIKE 'Screenshot%')"

private fun extIn(exts: List<String>): String = "(" + exts.joinToString(" OR ") { "_display_name LIKE '%.$it'" } + ")"

/** MediaStore 快速计数：只取 _id 列，用 cursor.count（API 30+ 不允许 count(*) 投影）。 */
private fun queryMediaCounts(context: Context): MediaCounts {
    val uri = MediaStore.Files.getContentUri("external")
    val notHidden = "_data NOT LIKE '%/.%'"
    fun count(sel: String): Int = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), "($sel) AND $notHidden", null, null)
            ?.use { it.count } ?: 0
    } catch (_: Exception) {
        0
    }
    val mt = MediaStore.Files.FileColumns.MEDIA_TYPE
    val shots = count("$mt=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND $SHOT_LIKE")
    val images = (count("$mt=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}") - shots).coerceAtLeast(0)
    val videos = count("$mt=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
    val audio = count("$mt=${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}")
    val docs = count(extIn(DOC_EXTS))
    val apks = count(extIn(APK_EXTS))
    val archives = count(extIn(ARCHIVE_EXTS))
    // 文件（非目录）总数 - 已知类别 = 其他
    val all = count("format != 12289 AND _display_name LIKE '%.%'")
    val other = (all - shots - images - videos - audio - docs - apks - archives).coerceAtLeast(0)
    val wechat = count(
        "_data LIKE '%/com.tencent.mm/%' OR _data LIKE '%/Pictures/WeiXin/%' OR _data LIKE '%/Download/WeiXin/%' " +
            "OR _data LIKE '%/Tencent/MicroMsg/%' OR _display_name LIKE 'mmexport%'",
    )
    val qq = count(
        "_data LIKE '%/com.tencent.mobileqq/%' OR _data LIKE '%/Tencent/QQ_%' OR _data LIKE '%/Tencent/QQfile_recv/%' " +
            "OR _data LIKE '%/Pictures/QQ/%' OR _data LIKE '%/Download/QQ/%'",
    )
    val download = count("_data LIKE '$ROOT/Download/%' AND format != 12289")
    val weekAgoSec = (System.currentTimeMillis() - 7 * DAY) / 1000
    val recent = count("${MediaStore.MediaColumns.DATE_MODIFIED} >= $weekAgoSec AND format != 12289 AND _display_name LIKE '%.%'")
    return MediaCounts(
        byCategory = mapOf(
            Category.IMAGE to images, Category.VIDEO to videos, Category.AUDIO to audio, Category.DOC to docs,
            Category.APK to apks, Category.ARCHIVE to archives, Category.SCREENSHOT to shots, Category.OTHER to other,
        ),
        wechat = wechat, qq = qq, screenshot = shots, download = download, recent = recent, allFiles = all,
    )
}

/** 主存储 + SD 卡 / U 盘：用 getExternalFilesDirs 找到各卷根目录，StatFs 取容量。 */
private fun loadVolumes(context: Context): List<VolumeInfo> {
    val sm = context.getSystemService(StorageManager::class.java)
    val out = ArrayList<VolumeInfo>()
    val seen = HashSet<String>()
    val dirs = context.getExternalFilesDirs(null)
    for (d in dirs) {
        if (d == null) continue
        val p = d.absolutePath
        val i = p.indexOf("/Android/data/")
        val root = if (i > 0) p.substring(0, i) else continue
        if (!seen.add(root)) continue
        val primary = root == ROOT || root.startsWith("/storage/emulated/")
        val stat = try { StatFs(root) } catch (_: Exception) { continue }
        val total = stat.totalBytes
        if (total <= 0) continue
        val desc = try { sm?.getStorageVolume(File(root))?.getDescription(context) } catch (_: Exception) { null }
        val label = when {
            primary -> "手机存储"
            !desc.isNullOrBlank() -> desc
            else -> "外部存储"
        }
        out.add(VolumeInfo(root, label, primary, total, stat.availableBytes))
    }
    return out.sortedByDescending { it.primary }
}

// ================================================================= 工具

/** 最近一次“扫描完成”的索引统计；扫描中保持上一次的值不变，从未完成过则为 null。 */
@Composable
private fun stableSnapshot(stats: IndexStats): IndexStats? {
    // 普通持有者（非 State）：stats 变化本身就会触发重组，这里只是“记住最后一个完成值”，组合期间不写 State。
    val holder = remember { arrayOfNulls<IndexStats>(1) }
    if (!stats.scanning && stats.lastScanAt > 0L && stats.totalFiles > 0) holder[0] = stats
    return holder[0]
}

/** “正在建立索引 35%”：scanProgress 自带百分比就用；否则按“已扫描 N”与 MediaStore 总数估算（封顶 99%）。 */
private fun indexProgressText(progress: String, estimatedTotal: Int?): String {
    Regex("\\d{1,3}(\\.\\d+)?%").find(progress)?.let { return "正在建立索引 " + it.value }
    val scanned = Regex("已扫描\\s*(\\d+)").find(progress)?.groupValues?.get(1)?.toIntOrNull()
    if (scanned != null && estimatedTotal != null && estimatedTotal > 0) {
        val pct = (scanned * 100L / estimatedTotal).toInt().coerceIn(0, 99)
        return "正在建立索引 $pct%"
    }
    return if (progress.isBlank()) "正在建立索引…" else "正在建立索引 · $progress"
}

/** 像指令（有动作 / 条件词）就走 AI 解析，否则当关键词搜文件名。 */
private fun looksLikeCommand(t: String): Boolean {
    val keys = listOf(
        "删", "清理", "清除", "整理", "归类", "归档", "移到", "移动", "放到", "找", "查", "帮我", "所有", "全部",
        "天前", "周前", "月前", "年前", "最近", "上个月", "上周", "今天", "昨天", "今年", "去年",
        "大于", "小于", "超过", "里的", "中的", "截图", "安装包", "照片", "视频", "文档", "压缩包", "语音", "录音",
    )
    if (keys.any { t.contains(it) }) return true
    return t.length >= 12 && t.contains(' ')
}

private fun fmtSize(context: Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

private fun formatCount(n: Int): String = when {
    n < 10_000 -> n.toString()
    else -> String.format(Locale.ROOT, "%.1f", n / 10_000.0).removeSuffix(".0") + " 万"
}

private fun launchScan(scope: CoroutineScope) {
    scope.launch { safeIo { AiGraph.index.startScan(false) } }
}

/** 在 IO 线程执行，异常转成 Result（取消照常抛出）。 */
private suspend fun <T> safeIo(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
