package app.local1st.files.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.AppSettingsAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.MountedVolume
import app.local1st.files.core.fs.TrashPaths
import app.local1st.files.core.fs.TrashVolume
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.ops.canMoveSource
import app.local1st.files.core.util.AppComponents
import app.local1st.files.core.util.ComponentType
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.Format
import app.local1st.files.core.util.IntentUtils
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.EntryIcon
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.main.isFileOperationDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Long-press / overflow action sheet. Actions are grouped (frequent first, destructive last)
 * and every row carries an icon. A row that cannot run stays visible, greyed out, with the
 * reason written underneath instead of hidden in a tooltip.
 */
@Composable
internal fun EntryMenuContent(
    vm: MainViewModel,
    req: DialogRequest.EntryMenu,
    dismiss: () -> Unit,
) {
    val entry = req.entry
    val context = Graph.appContext
    val clipboard = LocalClipboardManager.current
    val mountedNow = Graph.roots.mountedVolumes.collectAsState().value
    val volumeRoots = mountedNow.map { it.path }
    val trashVolumes = mountedNow.map { TrashVolume(it.path, it.label, it.writable) }
    val binTitle = stringResource(R.string.recycle_bin)

    val otherPane = vm.otherPaneDestination()
    val canUseOtherPane = isFileOperationDestination(otherPane, volumeRoots)
    val otherPaneTarget = otherPane?.let {
        stringResource(R.string.menu_target_other_pane, friendlyLocation(it, mountedNow, binTitle))
    }
    val otherPaneReason = if (otherPane == null) {
        stringResource(R.string.menu_reason_no_other_pane)
    } else {
        stringResource(R.string.menu_reason_other_pane_readonly, otherPane.name)
    }
    val otherPaneSub = if (canUseOtherPane) otherPaneTarget else null

    val groups = mutableListOf<List<MenuAction>>()

    if (entry?.kind == EntryKind.APP_COMPONENT) {
        val parsed = AppComponents.parseId(entry.id)
        // Non-null = the component's current enabled state, and we can actually flip it
        // (own package, or working root). Resolved off the main thread: the root probe
        // and PackageManager lookups both block.
        val toggleEnabled by produceState<Boolean?>(null, entry.id) {
            value = withContext(Dispatchers.IO) {
                parsed?.takeIf { AppComponents.canToggle(context, it.packageName) }
                    ?.let { AppComponents.isEnabled(context, it) }
            }
        }
        val main = mutableListOf<MenuAction>()
        if (parsed?.type == ComponentType.ACTIVITY) {
            main += MenuAction(Icons.AutoMirrored.Outlined.Launch, stringResource(R.string.launch)) {
                vm.launchComponent(entry); dismiss()
            }
            main += MenuAction(Icons.AutoMirrored.Outlined.Shortcut, stringResource(R.string.create_shortcut)) {
                vm.createComponentShortcut(entry); dismiss()
            }
        }
        toggleEnabled?.let { enabled ->
            main += MenuAction(
                if (enabled) Icons.Outlined.ToggleOff else Icons.Outlined.ToggleOn,
                stringResource(if (enabled) R.string.disable else R.string.enable),
            ) {
                vm.setComponentEnabled(entry, !enabled)
                dismiss()
            }
        }
        groups += main
        val more = mutableListOf<MenuAction>()
        more += MenuAction(Icons.Outlined.ContentPaste, stringResource(R.string.copy_class_name)) {
            clipboard.setText(AnnotatedString(parsed?.className ?: entry.name))
            dismiss()
        }
        parsed?.let { p ->
            more += MenuAction(Icons.Outlined.Info, stringResource(R.string.app_details)) {
                vm.showAppDetails(p.packageName); dismiss()
            }
        }
        groups += more
    } else if (entry?.kind == EntryKind.APP) {
        val main = mutableListOf<MenuAction>()
        main += MenuAction(Icons.AutoMirrored.Outlined.Launch, stringResource(R.string.launch)) {
            IntentUtils.launchApp(context, entry.path); dismiss()
        }
        main += MenuAction(Icons.Outlined.FolderOpen, stringResource(R.string.open_as_zip)) {
            vm.openAppAsZip(entry); dismiss()
        }
        if (entry.localPath != null) {
            main += MenuAction(
                Icons.Outlined.ContentCopy,
                stringResource(R.string.menu_copy_to_other_pane),
                supporting = otherPaneSub,
                enabled = canUseOtherPane,
                disabledReason = otherPaneReason,
            ) {
                vm.copySelection(move = false, sources = listOf(entry))
                dismiss()
            }
        }
        groups += main
        groups += listOf(
            MenuAction(Icons.Outlined.Info, stringResource(R.string.details)) {
                vm.showAppDetails(entry.path); dismiss()
            },
            MenuAction(Icons.Outlined.AppSettingsAlt, stringResource(R.string.system_info)) {
                IntentUtils.appInfo(context, entry.path); dismiss()
            },
        )
        groups += listOf(
            MenuAction(Icons.Outlined.Delete, stringResource(R.string.uninstall), danger = true) {
                IntentUtils.uninstall(context, entry.path); dismiss()
            },
        )
    } else if (entry?.kind == EntryKind.RECYCLE_BIN) {
        groups += listOf(
            MenuAction(
                Icons.Outlined.DeleteForever,
                stringResource(R.string.empty_recycle_bin),
                supporting = stringResource(R.string.menu_empty_bin_sub),
                danger = true,
            ) { vm.requestEmptyTrash() },
        )
    } else if (entry != null) {
        val entryPath = entry.localPath ?: entry.path
        val binSymlink = entry.scheme == XId.SCHEME_FILE &&
            (TrashPaths.isVolumeBinSymlink(entryPath, volumeRoots) ||
                TrashPaths.crossesVolumeBin(entryPath, volumeRoots))
        val topLevelTrash = entry.scheme == XId.SCHEME_FILE &&
            TrashPaths.isRestorableBinItem(entryPath, volumeRoots)
        val underTrash = entry.scheme == XId.SCHEME_FILE &&
            TrashPaths.isInsideVolumeBin(entryPath, volumeRoots)
        val fileLike = entry.kind == EntryKind.DIR || entry.kind == EntryKind.FILE ||
            entry.kind == EntryKind.ARCHIVE
        val movable = canMoveSource(entry)
        // Read-only files/folders keep their edit rows, greyed out with the reason.
        // Pane roots (volumes, locations, `/`) never offer them: deleting a root would
        // empty the whole storage.
        val showEditRows = movable || (fileLike && !entry.canWrite)
        val readOnlyReason = stringResource(R.string.menu_reason_read_only)

        // ---- 1. Frequent: open / share / restore
        val open = mutableListOf<MenuAction>()
        if (topLevelTrash) {
            open += MenuAction(
                Icons.Outlined.RestoreFromTrash,
                stringResource(R.string.restore),
                supporting = stringResource(R.string.menu_restore_sub),
            ) {
                vm.restore(listOf(entry))
                dismiss()
            }
        }
        if (!entry.isDir) {
            val hasLocalFile = entry.localPath != null
            // FileProvider follows a bin symlink and would hand the live target to another app.
            val canHandoff = !binSymlink && (hasLocalFile || entry.scheme == XId.SCHEME_SAF)
            val handoffReason = if (binSymlink) {
                stringResource(R.string.menu_reason_restore_first)
            } else {
                stringResource(R.string.menu_reason_not_local)
            }
            open += MenuAction(
                Icons.AutoMirrored.Outlined.OpenInNew,
                stringResource(R.string.open_with),
                enabled = canHandoff,
                disabledReason = handoffReason,
            ) { vm.openWith(entry); dismiss() }
            open += MenuAction(
                Icons.Outlined.Share,
                stringResource(R.string.share),
                enabled = canHandoff,
                disabledReason = handoffReason,
            ) { vm.shareSelection(listOf(entry)); dismiss() }
        }
        groups += open

        // ---- 2. Organize: copy / move / rename, with the destination spelled out
        val organize = mutableListOf<MenuAction>()
        if (!underTrash) {
            organize += MenuAction(
                Icons.Outlined.ContentCopy,
                stringResource(R.string.menu_copy_to_other_pane),
                supporting = otherPaneSub,
                enabled = canUseOtherPane,
                disabledReason = otherPaneReason,
            ) {
                vm.copySelection(move = false, sources = listOf(entry))
                dismiss()
            }
            if (showEditRows) {
                organize += MenuAction(
                    Icons.AutoMirrored.Outlined.DriveFileMove,
                    stringResource(R.string.menu_move_to_other_pane),
                    supporting = otherPaneSub,
                    enabled = movable && canUseOtherPane,
                    disabledReason = if (!movable) readOnlyReason else otherPaneReason,
                ) {
                    vm.copySelection(move = true, sources = listOf(entry))
                    dismiss()
                }
            }
        }
        // Explicit-location workflow: always available, opens the folder chooser directly.
        organize += MenuAction(
            Icons.Outlined.FolderOpen,
            stringResource(R.string.copy_to),
            supporting = stringResource(R.string.menu_pick_any_folder),
        ) {
            vm.chooseTransferDestination(move = false, sources = listOf(entry))
            dismiss()
        }
        if (showEditRows) {
            organize += MenuAction(
                Icons.AutoMirrored.Outlined.DriveFileMove,
                stringResource(R.string.move_to),
                supporting = stringResource(R.string.menu_pick_any_folder),
                enabled = movable,
                disabledReason = readOnlyReason,
            ) {
                vm.chooseTransferDestination(move = true, sources = listOf(entry))
                dismiss()
            }
            if (!topLevelTrash) {
                // Replaces the sheet request with the naming dialog; do not dismiss after.
                organize += MenuAction(
                    Icons.Outlined.DriveFileRenameOutline,
                    stringResource(R.string.rename),
                    enabled = movable,
                    disabledReason = readOnlyReason,
                ) { vm.requestRename(entry) }
            }
        }
        groups += organize

        // ---- 3. Tools: zip / extract / install / open as / create
        val tools = mutableListOf<MenuAction>()
        if (!underTrash) {
            tools += MenuAction(
                Icons.Outlined.FolderZip,
                stringResource(R.string.menu_zip_to_other_pane),
                supporting = otherPaneSub,
                enabled = canUseOtherPane,
                disabledReason = otherPaneReason,
            ) { vm.requestCompress(listOf(entry)) }
        }
        if (entry.kind == EntryKind.ARCHIVE) {
            if (!underTrash) {
                tools += MenuAction(
                    Icons.Outlined.Unarchive,
                    stringResource(R.string.menu_extract_to_other_pane),
                    supporting = otherPaneSub,
                    enabled = canUseOtherPane,
                    disabledReason = otherPaneReason,
                ) {
                    vm.extractArchive(entry)
                    dismiss()
                }
            }
            if (FileTypes.isInstallable(entry.extension)) {
                tools += MenuAction(
                    Icons.Outlined.InstallMobile,
                    stringResource(R.string.install),
                    enabled = entry.localPath != null && !binSymlink,
                    disabledReason = if (binSymlink) {
                        stringResource(R.string.menu_reason_restore_first)
                    } else {
                        stringResource(R.string.menu_reason_install_not_local)
                    },
                ) { vm.installPackage(entry); dismiss() }
            }
        }
        if (!entry.isDir && entry.localPath != null && !binSymlink) {
            tools += MenuAction(Icons.AutoMirrored.Outlined.TextSnippet, stringResource(R.string.open_as_text)) {
                vm.openAsText(entry); dismiss()
            }
            tools += MenuAction(Icons.Outlined.DataObject, stringResource(R.string.open_as_hex)) {
                vm.openAsHex(entry); dismiss()
            }
        }
        if (entry.isDir && vm.canCreateFileIn(entry)) {
            // Both replace the sheet request with a naming dialog.
            tools += MenuAction(Icons.Outlined.CreateNewFolder, stringResource(R.string.new_folder)) {
                vm.dialog.value = DialogRequest.NewFolder(entry)
            }
            tools += MenuAction(Icons.AutoMirrored.Outlined.NoteAdd, stringResource(R.string.new_text_file)) {
                vm.requestNewTextFile(entry)
            }
        }
        groups += tools

        // ---- 4. Info: favorites, details, remove location
        val info = mutableListOf<MenuAction>()
        // Pin files/folders/archives as top-level shortcuts. Anything already at the top
        // level (volumes, App manager, Root) is excluded: pinning it again is a no-op.
        // The Graph cache is warm from startup (null only before the first DataStore read).
        val favorites by Graph.favorites.collectAsState()
        if (fileLike && !underTrash && (entry.pinned || !vm.activeCtrl.isTopLevelRoot(entry.id))) {
            favorites?.let { favs ->
                val pinned = favs.any { it.id == entry.id }
                info += MenuAction(
                    if (pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    stringResource(if (pinned) R.string.remove_from_favorites else R.string.add_to_favorites),
                ) {
                    vm.toggleFavorite(entry)
                    dismiss()
                }
            }
        }
        info += MenuAction(Icons.Outlined.Info, stringResource(R.string.details)) {
            vm.dialog.value = DialogRequest.Details(entry)
        }
        if (entry.kind == EntryKind.LOCATION && vm.activeCtrl.isTopLevelRoot(entry.id)) {
            info += MenuAction(Icons.Outlined.LinkOff, stringResource(R.string.remove_location)) {
                vm.removeLocation(entry)
                dismiss()
            }
        }
        groups += info

        // ---- 5. Destructive, always last and in red
        if (showEditRows) {
            val danger = mutableListOf<MenuAction>()
            val canTrash = !underTrash && Graph.trash.canTrash(entry, trashVolumes)
            danger += MenuAction(
                Icons.Outlined.Delete,
                stringResource(R.string.delete),
                supporting = stringResource(
                    when {
                        underTrash -> R.string.menu_delete_sub_in_bin
                        canTrash -> R.string.menu_delete_sub_bin
                        else -> R.string.menu_delete_sub_no_bin
                    },
                ),
                enabled = movable,
                disabledReason = readOnlyReason,
                danger = true,
            ) { vm.requestDelete(listOf(entry)) }
            if (movable && canTrash) {
                danger += MenuAction(
                    Icons.Outlined.DeleteForever,
                    stringResource(R.string.delete_permanently),
                    supporting = stringResource(R.string.menu_delete_forever_sub),
                    danger = true,
                ) { vm.requestDelete(listOf(entry), permanent = true) }
            }
            groups += danger
        }
    }

    if (req.showSettings) {
        groups += listOf(
            MenuAction(Icons.Outlined.Settings, stringResource(R.string.settings)) {
                vm.openSettings()
                dismiss()
            },
        )
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        if (entry != null) MenuHeader(entry, mountedNow, binTitle)
        var first = entry == null
        groups.filter { it.isNotEmpty() }.forEach { group ->
            if (!first) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            first = false
            group.forEach { MenuRow(it) }
        }
    }
}

internal class MenuAction(
    val icon: ImageVector,
    val label: String,
    val supporting: String? = null,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun MenuHeader(entry: XEntry, volumes: List<MountedVolume>, binTitle: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(entry, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (entry.kind == EntryKind.APP_COMPONENT) {
                AppComponents.parseId(entry.id)?.className
            } else {
                headerSubtitle(entry, volumes, binTitle)
            }
            if (!sub.isNullOrBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall.let {
                        if (entry.kind == EntryKind.APP_COMPONENT) it.copy(fontFamily = FontFamily.Monospace) else it
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun headerSubtitle(entry: XEntry, volumes: List<MountedVolume>, binTitle: String): String? {
    if (entry.kind == EntryKind.APP || entry.kind == EntryKind.RECYCLE_BIN) return null
    if (entry.kind == EntryKind.FILE || entry.kind == EntryKind.ARCHIVE) {
        val parts = mutableListOf<String>()
        if (entry.size >= 0) parts += Format.bytes(entry.size)
        if (entry.mtime > 0) parts += Format.dateTime(entry.mtime)
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
    }
    return friendlyLocation(entry, volumes, binTitle)
}

/**
 * A folder location people can read: "Internal storage/Download" instead of
 * "/storage/emulated/0/Download". Bin paths never show `.xfiles-trash/files/<id>`.
 */
internal fun friendlyLocation(dir: XEntry, volumes: List<MountedVolume>, binTitle: String): String {
    return when (dir.scheme) {
        XId.SCHEME_FILE -> {
            val path = dir.localPath ?: dir.path
            visibleBinLabel(path, volumes.map { it.path }, binTitle)?.let { return it }
            val volume = volumes
                .filter { TrashPaths.isInside(it.path, path) }
                .maxByOrNull { it.path.length }
                ?: return path
            val rel = TrashPaths.relativeTo(volume.path, path)
            if (rel.isEmpty()) volume.label else "${volume.label}/$rel"
        }
        XId.SCHEME_ROOT -> "root:" + dir.path
        XId.SCHEME_TRASH -> binTitle
        else -> dir.name
    }
}

@Composable
private fun MenuRow(action: MenuAction) {
    val scheme = MaterialTheme.colorScheme
    val base = if (action.danger) scheme.error else scheme.onSurface
    val iconTint = if (action.danger) scheme.error else scheme.onSurfaceVariant
    val alpha = if (action.enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            action.icon,
            contentDescription = null,
            tint = iconTint.copy(alpha = iconTint.alpha * alpha),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = base.copy(alpha = base.alpha * alpha),
            )
            // A disabled row always says why, in readable (not faded) text.
            val second = if (!action.enabled) action.disabledReason else action.supporting
            if (!second.isNullOrBlank()) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (action.danger && action.enabled) scheme.error.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
