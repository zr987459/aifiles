package app.local1st.files.ui.ai.plan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 删除类操作的确认按钮：必须按住 [holdMillis] 毫秒才触发，松手就取消。
 */
@Composable
internal fun HoldToConfirmButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1000,
    onConfirm: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val confirm by rememberUpdatedState(onConfirm)
    val container = if (enabled) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    val content = if (enabled) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .drawBehind { drawRect(fill, size = Size(size.width * progress.value, size.height)) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                            confirm()
                            progress.snapTo(0f)
                        }
                        tryAwaitRelease()
                        if (job.isActive) {
                            job.cancel()
                            scope.launch { progress.animateTo(0f, tween(150)) }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
