package app.local1st.files.ui.ai.history

import android.text.format.Formatter
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import app.local1st.files.R
import app.local1st.files.ui.ai.views.prettyPath
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.PlanAction
import app.local1st.files.ai.PlanKind
import app.local1st.files.ai.PlanRecord
import app.local1st.files.ai.PlanStatus
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.components.TooltipIconButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面数据的三种状态：加载中 / 出错 / 已加载。 */
private sealed interface HistoryUi {
    data object Loading : HistoryUi
    data class Error(val message: String) : HistoryUi
    data class Loaded(val interrupted: List<PlanRecord>, val history: List<PlanRecord>) : HistoryUi
}

/** 某条明细的加载状态。 */
private sealed interface DetailUi {
    data object Loading : DetailUi
    data class Error(val message: String) : DetailUi
    data class Loaded(val actions: List<PlanAction>) : DetailUi
}

/** 正在进行的 撤销 / 继续 / 回滚 操作。 */
private data class OpState(
    val title: String,
    val progress: ExecProgress? = null,
    val finished: Boolean = false,
    val error: String? = null,
)

/** 待确认的操作。 */
private data class PendingConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val run: () -> Unit,
)

/** AI 操作历史：被中断的操作、历次整理/清理记录、明细、整批撤销。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiHistoryScreen(nav: AiNav) {
    val scope = rememberCoroutineScope()
    val executor = AiGraph.executor

    var ui by remember { mutableStateOf<HistoryUi>(HistoryUi.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val details = remember { mutableStateMapOf<String, DetailUi>() }
    var op by remember { mutableStateOf<OpState?>(null) }
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }
    var showTrashHint by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        ui = HistoryUi.Loading
        ui = try {
            withContext(Dispatchers.IO) {
                val interrupted = executor.interrupted()
                val history = executor.history(100)
                HistoryUi.Loaded(interrupted, history)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            HistoryUi.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun loadDetail(planId: String) {
        details[planId] = DetailUi.Loading
        scope.launch {
            details[planId] = try {
                DetailUi.Loaded(withContext(Dispatchers.IO) { executor.actionsOf(planId) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DetailUi.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun runOp(title: String, block: () -> Flow<ExecProgress>) {
        op = OpState(title)
        scope.launch {
            try {
                block().flowOn(Dispatchers.IO).collect { p ->
                    op = op?.copy(progress = p, error = p.error ?: op?.error)
                }
                op = op?.copy(finished = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                op = op?.copy(finished = true, error = e.message ?: e.javaClass.simpleName)
            }
            details.clear()
            reloadKey++
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val undoTitle = stringResource(R.string.aiflow_history_undo_confirm_title)
    // 带 %1$s / %2$d 的模板：点按时再填入，避免在列表里为每条记录各取一次。
    val undoMessage = stringResource(R.string.aiflow_history_undo_confirm_message_tpl)
    val undoLabel = stringResource(R.string.aiflow_history_undo)
    val undoingTitle = stringResource(R.string.aiflow_history_undoing_tpl)
    val untitled = stringResource(R.string.aiflow_history_untitled)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("AI 操作历史") },
                    navigationIcon = {
                        TooltipIconButton("返回", Icons.AutoMirrored.Outlined.ArrowBack, onClick = nav.back)
                    },
                    actions = {
                        TextButton(onClick = { showTrashHint = true }) { Text("打开回收站") }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            when (val state = ui) {
                HistoryUi.Loading -> CenterBox(Modifier.padding(padding)) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在加载…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                is HistoryUi.Error -> CenterBox(Modifier.padding(padding)) {
                    Text("加载失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { reloadKey++ }) { Text("重试") }
                }

                is HistoryUi.Loaded -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.interrupted.isNotEmpty()) {
                        item(key = "interrupted-header") { SectionHeader("被中断的操作") }
                        items(state.interrupted, key = { "int-" + it.planId }) { rec ->
                            InterruptedCard(
                                record = rec,
                                busy = op?.finished == false,
                                onResume = {
                                    confirm = PendingConfirm(
                                        title = "继续执行？",
                                        message = "将继续完成「${rec.title}」中剩余的操作。",
                                        confirmLabel = "继续",
                                        run = { runOp("正在继续「${rec.title}」") { executor.resume(rec.planId) } },
                                    )
                                },
                                onUndo = {
                                    confirm = PendingConfirm(
                                        title = "整批回滚？",
                                        message = "将把「${rec.title}」已经完成的操作全部还原。",
                                        confirmLabel = "整批回滚",
                                        run = { runOp("正在回滚「${rec.title}」") { executor.undo(rec.planId) } },
                                    )
                                },
                            )
                        }
                    }

                    if (state.history.any { it.status == PlanStatus.DONE || it.status == PlanStatus.PARTIAL }) {
                        item(key = "undo-intro") { UndoIntroCard() }
                    }
                    item(key = "history-header") { SectionHeader("历史记录") }
                    if (state.history.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("还没有 AI 操作记录", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "执行过的整理和清理计划会显示在这里，可以整批撤销。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(state.history, key = { "his-" + it.planId }) { rec ->
                            val isOpen = expanded[rec.planId] == true
                            RecordCard(
                                record = rec,
                                expanded = isOpen,
                                detail = details[rec.planId],
                                busy = op?.finished == false,
                                onToggle = {
                                    expanded[rec.planId] = !isOpen
                                    if (!isOpen && details[rec.planId] !is DetailUi.Loaded) loadDetail(rec.planId)
                                },
                                onRetryDetail = { loadDetail(rec.planId) },
                                onUndo = {
                                    confirm = PendingConfirm(
                                        title = undoTitle,
                                        message = undoMessage.format(rec.title.ifBlank { untitled }, rec.done),
                                        confirmLabel = undoLabel,
                                        run = { runOp(undoingTitle.format(rec.title.ifBlank { untitled })) { executor.undo(rec.planId) } },
                                    )
                                },
                            )
                        }
                    }
                    item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    c.run()
                }) { Text(c.confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }

    op?.let { o -> ProgressDialog(o, onClose = { op = null }) }

    if (showTrashHint) {
        AlertDialog(
            onDismissRequest = { showTrashHint = false },
            title = { Text("回收站") },
            text = { Text("回收站在浏览器侧栏里。AI 删除的文件都先放进回收站，可以在那里还原或清空。") },
            confirmButton = {
                TextButton(onClick = { showTrashHint = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun InterruptedCard(
    record: PlanRecord,
    busy: Boolean,
    onResume: () -> Unit,
    onUndo: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                record.title.ifBlank { "未命名操作" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${kindZh(record.kind)} · ${formatTime(record.createdAt)} · 已完成 ${record.done}/${record.total}" +
                    if (record.failed > 0) "，失败 ${record.failed}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "App 在执行时被关闭，文件可能只搬了一部分。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume, enabled = !busy) { Text("继续") }
                OutlinedButton(onClick = onUndo, enabled = !busy) { Text("整批回滚") }
            }
        }
    }
}

@Composable
private fun RecordCard(
    record: PlanRecord,
    expanded: Boolean,
    detail: DetailUi?,
    busy: Boolean,
    onToggle: () -> Unit,
    onRetryDetail: () -> Unit,
    onUndo: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.title.ifBlank { "未命名操作" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusZh(record.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor(record.status),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${kindZh(record.kind)} · ${formatTime(record.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "成功 ${record.done} · 失败 ${record.failed} · 共 ${record.total} 项 · " +
                    Formatter.formatShortFileSize(context, record.bytes.coerceAtLeast(0)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.status == PlanStatus.UNDONE) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.aiflow_history_undone_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (record.status == PlanStatus.DONE || record.status == PlanStatus.PARTIAL) {
                    FilledTonalButton(onClick = onUndo, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.aiflow_history_undo_batch))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggle) {
                    Text(stringResource(if (expanded) R.string.aiflow_history_hide_detail else R.string.aiflow_history_show_detail))
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                when (detail) {
                    null, DetailUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载明细…", style = MaterialTheme.typography.bodySmall)
                    }

                    is DetailUi.Error -> Column {
                        Text(
                            "明细加载失败：${detail.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetryDetail) { Text("重试") }
                    }

                    is DetailUi.Loaded -> if (detail.actions.isEmpty()) {
                        Text(
                            "没有明细",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            detail.actions.take(MAX_DETAIL_ROWS).forEach { ActionRow(it) }
                            if (detail.actions.size > MAX_DETAIL_ROWS) {
                                Text(
                                    "还有 ${detail.actions.size - MAX_DETAIL_ROWS} 项未显示",
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
private fun ActionRow(action: PlanAction) {
    val context = LocalContext.current
    val src = action.srcPath.orEmpty()
    val srcName = src.substringAfterLast('/')
    val line = when (action.type) {
        ActionType.MOVE -> stringResource(
            R.string.aiflow_history_line_move,
            srcName,
            prettyPath(action.destDir.orEmpty().trimEnd('/')) +
                (action.destName?.takeIf { it != srcName }?.let { "/$it" } ?: ""),
        )
        ActionType.RENAME -> stringResource(R.string.aiflow_history_line_rename, srcName, action.destName.orEmpty())
        ActionType.MKDIR -> stringResource(R.string.aiflow_history_line_mkdir, prettyPath(action.destDir.orEmpty()))
        ActionType.TRASH -> stringResource(R.string.aiflow_history_line_trash, srcName)
    }
    val from = if (action.type == ActionType.MOVE || action.type == ActionType.TRASH) {
        src.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { stringResource(R.string.aiflow_line_from, prettyPath(it)) }
    } else null
    Column {
        Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (from != null) {
            Text(
                from,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = buildList {
            if (action.size > 0) add(Formatter.formatShortFileSize(context, action.size))
            if (action.reason.isNotBlank()) add(action.reason)
            if (action.flagged) add("⚠ " + (action.flagReason ?: "需注意"))
        }.joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = if (action.flagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressDialog(op: OpState, onClose: () -> Unit) {
    val p = op.progress
    AlertDialog(
        onDismissRequest = { if (op.finished) onClose() },
        title = {
            Text(
                when {
                    !op.finished -> op.title
                    op.error == null && (p?.failed ?: 0) == 0 -> stringResource(R.string.aiflow_history_op_ok)
                    else -> stringResource(R.string.aiflow_history_op_partial)
                },
            )
        },
        text = {
            Column {
                if (!op.finished) {
                    if (p != null && p.total > 0) {
                        LinearProgressIndicator(
                            progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (p != null) {
                    Text("进度 ${p.done}/${p.total}" + if (p.failed > 0) "，失败 ${p.failed}" else "")
                    if (!op.finished && p.current.isNotBlank()) {
                        Text(
                            p.current,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (!op.finished) {
                    Text("准备中…")
                }
                if (op.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("出错：${op.error}", color = MaterialTheme.colorScheme.error)
                    if (op.finished) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.aiflow_undo_partial_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (op.finished) {
                    Text(stringResource(R.string.aiflow_history_op_done_detail))
                }
            }
        },
        confirmButton = {
            if (op.finished) TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}

/** 历史页顶部的说明：撤销会做什么。 */
@Composable
private fun UndoIntroCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.AutoMirrored.Outlined.Undo,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    stringResource(R.string.aiflow_history_intro_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.aiflow_undo_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CenterBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun statusColor(status: PlanStatus) = when (status) {
    PlanStatus.DONE -> MaterialTheme.colorScheme.primary
    PlanStatus.PARTIAL, PlanStatus.INTERRUPTED, PlanStatus.FAILED -> MaterialTheme.colorScheme.error
    PlanStatus.RUNNING, PlanStatus.UNDONE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun kindZh(kind: PlanKind): String = when (kind) {
    PlanKind.ORGANIZE -> "整理"
    PlanKind.CLEAN -> "清理"
    PlanKind.MANUAL -> "手动"
}

private fun statusZh(status: PlanStatus): String = when (status) {
    PlanStatus.RUNNING -> "执行中"
    PlanStatus.DONE -> "已完成"
    PlanStatus.PARTIAL -> "部分完成"
    PlanStatus.FAILED -> "失败"
    PlanStatus.UNDONE -> "已撤销"
    PlanStatus.INTERRUPTED -> "被中断"
}

private fun formatTime(ms: Long): String =
    if (ms <= 0) "时间未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))

private const val MAX_DETAIL_ROWS = 200
