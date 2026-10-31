package app.local1st.files.ai.clean

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 图片质量指标。 */
internal class ImageStat(
    /** 拉普拉斯方差，越小越模糊。 */
    val variance: Double,
    /** 平均亮度 0..255。 */
    val meanLuma: Double,
    /** 95 分位亮度。 */
    val p95Luma: Int,
    /** 64 位差异哈希（dHash），用于找相似图。 */
    val dHash: Long,
)

/**
 * 媒体探测（阻塞 I/O，只在 Dispatchers.IO 调用）。
 * 先用 java.io.File 直接读；读不到（如需特权的 Android/data）再走 Graph.fsRegistry 的 file 文件系统。
 */
internal object MediaProbe {
    private const val TARGET_W = 600

    private fun open(path: String): InputStream? {
        try {
            val f = File(path)
            if (f.canRead()) return FileInputStream(f)
        } catch (_: Exception) {}
        return try {
            val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
            val e = fs.stat(XId.file(path)) ?: return null
            fs.openIn(e)
        } catch (_: Throwable) { null }
    }

    fun analyzeImage(path: String): ImageStat? {
        var bmp: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val s0 = open(path) ?: return null
            s0.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w0 = bounds.outWidth
            val h0 = bounds.outHeight
            if (w0 <= 0 || h0 <= 0) return null
            var sample = 1
            while (w0 / (sample * 2) >= TARGET_W && h0 / (sample * 2) >= 64) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var b = open(path)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            if (b.width > TARGET_W) {
                val h = maxOf(1, (b.height.toLong() * TARGET_W / b.width).toInt())
                val s = Bitmap.createScaledBitmap(b, TARGET_W, h, true)
                if (s !== b) b.recycle()
                b = s
            }
            bmp = b
            val w = b.width
            val h = b.height
            if (w < 3 || h < 3) return null
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val gray = IntArray(w * h)
            val hist = IntArray(256)
            var sum = 0L
            for (i in px.indices) {
                val c = px[i]
                val y = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
                gray[i] = y
                hist[y]++
                sum += y
            }
            // 拉普拉斯方差
            var n = 0L
            var s1 = 0.0
            var s2 = 0.0
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                    s1 += lap
                    s2 += lap.toDouble() * lap
                    n++
                }
            }
            val mean = s1 / n
            val variance = s2 / n - mean * mean
            // 95 分位亮度
            val target = (px.size * 0.95).toLong()
            var acc = 0L
            var p95 = 255
            for (v in 0..255) { acc += hist[v]; if (acc >= target) { p95 = v; break } }
            return ImageStat(variance, sum.toDouble() / px.size, p95, dHash(gray, w, h))
        } catch (_: Throwable) {
            return null
        } finally {
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }

    /** 9x8 区域平均后相邻比较，得到 64 位 dHash。 */
    private fun dHash(gray: IntArray, w: Int, h: Int): Long {
        val cols = 9
        val rows = 8
        val cell = DoubleArray(cols * rows)
        for (r in 0 until rows) {
            val y0 = r * h / rows
            val y1 = maxOf(y0 + 1, (r + 1) * h / rows)
            for (c in 0 until cols) {
                val x0 = c * w / cols
                val x1 = maxOf(x0 + 1, (c + 1) * w / cols)
                var s = 0L
                var cnt = 0
                for (y in y0 until y1) for (x in x0 until x1) { s += gray[y * w + x]; cnt++ }
                cell[r * cols + c] = if (cnt == 0) 0.0 else s.toDouble() / cnt
            }
        }
        var hash = 0L
        var bit = 0
        for (r in 0 until rows) for (c in 0 until cols - 1) {
            if (cell[r * cols + c] > cell[r * cols + c + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    /** 视频时长（毫秒），读不到返回 null。 */
    fun videoDurationMs(path: String): Long? {
        val r = MediaMetadataRetriever()
        return try {
            val f = File(path)
            if (f.canRead()) {
                r.setDataSource(path)
            } else {
                val fs = Graph.fsRegistry.forScheme(XId.SCHEME_FILE)
                val e = fs.stat(XId.file(path)) ?: return null
                val local = e.localPath ?: return null
                r.setDataSource(local)
            }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }
}
