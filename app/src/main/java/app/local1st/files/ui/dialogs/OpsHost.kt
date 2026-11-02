package app.local1st.files.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.ops.BackgroundJobs
import app.local1st.files.core.ops.ConflictChoice
import app.local1st.files.core.ops.ConflictResolution
import app.local1st.files.core.ops.OpState
import app.local1st.files.di.Graph
import app.local1st.files.ui.components.TooltipIconButton
import kotlinx.coroutines.delay

/** Floating progress cards + conflict dialogs for running file operations and background jobs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpsHost() {
    val ops by Graph.opEngine.active.collectAsState()
    val jobs by BackgroundJobs.active.collectAsState()

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 96.dp),
    ) {
        DeleteUndoCard()
        jobs.forEach { job ->
            key(job.id) {
                val progress by job.progress.collectAsState()
                val elapsedSeconds by produceState(0L, progress.startedAtRealtimeMillis) {
                    while (true) {
                        value = (android.os.SystemClock.elapsedRealtime() -
                            progress.startedAtRealtimeMillis).coerceAtLeast(0L) / 1_000L
                        delay(1_000L)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(progress.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${progress.message}  ${elapsedSeconds / 60}:${(elapsedSeconds % 60).toString().padStart(2, '0')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TooltipIconButton(
                                stringResource(R.string.cancel_operation),
                                Icons.Outlined.Close,
                                onClick = { job.cancel() },
                            )
                        }
                        if (progress.indeterminate) {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearWavyProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        ops.forEach { op ->
            key(op.id) {
            val progress by op.progress.collectAsState()
            val finished = progress.state == OpState.DONE || progress.state == OpState.CANCELLED
            if (!finished) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(progress.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                progress.currentItem,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TooltipIconButton(stringResource(R.string.cancel_operation), Icons.Outlined.Close, onClick = { op.cancel() })
                    }
                    if (progress.state == OpState.SCANNING) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            val conflict by op.pendingConflict.collectAsState()
            conflict?.let { c ->
                var applyToAll by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(R.string.name_conflict)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.menu_conflict_body, c.existingName))
                            Spacer(Modifier.height(12.dp))
                            ConflictChoiceRow(
                                label = stringResource(R.string.keep_both),
                                hint = stringResource(R.string.menu_conflict_keep_both_sub),
                            ) { op.resolveConflict(ConflictResolution(ConflictChoice.RENAME, applyToAll)) }
                            ConflictChoiceRow(
                                label = stringResource(R.string.skip),
                                hint = stringResource(R.string.menu_conflict_skip_sub),
                            ) { op.resolveConflict(ConflictResolution(ConflictChoice.SKIP, applyToAll)) }
                            ConflictChoiceRow(
                                label = stringResource(R.string.overwrite),
                                hint = stringResource(R.string.menu_conflict_overwrite_sub),
                                danger = true,
                            ) { op.resolveConflict(ConflictResolution(ConflictChoice.OVERWRITE, applyToAll)) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = applyToAll,
                                        role = Role.Checkbox,
                                        onValueChange = { applyToAll = it },
                                    ),
                            ) {
                                Checkbox(checked = applyToAll, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.apply_to_all))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { op.cancel() }) {
                            Text(stringResource(R.string.cancel_operation))
                        }
                    },
                )
            }
            }
            }
        }
    }
}

@Composable
private fun ConflictChoiceRow(
    label: String,
    hint: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = color)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
