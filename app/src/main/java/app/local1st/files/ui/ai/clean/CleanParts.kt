package app.local1st.files.ui.ai.clean

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import app.local1st.files.R
import app.local1st.files.ui.ai.views.AiTileSelectionMark
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.ai.CleanGroup
import app.local1st.files.ai.CleanItem
import app.local1st.files.ai.ExecProgress
import app.local1st.files.ai.Risk
import app.local1st.files.core.util.Format
import app.local1st.files.ui.ai.views.AiFileThumb
import app.local1st.files.ui.ai.views.ThumbKind
import app.local1st.files.ui.ai.views.thumbKindOf

/* 清理页共用的小部件与规则（A1）。 */

/** 超过这个数量或体积时，执行前二次确认。 */
internal const val CLEAN_CONFIRM_COUNT = 200
internal const val CLEAN_CONFIRM_BYTES = 5L * 1024 * 1024 * 1024

internal val CleanRed = Color(0xFFE53935)
internal val CleanSafeColor = Color(0xFF2E7D32)
internal val CleanConfirmColor = Color(0xFFB28704)
internal val CleanKeepColor = Color(0xFF757575)

internal fun cleanBytes(b: Long): String = Format.bytes(b).ifBlank { "0 B" }

internal fun cleanSourceZh(source: String) = when (source.lowercase()) {
    "rule" -> "规则"
    "algo" -> "算法"
    "ai" -> "AI"
    else -> source
}

internal fun cleanRiskLabel(risk: Risk): Pair<String, Color> = when (risk) {
    Risk.SAFE -> "可安全删除" to CleanSafeColor
    Risk.CONFIRM -> "建议确认" to CleanConfirmColor
    Risk.KEEP -> "建议保留" to CleanKeepColor
}

/** 组里可以勾选的条目：KEEP 组和 keep=true 的一律不可选。 */
internal fun cleanSelectable(g: CleanGroup): List<CleanItem> =
    if (g.risk == Risk.KEEP) emptyList() else g.items.filterNot { it.keep }

internal fun cleanErr(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

internal fun cleanIsMedia(item: CleanItem): Boolean {
    val k = thumbKindOf(item.path, null, item.isDir)
    return k == ThumbKind.IMAGE || k == ThumbKind.VIDEO
}

@Composable
internal fun CleanRiskTag(risk: Risk) {
    val (text, color) = cleanRiskLabel(risk)
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun CleanKeepBadge(modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = CleanSafeColor, modifier = modifier) {
        Text(
            "保留",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 网格里的正方形缩略图格子。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CleanMediaTile(
    item: CleanItem,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 与智能分类网格一致：点按预览，长按选择；右上角的圈也可以直接点。
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = if (selectable) onToggle else onReveal),
    ) {
        AiFileThumb(
            path = item.path,
            mtime = item.mtime,
            size = item.size,
            isDir = item.isDir,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.keep) {
            CleanKeepBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
        } else if (selectable) {
            AiTileSelectionMark(selected = checked)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clickable(onClick = onToggle),
            )
            // 长按改成了选择，这里保留“在文件夹中定位”的入口。
            IconButton(
                onClick = onReveal,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    stringResource(R.string.aiflow_reveal_in_folder),
                    tint = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(3.dp)
                        .size(18.dp),
                )
            }
        }
    }
}

/** 重复组并排展示的格子：缩略图 + 所在文件夹 + 文件名 + 大小时间。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CleanDuplicateTile(
    item: CleanItem,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
) {
    Column(
        Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = if (selectable) onToggle else onReveal)
            .padding(4.dp),
    ) {
        Box(Modifier.size(124.dp).clip(RoundedCornerShape(10.dp))) {
            AiFileThumb(
                path = item.path,
                mtime = item.mtime,
                size = item.size,
                isDir = item.isDir,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.keep) {
                CleanKeepBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
            } else if (selectable) {
                AiTileSelectionMark(selected = checked)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(44.dp)
                        .clickable(onClick = onToggle),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 所在文件夹：点一下在浏览器里定位，方便判断该留哪份。
        Text(
            "📁 " + item.path.substringBeforeLast('/').substringAfterLast('/'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onReveal),
        )
        Text(
            item.path.substringAfterLast('/'),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(Format.bytes(item.size), Format.dateTime(item.mtime)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 普通文件 / 文件夹的一行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CleanItemRow(
    item: CleanItem,
    checked: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked && selectable) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = if (selectable) onToggle else onReveal)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.keep) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CleanKeepBadge() }
        } else {
            Checkbox(checked = checked && selectable, onCheckedChange = { onToggle() }, enabled = selectable)
        }
        AiFileThumb(
            path = item.path,
            mtime = item.mtime,
            size = item.size,
            isDir = item.isDir,
            iconSize = 22.dp,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.path.substringBeforeLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(Format.bytes(item.size), Format.dateTime(item.mtime), item.reason)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onReveal) {
            Icon(
                Icons.Outlined.FolderOpen,
                stringResource(R.string.aiflow_reveal_in_folder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 底部固定栏：执行中显示进度，否则显示“移入回收站 N 项 · X”。 */
@Composable
internal fun CleanTrashBar(
    count: Int,
    bytes: Long,
    executing: Boolean,
    label: String,
    progress: ExecProgress?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (executing) {
                val p = progress
                val total = p?.total ?: 0
                if (p != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (p.done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                val cur = p?.current.orEmpty()
                Text(
                    buildString {
                        append(label)
                        if (p != null && total > 0) append(" ${p.done}/$total")
                        if (cur.isNotBlank()) append(" · ${cur.substringAfterLast('/')}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (count == 0) "请选择要清理的文件" else "移入回收站 $count 项 · ${cleanBytes(bytes)}")
                }
                Text(
                    stringResource(R.string.aiflow_trash_bar_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                )
            }
        }
    }
}
