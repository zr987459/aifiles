package app.local1st.files.ui.ai.views

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.thumb.PrivFile
import app.local1st.files.core.thumb.VideoThumb
import app.local1st.files.di.Graph
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/*
 * AI 页面共用的缩略图：和浏览器列表（ui/browser/EntryRow.kt 的 EntryThumbnail）同一套规则，
 * 走 App 的单例 Coil ImageLoader（已注册 VideoThumbFetcher 视频帧解码 + 磁盘缓存、PrivFileFetcher）：
 *  - 先用 Graph.fsRegistry 把路径 stat 成 XEntry，拿到 localPath（能直接读的本地文件）。
 *  - 图片：有 localPath → File；Android/data 等只能走特权/授权通道 → 小于 8MB 时读字节，否则 PrivFile。
 *  - 视频：VideoThumb(localPath)；没有 localPath → VideoThumb(privileged = true)，经 Shizuku 取 fd。
 *  - APK：PackageManager 解析安装包图标，内存 LRU 缓存。
 * 所有 stat / 读文件 / 解析 APK 都在 Dispatchers.IO。
 */

enum class ThumbKind { IMAGE, VIDEO, APK, DIR, OTHER }

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v", "ts", "flv", "wmv", "3g2", "mpg", "mpeg")

fun thumbKindOf(path: String, mime: String? = null, isDir: Boolean = false): ThumbKind {
    if (isDir) return ThumbKind.DIR
    val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    return when {
        ext == "apk" || mime == "application/vnd.android.package-archive" -> ThumbKind.APK
        ext in VIDEO_EXTS || mime?.startsWith("video/") == true -> ThumbKind.VIDEO
        ext in IMAGE_EXTS || mime?.startsWith("image/") == true -> ThumbKind.IMAGE
        else -> ThumbKind.OTHER
    }
}

fun thumbIconOf(kind: ThumbKind): ImageVector = when (kind) {
    ThumbKind.IMAGE -> Icons.Outlined.Image
    ThumbKind.VIDEO -> Icons.Outlined.Movie
    ThumbKind.APK -> Icons.Outlined.Android
    ThumbKind.DIR -> Icons.Outlined.Folder
    ThumbKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private const val THUMB_MAX_BYTES = 8L * 1024 * 1024

private fun needsPrivileged(path: String) = path.contains("/Android/data/") || path.contains("/Android/obb/")

/** 解析结果缓存：同一个文件来回滚动不重复 stat。 */
private val modelCache = object : LruCache<String, Any>(600) {}

/** APK 图标缓存（按条数）。 */
private val apkIconCache = object : LruCache<String, Bitmap>(200) {}

/** APK 解析比较重，限制并发。 */
private val apkSemaphore = Semaphore(2)

private object NoThumb

private fun cacheKey(path: String, mtime: Long, size: Long) = "$path|$mtime|$size"

private fun statEntry(path: String): XEntry? = runCatching {
    Graph.fsRegistry.forScheme(XId.SCHEME_FILE).stat(XId.file(path))
}.getOrNull()

private fun readSmall(entry: XEntry): ByteArray? = runCatching {
    if (entry.size > THUMB_MAX_BYTES) return null
    Graph.fsRegistry.forId(entry.id).openIn(entry).use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > THUMB_MAX_BYTES) return null
            out.write(chunk, 0, n)
        }
        out.toByteArray()
    }
}.getOrNull()

private fun loadApkIcon(context: Context, path: String): Bitmap? = runCatching {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val info = pm.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES) ?: return null
    val app = info.applicationInfo ?: return null
    app.sourceDir = path
    app.publicSourceDir = path
    val d = app.loadIcon(pm) ?: return null
    val px = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
    d.toBitmap(px, px, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** 在 IO 线程里算出 Coil 模型（File / ByteArray / VideoThumb / PrivFile / Bitmap），失败返回 null。 */
private suspend fun resolveModel(context: Context, path: String, mtime: Long, size: Long, kind: ThumbKind): Any? {
    if (kind == ThumbKind.DIR || kind == ThumbKind.OTHER) return null
    val key = cacheKey(path, mtime, size)
    if (kind == ThumbKind.APK) {
        apkIconCache.get(key)?.let { return it }
    } else {
        modelCache.get(key)?.let { return if (it === NoThumb) null else it }
    }
    return withContext(Dispatchers.IO) {
        val entry = statEntry(path)
        val local = entry?.localPath
        val model: Any? = when (kind) {
            ThumbKind.VIDEO -> when {
                local != null -> VideoThumb(path = local, mtime = entry?.mtime ?: mtime, size = entry?.size ?: size)
                entry != null || needsPrivileged(path) ->
                    VideoThumb(path = path, mtime = entry?.mtime ?: mtime, size = entry?.size ?: size, privileged = true)
                else -> VideoThumb(path = path, mtime = mtime, size = size)
            }
            ThumbKind.IMAGE -> when {
                local != null -> File(local)
                entry != null -> readSmall(entry) ?: PrivFile(path, entry.mtime, entry.size)
                needsPrivileged(path) -> PrivFile(path, mtime, size)
                else -> File(path)
            }
            ThumbKind.APK -> {
                val readable = local ?: path.takeIf { File(it).canRead() }
                readable?.let { p -> apkSemaphore.withPermit { loadApkIcon(context, p) } }
                    ?.also { apkIconCache.put(key, it) }
            }
            else -> null
        }
        if (kind != ThumbKind.APK) {
            // ByteArray 体积大，不放进模型缓存（Coil 自己有内存缓存）。
            if (model !is ByteArray) modelCache.put(key, model ?: NoThumb)
        }
        model
    }
}

/**
 * 文件缩略图：真实预览加载出来之前（或失败时）显示类型图标；视频叠加播放角标。
 * [modifier] 决定尺寸和裁切形状。
 */
@Composable
fun AiFileThumb(
    path: String,
    mtime: Long,
    size: Long,
    modifier: Modifier = Modifier,
    mime: String? = null,
    isDir: Boolean = false,
    iconSize: Dp = 28.dp,
) {
    val context = LocalContext.current.applicationContext
    val kind = remember(path, mime, isDir) { thumbKindOf(path, mime, isDir) }
    val model by produceState<Any?>(null, path, mtime, size, kind) {
        value = resolveModel(context, path, mtime, size, kind)
    }
    var coilLoaded by remember(path, mtime) { mutableStateOf(false) }
    val loaded = coilLoaded || model is Bitmap
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(
                thumbIconOf(kind),
                contentDescription = null,
                tint = if (kind == ThumbKind.APK) Color(0xFF3DDC84) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize).drawWithContent { if (!coilLoaded) drawContent() },
            )
        }
        when (val m = model) {
            null -> Unit
            is Bitmap -> {
                Image(
                    m.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
            }
            else -> AsyncImage(
                model = m,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { coilLoaded = it is AsyncImagePainter.State.Success },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (kind == ThumbKind.VIDEO && loaded) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}
