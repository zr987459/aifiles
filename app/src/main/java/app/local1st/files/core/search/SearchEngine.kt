package app.local1st.files.core.search

import app.local1st.files.core.fs.XEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

data class SearchHit(
    val entry: XEntry,
    /** Human-readable location of the parent container. */
    val parentId: String,
)

/** Most hits a single search returns before it stops walking. */
const val SEARCH_MAX_HITS = 500

/**
 * How a search narrows its results.
 *
 * [recursive] false matches only the root's direct children. [includeHidden] false skips
 * hidden entries and never walks into hidden folders.
 */
data class SearchOptions(
    val type: SearchType = SearchType.ALL,
    val recursive: Boolean = true,
    val includeHidden: Boolean = true,
)

/** Recursive filename search below a root container. Implementation: DefaultSearchEngine. */
interface SearchEngine {
    /**
     * Emits hits as they are found. Cancellation of the collecting coroutine stops the walk.
     * [query] is matched case-insensitively as a substring; '*' and '?' wildcards supported.
     */
    fun search(root: XEntry, query: String): Flow<SearchHit>

    /**
     * Same as [search], narrowed by [options]. An empty [query] matches every name, which
     * together with a [SearchType] lists all files of that kind.
     */
    fun search(root: XEntry, query: String, options: SearchOptions): Flow<SearchHit> =
        search(root, query).filter { hit ->
            options.type.matches(hit.entry) && (options.includeHidden || !hit.entry.hidden)
        }
}
