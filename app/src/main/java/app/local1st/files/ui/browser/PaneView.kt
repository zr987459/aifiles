package app.local1st.files.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.ui.statusBarsStable
import kotlinx.coroutines.flow.first

/**
 * Shared height of a breadcrumb pill and the compact screen's other-pane target chip,
 * so a single header row can keep them on one mid-line.
 */
val CrumbBarHeight = 40.dp

/**
 * One browser pane: breadcrumb bar + flattened tree list.
 * The whole X-plore signature view.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun PaneView(
    controller: PaneController,
    active: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (XEntry) -> Unit,
    onEntryMenu: (XEntry) -> Unit,
    onAddLocation: () -> Unit,
    onInitialLayoutReady: (treeVersion: Long) -> Unit,
    breadcrumbAlignment: Alignment = Alignment.TopStart,
    headerStartPadding: Dp = 6.dp,
    headerEndPadding: Dp = 6.dp,
    headerOverlay: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val initialScrollIndex = state.initialScrollIndex
    if (initialScrollIndex == null) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = if (active) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
                PaneHeader(
                    focusedDirId = state.focusedDirId,
                    crumbNames = emptyMap(),
                    active = active,
                    breadcrumbAlignment = breadcrumbAlignment,
                    headerStartPadding = headerStartPadding,
                    headerEndPadding = headerEndPadding,
                    headerOverlay = headerOverlay,
                    onCrumbClick = { id ->
                        onActivate()
                        controller.revealPath(id)
                    },
                )
            }
        }
        return
    }

    // This state is first created only after PaneController has published the fully restored tree.
    // LazyColumn therefore lays out the restored row on its first frame; there is no row-0 frame
    // followed by a corrective scroll (animated or otherwise).
    val selectionMode = state.selection.isNotEmpty()
    // Entry rows plus the empty / loading / failed hints under open folders. Hints shift list
    // positions, so node indexes are mapped through this list before scrolling.
    val nodesForList = state.nodes
    val withHints = !state.snapshotOnly
    val listItems = remember(nodesForList, withHints) {
        paneListItems(nodesForList, withHints)
    }
    val currentListItems by rememberUpdatedState(listItems)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = listPositionOfNode(listItems, initialScrollIndex),
    )
    val currentOnInitialLayoutReady by rememberUpdatedState(onInitialLayoutReady)
    // Covered destinations leave composition after the transition. Save these booleans with the
    // browser entry so returning from Settings/Search does not replay the lightweight-row phase.
    var richRowsEnabled by rememberSaveable(controller) { mutableStateOf(false) }
    var itemAnimationsEnabled by rememberSaveable(controller) { mutableStateOf(false) }

    // A measured lightweight list is already a valid first frame. Remove the startup cover now;
    // thumbnail painters and animation nodes are enabled only after that frame is safely visible.
    LaunchedEffect(controller, listState, state.treeVersion) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        currentOnInitialLayoutReady(state.treeVersion)
        if (!richRowsEnabled) {
            withFrameNanos { }
            withFrameNanos { }
            richRowsEnabled = true
        }
    }

    // A background restore may insert saved off-path branches. Let stable item keys preserve the
    // scroll anchor, and enable placement animation only on a later frame after reconciliation.
    LaunchedEffect(state.startupSettled, richRowsEnabled) {
        itemAnimationsEnabled = false
        if (state.startupSettled && richRowsEnabled) {
            withFrameNanos { }
            itemAnimationsEnabled = true
        }
    }

    LaunchedEffect(controller, listState) {
        controller.scrollTo.collect { request ->
            val nodeIndex = controller.state.value.nodes.indexOfFirst { it.entry.id == request.id }
            if (nodeIndex < 0) return@collect
            // Prefer the row as currently laid out; fall back to mapping the newest node index.
            val items = currentListItems
            val suffix = "|${request.id}"
            val laidOut = items.indexOfFirst { it is PaneListItem.Entry && it.key.endsWith(suffix) }
            val index = if (laidOut >= 0) laidOut else listPositionOfNode(items, nodeIndex)
            if (request.animate) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (active) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Rows scroll edge-to-edge under the status bar and the floating breadcrumb;
            // the top inset only keeps row 0 initially clear of both.
            // IgnoringVisibility so a hidden bar in the viewer does not reflow this list;
            // union with live insets because pre-R IgnoringVisibility can stick at 0 after return.
            val statusPad = WindowInsets.statusBarsStable
                .asPaddingValues().calculateTopPadding()

            if (state.loadingRoots && state.nodes.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingIndicator()
                    Text(
                        stringResource(R.string.rows_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = statusPad + 8.dp + CrumbBarHeight,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = listItems.size,
                        key = { listItems[it].key },
                    ) { position ->
                        val rowModifier = Modifier
                            .padding(horizontal = 4.dp)
                            .then(
                                if (itemAnimationsEnabled) Modifier.animateItem()
                                else Modifier,
                            )
                        when (val item = listItems[position]) {
                            is PaneListItem.Hint -> FolderPlaceholderRow(
                                parent = item.parent,
                                kind = item.kind,
                                enabled = !state.snapshotOnly,
                                onRetry = {
                                    onActivate()
                                    controller.refresh(item.parent.entry.id)
                                },
                                modifier = rowModifier,
                            )
                            is PaneListItem.Entry -> {
                                val node = state.nodes[item.index]
                                val entry = node.entry
                                val selectable = isSelectableEntry(entry)
                                val selected = entry.id in state.selection
                                EntryRow(
                                    node = node,
                                    selected = selected,
                                    focused = entry.id == state.focusedDirId,
                                    // While anything is ticked, a tap ticks; otherwise it opens.
                                    onClick = {
                                        onActivate()
                                        if (selectionMode && selectable) {
                                            controller.toggleSelect(entry)
                                        } else {
                                            onOpenEntry(entry)
                                        }
                                    },
                                    // Long press starts (or extends) a multi-selection with this
                                    // row ticked. Scaffolding rows (volumes, locations) have no
                                    // selection, so they keep opening their menu.
                                    onLongClick = {
                                        onActivate()
                                        if (selectable) {
                                            if (!selected) controller.toggleSelect(entry)
                                        } else {
                                            onEntryMenu(entry)
                                        }
                                    },
                                    onToggleSelect = {
                                        onActivate()
                                        controller.toggleSelect(entry)
                                    },
                                    enabled = !state.snapshotOnly,
                                    richContent = richRowsEnabled,
                                    selectionMode = selectionMode,
                                    onMenu = {
                                        onActivate()
                                        onEntryMenu(entry)
                                    },
                                    onLeadingClick = if (selectionMode && entry.isContainer) {
                                        {
                                            onActivate()
                                            onOpenEntry(entry)
                                        }
                                    } else {
                                        null
                                    },
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                    item(key = "action:add-location") {
                        AddLocationRow(
                            enabled = !state.snapshotOnly,
                            onClick = {
                                onActivate()
                                onAddLocation()
                            },
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .then(
                                    if (itemAnimationsEnabled) Modifier.animateItem()
                                    else Modifier,
                                ),
                        )
                    }
                }
            }

            // Only the focused path's names: expanding or collapsing a folder then leaves the
            // header's input equal, so the breadcrumb skips instead of recomposing.
            val focusedDirId = state.focusedDirId
            val nodes = state.nodes
            val crumbNames = remember(focusedDirId, nodes) {
                val path = generateSequence(focusedDirId) { XId.parent(it) }.toHashSet()
                nodes.filter { it.entry.id in path }.associate { it.entry.id to it.entry.name }
            }
            PaneHeader(
                focusedDirId = state.focusedDirId,
                crumbNames = crumbNames,
                active = active,
                breadcrumbAlignment = breadcrumbAlignment,
                headerStartPadding = headerStartPadding,
                headerEndPadding = headerEndPadding,
                headerOverlay = headerOverlay,
                onCrumbClick = { id ->
                    onActivate()
                    controller.revealPath(id)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.PaneHeader(
    focusedDirId: String?,
    crumbNames: Map<String, String>,
    active: Boolean,
    breadcrumbAlignment: Alignment,
    headerStartPadding: Dp,
    headerEndPadding: Dp,
    headerOverlay: (@Composable () -> Unit)?,
    onCrumbClick: (String) -> Unit,
) {
    // One row so the compact target chip only takes the width it needs. Overlaying two
    // independently aligned pills forced a worst-case hole on the opposite side.
    val chipOnStart = headerOverlay != null && breadcrumbAlignment == Alignment.TopEnd
    val chipOnEnd = headerOverlay != null && !chipOnStart
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsStable)
            .padding(
                start = headerStartPadding,
                top = 4.dp,
                end = headerEndPadding,
            ),
    ) {
        if (chipOnStart) {
            headerOverlay?.invoke()
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = if (chipOnStart) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            BreadcrumbBar(
                focusedDirId = focusedDirId,
                crumbNames = crumbNames,
                active = active,
                onCrumbClick = onCrumbClick,
                modifier = Modifier.wrapContentWidth(
                    align = if (chipOnStart) Alignment.End else Alignment.Start,
                    unbounded = false,
                ),
            )
        }
        if (chipOnEnd) {
            Spacer(Modifier.width(8.dp))
            headerOverlay?.invoke()
        }
    }
}

/**
 * Floating breadcrumb pill; the list scrolls underneath it.
 *
 * It shares a row (and [CrumbBarHeight] mid-line) with the compact target chip. A floor
 * rather than a fixed height: at large font scales the pill grows instead of clipping
 * the trail.
 */
@Composable
private fun BreadcrumbBar(
    focusedDirId: String?,
    crumbNames: Map<String, String>,
    active: Boolean,
    onCrumbClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val crumbs = crumbsFor(focusedDirId, crumbNames)
    Surface(
        shape = RoundedCornerShape(CrumbBarHeight / 2),
        // On wide screens both panes are visible: the inactive pane's breadcrumb is the target.
        color = if (active) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
        },
        modifier = modifier.defaultMinSize(
            minWidth = CrumbBarHeight,
            minHeight = CrumbBarHeight,
        ),
    ) {
        // No horizontal scrolling here: on phones the panes sit in a HorizontalPager, and a
        // scrollable trail fought the page swipe. Long trails collapse their middle into "…".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            if (crumbs.isEmpty()) {
                Text(
                    "XFiles",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Padded exactly like a crumb: the fallback and a real trail have to
                    // measure alike at every font scale, or the pill would resize (and jolt
                    // the list under it) every time the tree collapses back to the root.
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                )
            }
            val collapsed = crumbs.size > MAX_VISIBLE_CRUMBS
            // First crumb, "…" for the middle, then the last two.
            val hidden = if (collapsed) crumbs.subList(1, crumbs.size - 2) else emptyList()
            val visible = if (collapsed) {
                listOf(crumbs.first()) + crumbs.subList(crumbs.size - 2, crumbs.size)
            } else {
                crumbs
            }
            visible.forEachIndexed { index, (id, name) ->
                if (index > 0) {
                    CrumbSeparator()
                }
                if (collapsed && index == 1) {
                    HiddenCrumbs(hidden = hidden, active = active, onCrumbClick = onCrumbClick)
                    CrumbSeparator()
                }
                val isLast = index == visible.lastIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(
                            // The current folder takes what is left and ellipsizes; earlier
                            // crumbs are capped so one long name cannot push it out.
                            if (isLast) Modifier.weight(1f, fill = false)
                            else Modifier.widthIn(max = CrumbMaxWidth),
                        )
                        // 48dp wide at least; the 40dp pill's height is extended to a 48dp
                        // touch target by Compose since nothing else is tappable above/below.
                        .widthIn(min = CrumbMinTouch)
                        .heightIn(min = CrumbBarHeight)
                        .clip(RoundedCornerShape(CrumbBarHeight / 2))
                        .clickable(role = Role.Button) { onCrumbClick(id) }
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = when {
                            isLast && active -> MaterialTheme.colorScheme.primary
                            isLast -> MaterialTheme.colorScheme.onSecondaryContainer
                            !active -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private const val MAX_VISIBLE_CRUMBS = 3
private val CrumbMaxWidth = 120.dp
private val CrumbMinTouch = 48.dp

@Composable
private fun CrumbSeparator() {
    Icon(
        Icons.Outlined.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(16.dp),
    )
}

/** "…" crumb: lists the collapsed middle of the path; picking one jumps there. */
@Composable
private fun HiddenCrumbs(
    hidden: List<Pair<String, String>>,
    active: Boolean,
    onCrumbClick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = CrumbMinTouch, height = CrumbBarHeight)
                .clip(RoundedCornerShape(CrumbBarHeight / 2))
                .clickable(role = Role.Button) { open = true },
        ) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = stringResource(R.string.rows_show_full_path),
                tint = if (active) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            hidden.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onCrumbClick(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun crumbsFor(
    focusedDirId: String?,
    crumbNames: Map<String, String>,
): List<Pair<String, String>> {
    focusedDirId ?: return emptyList()
    val chain = generateSequence(focusedDirId) { XId.parent(it) }.toList().reversed()
    return chain.map { id ->
        val named = crumbNames[id]
        val raw = id.substringAfter("://")
        val name = named ?: when (raw) {
            "@user" -> stringResource(R.string.installed_apps)
            "@system" -> stringResource(R.string.system_apps)
            else -> raw.trimEnd('/').substringAfterLast('/')
                .substringAfterLast(XId.ARCHIVE_SEP)
                .ifEmpty { if (id.startsWith(XId.SCHEME_APPS)) stringResource(R.string.apps) else "/" }
        }
        id to name
    }
}
