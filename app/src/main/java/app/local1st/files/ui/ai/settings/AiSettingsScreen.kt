package app.local1st.files.ui.ai.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.local1st.files.ai.AiGraph
import app.local1st.files.ai.LlmSettings
import app.local1st.files.ai.OrganizeRule
import app.local1st.files.core.fs.priv.ShizukuGate
import app.local1st.files.core.fs.priv.ShizukuState
import app.local1st.files.ui.ai.AiNav
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.hasStorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuProvider


private sealed interface RulesUi {
    data object Loading : RulesUi
    data class Error(val message: String) : RulesUi
    data class Loaded(val rules: List<OrganizeRule>) : RulesUi
}

/** AI 设置：快速开通 AI、隐私、整理规则、权限与覆盖范围。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiSettingsScreen(nav: AiNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val llm = AiGraph.llm
    val settings by llm.settings.collectAsState()

    // 设置变更统一走 llm.update，失败只提示不崩溃。
    fun updateSettings(transform: (LlmSettings) -> LlmSettings) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { llm.update(transform(llm.settings.value)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(context, "保存失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ---- 隐私 ----
    var newExcluded by rememberSaveable { mutableStateOf("") }

    // ---- 整理规则 ----
    var rulesUi by remember { mutableStateOf<RulesUi>(RulesUi.Loading) }
    var rulesKey by remember { mutableStateOf(0) }
    var deleteRule by remember { mutableStateOf<OrganizeRule?>(null) }

    LaunchedEffect(rulesKey) {
        rulesUi = RulesUi.Loading
        rulesUi = try {
            RulesUi.Loaded(withContext(Dispatchers.IO) { AiGraph.organizer.rules() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            RulesUi.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun saveRule(rule: OrganizeRule) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AiGraph.organizer.saveRule(rule) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(context, "规则保存失败：" + (e.message ?: e.javaClass.simpleName))
            }
            rulesKey++
        }
    }

    // ---- 权限 ----
    var storageGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    LifecycleResumeEffect(Unit) {
        storageGranted = hasStorageAccess(context)
        onPauseOrDispose { }
    }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { storageGranted = hasStorageAccess(context) }
    val shizukuState by ShizukuGate.state.collectAsState()
    val shizukuDenied by ShizukuGate.permissionPermanentlyDeniedState.collectAsState()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("AI 设置") },
                    navigationIcon = {
                        TooltipIconButton("返回", Icons.AutoMirrored.Outlined.ArrowBack, onClick = nav.back)
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
            ) {
                // ================= 快速开通 AI =================
                AiQuickSetup(settings)

                // ================= 隐私 =================
                SectionHeader("隐私")
                SwitchRow(
                    title = "允许发送内容摘要",
                    subtitle = "开启后会发送截图文字、文档开头片段，帮助更准确地分类。默认只发文件名、大小、时间等信息",
                    checked = settings.allowContent,
                    onCheckedChange = { on -> updateSettings { it.copy(allowContent = on) } },
                )
                Text(
                    "不发给 AI 的文件夹",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                if (settings.excludedPaths.isEmpty()) {
                    Hint("还没有添加。证件、银行等敏感文件名本来就不会发送。")
                }
                settings.excludedPaths.forEach { path ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            path,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            updateSettings { s -> s.copy(excludedPaths = s.excludedPaths - path) }
                        }) { Text("删除") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newExcluded,
                        onValueChange = { newExcluded = it },
                        label = { Text("文件夹路径，如 /storage/emulated/0/证件") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = newExcluded.isNotBlank(),
                        onClick = {
                            val p = normalizePath(newExcluded)
                            when {
                                !p.startsWith("/") -> toast(context, "请输入以 / 开头的完整路径")
                                settings.excludedPaths.contains(p) -> toast(context, "已经在列表里了")
                                else -> {
                                    updateSettings { s -> s.copy(excludedPaths = (s.excludedPaths + p).distinct()) }
                                    newExcluded = ""
                                }
                            }
                        },
                    ) { Text("添加") }
                }

                // ================= 整理规则 =================
                SectionHeader("整理规则")
                when (val r = rulesUi) {
                    RulesUi.Loading -> Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载规则…", style = MaterialTheme.typography.bodySmall)
                    }

                    is RulesUi.Error -> Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(
                            "规则加载失败：${r.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { rulesKey++ }) { Text("重试") }
                    }

                    is RulesUi.Loaded -> if (r.rules.isEmpty()) {
                        Hint("还没有整理规则。同类整理确认满 3 次后，AI 会建议生成一条规则。")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.rules.forEach { rule ->
                                RuleCard(
                                    rule = rule,
                                    onEnabled = { saveRule(rule.copy(enabled = it)) },
                                    onAuto = { saveRule(rule.copy(auto = it)) },
                                    onDelete = { deleteRule = rule },
                                )
                            }
                        }
                    }
                }

                // ================= 权限与覆盖 =================
                SectionHeader("权限与覆盖范围")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("所有文件访问权限", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (storageGranted) "已授权，可以整理和清理整个内部存储" else "未授权，AI 看不到大部分文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (storageGranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        if (!storageGranted) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    requestAllFilesAccess(context)
                                } else {
                                    legacyLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.READ_EXTERNAL_STORAGE,
                                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        ),
                                    )
                                }
                            }) { Text("去授权") }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Shizuku", style = MaterialTheme.typography.titleSmall)
                        Text(
                            shizukuLabel(shizukuState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (shizukuState != ShizukuState.Ready) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "授权 Shizuku 后可管理 Android/data 里的微信/QQ 文件",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        when {
                            shizukuState == ShizukuState.PermissionRequired && !shizukuDenied -> {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = {
                                    if (!ShizukuGate.requestPermission()) {
                                        toast(context, "无法弹出授权，请在 Shizuku 里手动授权")
                                    }
                                }) { Text("请求 Shizuku 授权") }
                            }

                            shizukuState == ShizukuState.PermissionRequired && shizukuDenied -> {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "之前已拒绝且不再询问，请打开 Shizuku 手动授权本 App。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { openShizuku(context) }) { Text("打开 Shizuku") }
                            }

                            shizukuState == ShizukuState.NotRunning -> {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { openShizuku(context) }) { Text("打开 Shizuku 启动服务") }
                            }

                            else -> Unit
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    deleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteRule = null },
            title = { Text("删除规则？") },
            text = { Text("删除「${rule.name}」后不会再按它整理，已整理的文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRule = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { AiGraph.organizer.deleteRule(rule.id) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            toast(context, "删除失败：" + (e.message ?: e.javaClass.simpleName))
                        }
                        rulesKey++
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteRule = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun RuleCard(
    rule: OrganizeRule,
    onEnabled: (Boolean) -> Unit,
    onAuto: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                rule.name.ifBlank { "未命名规则" },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                "移到 ${rule.destDir}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            SwitchRow(title = "启用", checked = rule.enabled, onCheckedChange = onEnabled)
            SwitchRow(
                title = "自动执行",
                subtitle = "关闭时只生成建议，执行前由你确认",
                checked = rule.auto,
                onCheckedChange = onAuto,
            )
            TextButton(onClick = onDelete) { Text("删除规则", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun FieldWithSave(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    changed: Boolean,
    onSave: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = changed, onClick = onSave) { Text("保存") }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun shizukuLabel(state: ShizukuState): String = when (state) {
    ShizukuState.NotInstalled -> "未安装"
    ShizukuState.NotRunning -> "已安装，服务未运行"
    ShizukuState.PermissionRequired -> "服务运行中，未授权本 App"
    ShizukuState.Ready -> "已授权，可访问 Android/data"
}

private fun normalizePath(raw: String): String {
    var p = raw.trim()
    while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
    return p
}

internal fun requestAllFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:" + context.packageName),
    )
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
}

private fun openShizuku(context: Context) {
    val launched = runCatching {
        val packageName = context.packageManager
            .getPermissionInfo(ShizukuProvider.PERMISSION, 0)
            .packageName
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@runCatching false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
    if (!launched) toast(context, "没有找到 Shizuku，请先安装")
}

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
