package app.local1st.files.ui.main

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.local1st.files.ai.FileQuery
import app.local1st.files.ai.Plan
import app.local1st.files.core.fs.XEntry
import app.local1st.files.ui.viewer.ViewerRequest

/** A full-screen destination. Dialogs and operation progress are not navigation destinations. */
sealed interface AppScreen {
    data object Browser : AppScreen
    data class Search(val root: XEntry) : AppScreen
    data object Settings : AppScreen
    data class AppInfo(val packageName: String) : AppScreen
    data class Viewer(val request: ViewerRequest) : AppScreen
    data class DestinationPicker(val transfer: PendingTransfer) : AppScreen

    // ---- AI 文件管理（主控维护）----
    data object AiHome : AppScreen
    data object AiClean : AppScreen
    /** viewKey 为 SmartView.key；query 非空时直接显示该查询结果（如一句话搜索）。 */
    data class AiViews(val viewKey: String? = null, val query: FileQuery? = null, val title: String? = null) : AppScreen
    data class AiPlanReview(val plan: Plan) : AppScreen
    data object AiHistory : AppScreen
    data object AiSettings : AppScreen
    /** AI 助手：对话式提问/指令（可带一句初始输入）。 */
    data class AiAssistant(val initial: String? = null) : AppScreen
}

/** Stable identity lets Compose retain saveable UI state for screens below the current one. */
class AppScreenEntry internal constructor(
    val id: Long,
    val screen: AppScreen,
) {
    // Viewer and picker payloads may contain long entry lists. Navigation identity is the cheap,
    // stable id rather than recursively hashing those payloads whenever the host resolves state.
    override fun equals(other: Any?): Boolean = other is AppScreenEntry && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "AppScreenEntry(id=$id)"
}

/**
 * Small in-memory navigation stack tailored to this single-activity app.
 *
 * Payloads such as [XEntry] and viewer playlists stay strongly typed instead of being serialized
 * into route strings. The browser is the root and cannot be popped.
 */
internal class AppNavigationState {
    private var nextId = 2L
    val backStack: SnapshotStateList<AppScreenEntry> = mutableStateListOf(
        AppScreenEntry(id = 0L, screen = AppScreen.Browser),
        // AI 首页是启动页；返回进入文件浏览器。
        AppScreenEntry(id = 1L, screen = AppScreen.AiHome),
    )

    fun navigate(screen: AppScreen, replaceTop: Boolean = false): AppScreenEntry {
        val current = backStack.last()
        if (current.screen === screen) return current
        // 连点同一个入口不会叠出两层一样的页面（大载荷页面只按引用比较，见 [isCheapToCompare]）。
        if (isCheapToCompare(screen) && current.screen == screen) return current

        // AI 首页只有一个，且总是紧贴在浏览器上面：从任何地方“回首页”都是退回到它，
        // 而不是再压一层新的首页，这样返回键永远是“首页 → 退出”。
        if (screen == AppScreen.AiHome) {
            val existing = backStack.indexOfFirst { it.screen == AppScreen.AiHome }
            if (existing >= 0) {
                while (backStack.lastIndex > existing) backStack.removeAt(backStack.lastIndex)
                return backStack[existing]
            }
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }

        val entry = AppScreenEntry(id = nextId++, screen = screen)
        if (replaceTop && backStack.size > 1) {
            backStack[backStack.lastIndex] = entry
        } else {
            backStack += entry
        }
        return entry
    }

    /** Pops only the screen that issued the callback, so a stale callback cannot pop a new page. */
    fun navigateBack(expectedEntryId: Long? = null): Boolean {
        if (backStack.size <= 1) return false
        if (expectedEntryId != null && backStack.last().id != expectedEntryId) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** 关掉浏览器上面的所有页面，回到文件浏览器。 */
    fun popToRoot() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** 当前最上面的页面。 */
    val top: AppScreen get() = backStack.last().screen
}

/** 列表类载荷（查看器播放列表、待传输文件、整理计划）可能很大，不做逐项相等比较。 */
private fun isCheapToCompare(screen: AppScreen): Boolean = when (screen) {
    is AppScreen.Viewer, is AppScreen.DestinationPicker, is AppScreen.AiPlanReview -> false
    else -> true
}
