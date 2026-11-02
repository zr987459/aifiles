package app.local1st.files.ui.ai.plan

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.ai.ActionType
import app.local1st.files.ai.PlanAction
import app.local1st.files.ui.ai.views.formatBytes
import app.local1st.files.ui.ai.views.prettyPath

/** 置信度低于这个值的动作算“需要留意”。 */
internal const val PLAN_LOW_CONFIDENCE = 0.6f

/** 一次执行超过这么多项，即使只是移动也再确认一次。 */
internal const val PLAN_CONFIRM_COUNT = 50

/** 已勾选动作的影响汇总：会动哪些文件、移到哪、能否撤销。 */
internal data class PlanImpact(
    val moveCount: Int,
    val moveBytes: Long,
    /** 目标文件夹 → 移进去的数量，按数量从多到少。 */
    val moveTargets: List<Pair<String, Int>>,
    val renameCount: Int,
    val mkdirCount: Int,
    val trashCount: Int,
    val trashBytes: Long,
    val attentionCount: Int,
    val total: Int,
) {
    /** 删除、被标红或低置信度、或数量很多时，执行前要二次确认。 */
    val needsConfirm: Boolean get() = trashCount > 0 || attentionCount > 0 || total >= PLAN_CONFIRM_COUNT
}

internal fun needsAttention(a: PlanAction): Boolean = a.flagged || a.confidence < PLAN_LOW_CONFIDENCE

internal fun planImpactOf(chosen: List<PlanAction>): PlanImpact {
    val moves = chosen.filter { it.type == ActionType.MOVE }
    val trash = chosen.filter { it.type == ActionType.TRASH }
    return PlanImpact(
        moveCount = moves.size,
        moveBytes = moves.sumOf { it.size },
        moveTargets = moves.groupingBy { (it.destDir ?: "").trimEnd('/') }.eachCount()
            .toList().sortedByDescending { it.second },
        renameCount = chosen.count { it.type == ActionType.RENAME },
        mkdirCount = chosen.count { it.type == ActionType.MKDIR },
        trashCount = trash.size,
        trashBytes = trash.sumOf { it.size },
        attentionCount = chosen.count(::needsAttention),
        total = chosen.size,
    )
}

@Composable
private fun ImpactLine(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** 影响清单的几行（卡片和确认框共用）。 */
@Composable
private fun ImpactLines(impact: PlanImpact, maxTargets: Int) {
    if (impact.moveCount > 0) {
        ImpactLine(
            Icons.AutoMirrored.Outlined.DriveFileMove,
            stringResource(R.string.aiflow_impact_move, impact.moveCount, formatBytes(impact.moveBytes)),
        )
        impact.moveTargets.take(maxTargets).forEach { (dir, n) ->
            Text(
                stringResource(R.string.aiflow_impact_move_target, prettyPath(dir), n),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 26.dp),
            )
        }
        val rest = impact.moveTargets.size - maxTargets
        if (rest > 0) {
            Text(
                stringResource(R.string.aiflow_impact_more_targets, rest),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp),
            )
        }
    }
    if (impact.renameCount > 0) {
        ImpactLine(Icons.Outlined.Edit, stringResource(R.string.aiflow_impact_rename, impact.renameCount))
    }
    if (impact.mkdirCount > 0) {
        ImpactLine(Icons.Outlined.CreateNewFolder, stringResource(R.string.aiflow_impact_mkdir, impact.mkdirCount))
    }
    if (impact.trashCount > 0) {
        ImpactLine(
            Icons.Outlined.DeleteOutline,
            stringResource(R.string.aiflow_impact_trash, impact.trashCount, formatBytes(impact.trashBytes)),
            tint = MaterialTheme.colorScheme.error,
        )
    }
    if (impact.attentionCount > 0) {
        ImpactLine(
            Icons.Outlined.WarningAmber,
            stringResource(R.string.aiflow_impact_attention, impact.attentionCount),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

/** 计划确认页顶部的“这次会做什么”卡片。 */
@Composable
internal fun PlanImpactCard(
    impact: PlanImpact,
    totalCount: Int,
    totalBytes: Long,
    blockedCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.aiflow_impact_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.aiflow_impact_selected, impact.total, totalCount, formatBytes(totalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (impact.total == 0) {
                Text(
                    stringResource(R.string.aiflow_impact_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ImpactLines(impact, maxTargets = 3)
            }
            val untouched = totalCount - impact.total
            if (untouched > 0 || blockedCount > 0) {
                Spacer(Modifier.height(4.dp))
                val untouchedText = if (untouched > 0) stringResource(R.string.aiflow_impact_untouched, untouched) else null
                val blockedText = if (blockedCount > 0) stringResource(R.string.aiflow_impact_blocked, blockedCount) else null
                Text(
                    listOfNotNull(untouchedText, blockedText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Undo,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.aiflow_undo_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            stringResource(R.string.aiflow_undo_explain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** 执行前的二次确认：再列一遍会动什么，删除用红色按钮。 */
@Composable
internal fun PlanConfirmDialog(
    impact: PlanImpact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val danger = impact.trashCount > 0 || impact.attentionCount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (danger) ({ Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) }) else null,
        title = { Text(stringResource(R.string.aiflow_confirm_title, impact.total)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ImpactLines(impact, maxTargets = 5)
                Spacer(Modifier.height(10.dp))
                if (impact.trashCount > 0) {
                    Text(
                        stringResource(R.string.aiflow_confirm_trash_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (impact.attentionCount > 0) {
                    Text(
                        stringResource(R.string.aiflow_confirm_attention_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.aiflow_confirm_undo_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (danger) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(
                    stringResource(
                        if (impact.trashCount > 0) R.string.aiflow_confirm_go_trash else R.string.aiflow_confirm_go,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.aiflow_cancel)) } },
    )
}
