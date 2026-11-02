package app.local1st.files.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.local1st.files.R

/** 两次返回键之间不超过这么久才算“确认退出”。 */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

/**
 * 返回键的最后两级，和浏览器上面的普通页面（由 AppHost 的预测式返回处理）互斥：
 *
 * - 浏览器（栈底，上面没有别的页面）：先取消选择，再逐级收起文件夹回到上一级，
 *   已在顶层时回到 AI 首页。
 * - AI 首页：第一次按提示“再按一次退出”，两秒内再按才离开应用。
 */
@Composable
internal fun RootBackHandler(
    vm: MainViewModel,
    onBrowserRoot: Boolean,
    onAiHome: Boolean,
) {
    val context = LocalContext.current
    var lastPressAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = onBrowserRoot || onAiHome) {
        if (onBrowserRoot) {
            lastPressAt = 0L
            val ctrl = vm.activeCtrl
            when {
                ctrl.state.value.selection.isNotEmpty() -> ctrl.clearSelection()
                vm.sessionReady.value && ctrl.stepBack() -> Unit
                else -> vm.openAi(AppScreen.AiHome)
            }
            return@BackHandler
        }
        val now = SystemClock.uptimeMillis()
        if (lastPressAt != 0L && now - lastPressAt <= EXIT_CONFIRM_WINDOW_MS) {
            lastPressAt = 0L
            context.findActivity()?.let { activity ->
                // 和系统对根页面的处理一致：放到后台，下次打开是热启动；不是任务根时才结束。
                if (!activity.moveTaskToBack(false)) activity.finish()
            }
        } else {
            lastPressAt = now
            Toast.makeText(context, R.string.nav_press_back_again_to_exit, Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
