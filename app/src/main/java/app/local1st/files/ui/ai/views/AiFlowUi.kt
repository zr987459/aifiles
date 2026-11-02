package app.local1st.files.ui.ai.views

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.ai.Sort

/*
 * aiflow：智能分类 / 清理 / 计划确认 / 历史 几个页面共用的交互部件，
 * 让“选择”和“排序”在列表、网格、分组视图里长得一样、用起来一样。
 */

/** 内部存储根目录，界面上显示成“内部存储”。 */
internal const val AIFLOW_INTERNAL_ROOT = "/storage/emulated/0"

/** 把绝对路径里的内部存储根换成易懂的名字（不影响真实路径）。 */
internal fun prettyPathWith(path: String, internalLabel: String): String {
    if (path.isEmpty()) return path
    return when {
        path == AIFLOW_INTERNAL_ROOT -> internalLabel
        path.startsWith("$AIFLOW_INTERNAL_ROOT/") -> internalLabel + path.removePrefix(AIFLOW_INTERNAL_ROOT)
        else -> path
    }
}

@Composable
internal fun prettyPath(path: String): String = prettyPathWith(path, stringResource(R.string.aiflow_internal_storage))

/** 统一的排序选项：智能分类文件列表和清理详情共用。 */
internal enum class AiSortOption(val labelRes: Int, val sort: Sort) {
    TIME(R.string.aiflow_sort_time, Sort.MTIME_DESC),
    SIZE(R.string.aiflow_sort_size, Sort.SIZE_DESC),
    NAME(R.string.aiflow_sort_name, Sort.NAME_ASC),
    ;

    companion object {
        fun of(sort: Sort): AiSortOption = when (sort) {
            Sort.SIZE_DESC -> SIZE
            Sort.NAME_ASC -> NAME
            else -> TIME
        }
    }
}

/** 按统一规则在内存里排序（时间：新→旧；大小：大→小；名称：A→Z）。 */
internal fun <T> List<T>.sortedForAi(
    opt: AiSortOption,
    name: (T) -> String,
    size: (T) -> Long,
    mtime: (T) -> Long,
): List<T> = when (opt) {
    AiSortOption.TIME -> sortedByDescending(mtime)
    AiSortOption.SIZE -> sortedByDescending(size)
    AiSortOption.NAME -> sortedBy { name(it).lowercase() }
}

/** 一排排序标签：“排序 时间 大小 名称”，右侧可放统计文字。 */
@Composable
internal fun AiSortChips(
    selected: AiSortOption,
    onSelect: (AiSortOption) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.aiflow_sort_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AiSortOption.entries.forEach { opt ->
                FilterChip(
                    selected = selected == opt,
                    onClick = { onSelect(opt) },
                    enabled = enabled,
                    label = { Text(stringResource(opt.labelRes)) },
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/**
 * 缩略图格子上的统一选择标记：选中时整格染色 + 实心勾；可选未选时空心圈。
 * [visible] 为 false 时不画（例如还没进入选择模式）。
 */
@Composable
internal fun BoxScope.AiTileSelectionMark(selected: Boolean, visible: Boolean = true) {
    if (!visible) return
    Spacer(
        Modifier
            .matchParentSize()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent),
    )
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
        contentDescription = stringResource(if (selected) R.string.aiflow_selected else R.string.aiflow_not_selected),
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(24.dp)
            .background(
                if (selected) Color.White else Color.Black.copy(alpha = 0.25f),
                androidx.compose.foundation.shape.CircleShape,
            ),
    )
}
