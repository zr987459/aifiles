package app.local1st.files.ui.ai.clean

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.ai.CleanGroup
import app.local1st.files.ai.IndexStats
import app.local1st.files.ai.Risk

/* aiflow：清理页的“分析进度两步走”和“清理前确认”。 */

/** 确认框里的一行：某类文件选了几项、多大。 */
internal data class CleanBreakdownRow(val label: String, val count: Int, val bytes: Long, val risky: Boolean)

/** 把要清理的路径按分组类型汇总；同一路径只算一次。 */
internal fun cleanBreakdown(snapshot: List<CleanGroup>, paths: Set<String>): List<CleanBreakdownRow> {
    val seen = HashSet<String>()
    val acc = LinkedHashMap<String, CleanBreakdownRow>()
    snapshot.forEach { g ->
        cleanSelectable(g).forEach { item ->
            if (item.path in paths && seen.add(item.path)) {
                val key = g.kind.zh
                val old = acc[key]
                acc[key] = CleanBreakdownRow(
                    label = key,
                    count = (old?.count ?: 0) + 1,
                    bytes = (old?.bytes ?: 0L) + item.size,
                    risky = (old?.risky ?: false) || g.risk != Risk.SAFE,
                )
            }
        }
    }
    return acc.values.sortedByDescending { it.bytes }
}

/** 选中的条目里有没有“建议确认”的（不是规则判定为安全的）。 */
internal fun cleanHasRisky(snapshot: List<CleanGroup>, paths: Set<String>): Boolean =
    snapshot.any { g -> g.risk != Risk.SAFE && cleanSelectable(g).any { it.path in paths } }

private enum class StepState { WAITING, RUNNING, DONE, ERROR }

@Composable
private fun StepRow(index: Int, title: String, detail: String?, state: StepState, progress: Float? = null) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                StepState.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = CleanSafeColor, modifier = Modifier.size(20.dp))
                StepState.ERROR -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                StepState.WAITING -> Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.aiflow_scan_step, index, title),
                style = MaterialTheme.typography.labelLarge,
                color = if (state == StepState.WAITING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (state == StepState.RUNNING) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == StepState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 分析进度：第 1 步建立文件索引，第 2 步查找可清理的文件。
 * 让用户知道现在在干什么、还要等什么、结果是多少。
 */
@Composable
internal fun CleanScanSteps(
    stats: IndexStats,
    indexPercent: Int?,
    analyzing: Boolean,
    progressText: String,
    scanError: String?,
    foundCount: Int,
    foundBytes: Long,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val indexState = when {
                stats.scanning -> StepState.RUNNING
                stats.totalFiles > 0 || stats.lastScanAt > 0 -> StepState.DONE
                else -> StepState.WAITING
            }
            StepRow(
                index = 1,
                title = stringResource(R.string.aiflow_scan_step_index),
                detail = when (indexState) {
                    StepState.RUNNING -> if (indexPercent != null) {
                        stringResource(R.string.aiflow_scan_index_running_pct, stats.totalFiles, indexPercent)
                    } else {
                        stringResource(R.string.aiflow_scan_index_running, stats.totalFiles)
                    }
                    StepState.DONE -> stringResource(R.string.aiflow_scan_index_done, stats.totalFiles, cleanBytes(stats.totalBytes))
                    else -> stringResource(R.string.aiflow_scan_index_waiting)
                },
                state = indexState,
                progress = if (indexState == StepState.RUNNING) indexPercent?.let { it / 100f } else null,
            )
            val findState = when {
                scanError != null -> StepState.ERROR
                stats.scanning -> StepState.WAITING
                analyzing -> StepState.RUNNING
                indexState == StepState.DONE -> StepState.DONE
                else -> StepState.WAITING
            }
            StepRow(
                index = 2,
                title = stringResource(R.string.aiflow_scan_step_find),
                detail = when (findState) {
                    StepState.ERROR -> stringResource(R.string.aiflow_scan_find_error, scanError.orEmpty())
                    StepState.RUNNING -> buildFindRunning(progressText, foundCount, foundBytes)
                    StepState.DONE -> if (foundCount > 0) {
                        stringResource(R.string.aiflow_scan_find_done, foundCount, cleanBytes(foundBytes))
                    } else {
                        stringResource(R.string.aiflow_scan_find_none)
                    }
                    StepState.WAITING -> stringResource(R.string.aiflow_scan_find_waiting)
                },
                state = findState,
            )
            if (findState == StepState.ERROR) {
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.aiflow_retry))
                }
            }
        }
    }
}

@Composable
private fun buildFindRunning(progressText: String, foundCount: Int, foundBytes: Long): String {
    val head = progressText.ifBlank { stringResource(R.string.aiflow_scan_find_running) }
    return if (foundCount > 0) head + " · " + stringResource(R.string.aiflow_scan_find_so_far, foundCount, cleanBytes(foundBytes)) else head
}

/** 清理前的确认：按类型列出要动的文件，说明只是进回收站、可以撤销。 */
@Composable
internal fun CleanConfirmDialog(
    rows: List<CleanBreakdownRow>,
    risky: Boolean,
    oneClick: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = rows.sumOf { it.count }
    val bytes = rows.sumOf { it.bytes }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (risky) Icons.Outlined.WarningAmber else Icons.Outlined.DeleteOutline,
                null,
                tint = if (risky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                stringResource(
                    if (oneClick) R.string.aiflow_clean_confirm_title_oneclick else R.string.aiflow_clean_confirm_title,
                    count,
                    cleanBytes(bytes),
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.take(8).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.aiflow_clean_confirm_row, r.count, cleanBytes(r.bytes)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (r.risky) CleanConfirmColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (rows.size > 8) {
                    Text(
                        stringResource(R.string.aiflow_clean_confirm_more, rows.size - 8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (risky) {
                    Text(
                        stringResource(R.string.aiflow_clean_confirm_risky),
                        style = MaterialTheme.typography.bodySmall,
                        color = CleanConfirmColor,
                    )
                }
                Text(
                    stringResource(R.string.aiflow_clean_confirm_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.aiflow_clean_confirm_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.aiflow_cancel)) } },
    )
}
