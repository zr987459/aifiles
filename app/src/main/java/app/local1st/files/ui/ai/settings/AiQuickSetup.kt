package app.local1st.files.ui.ai.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.LlmSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 服务商预设：选了自动填地址和模型，用户只需粘贴 Key。 */
internal data class LlmPreset(val id: String, val name: String, val baseUrl: String, val model: String, val keyHint: String)

internal val LLM_PRESETS = listOf(
    LlmPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "platform.openai.com 获取，sk- 开头"),
    LlmPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com 获取，国内可直连"),
    LlmPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "cloud.siliconflow.cn 获取，国内可直连"),
    LlmPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "openrouter.ai 获取，sk-or- 开头"),
    LlmPreset("custom", "自定义", "", "", "任何 OpenAI 兼容接口"),
)

private fun presetOf(s: LlmSettings): LlmPreset {
    val url = s.baseUrl.trim().trimEnd('/')
    return LLM_PRESETS.firstOrNull { it.id != "custom" && it.baseUrl == url } ?: LLM_PRESETS.last()
}

/** 设置页 AI 区：快速开通（服务商 → 粘贴 Key → 测试并开启），开通后显示状态与高级项。 */
@Composable
internal fun AiQuickSetup(settings: LlmSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val llm = AiGraph.llm

    var presetId by rememberSaveable { mutableStateOf(presetOf(settings).id) }
    val preset = LLM_PRESETS.first { it.id == presetId }
    var menuOpen by remember { mutableStateOf(false) }
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var apiKey by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var limitText by rememberSaveable(settings.dailyRequestLimit) { mutableStateOf(settings.dailyRequestLimit.toString()) }
    var requestsToday by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        requestsToday = runCatching { withContext(Dispatchers.IO) { llm.requestsToday() } }.getOrNull()
    }

    val active = settings.enabled && settings.hasKey

    fun save(transform: (LlmSettings) -> LlmSettings, ok: String? = null) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { llm.update(transform(llm.settings.value)) }
                if (ok != null) result = true to ok
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
        val key = apiKey.trim()
        when {
            !url.startsWith("https://") && !url.startsWith("http://") -> { result = false to "接口地址要以 https:// 开头"; return }
            m.isEmpty() -> { result = false to "请填写模型名"; return }
            key.isEmpty() && !settings.hasKey -> { result = false to "请先粘贴 API Key"; return }
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
                        true to ("已开启 · " + r.getOrNull().orEmpty().ifBlank { "连接成功" })
                    } else {
                        false to ("测试失败：" + (r.exceptionOrNull()?.message ?: "连接失败") + "。Key 已保存，可检查后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false to ("出错了：" + (e.message ?: e.javaClass.simpleName))
            }
            if (result?.first == true) apiKey = ""
            working = false
            refresh++
        }
    }

    SectionHeader("AI 开通")
    Card(
        Modifier.fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (active) {
                Text("✓ 云端 AI 已开启", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${presetOf(settings).name.let { if (it == "自定义") settings.baseUrl else it }} · ${settings.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("快速开通 AI", style = MaterialTheme.typography.titleMedium)
                Text(
                    "不开通也能用（本地规则）。开通后能听懂更多说法、给出更聪明的清理和整理建议。选服务商 → 粘贴 Key → 测试并开启。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))

            // 服务商
            Text("服务商", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(preset.name, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    LLM_PRESETS.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(p.name)
                                    Text(p.keyHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                menuOpen = false
                                presetId = p.id
                                if (p.id != "custom") {
                                    baseUrl = p.baseUrl
                                    model = p.model
                                }
                                result = null
                            },
                        )
                    }
                }
            }
            Text(preset.keyHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim(); result = null },
                label = { Text(if (settings.hasKey) "API Key（已保存，粘贴可替换）" else "粘贴 API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                Text(
                    "地址 ${baseUrl.ifBlank { "—" }} · 模型 ${model.ifBlank { "—" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !working && (apiKey.isNotBlank() || settings.hasKey),
                    onClick = { testAndEnable() },
                ) { Text(if (active) "重新测试" else "测试并开启") }
                Spacer(Modifier.width(12.dp))
                if (working) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在连接…", style = MaterialTheme.typography.bodySmall)
                }
            }
            result?.let { (ok, msg) ->
                Text(
                    (if (ok) "✓ " else "✗ ") + msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "默认只发文件名、类型、大小、时间和文件夹名，不发文件内容；证件、银行等敏感文件和排除的文件夹永远不发。费用按服务商计算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
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
                                true to "Key 已清除，云端 AI 已关闭"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                false to ("清除失败：" + (e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }) { Text("清除 Key", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
