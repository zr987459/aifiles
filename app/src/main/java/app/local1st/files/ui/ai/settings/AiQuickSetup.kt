package app.local1st.files.ui.ai.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.LlmSettings
import app.local1st.files.ui.components.TooltipIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 服务商预设：选了自动填地址和模型，用户只需粘贴 Key。keyUrl 是“去哪拿 Key”的网页。 */
internal data class LlmPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val keyHint: String,
    val keyUrl: String? = null,
)

internal val LLM_PRESETS = listOf(
    LlmPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com 获取，国内可直连", "https://platform.deepseek.com/api_keys"),
    LlmPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "cloud.siliconflow.cn 获取，国内可直连", "https://cloud.siliconflow.cn/account/ak"),
    LlmPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "platform.openai.com 获取，sk- 开头", "https://platform.openai.com/api-keys"),
    LlmPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "openrouter.ai 获取，sk-or- 开头", "https://openrouter.ai/keys"),
    LlmPreset("custom", "自定义", "", "", "任何 OpenAI 兼容接口"),
)

private fun presetOf(s: LlmSettings): LlmPreset {
    val url = s.baseUrl.trim().trimEnd('/')
    return LLM_PRESETS.firstOrNull { it.id != "custom" && it.baseUrl == url } ?: LLM_PRESETS.last()
}

/** 测试结果：标题 + 细节 + 下一步建议。 */
private data class TestResult(val ok: Boolean, val title: String, val detail: String? = null, val advice: String? = null)

/** 粘贴的 Key 去掉空白、换行和误带的 “Bearer ” 前缀。 */
private fun cleanKey(raw: String): String = raw.replace(Regex("\\s+"), "").removePrefix("Bearer").trim()

private fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(context, url) }
}

/** 按错误信息给出下一步建议（错误文本来自 DefaultLlmClient）。 */
private fun adviceFor(context: Context, msg: String): String? = when {
    msg.contains("401") || msg.contains("403") || msg.contains("Key 无效") -> context.getString(R.string.aihome_advice_key)
    msg.contains("404") -> context.getString(R.string.aihome_advice_404)
    msg.contains("429") -> context.getString(R.string.aihome_advice_429)
    msg.contains("400") || msg.contains("JSON") -> context.getString(R.string.aihome_advice_json)
    msg.contains("超时") || msg.contains("无法连接") || msg.contains("网络") -> context.getString(R.string.aihome_advice_net)
    msg.contains("上限") -> context.getString(R.string.aihome_advice_limit)
    else -> null
}

/** 设置页 AI 区：快速开通（选服务商 → 粘贴 Key → 测试并开启）；开通后收起成状态卡，需要时再展开修改。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiQuickSetup(settings: LlmSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val llm = AiGraph.llm

    // 还没填过 Key 时默认选国内可直连的服务商；已配置过就沿用当前设置
    val initialPreset = remember {
        val cur = presetOf(settings)
        if (!settings.hasKey && cur.id == "openai") LLM_PRESETS.first() else cur
    }
    var presetId by rememberSaveable { mutableStateOf(initialPreset.id) }
    val preset = LLM_PRESETS.firstOrNull { it.id == presetId } ?: LLM_PRESETS.last()
    var baseUrl by rememberSaveable { mutableStateOf(if (initialPreset.id == presetOf(settings).id) settings.baseUrl else initialPreset.baseUrl) }
    var model by rememberSaveable { mutableStateOf(if (initialPreset.id == presetOf(settings).id) settings.model else initialPreset.model) }
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var limitText by rememberSaveable(settings.dailyRequestLimit) { mutableStateOf(settings.dailyRequestLimit.toString()) }
    var requestsToday by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        requestsToday = runCatching { withContext(Dispatchers.IO) { llm.requestsToday() } }.getOrNull()
    }

    val active = settings.enabled && settings.hasKey
    val showForm = !active || editing

    fun save(transform: (LlmSettings) -> LlmSettings, ok: String? = null) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { llm.update(transform(llm.settings.value)) }
                if (ok != null) toast(context, ok)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(context, "保存失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun testAndEnable() {
        val url = baseUrl.trim().trimEnd('/')
        val m = model.trim()
        val key = cleanKey(apiKey)
        when {
            !url.startsWith("https://") && !url.startsWith("http://") -> {
                result = TestResult(false, context.getString(R.string.aihome_check_url)); showAdvanced = true; return
            }
            m.isEmpty() -> { result = TestResult(false, context.getString(R.string.aihome_check_model)); showAdvanced = true; return }
            key.isEmpty() && !settings.hasKey -> { result = TestResult(false, context.getString(R.string.aihome_check_key)); return }
        }
        working = true
        result = null
        scope.launch {
            result = try {
                withContext(Dispatchers.IO) {
                    // 先保存地址/模型（暂不开启），再存 Key，测试通过才开启
                    llm.update(llm.settings.value.copy(baseUrl = url, model = m))
                    if (key.isNotEmpty()) llm.setApiKey(key)
                    val r = llm.test()
                    if (r.isSuccess) {
                        llm.update(llm.settings.value.copy(enabled = true))
                        TestResult(true, context.getString(R.string.aihome_test_ok), r.getOrNull().orEmpty().ifBlank { null })
                    } else {
                        val msg = r.exceptionOrNull()?.message ?: context.getString(R.string.aihome_test_unknown)
                        TestResult(false, context.getString(R.string.aihome_test_fail), msg, adviceFor(context, msg))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                TestResult(false, context.getString(R.string.aihome_test_fail), msg, adviceFor(context, msg))
            }
            if (result?.ok == true) {
                apiKey = ""
                editing = false
            }
            working = false
            refresh++
        }
    }

    SectionHeader(stringResource(R.string.aihome_setup_header))
    Card(
        Modifier.fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.aihome_active_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "${presetOf(settings).name.let { if (it == "自定义") settings.baseUrl else it }} · ${settings.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!editing) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !working, onClick = { testAndEnable() }) { Text(stringResource(R.string.aihome_retest)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { editing = true; result = null }) { Text(stringResource(R.string.aihome_change_provider)) }
                    }
                }
            } else {
                Text(stringResource(R.string.aihome_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.aihome_setup_desc), style = MaterialTheme.typography.bodySmall)
            }

            if (showForm) {
                Spacer(Modifier.height(12.dp))

                // ① 服务商：直接平铺，一眼可选
                Text(stringResource(R.string.aihome_step_provider), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LLM_PRESETS.forEach { p ->
                        FilterChip(
                            selected = p.id == presetId,
                            onClick = {
                                presetId = p.id
                                if (p.id != "custom") {
                                    baseUrl = p.baseUrl
                                    model = p.model
                                }
                                result = null
                            },
                            label = { Text(p.name) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        preset.keyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    preset.keyUrl?.let { url ->
                        TextButton(onClick = { openUrl(context, url) }) { Text(stringResource(R.string.aihome_get_key)) }
                    }
                }

                // ② 粘贴 Key
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.aihome_step_key), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = cleanKey(it); result = null },
                    label = {
                        Text(stringResource(if (settings.hasKey) R.string.aihome_key_saved_label else R.string.aihome_key_label))
                    },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row {
                            if (apiKey.isNotEmpty()) {
                                TooltipIconButton(
                                    stringResource(if (keyVisible) R.string.aihome_hide_key else R.string.aihome_show_key),
                                    if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                ) { keyVisible = !keyVisible }
                            }
                            TooltipIconButton(stringResource(R.string.aihome_paste), Icons.Outlined.ContentPaste) {
                                val clip = readClipboard(context)?.let(::cleanKey)
                                if (clip.isNullOrEmpty()) {
                                    toast(context, context.getString(R.string.aihome_clipboard_empty))
                                } else {
                                    apiKey = clip.take(300)
                                    result = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (presetId == "custom" || showAdvanced) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; result = null },
                        label = { Text("接口地址（以 /v1 结尾）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it; result = null },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.aihome_model_line, model.ifBlank { "—" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showAdvanced = true }) { Text(stringResource(R.string.aihome_edit_model)) }
                    }
                }

                // ③ 测试并开启
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !working && (apiKey.isNotBlank() || settings.hasKey),
                        onClick = { testAndEnable() },
                    ) { Text(stringResource(if (active) R.string.aihome_save_and_test else R.string.aihome_test_enable)) }
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = !working, onClick = { editing = false; result = null; apiKey = "" }) {
                            Text(stringResource(R.string.aihome_cancel))
                        }
                    }
                }
            }

            if (working) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.aihome_testing), style = MaterialTheme.typography.bodySmall)
                }
            }
            result?.let { ResultBox(it) }

            Text(
                stringResource(R.string.aihome_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }

    if (settings.hasKey) {
        SwitchRow(
            title = "使用云端 AI",
            subtitle = "关闭时所有功能只在本机用规则完成",
            checked = settings.enabled,
            onCheckedChange = { on -> save({ it.copy(enabled = on) }) },
        )
    }
    TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "收起高级设置" else "高级设置") }
    if (showAdvanced) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldWithSave(
                label = "每日请求上限",
                value = limitText,
                onValueChange = { limitText = it.filter(Char::isDigit).take(6) },
                changed = limitText != settings.dailyRequestLimit.toString(),
                keyboardType = KeyboardType.Number,
                onSave = {
                    val v = limitText.toIntOrNull()
                    if (v == null || v <= 0) toast(context, "请输入大于 0 的数字")
                    else save({ s -> s.copy(dailyRequestLimit = v) }, "已保存")
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日请求：${requestsToday?.toString() ?: "—"} / ${settings.dailyRequestLimit}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (settings.hasKey) {
                    TextButton(onClick = {
                        scope.launch {
                            result = try {
                                withContext(Dispatchers.IO) {
                                    llm.setApiKey(null)
                                    llm.update(llm.settings.value.copy(enabled = false))
                                }
                                TestResult(true, context.getString(R.string.aihome_key_cleared))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                TestResult(false, "清除失败", e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }) { Text("清除 Key", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun ResultBox(r: TestResult) {
    val bg = if (r.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (r.ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.padding(12.dp)) {
            Icon(
                if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = fg)
                r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg) }
                r.advice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
