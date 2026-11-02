package app.local1st.files.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.DeleteDisposition
import app.local1st.files.ui.browser.EntryIcon

private const val SHOWN_NAMES = 5

/**
 * Delete confirmation that says plainly what will happen: bin (restorable, with Undo right
 * after), permanent (red, cannot be recovered), or a mix of both with the two counts.
 */
@Composable
internal fun ConfirmDeleteDialog(
    req: DialogRequest.ConfirmDelete,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val total = req.entries.size
    val binCount = when (req.mode) {
        DeleteDisposition.TRASH -> total
        DeleteDisposition.PERMANENT -> 0
        DeleteDisposition.MIXED -> req.entries.count { it.id in req.trashableIds }
    }
    val permanent = req.mode == DeleteDisposition.PERMANENT
    val title = when (req.mode) {
        DeleteDisposition.TRASH -> stringResource(R.string.menu_delete_title_trash)
        DeleteDisposition.PERMANENT -> stringResource(R.string.menu_delete_title_forever)
        DeleteDisposition.MIXED -> stringResource(R.string.menu_delete_title_mixed, total)
    }
    val body = when (req.mode) {
        DeleteDisposition.TRASH -> stringResource(R.string.menu_delete_body_trash)
        DeleteDisposition.PERMANENT ->
            if (req.explicitPermanent) {
                stringResource(R.string.menu_delete_body_forever)
            } else {
                stringResource(R.string.menu_delete_body_no_bin)
            }
        DeleteDisposition.MIXED ->
            stringResource(R.string.menu_delete_body_mixed, binCount, total - binCount)
    }
    val confirmLabel = when (req.mode) {
        DeleteDisposition.TRASH -> stringResource(R.string.menu_delete_confirm_trash)
        DeleteDisposition.PERMANENT -> stringResource(R.string.delete_permanently)
        DeleteDisposition.MIXED -> stringResource(R.string.delete)
    }
    val dangerous = req.mode != DeleteDisposition.TRASH

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (permanent) Icons.Outlined.DeleteForever else Icons.Outlined.Delete,
                contentDescription = null,
                tint = if (dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(title) },
        text = {
            Column {
                req.entries.take(SHOWN_NAMES).forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        EntryIcon(
                            entry,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (total > SHOWN_NAMES) {
                    Text(
                        stringResource(R.string.menu_delete_more, total - SHOWN_NAMES),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (dangerous) FontWeight.Medium else null,
                    color = if (dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (dangerous) dangerButtonColors() else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Filled button in the error colour, for actions that cannot be undone. */
@Composable
internal fun dangerButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError,
)
