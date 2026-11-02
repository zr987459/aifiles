package app.local1st.files.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.local1st.files.di.Graph
import app.local1st.files.ui.appinfo.AppInfoScreen
import app.local1st.files.ui.dialogs.DestinationPickerScreen
import app.local1st.files.ui.dialogs.MainDialogs
import app.local1st.files.ui.dialogs.OpsHost
import app.local1st.files.ui.motion.AppScreenSurface
import app.local1st.files.ui.motion.AppScreenSwitcher
import app.local1st.files.ui.motion.DisplayBoundsMargin
import app.local1st.files.ui.motion.EnteringStartOffset
import app.local1st.files.ui.motion.WindowPose
import app.local1st.files.ui.motion.frontPose
import app.local1st.files.ui.motion.rearPose
import app.local1st.files.ui.motion.rememberDisplayCornerRadius
import app.local1st.files.ui.motion.scrimAlpha
import app.local1st.files.ui.motion.towardEndSign
import app.local1st.files.ui.navigationBarsStable
import app.local1st.files.ui.search.SearchScreen
import app.local1st.files.ui.settings.SettingsScreen
import app.local1st.files.ui.viewer.ViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top-level screen host. At most two destinations are composed: the front page and, during
 * a transition, the page behind it. */
@Composable
fun AppHost(vm: MainViewModel) {
    val backStack = vm.screenBackStack
    val snackbarHostState = remember { SnackbarHostState() }
    val sessionReady by vm.sessionReady.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val displayCornerRadius = rememberDisplayCornerRadius()
    val scope = rememberCoroutineScope()
    val switcher = remember {
        AppScreenSwitcher(stack = backStack.toList(), scope = scope)
    }
    val holder = rememberSaveableStateHolder()
    val seenIds = remember { mutableSetOf<Long>() }
    val enteringOffsetPx = with(density) { EnteringStartOffset.toPx() }
    val marginPx = with(density) { DisplayBoundsMargin.toPx() }
    val towardEnd = towardEndSign(layoutDirection)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    LaunchedEffect(switcher) {
        snapshotFlow { backStack.toList() }.collect(switcher::onStackChanged)
    }

    LaunchedEffect(vm) {
        vm.snackbar.collect { snackbarHostState.showSnackbar(it) }
    }
    if (sessionReady) LegacySafGrantHost(vm)
    if (sessionReady) SafLocationPickerHost(vm)

    // 返回键：AI 首页是最后一级（再按一次退出），不再预览“滑回”到还在加载的浏览器；
    // 浏览器在栈底时逐级返回，见 RootBackHandler。
    val topScreen = backStack.lastOrNull()?.screen
    val onAiHome = topScreen == AppScreen.AiHome
    val onBrowserRoot = backStack.size == 1 && topScreen == AppScreen.Browser
    RootBackHandler(vm = vm, onBrowserRoot = onBrowserRoot, onAiHome = onAiHome)

    val canGoBack = backStack.size > 1 && !onAiHome
    val gestureState = rememberNavigationEventState(
        currentInfo = NavigationEventInfo.None,
        backInfo = if (canGoBack) listOf(NavigationEventInfo.None) else emptyList(),
    )
    val backPreview = remember { object { var ignore = false } }
    LaunchedEffect(gestureState, switcher) {
        snapshotFlow { gestureState.transitionState }.collect { transition ->
            if (transition is NavigationEventTransitionState.InProgress) {
                if (backPreview.ignore) return@collect
                val current = backStack.lastOrNull() ?: return@collect
                val previous = backStack.getOrNull(backStack.lastIndex - 1) ?: return@collect
                switcher.onPreview(transition.latestEvent, current, previous)
            } else {
                backPreview.ignore = false
            }
        }
    }
    NavigationBackHandler(
        state = gestureState,
        isBackEnabled = canGoBack,
        onBackCancelled = {
            backPreview.ignore = false
            switcher.cancelPreview()
        },
        onBackCompleted = {
            backPreview.ignore = true
            switcher.completeBack { vm.navigateBack() }
        },
    )

    val front = switcher.front
    val rear = switcher.rear
    val renderedIds = listOfNotNull(front.id, rear?.id)
    SideEffect {
        seenIds += renderedIds
        val live = backStack.map { it.id }.toSet() + renderedIds
        seenIds.filter { it !in live }.forEach { id ->
            holder.removeState(id)
            seenIds.remove(id)
        }
    }

    val container = LocalWindowInfo.current.containerSize
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    var measuredHeight by remember { mutableFloatStateOf(0f) }
    val widthPx = measuredWidth.takeIf { it > 1f } ?: container.width.toFloat().coerceAtLeast(1f)
    val heightPx = measuredHeight.takeIf { it > 1f } ?: container.height.toFloat().coerceAtLeast(1f)
    val rearPose = switcher.rearPose(heightPx, marginPx, enteringOffsetPx, towardEnd)
    val frontPose = switcher.frontPose(widthPx, heightPx, marginPx, enteringOffsetPx, towardEnd)
    val scrim = switcher.scrimAlpha(isDark)

    // 冷启动先显示 AI 首页时，浏览器（栈底）从没组合过：权限门的 onGranted、会话恢复等布局、
    // 启动遮罩都依赖它和上游一样在启动时就组合。所以在它第一次露面之前，把它隐藏地组合在最底层
    // （不绘制、不收触摸、无障碍不可见），第一次露面后就恢复“被遮住即离开组合”的原有行为。
    val root = backStack.firstOrNull()
    var rootShown by rememberSaveable { mutableStateOf(false) }
    val rootVisible = root != null && (root.id == front.id || root.id == rear?.id)
    if (rootVisible && !rootShown) SideEffect { rootShown = true }
    // 冷启动先让 AI 首页单独画出前两帧，再在底下组合隐藏的浏览器，首屏更快能点。
    var hiddenRootAllowed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        hiddenRootAllowed = true
    }
    val keepRootBehind = hiddenRootAllowed && !rootShown && !rootVisible && root != null &&
        root.screen is AppScreen.Browser
    // 通知权限（用于文件操作进度）等到第一次进入文件浏览器才问，且只问一次，不在冷启动时挡住首页。
    if (sessionReady && (rootShown || rootVisible)) RequestNotificationPermission()

    val layers = buildList {
        if (keepRootBehind) add(ScreenLayer(root!!, WindowPose(alpha = 0f), hidden = true))
        if (rear != null) add(ScreenLayer(rear, rearPose))
        add(ScreenLayer(front, frontPose))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                measuredWidth = it.width.toFloat()
                measuredHeight = it.height.toFloat()
            }
            .background(MaterialTheme.colorScheme.background),
    ) {
        layers.forEach { (entry, pose, hidden) ->
            key(entry.id) {
                holder.SaveableStateProvider(entry.id) {
                    // 结构在“隐藏 / 后层 / 前层”之间保持不变，切换角色时不会重建页面状态。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier),
                    ) {
                        AppScreenSurface(
                            pose = pose,
                            displayCornerRadius = displayCornerRadius,
                            mayShowVideo = entry.screen is AppScreen.Viewer,
                        ) {
                            AppScreenBody(vm, entry)
                        }
                        if (hidden) HiddenLayerTouchBlocker()
                    }
                }
            }
            if (entry.id == rear?.id && scrim > 0.01f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsStable),
        )
    }
}

private data class ScreenLayer(
    val entry: AppScreenEntry,
    val pose: WindowPose,
    val hidden: Boolean = false,
)

/** 吃掉落到隐藏层上的触摸：前层没处理的点击不能穿到看不见的浏览器上。 */
@Composable
private fun HiddenLayerTouchBlocker() {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    )
}

@Composable
private fun AppScreenBody(vm: MainViewModel, entry: AppScreenEntry) {
    when (val screen = entry.screen) {
        AppScreen.Browser -> Box(Modifier.fillMaxSize()) {
            PermissionGate(
                onGranted = vm::onStorageAccessGranted,
            ) {
                MainScreen(vm)
            }
            OpsHost()
            MainDialogs(vm)
        }

        is AppScreen.Search -> SearchScreen(
            vm = vm,
            root = screen.root,
            onBack = { vm.navigateBack(entry.id) },
        )

        AppScreen.Settings -> SettingsScreen(
            onBack = { vm.navigateBack(entry.id) },
        )

        is AppScreen.AppInfo -> AppInfoScreen(
            packageName = screen.packageName,
            onBack = { vm.navigateBack(entry.id) },
        )

        is AppScreen.Viewer -> ViewerScreen(
            vm = vm,
            request = screen.request,
            onBack = { vm.navigateBack(entry.id) },
        )

        is AppScreen.DestinationPicker -> DestinationPickerScreen(
            vm = vm,
            transfer = screen.transfer,
            onBack = { vm.navigateBack(entry.id) },
        )

        // ---- AI 文件管理（主控维护）----
        AppScreen.AiHome -> app.local1st.files.ui.ai.home.AiHomeScreen(aiNav(vm, entry))
        AppScreen.AiClean -> app.local1st.files.ui.ai.clean.AiCleanScreen(aiNav(vm, entry))
        is AppScreen.AiViews -> app.local1st.files.ui.ai.views.AiViewsScreen(
            nav = aiNav(vm, entry),
            viewKey = screen.viewKey,
            query = screen.query,
            title = screen.title,
        )
        is AppScreen.AiPlanReview -> app.local1st.files.ui.ai.plan.AiPlanReviewScreen(aiNav(vm, entry), screen.plan)
        AppScreen.AiHistory -> app.local1st.files.ui.ai.history.AiHistoryScreen(aiNav(vm, entry))
        AppScreen.AiSettings -> app.local1st.files.ui.ai.settings.AiSettingsScreen(aiNav(vm, entry))
        is AppScreen.AiAssistant -> app.local1st.files.ui.ai.assistant.AiAssistantScreen(aiNav(vm, entry), screen.initial)
    }
}

private fun aiNav(vm: MainViewModel, entry: AppScreenEntry) = app.local1st.files.ui.ai.AiNav(
    open = { vm.openAi(it) },
    back = { vm.navigateBack(entry.id) },
    openFile = { vm.openPath(it) },
    reveal = { vm.revealInBrowser(it) },
    openFiles = { paths, index -> paths.getOrNull(index)?.let { vm.openPath(it, paths) } },
    home = { vm.openAi(AppScreen.AiHome) },
    browse = { while (vm.screenBackStack.size > 1) vm.navigateBack() },
)



/**
 * Requests POST_NOTIFICATIONS on Android 13+ so file-op progress is actually visible. Asked at
 * most once per install: a dismissed dialog no longer comes back on every cold start.
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: denial just means no progress notification */ }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect
        val prefs = context.getSharedPreferences(NAV_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return@LaunchedEffect
        prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
        runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

private const val NAV_PREFS = "nav_prefs"
private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"

/** Opens the system folder picker for Add location on every API level. */
@Composable
private fun SafLocationPickerHost(vm: MainViewModel) {
    val nonce by Graph.locationActions.pickerNonce.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val uri = if (result.resultCode == Activity.RESULT_OK) data?.data else null
        vm.completeAddLocation(uri, data?.flags ?: 0)
    }
    LaunchedEffect(nonce) {
        if (!Graph.locationActions.takePickerLaunch(nonce)) return@LaunchedEffect
        runCatching { launcher.launch(Graph.locationActions.pickerIntent()) }
            .onFailure { vm.completeAddLocation(null, 0) }
    }
}

/** Completes one pending API 26-29 secondary-volume write, then lets it retry in place. */
@Composable
private fun LegacySafGrantHost(vm: MainViewModel) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
    val saf = Graph.legacySaf ?: return
    val request by saf.pendingGrant.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val uri = if (result.resultCode == Activity.RESULT_OK) data?.data else null
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                saf.completePendingGrant(uri, data?.flags ?: 0)
            }
            if (error != null) vm.snackbar.tryEmit(error)
        }
    }

    LaunchedEffect(request?.requestId) {
        val pending = request ?: return@LaunchedEffect
        vm.snackbar.tryEmit("Grant access to ${pending.volume.label} to finish the write")
        runCatching { launcher.launch(saf.pickerIntent(pending)) }
            .onFailure { error ->
                val message = withContext(Dispatchers.IO) {
                    saf.completePendingGrant(null, 0)
                }
                vm.snackbar.tryEmit(message ?: error.message ?: "Cannot open storage picker")
            }
    }
}
