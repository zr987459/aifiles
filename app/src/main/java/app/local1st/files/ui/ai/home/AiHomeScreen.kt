package app.local1st.files.ui.ai.home

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
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
import java.util.Locale
import java.util.UUID

private val EXAMPLES = listOf("下载里 30 天前的安装包删掉", "上个月的截图", "微信里的 PDF 整理一下", "大于 100M 的视频")

/** FIND 结果在对话里预览几条。 */
private const val PREVIEW = 5
private const val FIND_LIMIT = 500

// ---------------------------------------------------------------- 对话数据

/** 对话里的一条。放在进程级的 [AiChat] 里，进出计划页 / 分类页后对话还在。 */
private sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem

    /** AI 的回复：解析中 → 我理解为…（+ 结果 / 执行按钮）。 */
    data class Reply(
        override val id: Long,
        val raw: String,
        val intent: CommandIntent? = null,
        val parsing: Boolean = true,
        val error: String? = null,
        /** FIND：查到的文件（最多 [FIND_LIMIT]）。null 表示还没查。 */
        val files: List<IndexedFile>? = null,
        val acting: Boolean = false,
        val message: String? = null,
    ) : ChatItem
}

private object AiChat {
    val items = mutableStateListOf<ChatItem>()
    private var seq = 0L
    fun nextId(): Long = ++seq

    fun update(id: Long, f: (ChatItem.Reply) -> ChatItem.Reply) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = items[i] as? ChatItem.Reply ?: return
        items[i] = f(cur)
    }

    fun reply(id: Long): ChatItem.Reply? = items.firstOrNull { it.id == id } as? ChatItem.Reply
}

// ---------------------------------------------------------------- 页面

/** AI 首页：以一句话对话为主；清理 / 分类 / 历史 / 设置收进“更多”菜单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHomeScreen(nav: AiNav) {
    val scope = rememberCoroutineScope()
    val stats by AiGraph.index.stats.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var input by rememberSaveable { mutableStateOf("") }
    var interrupted by remember { mutableStateOf<List<PlanRecord>>(emptyList()) }
    var storageGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val chat = AiChat.items

    LifecycleResumeEffect(Unit) {
        val now = hasStorageAccess(context)
        // 刚从系统设置授权回来：立即补一次扫描
        if (now && !storageGranted) launchScan(scope)
        storageGranted = now
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        // 索引在后台线程读出上次扫描时间，稍等再判断，避免把“还没读到”当成“从未扫描”
        delay(600)
        val s = AiGraph.index.stats.value
        if (s.lastScanAt == 0L && !s.scanning && hasStorageAccess(context)) {
            safeIo { AiGraph.index.startScan(false) }
        }
        interrupted = safeIo { AiGraph.executor.interrupted() }.getOrDefault(emptyList())
    }

    // 新消息时滚到底
    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex)
    }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (chat.any { it is ChatItem.Reply && it.parsing }) return
        keyboard?.hide()
        input = ""
        chat.add(ChatItem.User(AiChat.nextId(), t))
        val rid = AiChat.nextId()
        chat.add(ChatItem.Reply(rid, t))
        scope.launch {
            val parsed = safeIo { AiGraph.organizer.parseCommand(t) }
            parsed.fold(
                onSuccess = { intent ->
                    AiChat.update(rid) { it.copy(parsing = false, intent = intent) }
                    if (intent.action == IntentAction.FIND) {
                        val files = safeIo { AiGraph.index.query(intent.query.copy(limit = FIND_LIMIT)) }
                        AiChat.update(rid) { r ->
                            files.fold(
                                onSuccess = { r.copy(files = it) },
                                onFailure = { e -> r.copy(files = emptyList(), message = "查询出错：" + (e.message ?: e.javaClass.simpleName)) },
                            )
                        }
                    }
                },
                onFailure = { e -> AiChat.update(rid) { it.copy(parsing = false, error = e.message ?: "解析失败") } },
            )
        }
    }

    fun act(rid: Long) {
        val r = AiChat.reply(rid) ?: return
        val intent = r.intent ?: return
        if (r.acting) return
        AiChat.update(rid) { it.copy(acting = true, message = null) }
        scope.launch {
            val res: Result<AppScreen?> = when (intent.action) {
                IntentAction.FIND -> Result.success(AppScreen.AiViews(query = intent.query, title = intentTitle(intent)))
                IntentAction.CLEAN, IntentAction.TRASH -> if (!hasCondition(intent.query)) {
                    Result.failure(IllegalArgumentException("条件太宽，请说清楚位置、类型、时间或大小"))
                } else safeIo {
                    val files = AiGraph.index.query(intent.query)
                    if (files.isEmpty()) null else AppScreen.AiPlanReview(trashPlan(intent, files))
                }
                IntentAction.ORGANIZE -> safeIo {
                    val plan = AiGraph.organizer.proposeOrganize(intent.query.pathPrefix, intent.raw)
                    if (plan.actions.isEmpty()) null else AppScreen.AiPlanReview(plan)
                }
            }
            res.fold(
                onSuccess = { screen ->
                    AiChat.update(rid) { it.copy(acting = false, message = if (screen == null) "没有找到符合条件的文件" else null) }
                    if (screen != null) nav.open(screen)
                },
                onFailure = { e ->
                    val msg = if (e is IllegalArgumentException) e.message else "出错了：" + (e.message ?: e.javaClass.simpleName)
                    AiChat.update(rid) { it.copy(acting = false, message = msg) }
                },
            )
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI 文件") },
                    navigationIcon = {
                        TooltipIconButton("文件", Icons.Outlined.Folder, onClick = nav.back)
                    },
                    actions = {
                        if (chat.isNotEmpty()) {
                            TooltipIconButton("清空对话", Icons.Outlined.DeleteSweep) { chat.clear() }
                        }
                        Box {
                            TooltipIconButton("更多", Icons.Outlined.MoreVert) { menuOpen = true }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                MenuItem("一键清理", Icons.Outlined.CleaningServices) { menuOpen = false; nav.open(AppScreen.AiClean) }
                                MenuItem("智能分类", Icons.Outlined.Category) { menuOpen = false; nav.open(AppScreen.AiViews()) }
                                MenuItem("历史", Icons.Outlined.History) { menuOpen = false; nav.open(AppScreen.AiHistory) }
                                MenuItem("设置", Icons.Outlined.Settings) { menuOpen = false; nav.open(AppScreen.AiSettings) }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                // 小提示条 + 一行索引状态
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!storageGranted) {
                        TipBar("没有“所有文件访问”权限，无法扫描文件", "去授权", warn = false) { nav.open(AppScreen.AiSettings) }
                    }
                    if (interrupted.isNotEmpty()) {
                        val n = interrupted.size
                        TipBar(if (n > 1) "上次操作被中断（$n 个计划）" else "上次操作被中断", "去历史", warn = true) {
                            nav.open(AppScreen.AiHistory)
                        }
                    }
                    StatsLine(stats) { launchScan(scope) }
                }

                if (chat.isEmpty()) {
                    // 空对话：大输入框居中，示例在下面
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("一句话告诉我想找、整理或删除什么", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        CommandInput(input, { input = it }, big = true, onSubmit = { submit(input) })
                        Spacer(Modifier.height(10.dp))
                        ExampleChips(centered = true) { submit(it) }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(chat, key = { it.id }) { item ->
                            when (item) {
                                is ChatItem.User -> UserBubble(item.text)
                                is ChatItem.Reply -> ReplyBubble(
                                    item,
                                    onAct = { act(item.id) },
                                    onOpenFile = nav.openFile,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExampleChips(centered = false) { submit(it) }
                        CommandInput(input, { input = it }, big = false, onSubmit = { submit(input) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 输入与示例

@Composable
private fun CommandInput(value: String, onValue: (String) -> Unit, big: Boolean, onSubmit: () -> Unit) {
    val busy = AiChat.items.any { it is ChatItem.Reply && it.parsing }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (big) Modifier.heightIn(min = 120.dp) else Modifier),
        placeholder = { Text(if (big) "如“下载里 30 天前的安装包删掉”" else "继续说…") },
        trailingIcon = {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                TooltipIconButton("发送", Icons.AutoMirrored.Outlined.Send, enabled = value.isNotBlank(), onClick = onSubmit)
            }
        },
        minLines = if (big) 3 else 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExampleChips(centered: Boolean, onPick: (String) -> Unit) {
    if (centered) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EXAMPLES.forEach { ex -> SuggestionChip(onClick = { onPick(ex) }, label = { Text(ex, style = MaterialTheme.typography.labelMedium) }) }
        }
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { ex -> SuggestionChip(onClick = { onPick(ex) }, label = { Text(ex, style = MaterialTheme.typography.labelMedium) }) }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

// ---------------------------------------------------------------- 对话气泡

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyBubble(r: ChatItem.Reply, onAct: () -> Unit, onOpenFile: (String) -> Unit) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                r.parsing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在理解…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.error != null || r.intent == null -> Text(
                    "没能理解这句话：" + (r.error ?: "解析失败") + "。换个说法试试？",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    val intent: CommandIntent = r.intent!!
                    Text("我理解为", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        intent.chips.ifEmpty { listOf("搜索：" + intent.raw) }.forEach { c ->
                            AssistChip(
                                onClick = {},
                                label = { Text(c, style = MaterialTheme.typography.labelMedium) },
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

                    if (intent.action == IntentAction.FIND) {
                        val files = r.files
                        when {
                            files == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在查找…", style = MaterialTheme.typography.bodySmall)
                            }
                            files.isEmpty() -> Text("没有找到符合条件的文件", style = MaterialTheme.typography.bodyMedium)
                            else -> {
                                val total = files.sumOf { it.size }
                                val count = if (files.size >= FIND_LIMIT) "${FIND_LIMIT}+" else files.size.toString()
                                Text(
                                    "找到 $count 个文件 · " + Formatter.formatShortFileSize(context, total),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                files.take(PREVIEW).forEach { f -> FileRow(f) { onOpenFile(f.path) } }
                            }
                        }
                    }

                    r.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

                    val showButton = intent.action != IntentAction.FIND || !r.files.isNullOrEmpty()
                    if (showButton) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val label = when (intent.action) {
                                IntentAction.FIND -> "查看全部"
                                IntentAction.CLEAN, IntentAction.TRASH -> "生成删除计划"
                                IntentAction.ORGANIZE -> "生成整理计划"
                            }
                            if (intent.action == IntentAction.FIND) {
                                OutlinedButton(onClick = onAct, enabled = !r.acting) { Text(label) }
                            } else {
                                Button(onClick = onAct, enabled = !r.acting) {
                                    if (r.acting) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(label)
                                }
                            }
                            if (intent.action == IntentAction.CLEAN || intent.action == IntentAction.TRASH) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "先预览，只进回收站",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(f: IndexedFile, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            Formatter.formatShortFileSize(context, f.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val UncertainBg = Color(0xFFFFE082)
private val UncertainFg = Color(0xFF3E2C00)

// ---------------------------------------------------------------- 状态行与提示条

/** 一行小字：已索引 4.2 万个文件 · 扫描中 15%。点一下重新扫描。 */
@Composable
private fun StatsLine(stats: IndexStats, onRescan: () -> Unit) {
    val text = buildString {
        when {
            stats.totalFiles > 0 -> append("已索引 ").append(formatCount(stats.totalFiles)).append(" 个文件")
            stats.scanning -> append("正在建立索引")
            stats.lastScanAt == 0L -> append("还没有扫描过文件")
            else -> append("没有索引到文件")
        }
        if (stats.scanning) {
            val pct = Regex("\\d{1,3}(\\.\\d+)?%").find(stats.scanProgress)?.value
            append(" · 扫描中")
            if (pct != null) append(' ').append(pct)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !stats.scanning, onClick = onRescan)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!stats.scanning) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "重新扫描",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
    }
}

@Composable
private fun TipBar(text: String, actionText: String, warn: Boolean, onAction: () -> Unit) {
    val bg = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = fg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

// ---------------------------------------------------------------- 工具

private fun formatCount(n: Int): String = when {
    n < 10_000 -> n.toString()
    n < 100_000_000 -> String.format(Locale.ROOT, "%.1f", n / 10_000.0).removeSuffix(".0") + " 万"
    else -> String.format(Locale.ROOT, "%.1f", n / 100_000_000.0).removeSuffix(".0") + " 亿"
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
            // 解析不确定时默认不勾选，让用户逐个确认
            selected = !intent.uncertain,
        )
    },
)

/** 删除类指令至少要有一个限定条件，避免“删掉”这类空指令把整个索引都列进计划。 */
private fun hasCondition(q: FileQuery): Boolean =
    !q.text.isNullOrBlank() || q.categories.isNotEmpty() || q.subCategory != null || q.sourceApp != null ||
        !q.pathPrefix.isNullOrBlank() || q.exts.isNotEmpty() || q.minSize != null || q.maxSize != null ||
        q.olderThanMs != null || q.newerThanMs != null

private fun intentTitle(intent: CommandIntent): String =
    intent.chips.filterNot { it.startsWith("动作：") }.joinToString(" · ").ifBlank { intent.raw }.take(40)

private fun launchScan(scope: CoroutineScope) {
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
