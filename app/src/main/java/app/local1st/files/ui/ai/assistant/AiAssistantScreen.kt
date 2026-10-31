package app.local1st.files.ui.ai.assistant

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.AiGraph
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.AppScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    var busy by remember { mutableStateOf(false) }
    var runningKey by remember { mutableStateOf<String?>(null) }
    var initialSent by rememberSaveable { mutableStateOf(false) }
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
        msgs.add(ChatMsg(pid, false, "正在想…", pending = true))
        busy = true
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { answer(t) }
                replace(pid, reply)
            } catch (e: CancellationException) {
                replace(pid, ChatMsg(pid, false, "已中断，可以再问一次。"))
                throw e
            } catch (e: Throwable) {
                replace(pid, ChatMsg(pid, false, "出错了：" + (e.message ?: e.javaClass.simpleName)))
            } finally {
                busy = false
            }
        }
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
                            Toast.makeText(context, "出错了：" + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // 上次离开时没回完的消息
        msgs.indices.reversed().forEach { i ->
            if (msgs[i].pending) msgs[i] = msgs[i].copy(text = "已中断，可以再问一次。", pending = false)
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
                        Text("AI 助手")
                        Text(
                            if (ready) "云端 AI 已开启" else "本地模式",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TooltipIconButton("返回", Icons.AutoMirrored.Outlined.ArrowBack, onClick = nav.back) },
                actions = {
                    if (msgs.isNotEmpty()) {
                        TooltipIconButton("清空对话", Icons.Outlined.DeleteSweep, enabled = !busy, onClick = { msgs.clear() })
                    }
                    TooltipIconButton("AI 设置", Icons.Outlined.Settings, onClick = { nav.open(AppScreen.AiSettings) })
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
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(EXAMPLES) { ex ->
                            SuggestionChip(onClick = { send(ex) }, enabled = !busy, label = { Text(ex) })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(300) },
                            placeholder = { Text("问我，或说一句指令") },
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send(input) }),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(enabled = input.isNotBlank() && !busy, onClick = { send(input) }) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
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
                    Text(
                        "你好！我能帮你找文件、看看哪些能删、整理文件夹。点下面的示例或直接说一句。" +
                            if (ready) "" else "\n现在是本地模式，开通云端 AI 后建议会更聪明。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!ready) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { nav.open(AppScreen.AiSettings) }) { Text("开通 AI（更聪明）") }
                    }
                }
            }
            items(msgs, key = { it.id }) { m ->
                Bubble(user = m.user) {
                    if (!m.user && m.chips.isNotEmpty()) {
                        Text(
                            if (m.uncertain) "我理解为（不太确定，请核对）" else "我理解为",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (m.uncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            m.chips.forEach { c -> AssistChip(onClick = {}, label = { Text(c, style = MaterialTheme.typography.labelSmall) }) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.pending) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (m.user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    m.note?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (m.buttons.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
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
private fun Bubble(user: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (user) 16.dp else 4.dp, bottomEnd = if (user) 4.dp else 16.dp,
            ),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
        }
    }
}
