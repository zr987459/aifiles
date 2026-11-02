package app.local1st.files.ui.ai.assistant

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.ai.AiGraph
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.AppScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI 助手：对话式提问 / 指令。能执行的给“我理解为”+按钮；存储问题本地汇总，开通 AI 后让模型给建议。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiAssistantScreen(nav: AiNav, initial: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val msgs = AssistantSession.messages
    val llmSettings by AiGraph.llm.settings.collectAsState()
    val ready = remember(llmSettings) { runCatching { AiGraph.llm.isReady() }.getOrDefault(false) }

    var input by rememberSaveable { mutableStateOf("") }
    // 正在回答的那条占位消息 id；非 null 即“忙”
    var activeId by remember { mutableStateOf<Long?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    val busy = activeId != null
    var runningKey by remember { mutableStateOf<String?>(null) }
    var initialSent by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun replace(id: Long, m: ChatMsg) {
        val i = msgs.indexOfFirst { it.id == id }
        if (i >= 0) msgs[i] = m.copy(id = id) else msgs.add(m.copy(id = id))
    }

    fun send(raw: String) {
        val t = raw.trim()
        if (t.isEmpty() || busy) return
        input = ""
        keyboard?.hide()
        msgs.add(ChatMsg(AssistantSession.nextId(), true, t))
        val pid = AssistantSession.nextId()
        val thinking = context.getString(if (isStorageQuestion(t)) R.string.aihome_thinking_storage else R.string.aihome_thinking)
        msgs.add(ChatMsg(pid, false, thinking, pending = true))
        activeId = pid
        activeJob = scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { answer(t) }
                if (activeId == pid) replace(pid, reply)
            } catch (e: CancellationException) {
                if (msgs.any { it.id == pid && it.pending }) {
                    replace(pid, ChatMsg(pid, false, context.getString(R.string.aihome_stopped), retry = t))
                }
                throw e
            } catch (e: Throwable) {
                if (activeId == pid) replace(pid, errorMsg(context, pid, e, t))
            } finally {
                if (activeId == pid) {
                    activeId = null
                    activeJob = null
                }
            }
        }
    }

    fun stop() {
        val pid = activeId ?: return
        activeJob?.cancel()
        // 网络请求可能不会立刻响应取消：界面先结束“忙”，迟到的结果直接丢弃
        replace(pid, ChatMsg(pid, false, context.getString(R.string.aihome_stopped), retry = msgs.lastOrNull { it.user }?.text))
        activeId = null
        activeJob = null
    }

    fun click(msg: ChatMsg, b: ChatButton) {
        when (val a = b.action) {
            is BtnAction.Open -> nav.open(a.screen)
            is BtnAction.Run -> {
                if (runningKey != null) return
                val key = "${msg.id}:${b.label}"
                runningKey = key
                scope.launch {
                    val r = try {
                        Result.success(withContext(Dispatchers.IO) { a.work() })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Result.failure(e)
                    } finally {
                        runningKey = null
                    }
                    r.fold(
                        onSuccess = { s ->
                            if (s == null) Toast.makeText(context, a.emptyText, Toast.LENGTH_SHORT).show() else nav.open(s)
                        },
                        onFailure = { e ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.aihome_err_prefix, e.message ?: e.javaClass.simpleName),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // 上次离开时没回完的消息
        msgs.indices.reversed().forEach { i ->
            if (msgs[i].pending) msgs[i] = msgs[i].copy(text = context.getString(R.string.aihome_stopped), pending = false)
        }
        if (!initialSent) {
            initialSent = true
            if (!initial.isNullOrBlank()) send(initial)
        }
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size) // 第 0 项是开场白
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.aihome_assistant_title))
                        Text(
                            stringResource(if (ready) R.string.aihome_mode_cloud else R.string.aihome_mode_local),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TooltipIconButton(stringResource(R.string.aihome_back), Icons.AutoMirrored.Outlined.ArrowBack, onClick = nav.back) },
                actions = {
                    if (msgs.isNotEmpty()) {
                        TooltipIconButton(stringResource(R.string.aihome_clear_chat), Icons.Outlined.DeleteSweep, enabled = !busy, onClick = { confirmClear = true })
                    }
                    TooltipIconButton(stringResource(R.string.aihome_ai_settings), Icons.Outlined.Settings, onClick = { nav.open(AppScreen.AiSettings) })
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    if (input.isBlank() && !busy) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(EXAMPLES) { ex ->
                                SuggestionChip(onClick = { send(ex) }, label = { Text(ex) })
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(300) },
                            placeholder = { Text(stringResource(R.string.aihome_input_hint)) },
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send(input) }),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f),
                        )
                        if (busy) {
                            IconButton(onClick = { stop() }) {
                                Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.aihome_stop))
                            }
                        } else {
                            IconButton(enabled = input.isNotBlank(), onClick = { send(input) }) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.aihome_send))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Bubble(user = false) {
                    Text(stringResource(R.string.aihome_intro), style = MaterialTheme.typography.bodyMedium)
                    if (!ready) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.aihome_intro_local),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { nav.open(AppScreen.AiSettings) }) { Text(stringResource(R.string.aihome_ai_setup)) }
                    }
                }
            }
            items(msgs, key = { it.id }) { m ->
                Bubble(user = m.user, error = m.error) {
                    if (!m.user && m.chips.isNotEmpty()) {
                        Text(
                            if (m.uncertain) "我理解为（不太确定，请核对）" else "我理解为",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (m.uncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // 只是展示理解结果，不做成可点的样子
                            m.chips.forEach { c -> Tag(c) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.pending) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        SelectionContainer {
                            Text(
                                m.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    m.user -> MaterialTheme.colorScheme.onPrimaryContainer
                                    m.error -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                    m.note?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val retry = m.retry
                    if (m.buttons.isNotEmpty() || retry != null) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (retry != null) {
                                FilledTonalButton(enabled = !busy, onClick = { send(retry) }) { Text(stringResource(R.string.aihome_retry)) }
                            }
                            m.buttons.forEach { b ->
                                val running = runningKey == "${m.id}:${b.label}"
                                val enabled = runningKey == null
                                if (b.primary) {
                                    FilledTonalButton(enabled = enabled, onClick = { click(m, b) }) { BtnLabel(b.label, running) }
                                } else {
                                    OutlinedButton(enabled = enabled, onClick = { click(m, b) }) { BtnLabel(b.label, running) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.aihome_clear_chat_title)) },
            text = { Text(stringResource(R.string.aihome_clear_chat_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    msgs.clear()
                }) { Text(stringResource(R.string.aihome_clear_chat_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.aihome_cancel)) } },
        )
    }
}

/** 出错回复：说人话的原因 + “再试一次”；Key / 额度类问题再给一个“检查 AI 设置”。 */
private fun errorMsg(context: Context, id: Long, e: Throwable, question: String): ChatMsg {
    val raw = (e.message ?: e.javaClass.simpleName).take(160)
    val keyIssue = listOf("Key", "401", "403", "上限", "未开启").any { raw.contains(it) }
    val net = listOf("超时", "无法连接", "网络", "timeout", "Unable to resolve").any { raw.contains(it, ignoreCase = true) }
    val hint = when {
        keyIssue -> context.getString(R.string.aihome_err_hint_key)
        net -> context.getString(R.string.aihome_err_hint_net)
        else -> null
    }
    val text = context.getString(R.string.aihome_err_prefix, raw) + (hint?.let { "\n$it" } ?: "")
    val buttons = if (keyIssue) {
        listOf(ChatButton(context.getString(R.string.aihome_check_settings), BtnAction.Open(AppScreen.AiSettings)))
    } else {
        emptyList()
    }
    return ChatMsg(id, false, text, buttons = buttons, error = true, retry = question)
}

@Composable
private fun Tag(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun BtnLabel(label: String, running: Boolean) {
    if (running) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
    }
    Text(label)
}

@Composable
private fun Bubble(user: Boolean, error: Boolean = false, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (user) 16.dp else 4.dp, bottomEnd = if (user) 4.dp else 16.dp,
            ),
            color = when {
                user -> MaterialTheme.colorScheme.primaryContainer
                error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
        }
    }
}
