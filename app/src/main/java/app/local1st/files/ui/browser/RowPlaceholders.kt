package app.local1st.files.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R

/** What an open folder with no visible child rows is doing. */
internal enum class FolderPlaceholder { LOADING, EMPTY, ERROR }

/** One LazyColumn item of a pane: an entry row, or a hint row under an open folder. */
@Immutable
internal sealed interface PaneListItem {
    val key: String

    data class Entry(val index: Int, override val key: String) : PaneListItem

    data class Hint(
        val parent: TreeNode,
        val kind: FolderPlaceholder,
    ) : PaneListItem {
        override val key: String get() = "${parent.key}#hint"
    }
}

/**
 * The pane's rows plus a hint under every open folder that shows nothing below it, so an
 * empty folder, a slow listing and a failed one each read differently instead of all looking
 * like "nothing happened".
 */
internal fun paneListItems(nodes: List<TreeNode>, withHints: Boolean): List<PaneListItem> {
    val out = ArrayList<PaneListItem>(nodes.size + 4)
    nodes.forEachIndexed { index, node ->
        out += PaneListItem.Entry(index, node.key)
        if (!withHints) return@forEachIndexed
        val kind = placeholderFor(node, nodes.getOrNull(index + 1)) ?: return@forEachIndexed
        out += PaneListItem.Hint(node, kind)
    }
    return out
}

private fun placeholderFor(node: TreeNode, next: TreeNode?): FolderPlaceholder? {
    if (!node.entry.isContainer || !node.expanded) return null
    if (next != null && next.depth > node.depth) return null
    return when {
        node.error != null -> FolderPlaceholder.ERROR
        node.loading -> FolderPlaceholder.LOADING
        // Only a listed (or hinted) count of zero: an unknown count (-1) shows nothing.
        node.entry.childCountHint == 0 -> FolderPlaceholder.EMPTY
        else -> null
    }
}

/** Hint row indented one level under its folder, aligned with where a child's name would start. */
@Composable
internal fun FolderPlaceholderRow(
    parent: TreeNode,
    kind: FolderPlaceholder,
    onRetry: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
    ) {
        // Parent depth + 1 indent columns, then the chevron slot child rows keep.
        Spacer(Modifier.width(IndentWidth * (parent.depth + 2)))
        when (kind) {
            FolderPlaceholder.LOADING -> {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.rows_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FolderPlaceholder.EMPTY -> Text(
                stringResource(R.string.rows_empty_folder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FolderPlaceholder.ERROR -> {
                // The folder row above already shows the reason; this row offers the way out.
                Text(
                    stringResource(R.string.rows_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(vertical = 4.dp),
                )
                TextButton(onClick = onRetry, enabled = enabled) {
                    Text(stringResource(R.string.rows_retry))
                }
            }
        }
    }
}

/** LazyColumn position of the entry row for node [nodeIndex]; hints before it push it down. */
internal fun listPositionOfNode(items: List<PaneListItem>, nodeIndex: Int): Int {
    if (nodeIndex <= 0) return nodeIndex.coerceAtLeast(0)
    val position = items.indexOfFirst { it is PaneListItem.Entry && it.index == nodeIndex }
    return if (position >= 0) position else nodeIndex
}
