package app.local1st.files.ui.ai.plan

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.OrganizeRule
import app.local1st.files.ai.Plan
import app.local1st.files.ai.PlanAction
import app.local1st.files.ai.PlanKind
import app.local1st.files.ai.ValidationIssue
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.ai.views.AiLoading
import app.local1st.files.ui.ai.views.AiMessage
import app.local1st.files.ui.ai.views.formatBytes
import app.local1st.files.ui.ai.views.formatMb
import app.local1st.files.ui.ai.views.nameOf
import app.local1st.files.ui.ai.views.parentOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LowConfidenceBg = Color(0x33FFC107)
private val LowConfidenceFg = Color(0xFF9A6B00)

private sealed interface Phase {
    data object Validating : Phase
    data class ValidateFailed(val message: String) : Phase
    data object Review : Phase
    data class Running(val progress: ExecProgress?) : Phase
    data class Finished(val progress: ExecProgress?, val error: String?) : Phase
    data class Undoing(val progress: ExecProgress?) : Phase
    data class Undone(val progress: ExecProgress?, val error: String?) : Phase
}

private fun Throwable.zhMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

private fun groupKeyOf(a: PlanAction): String = when (a.type) {
    ActionType.MOVE -> "移动到 ${a.destDir ?: "?"}"
    ActionType.RENAME -> "重命名"
    ActionType.MKDIR -> "新建文件夹"
    ActionType.TRASH -> "移入回收站"
}

private fun describe(a: PlanAction): Pair<String, String> {
    val src = a.srcPath.orEmpty()
    return when (a.type) {
        ActionType.MOVE -> {
            val dest = (a.destDir ?: "?").trimEnd('/') + "/" + (a.destName ?: nameOf(src))
            nameOf(src) to "$src → $dest"
        }
        ActionType.RENAME -> nameOf(src) to "$src → ${parentOf(src).trimEnd('/')}/${a.destName ?: "?"}"
        ActionType.MKDIR -> {
            val dir = (a.destDir ?: "").trimEnd('/') + (a.destName?.let { "/$it" } ?: "")
            nameOf(dir).ifEmpty { dir } to "新建 $dir"
        }
        ActionType.TRASH -> nameOf(src) to "$src → 回收站"
    }
}

/** 计划确认页（F3）：校验 → 勾选 → 执行（带进度）→ 结果 / 撤销 / 学习规则。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPlanReviewScreen(nav: AiNav, plan: Plan) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember(plan.planId) { mutableStateOf<Phase>(Phase.Validating) }
    var validated by remember(plan.planId) { mutableStateOf(plan) }
    var issues by remember(plan.planId) { mutableStateOf<Map<Int, List<ValidationIssue>>>(emptyMap()) }
    val checked = remember(plan.planId) { mutableStateMapOf<Int, Boolean>() }
    var validateTick by remember { mutableStateOf(0) }
    var suggestedRule by remember { mutableStateOf<OrganizeRule?>(null) }
    var executedPlan by remember { mutableStateOf<Plan?>(null) }

    LaunchedEffect(plan.planId, validateTick) {
        phase = Phase.Validating
        try {
            val (fixed, list) = withContext(Dispatchers.IO) { AiGraph.executor.validate(plan) }
            val byId = list.groupBy { it.actionId }
            validated = fixed
            issues = byId
            checked.clear()
            fixed.actions.forEach { a ->
                val blocking = byId[a.actionId].orEmpty().any { it.blocking }
                checked[a.actionId] = a.selected && !a.flagged && !blocking
            }
            phase = Phase.Review
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            phase = Phase.ValidateFailed(e.zhMessage())
        }
    }

    fun isBlocked(a: PlanAction) = issues[a.actionId].orEmpty().any { it.blocking }

    val actions = validated.actions
    val selectable = actions.filterNot { isBlocked(it) }
    val chosen = actions.filter { checked[it.actionId] == true && !isBlocked(it) }
    val chosenBytes = chosen.sumOf { it.size }
    val hasTrash = chosen.any { it.type == ActionType.TRASH }
    val running = phase is Phase.Running || phase is Phase.Undoing

    BackHandler(enabled = running) { /* 执行中不允许离开，避免中断批次。 */ }

    fun execute() {
        if (chosen.isEmpty() || running) return
        val toRun = validated.copy(actions = actions.map { it.copy(selected = checked[it.actionId] == true && !isBlocked(it)) })
        executedPlan = toRun
        phase = Phase.Running(null)
        scope.launch {
            var last: ExecProgress? = null
            var error: String? = null
            try {
                AiGraph.executor.execute(toRun).flowOn(Dispatchers.IO).collect { p ->
                    last = p
                    phase = Phase.Running(p)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.zhMessage()
            }
            val err = error ?: last?.error
            phase = Phase.Finished(last, err)
            val ok = err == null && (last?.failed ?: 0) == 0
            if (ok && toRun.kind == PlanKind.ORGANIZE) {
                suggestedRule = try {
                    withContext(Dispatchers.IO) { AiGraph.organizer.recordConfirmed(toRun) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    fun undo() {
        val id = (executedPlan ?: validated).planId
        phase = Phase.Undoing(null)
        scope.launch {
            var last: ExecProgress? = null
            var error: String? = null
            try {
                AiGraph.executor.undo(id).flowOn(Dispatchers.IO).collect { p ->
                    last = p
                    phase = Phase.Undoing(p)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.zhMessage()
            }
            phase = Phase.Undone(last, error ?: last?.error)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(plan.title.ifBlank { "确认计划" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = nav.back, enabled = !running) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                    },
                )
            },
            bottomBar = {
                if (phase == Phase.Review && actions.isNotEmpty()) {
                    BottomAppBar {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = { selectable.forEach { checked[it.actionId] = true } }) { Text("全选") }
                            TextButton(onClick = { actions.forEach { checked[it.actionId] = false } }) { Text("全不选") }
                            Spacer(Modifier.weight(1f))
                            if (hasTrash) {
                                HoldToConfirmButton(
                                    text = "长按 1 秒执行",
                                    enabled = chosen.isNotEmpty(),
                                    onConfirm = ::execute,
                                )
                            } else {
                                Button(onClick = ::execute, enabled = chosen.isNotEmpty()) { Text("执行 ${chosen.size} 项") }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when (val p = phase) {
                    Phase.Validating -> AiLoading("正在检查计划…")
                    is Phase.ValidateFailed -> AiMessage(
                        Icons.Outlined.ErrorOutline, "检查计划失败", p.message, isError = true,
                        actionLabel = "重试", onAction = { validateTick++ },
                    )
                    Phase.Review -> if (actions.isEmpty()) {
                        AiMessage(Icons.Outlined.FolderOpen, "计划里没有可执行的项目", "没有需要整理或删除的文件。", actionLabel = "返回", onAction = nav.back)
                    } else {
                        ReviewList(
                            actions = actions,
                            issues = issues,
                            checked = { checked[it] == true },
                            onCheck = { id, v -> checked[id] = v },
                            isBlocked = ::isBlocked,
                            header = {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        "共 ${actions.size} 项，${formatMb(actions.sumOf { it.size })}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        buildString {
                                            append("已选 ${chosen.size} 项，${formatMb(chosenBytes)}")
                                            val blocked = actions.count { isBlocked(it) }
                                            if (blocked > 0) append(" · $blocked 项无法执行")
                                            if (hasTrash) append(" · 删除只进回收站，可撤销")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                    is Phase.Running -> ProgressPane("正在执行…", p.progress)
                    is Phase.Undoing -> ProgressPane("正在撤销…", p.progress)
                    is Phase.Finished -> ResultPane(
                        title = if (p.error == null && (p.progress?.failed ?: 0) == 0) "执行完成" else "执行结束（有问题）",
                        progress = p.progress,
                        error = p.error,
                        ok = p.error == null && (p.progress?.failed ?: 0) == 0,
                        onUndo = ::undo,
                        onBack = nav.back,
                    )
                    is Phase.Undone -> ResultPane(
                        title = if (p.error == null) "已撤销本批" else "撤销未完全成功",
                        progress = p.progress,
                        error = p.error,
                        ok = p.error == null,
                        onUndo = null,
                        onBack = nav.back,
                    )
                }
            }
        }
    }

    suggestedRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { suggestedRule = null },
            title = { Text("以后自动这样整理？") },
            text = {
                Text("同类文件你已经确认过几次了。保存规则「${rule.name}」后，以后会把同类文件整理到：\n${rule.destDir}\n\n新规则前 7 天只会提醒，不会自动执行。")
            },
            confirmButton = {
                TextButton(onClick = {
                    suggestedRule = null
                    scope.launch {
                        val msg = try {
                            withContext(Dispatchers.IO) { AiGraph.organizer.saveRule(rule) }
                            "已保存规则"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            "保存规则失败：${e.zhMessage()}"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("好的") }
            },
            dismissButton = { TextButton(onClick = { suggestedRule = null }) { Text("不用") } },
        )
    }
}

@Composable
private fun ReviewList(
    actions: List<PlanAction>,
    issues: Map<Int, List<ValidationIssue>>,
    checked: (Int) -> Boolean,
    onCheck: (Int, Boolean) -> Unit,
    isBlocked: (PlanAction) -> Boolean,
    header: @Composable () -> Unit,
) {
    val groups = remember(actions) { actions.groupBy { groupKeyOf(it) }.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "summary") {
            header()
            HorizontalDivider()
        }
        groups.forEach { (group, list) ->
            item(key = "g_$group") {
                val enabledItems = list.filterNot(isBlocked)
                val n = enabledItems.count { checked(it.actionId) }
                val state = when {
                    enabledItems.isEmpty() || n == 0 -> ToggleableState.Off
                    n == enabledItems.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        state = state,
                        enabled = enabledItems.isNotEmpty(),
                        onClick = {
                            val target = state != ToggleableState.On
                            enabledItems.forEach { onCheck(it.actionId, target) }
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(group, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${list.size} 项 · ${formatBytes(list.sumOf { it.size })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(list, key = { "a_${it.actionId}" }) { a ->
                ActionRow(
                    a = a,
                    issues = issues[a.actionId].orEmpty(),
                    checked = checked(a.actionId),
                    blocked = isBlocked(a),
                    onCheck = { onCheck(a.actionId, it) },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    a: PlanAction,
    issues: List<ValidationIssue>,
    checked: Boolean,
    blocked: Boolean,
    onCheck: (Boolean) -> Unit,
) {
    val lowConfidence = a.confidence < 0.6f
    val bg = when {
        a.flagged -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        lowConfidence -> LowConfidenceBg
        else -> Color.Transparent
    }
    val (name, detail) = remember(a) { describe(a) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(enabled = !blocked) { onCheck(!checked) }
            .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked && !blocked, onCheckedChange = onCheck, enabled = !blocked)
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (a.size > 0) append(formatBytes(a.size))
                if (a.reason.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("理由：").append(a.reason)
                }
            }
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "置信度 ${(a.confidence.coerceIn(0f, 1f) * 100).toInt()}%" + if (lowConfidence) "（较低，请确认）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (lowConfidence) LowConfidenceFg else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (a.flagged) {
                Text(
                    "⚠ " + (a.flagReason ?: "需要特别留意，默认不勾选"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            issues.forEach { issue ->
                Text(
                    (if (issue.blocking) "无法执行：" else "提示：") + issue.message,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (issue.blocking) MaterialTheme.colorScheme.error else LowConfidenceFg,
                )
            }
        }
    }
}

@Composable
private fun ProgressPane(title: String, progress: ExecProgress?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        val total = progress?.total ?: 0
        if (progress == null || total <= 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val frac = (progress.done.toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${progress.done} / $total" + if (progress.failed > 0) "，失败 ${progress.failed}" else "")
        }
        if (!progress?.current.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                progress?.current.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("请不要离开此页面", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultPane(
    title: String,
    progress: ExecProgress?,
    error: String?,
    ok: Boolean,
    onUndo: (() -> Unit)?,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            val succeeded = (progress.done - progress.failed).coerceAtLeast(0)
            Text(
                "共 ${progress.total} 项，成功 $succeeded 项" + if (progress.failed > 0) "，失败 ${progress.failed} 项" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onUndo != null) {
                OutlinedButton(onClick = onUndo) { Text("撤销本批") }
            }
            Button(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.width(1.dp))
    }
}
