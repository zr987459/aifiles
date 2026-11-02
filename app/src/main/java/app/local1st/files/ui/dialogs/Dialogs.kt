package app.local1st.files.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.DeleteDisposition
import app.local1st.files.core.fs.addLocationGuideUrl
import app.local1st.files.core.util.Format
import app.local1st.files.core.util.IntentUtils
import app.local1st.files.di.Graph
import app.local1st.files.ui.main.MainViewModel

/**
 * Renders the dialog requested via [MainViewModel.dialog].
 * (Baseline implementation; visual polish iterates here.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDialogs(vm: MainViewModel) {
    val request by vm.dialog.collectAsState()
    val dismiss = { vm.dialog.value = null }

    when (val req = request) {
        null -> Unit

        is DialogRequest.ConfirmDelete -> ConfirmDeleteDialog(
            req = req,
            onDismiss = dismiss,
            onConfirm = {
                // Undo is offered only for what really goes to the bin.
                if (!req.explicitPermanent && req.mode != DeleteDisposition.PERMANENT) {
                    val binned = if (req.mode == DeleteDisposition.MIXED) {
                        req.entries.filter { it.id in req.trashableIds }
                    } else {
                        req.entries
                    }
                    DeleteUndo.arm(binned)
                }
                vm.performDelete(req.entries, req.mode, req.explicitPermanent, req.trashableIds)
            },
        )

        is DialogRequest.ConfirmEmptyTrash -> AlertDialog(
            onDismissRequest = dismiss,
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.empty_recycle_bin)) },
            text = { Text(stringResource(R.string.empty_recycle_bin_confirmation)) },
            confirmButton = {
                Button(
                    onClick = { vm.performEmptyTrash() },
                    colors = dangerButtonColors(),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )

        is DialogRequest.Rename -> NameDialog(
            title = stringResource(R.string.rename),
            initial = req.entry.name,
            confirmLabel = stringResource(R.string.rename),
            onDismiss = dismiss,
            onConfirm = { vm.performRename(req.entry, it) },
        )

        is DialogRequest.NewFolder -> NameDialog(
            title = stringResource(R.string.new_folder),
            initial = "",
            confirmLabel = stringResource(R.string.create),
            onDismiss = dismiss,
            onConfirm = { vm.performNewFolder(req.parent, it) },
        )

        is DialogRequest.NewTextFile -> NameDialog(
            title = stringResource(R.string.new_text_file),
            initial = stringResource(R.string.default_text_file_name),
            confirmLabel = stringResource(R.string.create),
            selectStem = true,
            onDismiss = dismiss,
            onConfirm = { vm.performNewTextFile(req.parent, it) },
        )

        is DialogRequest.CompressTo -> NameDialog(
            title = stringResource(R.string.create_zip_in, req.destDir.name),
            initial = (req.sources.firstOrNull()?.name?.substringBeforeLast('.') ?: "archive") + ".zip",
            confirmLabel = stringResource(R.string.compress),
            onDismiss = dismiss,
            onConfirm = { vm.performCompress(req.sources, req.destDir, it) },
        )

        is DialogRequest.Details -> {
            val detailRoots = Graph.roots.mountedVolumes.collectAsState().value.map { it.path }
            val detailBin = stringResource(R.string.recycle_bin)
            val detailPath = req.entry.localPath ?: req.entry.id.substringAfter("://")
            val detailLocation = detailLocationOf(req.entry, detailPath, detailRoots, detailBin)
            AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(req.entry.name) },
            text = {
                Column {
                    Text(stringResource(R.string.location, detailLocation))
                    if (!req.entry.isDir) Text(stringResource(R.string.size, Format.bytes(req.entry.size)))
                    Text(stringResource(R.string.modified, Format.dateTime(req.entry.mtime)))
                    req.entry.mime?.let { Text(stringResource(R.string.file_type, it)) }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } },
            )
        }

        is DialogRequest.EntryMenu -> ModalBottomSheet(onDismissRequest = dismiss) {
            EntryMenuContent(vm, req, dismiss)
        }

        is DialogRequest.LocationGuide -> LocationGuideDialog(
            onContinue = { vm.finishLocationGuide(continueToPicker = true, dontRemind = it) },
            onDismiss = { vm.finishLocationGuide(continueToPicker = false, dontRemind = it) },
        )
    }
}

@Composable
fun LocationGuideDialog(
    onContinue: (dontRemind: Boolean) -> Unit,
    onDismiss: (dontRemind: Boolean) -> Unit,
) {
    var dontRemind by remember { mutableStateOf(true) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { onDismiss(dontRemind) },
        title = { Text(stringResource(R.string.location_guide_title)) },
        text = {
            Column {
                Text(stringResource(R.string.location_guide_body))
                TextButton(
                    onClick = { IntentUtils.openUrl(context, addLocationGuideUrl(context)) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.location_guide_open))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth()
                        .toggleable(
                            value = dontRemind,
                            role = Role.Checkbox,
                            onValueChange = { dontRemind = it },
                        ),
                ) {
                    Checkbox(
                        checked = dontRemind,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.location_guide_dont_remind))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onContinue(dontRemind) }) {
                Text(stringResource(R.string.location_guide_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(dontRemind) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    selectStem: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial, selectStem) {
        val cursor = if (selectStem) initial.substringBeforeLast('.', initial).length else initial.length
        mutableStateOf(
            TextFieldValue(
                text = initial,
                selection = if (selectStem) TextRange(0, cursor) else TextRange(cursor),
            ),
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(selectStem) {
        if (selectStem) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    val trimmedName = value.text.trim()
    // Say why the button is disabled instead of silently greying it out.
    val nameError = when {
        trimmedName.contains('/') || trimmedName.contains('\\') ->
            stringResource(R.string.menu_name_invalid_slash)
        trimmedName == "." || trimmedName == ".." -> stringResource(R.string.menu_name_invalid_dots)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = nameError != null,
                supportingText = if (nameError != null) {
                    { Text(nameError) }
                } else {
                    null
                },
                modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = value.text.isNotBlank() && nameError == null,
                onClick = { onConfirm(value.text.trim()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
