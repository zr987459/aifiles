package app.local1st.files.ui.ai.home

import android.content.Context
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
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.Category
import app.local1st.files.ai.CommandIntent
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.IndexStats
import app.local1st.files.ai.IndexedFile
import app.local1st.files.ai.IntentAction
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanAction
import app.local1st.files.ai.PlanKind
import app.local1st.files.ai.PlanRecord
import app.local1st.files.ai.Risk
import app.local1st.files.ui.ai.AiNav
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
import java.util.UUID

private const val ROOT = "/storage/emulated/0"
private const val DAY = 86_400_000L

// ================================================================= 首页

/**
 * AI 文件唯一的首页（U2）：搜索/指令框 → 分类宫格 → 快捷访问 → 存储位置 → 空间清理条，底部导航 最近 / 文件 / 历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHomeScreen(nav: AiNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val stats by AiGraph.index.stats.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var storageGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    var interrupted by remember { mutableStateOf<List<PlanRecord>>(emptyList()) }
    var mediaCounts by remember { mutableStateOf<MediaCounts?>(null) }
    var quick by remember { mutableStateOf(QuickCounts()) }
    var volumes by remember { mutableStateOf<List<VolumeInfo>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0) }

    // 指令弹窗
    var sheet by remember { mutableStateOf<CmdSheet?>(null) }

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

    // 存储卷与 MediaStore 计数：每次回到首页刷新一次（都在 IO 线程）
    LaunchedEffect(refreshTick, storageGranted) {
        volumes = safeIo { loadVolumes(context) }.getOrDefault(emptyList())
        if (storageGranted) mediaCounts = safeIo { queryMediaCounts(context) }.getOrNull() ?: mediaCounts
    }

    // 快捷访问计数：索引可用用索引，否则 MediaStore
    val indexReady = stats.lastScanAt > 0L && !stats.scanning && stats.totalFiles > 0
    LaunchedEffect(indexReady, mediaCounts, stats.lastScanAt) {
        quick = if (indexReady) {
            safeIo {
                val src = AiGraph.index.sourceApps()
                QuickCounts(
                    wechat = src["wechat"],
                    qq = src["qq"],
                    screenshot = stats.byCategory[Category.SCREENSHOT]?.count ?: 0,
                    download = mediaCounts?.download ?: AiGraph.index.query(FileQuery(pathPrefix = "$ROOT/Download", limit = 100_000)).size,
                )
            }.getOrDefault(QuickCounts())
        } else {
            val m = mediaCounts
            if (m == null) QuickCounts() else QuickCounts(m.wechat, m.qq, m.screenshot, m.download)
        }
    }

    fun categoryCount(c: Category): Int? =
        if (indexReady) stats.byCategory[c]?.count ?: 0 else mediaCounts?.byCategory?.get(c)

    fun submit() {
        val t = input.trim()
        if (t.isEmpty()) return
        keyboard?.hide()
        if (!looksLikeCommand(t)) {
            nav.open(AppScreen.AiViews(query = FileQuery(text = t), title = "搜索：$t"))
            return
        }
        sheet = CmdSheet(raw = t)
        scope.launch {
            val r = safeIo { AiGraph.organizer.parseCommand(t) }
            val cur = sheet ?: return@launch
            if (cur.raw != t) return@launch
            sheet = r.fold(
                onSuccess = { cur.copy(parsing = false, intent = it) },
                onFailure = { cur.copy(parsing = false, error = it.message ?: "解析失败") },
            )
        }
    }

    fun act() {
        val s = sheet ?: return
        val intent = s.intent ?: return
        if (s.acting) return
        if (intent.action == IntentAction.FIND) {
            sheet = null
            nav.open(AppScreen.AiViews(query = intent.query, title = intentTitle(intent)))
            return
        }
        sheet = s.copy(acting = true, message = null)
        scope.launch {
            val res: Result<AppScreen?> = when (intent.action) {
                IntentAction.CLEAN, IntentAction.TRASH -> if (!hasCondition(intent.query)) {
                    Result.failure(IllegalArgumentException("条件太宽，请说清楚位置、类型、时间或大小"))
                } else safeIo {
                    val files = AiGraph.index.query(intent.query)
                    if (files.isEmpty()) null else AppScreen.AiPlanReview(trashPlan(intent, files))
                }
                else -> safeIo {
                    val plan = AiGraph.organizer.proposeOrganize(intent.query.pathPrefix, intent.raw)
                    if (plan.actions.isEmpty()) null else AppScreen.AiPlanReview(plan)
                }
            }
            val cur = sheet ?: return@launch
            res.fold(
                onSuccess = { screen ->
                    if (screen == null) sheet = cur.copy(acting = false, message = "没有找到符合条件的文件")
                    else { sheet = null; nav.open(screen) }
                },
                onFailure = { e ->
                    sheet = cur.copy(
                        acting = false,
                        message = if (e is IllegalArgumentException) e.message else "出错了：" + (e.message ?: e.javaClass.simpleName),
                    )
                },
            )
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = false,
                        onClick = {
                            nav.open(AppScreen.AiViews(query = FileQuery(newerThanMs = 7 * DAY), title = "最近 7 天"))
                        },
                        icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                        label = { Text("最近") },
                    )
                    NavigationBarItem(
                        selected = true,
                        onClick = {},
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        label = { Text("文件") },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { nav.open(AppScreen.AiHistory) },
                        icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        label = { Text("历史") },
                    )
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                // 1. 搜索 / 指令框 + 设置
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索文件，或告诉 AI 要做什么", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (input.isNotEmpty()) {
                                TooltipIconButton("清除", Icons.Outlined.Close) { input = "" }
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
                    Spacer(Modifier.width(4.dp))
                    TooltipIconButton("设置", Icons.Outlined.Settings) { nav.open(AppScreen.AiSettings) }
                }

                // 7. 索引进度一行小字 + 小提示条
                if (stats.scanning) {
                    val pct = Regex("\\d{1,3}(\\.\\d+)?%").find(stats.scanProgress)?.value
                    Text(
                        "正在建立索引" + (pct?.let { " $it" } ?: "…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 0.dp),
                    )
                }
                if (!storageGranted) {
                    TipBar("没有“所有文件访问”权限，无法扫描文件", "去授权", warn = false) { nav.open(AppScreen.AiSettings) }
                }
                if (interrupted.isNotEmpty()) {
                    val n = interrupted.size
                    TipBar(if (n > 1) "上次操作被中断（$n 个计划）" else "上次操作被中断", "去历史", warn = true) {
                        nav.open(AppScreen.AiHistory)
                    }
                }

                // 2. 分类宫格
                CategoryGrid({ categoryCount(it) }) { c -> nav.open(AppScreen.AiViews(viewKey = "cat:" + c.name, title = c.zh)) }

                // 3. 快捷访问
                SectionTitle("快捷访问")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile(Modifier.weight(1f), "微信", Icons.Outlined.Chat, Color(0xFF07C160), quick.wechat) {
                        nav.open(AppScreen.AiViews(query = FileQuery(sourceApp = "wechat"), title = "微信"))
                    }
                    QuickTile(Modifier.weight(1f), "QQ", Icons.Outlined.Forum, Color(0xFF12B7F5), quick.qq) {
                        nav.open(AppScreen.AiViews(query = FileQuery(sourceApp = "qq"), title = "QQ"))
                    }
                    QuickTile(Modifier.weight(1f), "截屏", Icons.Outlined.Screenshot, Color(0xFF7E57C2), quick.screenshot) {
                        nav.open(AppScreen.AiViews(query = FileQuery(categories = setOf(Category.SCREENSHOT)), title = "截屏"))
                    }
                    QuickTile(Modifier.weight(1f), "下载", Icons.Outlined.Download, Color(0xFF1E88E5), quick.download) {
                        nav.open(AppScreen.AiViews(query = FileQuery(pathPrefix = "$ROOT/Download"), title = "下载"))
                    }
                }

                // 4. 存储位置
                if (volumes.isNotEmpty()) {
                    SectionTitle("存储位置")
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                        Column {
                            volumes.forEach { v -> VolumeRow(v) { nav.back() } }
                        }
                    }
                }

                // 5. 空间清理条
                volumes.firstOrNull { it.primary }?.let { v -> CleanBar(v) { nav.open(AppScreen.AiClean) } }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    sheet?.let { s ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) {
            CommandSheet(s, onAct = { act() }, onSearchInstead = {
                sheet = null
                nav.open(AppScreen.AiViews(query = FileQuery(text = s.raw), title = "搜索：" + s.raw))
            })
        }
    }
}

// ================================================================= 指令弹窗

private data class CmdSheet(
    val raw: String,
    val parsing: Boolean = true,
    val intent: CommandIntent? = null,
    val error: String? = null,
    val acting: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandSheet(s: CmdSheet, onAct: () -> Unit, onSearchInstead: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("“${s.raw}”", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val intent = s.intent
        when {
            s.parsing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在理解…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            intent == null -> {
                Text("没能理解这句话：" + (s.error ?: "解析失败"), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onSearchInstead) { Text("按文件名搜索") }
            }
            else -> {
                Text("我理解为", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    intent.chips.ifEmpty { listOf("搜索：" + intent.raw) }.forEach { c ->
                        AssistChip(
                            onClick = {},
                            label = { Text(c) },
                            colors = if (intent.uncertain) {
                                AssistChipDefaults.assistChipColors(containerColor = UncertainBg, labelColor = UncertainFg)
                            } else AssistChipDefaults.assistChipColors(),
                        )
                    }
                }
                if (intent.uncertain) {
                    Text(
                        "不太确定，标黄的条件请核对；不对可以换个说法。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onAct, enabled = !s.acting) {
                        if (s.acting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when (intent.action) {
                                IntentAction.FIND -> "查看结果"
                                IntentAction.CLEAN, IntentAction.TRASH -> "生成删除计划"
                                IntentAction.ORGANIZE -> "生成整理计划"
                            },
                        )
                    }
                    if (intent.action == IntentAction.CLEAN || intent.action == IntentAction.TRASH) {
                        Spacer(Modifier.width(10.dp))
                        Text("先预览，只进回收站", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val UncertainBg = Color(0xFFFFE082)
private val UncertainFg = Color(0xFF3E2C00)

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

// ================================================================= 存储位置与清理条

private data class VolumeInfo(val path: String, val label: String, val primary: Boolean, val total: Long, val free: Long)

@Composable
private fun VolumeRow(v: VolumeInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
private fun CleanBar(v: VolumeInfo, onClean: () -> Unit) {
    val context = LocalContext.current
    val used = (v.total - v.free).coerceAtLeast(0)
    val frac = if (v.total > 0) used.toFloat() / v.total else 0f
    Surface(
        onClick = onClean,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已用 ${fmtSize(context, used)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text("空间清理", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            LinearProgressIndicator(
                progress = { frac.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (frac > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                drawStopIndicator = {},
            )
        }
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

private data class QuickCounts(val wechat: Int? = null, val qq: Int? = null, val screenshot: Int? = null, val download: Int? = null)

private data class MediaCounts(
    val byCategory: Map<Category, Int>,
    val wechat: Int,
    val qq: Int,
    val screenshot: Int,
    val download: Int,
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
    return MediaCounts(
        byCategory = mapOf(
            Category.IMAGE to images, Category.VIDEO to videos, Category.AUDIO to audio, Category.DOC to docs,
            Category.APK to apks, Category.ARCHIVE to archives, Category.SCREENSHOT to shots, Category.OTHER to other,
        ),
        wechat = wechat, qq = qq, screenshot = shots, download = download,
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
            reason = "符合指令：" + intent.raw,
            risk = Risk.CONFIRM,
            selected = !intent.uncertain,
        )
    },
)

/** 删除类指令至少要有一个限定条件。 */
private fun hasCondition(q: FileQuery): Boolean =
    !q.text.isNullOrBlank() || q.categories.isNotEmpty() || q.subCategory != null || q.sourceApp != null ||
        !q.pathPrefix.isNullOrBlank() || q.exts.isNotEmpty() || q.minSize != null || q.maxSize != null ||
        q.olderThanMs != null || q.newerThanMs != null

private fun intentTitle(intent: CommandIntent): String =
    intent.chips.filterNot { it.startsWith("动作：") }.joinToString(" · ").ifBlank { intent.raw }.take(40)

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
