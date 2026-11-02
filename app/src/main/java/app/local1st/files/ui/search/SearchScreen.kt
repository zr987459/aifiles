package app.local1st.files.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.search.SEARCH_MAX_HITS
import app.local1st.files.core.search.SearchHit
import app.local1st.files.core.search.SearchOptions
import app.local1st.files.core.search.SearchType
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.EntryIcon
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.dialogs.searchHitLocation
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.navigationBarsStable
import app.local1st.files.ui.statusBarsStable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DEBOUNCE_MS = 300L

/** ASCII queries need two characters; one Chinese (or any non-ASCII) character is enough. */
private const val MIN_ASCII_QUERY_LENGTH = 2

private enum class SearchPhase { IDLE, SEARCHING, DONE }

/** Where a search walks, relative to the folder it was opened from. */
private enum class SearchScope { HERE_AND_BELOW, HERE_ONLY, WHOLE_STORAGE }

private data class SearchRequest(
    val query: String,
    val type: SearchType,
    val scope: SearchScope,
    val showHidden: Boolean,
    val storageRoot: XEntry?,
)

internal fun isSearchQueryReady(query: String, type: SearchType): Boolean = when {
    query.isEmpty() -> type != SearchType.ALL
    query.any { it.code > 0x7F } -> true
    else -> query.length >= MIN_ASCII_QUERY_LENGTH
}

@StringRes
private fun SearchType.labelRes(): Int = when (this) {
    SearchType.ALL -> R.string.ux_search_type_all
    SearchType.FOLDER -> R.string.ux_search_type_folder
    SearchType.IMAGE -> R.string.ux_search_type_image
    SearchType.VIDEO -> R.string.ux_search_type_video
    SearchType.AUDIO -> R.string.ux_search_type_audio
    SearchType.DOCUMENT -> R.string.ux_search_type_document
    SearchType.ARCHIVE -> R.string.ux_search_type_archive
    SearchType.APK -> R.string.ux_search_type_apk
}

@StringRes
private fun SearchScope.labelRes(): Int = when (this) {
    SearchScope.HERE_AND_BELOW -> R.string.ux_search_scope_here_and_below
    SearchScope.HERE_ONLY -> R.string.ux_search_scope_here_only
    SearchScope.WHOLE_STORAGE -> R.string.ux_search_scope_storage
}

/** The storage volume that contains [root], when [root] is a local folder below it. */
private fun storageRootFor(root: XEntry): XEntry? {
    if (root.scheme != XId.SCHEME_FILE) return null
    val rootPath = (root.localPath ?: root.path).trimEnd('/')
    val volumes = runCatching { Graph.roots.volumes() }.getOrDefault(emptyList())
    val volume = volumes
        .map { it.entry }
        .filter { it.scheme == XId.SCHEME_FILE }
        .filter {
            val p = (it.localPath ?: it.path).trimEnd('/')
            p.isNotEmpty() && (rootPath == p || rootPath.startsWith("$p/"))
        }
        .maxByOrNull { (it.localPath ?: it.path).length }
        ?: return null
    return if (volume.id == root.id) null else volume
}

/** Full-screen recursive filename search destination. */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
    FlowPreview::class,
)
@Composable
fun SearchScreen(
    vm: MainViewModel,
    root: XEntry,
    onBack: () -> Unit,
) {
    val r = root
    val close = onBack

    // Navigation 3 retains saveable entry state while another destination is on top.
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(SearchType.ALL) }
    var scope by rememberSaveable { mutableStateOf(SearchScope.HERE_AND_BELOW) }
    val results = remember { mutableStateListOf<SearchHit>() }
    var phase by remember { mutableStateOf(SearchPhase.IDLE) }
    var failed by remember { mutableStateOf(false) }

    val showHidden by Graph.settings.showHidden.collectAsState(initial = false)
    val storageRoot by produceState<XEntry?>(null, r.id) {
        value = withContext(Dispatchers.IO) { storageRootFor(r) }
    }
    val effectiveRoot = if (scope == SearchScope.WHOLE_STORAGE) storageRoot ?: r else r

    LaunchedEffect(r.id) {
        snapshotFlow { SearchRequest(query.trim(), type, scope, showHidden, storageRoot) }
            .debounce(DEBOUNCE_MS)
            .collectLatest { req ->
                results.clear()
                failed = false
                if (!isSearchQueryReady(req.query, req.type)) {
                    phase = SearchPhase.IDLE
                    return@collectLatest
                }
                phase = SearchPhase.SEARCHING
                val target = if (req.scope == SearchScope.WHOLE_STORAGE) req.storageRoot ?: r else r
                // Hidden rows follow the browsing setting, unless the user is clearly
                // looking for one (".nomedia") or is already inside a hidden place.
                val includeHidden = req.showHidden ||
                    req.query.startsWith('.') ||
                    target.scheme == XId.SCHEME_TRASH ||
                    (target.localPath ?: target.path).contains("/.")
                val options = SearchOptions(
                    type = req.type,
                    recursive = req.scope != SearchScope.HERE_ONLY,
                    includeHidden = includeHidden,
                )
                try {
                    Graph.searchEngine.search(target, req.query, options)
                        .flowOn(Dispatchers.IO)
                        .collect { results.add(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Raw exception text is English and technical; show a plain message instead.
                    failed = true
                }
                phase = SearchPhase.DONE
            }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current
    val binName = stringResource(R.string.recycle_bin)
    val volumeRoots = Graph.roots.mountedVolumes.collectAsState().value.map { it.path }

    val bottomSafe = WindowInsets.ime.union(WindowInsets.navigationBarsStable)

    val openScope = rememberCoroutineScope()

    fun openHit(entry: XEntry) {
        keyboard?.hide()
        openSearchHit(vm, entry, openScope)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBarsStable)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
                    // singleLine covers only the query; a long hint would still wrap and grow the field.
                    placeholder = {
                        Text(
                            stringResource(R.string.ux_search_hint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        TooltipIconButton(
                            stringResource(R.string.close_search),
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            onClick = close,
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            TooltipIconButton(
                                stringResource(R.string.clear_query),
                                Icons.Outlined.Close,
                                onClick = { query = "" },
                            )
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )

                ChipRow {
                    SearchType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(stringResource(option.labelRes())) },
                        )
                    }
                }
                ChipRow {
                    SearchScope.entries
                        .filter { it != SearchScope.WHOLE_STORAGE || storageRoot != null }
                        .forEach { option ->
                            FilterChip(
                                selected = scope == option,
                                onClick = { scope = option },
                                label = { Text(stringResource(option.labelRes())) },
                            )
                        }
                }

                Text(
                    stringResource(R.string.ux_search_scope_label, effectiveRoot.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                when (phase) {
                    SearchPhase.SEARCHING -> Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (results.isEmpty()) stringResource(R.string.searching)
                            else stringResource(R.string.found_so_far, results.size),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    SearchPhase.DONE -> {
                        if (!failed && results.isNotEmpty()) {
                            Text(
                                when {
                                    results.size >= SEARCH_MAX_HITS ->
                                        stringResource(R.string.ux_search_too_many, SEARCH_MAX_HITS)
                                    results.size == 1 -> stringResource(R.string.one_result)
                                    else -> stringResource(R.string.results, results.size)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                    SearchPhase.IDLE -> {}
                }
            }

            if (results.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .windowInsetsPadding(bottomSafe),
                    contentAlignment = Alignment.Center,
                ) {
                    val trimmed = query.trim()
                    when (phase) {
                        SearchPhase.IDLE -> CenteredHint(stringResource(R.string.ux_search_idle_hint))
                        SearchPhase.SEARCHING -> LoadingIndicator()
                        SearchPhase.DONE -> when {
                            failed -> CenteredHint(
                                stringResource(R.string.ux_search_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                            trimmed.isEmpty() -> CenteredHint(
                                stringResource(R.string.ux_search_no_type_results),
                            )
                            else -> CenteredHint(
                                stringResource(R.string.ux_search_no_results, trimmed) + "\n" +
                                    stringResource(
                                        if (scope == SearchScope.WHOLE_STORAGE || storageRoot == null) {
                                            R.string.ux_search_no_results_tip
                                        } else {
                                            R.string.ux_search_no_results_tip_storage
                                        },
                                    ),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        bottom = bottomSafe.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    items(results, key = { it.entry.id }) { hit ->
                        SearchHitRow(
                            hit,
                            query = query.trim(),
                            volumeRoots = volumeRoots,
                            binName = binName,
                            onClick = { openHit(hit.entry) },
                            onShowInFolder = {
                                keyboard?.hide()
                                vm.revealSearchHit(hit.entry.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A tap opens the result itself: folders and archives open in the browser, files open in
 * their viewer on top of the results so Back returns here.
 */
private fun openSearchHit(vm: MainViewModel, entry: XEntry, scope: CoroutineScope) {
    if (entry.isContainer) {
        vm.revealSearchHit(entry.id)
        vm.activeCtrl.expand(entry)
        return
    }
    val category = runCatching { FileTypes.categoryOf(entry.name, entry.mime) }
        .getOrDefault(FileCategory.GENERIC)
    val needsBrowser = entry.kind == EntryKind.APP_COMPONENT ||
        category == FileCategory.APK ||
        category == FileCategory.ARCHIVE
    if (needsBrowser) {
        // These open an action menu that lives on the browser screen.
        vm.revealSearchHit(entry.id)
        vm.openEntry(vm.activeCtrl, entry)
        return
    }
    val ctrl = vm.activeCtrl
    val usesPaneSiblings = entry.scheme != XId.SCHEME_SAF && category in MEDIA_CATEGORIES
    if (!usesPaneSiblings || ctrl.siblings(entry, category).any { it.id == entry.id }) {
        vm.openEntry(ctrl, entry)
        return
    }
    // The image / media viewers take their list from the pane's cached folder listing. A
    // listing read before this file appeared would open a different photo (or "cannot play"),
    // so re-read that folder first and open only once the hit is in it.
    XId.parent(entry.id)?.let { ctrl.refresh(it) }
    scope.launch {
        val listed = withTimeoutOrNull(STALE_LISTING_WAIT_MS) {
            while (ctrl.siblings(entry, category).none { it.id == entry.id }) delay(50)
            true
        } == true
        when {
            listed -> vm.openEntry(ctrl, entry)
            // Still not listed: open just this file from its path, never a neighbour.
            entry.scheme == XId.SCHEME_FILE -> vm.openPath(entry.path)
            else -> vm.openEntry(ctrl, entry)
        }
    }
}

private val MEDIA_CATEGORIES = setOf(FileCategory.IMAGE, FileCategory.AUDIO, FileCategory.VIDEO)
private const val STALE_LISTING_WAIT_MS = 1_500L

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun CenteredHint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

/** Bold-highlights the first case-insensitive occurrence of [query] in [name]. */
@Composable
private fun highlighted(name: String, query: String): AnnotatedString {
    if (query.isEmpty() || '*' in query || '?' in query) return AnnotatedString(name)
    val start = name.indexOf(query, ignoreCase = true)
    if (start < 0) return AnnotatedString(name)
    val color = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(name.substring(0, start))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
            append(name.substring(start, start + query.length))
        }
        append(name.substring(start + query.length))
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    query: String,
    volumeRoots: List<String>,
    binName: String,
    onClick: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    val entry = hit.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(
            entry,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                highlighted(entry.name, query),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                searchHitLocation(hit.parentId, volumeRoots, binName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            val size = if (entry.isDir) "" else Format.bytes(entry.size)
            if (size.isNotEmpty()) {
                Text(size, style = MaterialTheme.typography.labelSmall)
            }
            val date = Format.dateTime(entry.mtime)
            if (date.isNotEmpty()) {
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TooltipIconButton(
            stringResource(R.string.ux_search_show_in_folder),
            Icons.Outlined.FolderOpen,
            onClick = onShowInFolder,
        )
    }
}
