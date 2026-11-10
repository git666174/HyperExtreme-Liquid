package com.frederic22.hyperextreme.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 液态壁纸的**素材仓库**（2026-11-09）。
 *
 * ## 存哪、怎么命名
 *
 * ```
 * files/liquid/<key>/base.jpg      key = 压缩后字节的 sha256 前 16 位
 * ```
 *
 * 与空间壁纸（`files/spatial/<key>/`）、光栅壁纸（`files/raster/<组id>/`）同一套约定：
 * 目录即真相，key 由内容算出来 —— 同一张图重复选不会产生第二份，换图必然产生新 key
 * （SystemUI 侧靠 key 变化判断「要重建渲染器」）。
 *
 * ## 为什么在这里压到 1080 宽
 *
 * 渲染器把整张图当纹理常驻（RGBA8 = 宽×高×4 字节）。1080×2400 ≈ 10MB；
 * 直接拿相机原图（4000×3000）就是 46MB，SystemUI 里这笔内存会直接顶到 LMK。
 * 液态效果本身是「低频折射 + 高光」，1080 宽完全够看。
 *
 * ## 方向
 *
 * 用 [ImageDecoder] 而不是 `BitmapFactory`：它会**自动应用 EXIF 旋转**。
 * BitmapFactory 读出来是传感器的原始方向，竖拍照片会躺倒 —— 这是自选图最容易踩的一条。
 *
 * ## 谁读它
 *
 * 只有 SystemUI 进程的 `LiquidRenderer`，走 [MaskProvider] 的
 * `content://<authority>/liquid/<key>/base`（模块私有目录它读不到）。
 */
object LiquidStore {

    private const val DIR = "liquid"

    /** 素材最长边上限（宽）。见类注释「为什么在这里压到 1080 宽」。 */
    private const val MAX_WIDTH = 1080

    private const val JPEG_QUALITY = 92

    /** 素材文件；`key` 为空或文件不存在时由调用方判断。 */
    fun baseFile(ctx: Context, key: String): File =
        File(File(ctx.filesDir, DIR), "$key/base.jpg")

    fun exists(ctx: Context, key: String): Boolean =
        key.isNotEmpty() && baseFile(ctx, key).isFile

    /**
     * 把相册里选的图导入成素材，返回新 key；失败返回 null。
     *
     * **必须在 IO 线程调用**：解码 + 压缩一张 4000px 的照片要几十到几百毫秒。
     */
    fun import(ctx: Context, uri: Uri): String? {
        val bmp = decode(ctx, uri) ?: return null
        val bytes = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (!bmp.isRecycled) bmp.recycle()
        if (bytes.isEmpty()) return null
        val key = sha256(bytes).take(16)
        val file = baseFile(ctx, key)
        file.parentFile?.mkdirs()
        runCatching { file.writeBytes(bytes) }.getOrElse { return null }
        return key
    }

    /** 页面预览用：按需降采样，别把 1080 的全图读进 Compose。 */
    fun preview(ctx: Context, key: String, maxSize: Int = 720): Bitmap? = runCatching {
        val file = baseFile(ctx, key)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize * 2) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    /** 选区遮罩（屏幕空间 PNG，白 = 有液体），与素材同目录。 */
    fun maskFile(ctx: Context, key: String): File =
        File(File(ctx.filesDir, DIR), "$key/mask.png")

    fun hasMask(ctx: Context, key: String): Boolean =
        key.isNotEmpty() && maskFile(ctx, key).isFile

    /** 保存涂抹出来的选区遮罩。返回是否成功。 */
    fun saveMask(ctx: Context, key: String, bitmap: Bitmap): Boolean = runCatching {
        val file = maskFile(ctx, key)
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        true
    }.getOrDefault(false)

    /**
     * 系统当前壁纸的预览位图（页面实时预览用，≤ [maxWidth]）。
     *
     * 优先读 `WallpaperSource.displayPaths` 指向的已应用壁纸文件（和渲染器同源），
     * 读不到再退 `WallpaperManager`。**必须在 IO 线程调用**。
     */
    fun systemPreview(ctx: Context, maxWidth: Int = 720): Bitmap? = runCatching {
        val uid = WallpaperSource.currentUserId()
        for (path in WallpaperSource.displayPaths(uid)) {
            val f = java.io.File(path)
            if (!f.isFile || f.length() < 50_000L) continue
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
            if (bmp != null) return bmp
        }
        val drawable = android.app.WallpaperManager.getInstance(ctx).drawable ?: return null
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxWidth
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: (maxWidth * 16 / 9)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, w, h)
            drawable.draw(android.graphics.Canvas(it))
        }
    }.getOrNull()

    /** 删掉一张素材（用户换图后旧图最多留 2 份，见页面里的清理策略）。 */
    fun delete(ctx: Context, key: String) {
        if (key.isEmpty()) return
        runCatching { File(File(ctx.filesDir, DIR), key).deleteRecursively() }
    }

    /** 清空所有素材（调试用）。 */
    fun clearAll(ctx: Context) {
        runCatching { File(ctx.filesDir, DIR).deleteRecursively() }
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 软件位图：后面要 compress，硬件位图 compress 会抛。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val srcW = info.size.width
            if (srcW > MAX_WIDTH) {
                val srcH = info.size.height
                val h = (srcH.toLong() * MAX_WIDTH / srcW).toInt().coerceAtLeast(1)
                decoder.setTargetSize(MAX_WIDTH, h)
            }
        }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
