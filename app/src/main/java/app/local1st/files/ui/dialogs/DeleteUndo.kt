package app.local1st.files.ui.dialogs

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.TrashFileSystem
import app.local1st.files.core.fs.TrashPaths
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.ops.FileOp
import app.local1st.files.di.Graph
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Undo" for a delete that went to the Recycle Bin. Nothing is faked: after the delete op
 * finishes we look up the bin records that came from exactly those paths, and Undo submits
 * a normal Restore for them. Permanent deletes never arm this.
 */
internal object DeleteUndo {
    private const val ARM_WINDOW_MS = 10 * 60_000L
    const val OFFER_MS = 8_000L

    private class Armed(val paths: List<String>, val since: Long)

    class Offer(val token: Long, val storedIds: Set<String>, val count: Int)

    private val armed = AtomicReference<Armed?>(null)
    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer

    /** Called when the user confirms a delete; [entries] are the ones headed for the bin. */
    fun arm(entries: List<XEntry>) {
        val paths = entries.filter { it.scheme == XId.SCHEME_FILE }.map { it.localPath ?: it.path }
        _offer.value = null
        // Small slack: the bin stamps deletedAt with its own clock read.
        armed.set(if (paths.isEmpty()) null else Armed(paths, System.currentTimeMillis() - 2_000L))
    }

    fun clear(token: Long) {
        if (_offer.value?.token == token) _offer.value = null
    }

    /** Called (off the main thread) after any operation finishes. */
    fun onOperationFinished() {
        val a = armed.get() ?: return
        if (System.currentTimeMillis() - a.since > ARM_WINDOW_MS) {
            armed.compareAndSet(a, null)
            return
        }
        val records = runCatching { Graph.trash.list() }.getOrNull() ?: return
        val matched = records.filter { record ->
            !record.orphan && record.deletedAt >= a.since &&
                a.paths.any { TrashPaths.samePath(originalPath(record.volumeRoot, record.originalRelativePath), it) }
        }
        if (matched.isEmpty()) return
        if (!armed.compareAndSet(a, null)) return
        _offer.value = Offer(
            token = System.nanoTime(),
            storedIds = matched.mapTo(HashSet()) { XId.file(it.storedPath) },
            count = matched.size,
        )
    }

    /** Restores the offered items through the normal Restore op. False when none were found. */
    suspend fun undo(offer: Offer): Boolean = withContext(Dispatchers.IO) {
        val items = runCatching {
            Graph.fsRegistry.forScheme(XId.SCHEME_TRASH).list(TrashFileSystem.rootEntry())
        }.getOrNull() ?: return@withContext false
        val targets = items.filter { it.id in offer.storedIds }
        if (targets.isEmpty()) return@withContext false
        Graph.opEngine.submit(FileOp.Restore(targets))
        true
    }

    private fun originalPath(volumeRoot: String, relative: String): String =
        if (volumeRoot.trimEnd('/').isEmpty()) "/$relative" else volumeRoot.trimEnd('/') + "/" + relative
}

/** Listens for finished ops and shows the Undo card while an offer is open. */
@Composable
internal fun DeleteUndoCard() {
    LaunchedEffect(Unit) {
        Graph.opEngine.events.collect {
            withContext(Dispatchers.IO) { DeleteUndo.onOperationFinished() }
        }
    }
    val offer by DeleteUndo.offer.collectAsState()
    val current = offer ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val failed = stringResource(R.string.menu_undo_failed)
    LaunchedEffect(current.token) {
        delay(DeleteUndo.OFFER_MS)
        DeleteUndo.clear(current.token)
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.menu_undo_moved, current.count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                DeleteUndo.clear(current.token)
                scope.launch {
                    if (!DeleteUndo.undo(current)) {
                        Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text(stringResource(R.string.menu_undo)) }
        }
    }
}
