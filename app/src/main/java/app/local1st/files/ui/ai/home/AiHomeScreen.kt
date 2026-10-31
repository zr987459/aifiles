package app.local1st.files.ui.ai.home

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.Category
import app.local1st.files.ai.CategoryStat
import app.local1st.files.ai.CommandIntent
import app.local1st.files.ai.IndexStats
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val EXAMPLES = listOf("把下载里的安装包删掉", "找上个月的发票截图", "整理下载文件夹")

/** 一句话指令的状态。 */
private sealed interface CommandState {
    data object Idle : CommandState
    data object Parsing : CommandState
    data class Parsed(val intent: CommandIntent) : CommandState
    data class Error(val message: String) : CommandState
}

/** AI 首页（F1）：一句话指令、存储概览、清理/分类入口、分类宫格。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiHomeScreen(nav: AiNav) {
    val scope = rememberCoroutineScope()
    val stats by AiGraph.index.stats.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var input by rememberSaveable { mutableStateOf("") }
    var command by remember { mutableStateOf<CommandState>(CommandState.Idle) }
    /** 正在根据解析结果生成计划 / 查询文件。 */
    var acting by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var interrupted by remember { mutableStateOf<List<PlanRecord>>(emptyList()) }

    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    LifecycleResumeEffect(Unit) {
        val now = hasStorageAccess(context)
        // 刚从系统设置授权回来：立即补一次扫描
        if (now && !storageGranted) safeLaunchScan(scope)
        storageGranted = now
        onPauseOrDispose { }
    }

    // 进入页面：从未扫描过就自动扫描；查一下有没有被中断的计划
    LaunchedEffect(Unit) {
        // 索引在后台线程从磁盘读出上次扫描时间，稍等一下再判断，避免把“还没读到”当成“从未扫描”
        delay(600)
        val s = AiGraph.index.stats.value
        if (s.lastScanAt == 0L && !s.scanning && hasStorageAccess(context)) {
            safeIo { AiGraph.index.startScan(false) }
        }
        interrupted = safeIo { AiGraph.executor.interrupted() }.getOrDefault(emptyList())
    }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty() || command == CommandState.Parsing) return
        command = CommandState.Parsing
        actionMessage = null
        scope.launch {
            command = safeIo { AiGraph.organizer.parseCommand(t) }.fold(
                onSuccess = { CommandState.Parsed(it) },
                onFailure = { CommandState.Error(it.message ?: "解析失败") },
            )
        }
    }

    fun act(intent: CommandIntent) {
        if (acting) return
        acting = true
        actionMessage = null
        scope.launch {
            val r: Result<AppScreen?> = when (intent.action) {
                IntentAction.FIND -> Result.success(
                    AppScreen.AiViews(query = intent.query, title = intentTitle(intent)),
                )
                IntentAction.CLEAN, IntentAction.TRASH -> if (!hasCondition(intent.query)) {
                    Result.failure(IllegalArgumentException("条件太宽，请说清楚位置、类型、时间或大小"))
                } else safeIo {
                    val files = AiGraph.index.query(intent.query)
                    if (files.isEmpty()) null
                    else AppScreen.AiPlanReview(
                        Plan(
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
                                    // 解析不确定时默认不勾选，让用户逐个确认
                                    selected = !intent.uncertain,
                                )
                            },
                        ),
                    )
                }
                IntentAction.ORGANIZE -> safeIo {
                    val plan = AiGraph.organizer.proposeOrganize(intent.query.pathPrefix, intent.raw)
                    if (plan.actions.isEmpty()) null else AppScreen.AiPlanReview(plan)
                }
            }
            acting = false
            r.fold(
                onSuccess = { screen ->
                    if (screen == null) actionMessage = "没有找到符合条件的文件"
                    else nav.open(screen)
                },
                onFailure = {
                    actionMessage = if (it is IllegalArgumentException) it.message
                    else "出错了：" + (it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("AI 文件") },
                    navigationIcon = {
                        TooltipIconButton("返回", Icons.AutoMirrored.Outlined.ArrowBack, onClick = nav.back)
                    },
                    actions = {
                        TooltipIconButton("历史", Icons.Outlined.History) { nav.open(AppScreen.AiHistory) }
                        TooltipIconButton("设置", Icons.Outlined.Settings) { nav.open(AppScreen.AiSettings) }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!storageGranted) {
                    PermissionBanner { nav.open(AppScreen.AiSettings) }
                }
                if (interrupted.isNotEmpty()) {
                    InterruptedBanner(interrupted.size) { nav.open(AppScreen.AiHistory) }
                }

                CommandSection(
                    input = input,
                    onInput = { input = it },
                    onSubmit = { submit(input) },
                    onExample = { input = it; submit(it) },
                    state = command,
                    acting = acting,
                    message = actionMessage,
                    onAct = { act(it) },
                    onDismiss = { command = CommandState.Idle; actionMessage = null },
                )

                StatsCard(stats) { safeLaunchScan(scope) }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EntryCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.CleaningServices,
                        title = "一键清理",
                        subtitle = "重复、安装包、旧下载…",
                    ) { nav.open(AppScreen.AiClean) }
                    EntryCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Category,
                        title = "智能分类",
                        subtitle = "按类型、来源、时间看",
                    ) { nav.open(AppScreen.AiViews()) }
                }

                CategoryGrid(stats.byCategory) { c ->
                    nav.open(AppScreen.AiViews(viewKey = "cat:" + c.name, title = c.zh))
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- 一句话指令

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandSection(
    input: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onExample: (String) -> Unit,
    state: CommandState,
    acting: Boolean,
    message: String?,
    onAct: (CommandIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = state == CommandState.Parsing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            label = { Text("想让我做什么？") },
            placeholder = { Text("如“把下载里的安装包删掉”“找上个月的发票截图”") },
            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
            trailingIcon = {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    TooltipIconButton("发送", Icons.AutoMirrored.Outlined.Send, enabled = input.isNotBlank()) {
                        keyboard?.hide(); onSubmit()
                    }
                }
            },
            minLines = 2,
            maxLines = 4,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { keyboard?.hide(); onSubmit() }),
            shape = MaterialTheme.shapes.large,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { ex ->
                AssistChip(onClick = { keyboard?.hide(); onExample(ex) }, label = { Text(ex) }, enabled = !busy)
            }
        }

        when (state) {
            CommandState.Idle, CommandState.Parsing -> Unit
            is CommandState.Error -> Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "没能理解这句话：" + state.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TooltipIconButton("关闭", Icons.Outlined.Close, onClick = onDismiss)
                }
            }
            is CommandState.Parsed -> ParsedCard(state.intent, acting, message, onAct, onDismiss)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParsedCard(
    intent: CommandIntent,
    acting: Boolean,
    message: String?,
    onAct: (CommandIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我理解为", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TooltipIconButton("关闭", Icons.Outlined.Close, onClick = onDismiss)
            }
            val chips = intent.chips.ifEmpty { listOf("搜索：" + intent.raw) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { c ->
                    AssistChip(
                        onClick = {},
                        label = { Text(c) },
                        colors = if (intent.uncertain) {
                            AssistChipDefaults.assistChipColors(containerColor = UncertainBg, labelColor = UncertainFg)
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                    )
                }
            }
            if (intent.uncertain) {
                Text(
                    "不太确定，标黄的条件请核对；不对可以换个说法再试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onAct(intent) }, enabled = !acting) {
                    if (acting) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
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
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "删除前会先预览，只进回收站",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val UncertainBg = Color(0xFFFFE082)
private val UncertainFg = Color(0xFF3E2C00)

// ---------------------------------------------------------------- 存储概览

@Composable
private fun StatsCard(stats: IndexStats, onRescan: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("存储概览", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onRescan, enabled = !stats.scanning) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新扫描")
                }
            }
            if (stats.totalFiles == 0 && !stats.scanning) {
                Text(
                    if (stats.lastScanAt == 0L) "还没有扫描过，点“重新扫描”建立文件索引" else "没有索引到文件",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%,d".format(stats.totalFiles),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(" 个文件 · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        Formatter.formatShortFileSize(context, stats.totalBytes),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            if (stats.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stats.scanProgress.ifBlank { "正在扫描…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (stats.lastScanAt > 0L) {
                Text(
                    "上次扫描：" + DateUtils.getRelativeTimeSpanString(stats.lastScanAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 入口卡与宫格

@Composable
private fun EntryCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CategoryGrid(byCategory: Map<Category, CategoryStat>, onClick: (Category) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        Category.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    val st = byCategory[c]
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onClick(c) },
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Icon(categoryIcon(c), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(c.zh, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(
                                if (st == null) "—" else "${st.count} 个",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                if (st == null) " " else Formatter.formatShortFileSize(context, st.bytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.DOC -> Icons.Outlined.Description
    Category.IMAGE -> Icons.Outlined.Image
    Category.SCREENSHOT -> Icons.Outlined.Screenshot
    Category.VIDEO -> Icons.Outlined.Movie
    Category.AUDIO -> Icons.Outlined.MusicNote
    Category.APK -> Icons.Outlined.Android
    Category.ARCHIVE -> Icons.Outlined.FolderZip
    Category.OTHER -> Icons.Outlined.MoreHoriz
}

@Composable
private fun InterruptedBanner(count: Int, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                if (count > 1) "上次操作被中断（$count 个计划）" else "上次操作被中断",
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("去历史") }
        }
    }
}

@Composable
private fun PermissionBanner(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                "没有“所有文件访问”权限，无法扫描文件",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("去授权") }
        }
    }
}

// ---------------------------------------------------------------- 工具

/** 删除类指令至少要有一个限定条件，避免“删掉”这类空指令把整个索引都列进计划。 */
private fun hasCondition(q: app.local1st.files.ai.FileQuery): Boolean =
    !q.text.isNullOrBlank() || q.categories.isNotEmpty() || q.subCategory != null || q.sourceApp != null ||
        !q.pathPrefix.isNullOrBlank() || q.exts.isNotEmpty() || q.minSize != null || q.maxSize != null ||
        q.olderThanMs != null || q.newerThanMs != null

private fun intentTitle(intent: CommandIntent): String =
    intent.chips.joinToString(" · ").ifBlank { intent.raw }.take(40)

private fun safeLaunchScan(scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch { safeIo { AiGraph.index.startScan(false) } }
}

/** 在 IO 线程执行，异常转成 Result（取消照常抛出），保证后端是桩或出错时页面不崩。 */
private suspend fun <T> safeIo(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
