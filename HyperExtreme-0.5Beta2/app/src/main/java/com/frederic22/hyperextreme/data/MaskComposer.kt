package com.frederic22.hyperextreme.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * 把「壁纸 ∩ 遮罩」合成主体，和系统 `TemplatePreviewImageComposer.composeSubjectImage` 同一套算法：
 * 灰度 v → RGB=(v,v,v)、alpha=v（黑=透明），再拿壁纸做 SRC_IN。
 *
 * 用途：用户只给了遮罩时，模块自己算出主体，从而不必等编辑器重新合成。
 */
object MaskComposer {

    private val BLACK_TO_TRANSPARENT = ColorMatrix(
        floatArrayOf(
            1f, 1f, -1f, 0f, 0f,
            1f, 1f, -1f, 0f, 0f,
            1f, 1f, -1f, 0f, 0f,
            1f, 1f, -1f, 0f, 0f,
        ),
    )

    /** 编辑器 `TemplatePreviewImageComposer.CONVERT_TO_BLACK_AND_WHITE` 的同款矩阵：RGB 全白、alpha 取源。 */
    private val TO_WHITE_SILHOUETTE = ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    /**
     * 主体 → 「白剪影」遮罩（与编辑器 `convertSubjectMask` 同一套算法：RGB 全白、alpha 取源）。
     *
     * 用于"用户只选了主体、没选遮罩"时**补一份配套的 `subject_mask`**（2026-10-04 用户要求"保险一点"）：
     * 编辑器/系统之后要拿 mask 重建主体，缺了它就会退到 AI 抠图或空白；有它则和主体完全一致。
     *
     * ⚠️ 主体如果没有 alpha（比如不透明 JPEG），剪影就是**整块白**（等于"整张壁纸都是主体"）——
     * 这不是错，是"只给主体"的必然结果；日志里会说明。
     */
    fun whiteSilhouette(source: Bitmap): Bitmap? = runCatching {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(TO_WHITE_SILHOUETTE)
        }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        out
    }.getOrNull()

    fun compose(wallpaper: Bitmap, mask: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(wallpaper.width, wallpaper.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val maskPaint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(BLACK_TO_TRANSPARENT)
            isFilterBitmap = true
        }
        canvas.drawBitmap(
            mask,
            Rect(0, 0, mask.width, mask.height),
            Rect(0, 0, wallpaper.width, wallpaper.height),
            maskPaint,
        )
        val wallpaperPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            isFilterBitmap = true
        }
        canvas.drawBitmap(wallpaper, 0f, 0f, wallpaperPaint)
        return output
    }

    /**
     * 把「源图空间」的主体裁成「显示空间」—— 编辑器那步的等价实现。
     *
     * 编辑器生成 `crop_subject` 用的是
     * `BitmapUtil.cropBitmap(bitmap, positionInfo.left, positionInfo.right, positionInfo.top, positionInfo.bottom)`，
     * 纯矩形裁切（`matrixArr` 那个缩放矩阵并没有参与这一步）。这里按同样的钳制规则复刻，
     * 越界不抛异常；裁不出东西（或位置信息缺失/非法）返回 null，调用方自己决定退路。
     *
     * 返回的是**新位图**，用完要负责 recycle。
     */
    fun cropToDisplay(subject: Bitmap, position: WallpaperPosition?): Bitmap? {
        if (position == null || !position.valid) return null
        val left = position.left.toInt()
        val top = position.top.toInt()
        val right = position.right.toInt()
        val bottom = position.bottom.toInt()
        // 钳制规则与 BitmapUtil.cropBitmap 一致：先夹原点，再把宽高夹到剩余范围内。
        val x = left.coerceIn(0, subject.width)
        val y = top.coerceIn(0, subject.height)
        val width = (right - left).coerceIn(0, subject.width - x)
        val height = (bottom - top).coerceIn(0, subject.height - y)
        if (width <= 0 || height <= 0) return null
        return runCatching { Bitmap.createBitmap(subject, x, y, width, height) }.getOrNull()
    }

    /**
     * 把主体缩放到与**已应用壁纸**同样的像素尺寸 —— 一级「我的搭配」的当前卡片要的就是这个。
     *
     * 为什么必须对齐：那张卡片把「已应用的壁纸」和 `crop_subject` 当**一对**叠着画，位置来自
     * GL 层按**壁纸贴图**算出来的矩阵（`HierarchyImageView.onChanged(Matrix)`）。两者像素尺寸
     * 不一致时，矩阵就按壁纸的尺寸去缩放主体 —— 原图与屏幕差得越多，错位越明显；两者接近时看不出来。
     *
     * 宽高比一致（裁切窗口按构造就是屏幕比例）才缩放，且用的是目标宽高**原样**拉伸，
     * 这样缩放后与壁纸严格同尺度。
     *
     * 返回新位图（未必；尺寸本来就一致时返回 null 表示"不必缩放"），调用方负责 recycle。
     */
    fun fitTo(bitmap: Bitmap, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (bitmap.width == width && bitmap.height == height) return null
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }.getOrNull()
    }

    /**
     * 按给定宽高比把主体**居中裁**一下 —— 拿不到裁切矩形时的退路。
     *
     * 比"原样写入未裁切的主体"好得多：裁切窗口的宽高比就是屏幕比例，而居中裁至少在没有拖动过
     * 壁纸时是精确正确的；用户真拖动过就还需要 `positionInfo`（那种情况日志会说明）。
     */
    fun centerCropToAspect(bitmap: Bitmap, aspect: Float): Bitmap? {
        if (aspect <= 0f) return null
        val current = bitmap.width.toFloat() / bitmap.height.toFloat()
        if (kotlin.math.abs(current - aspect) < 0.01f) return null
        return if (current > aspect) {
            // 太宽：裁掉左右（roundToInt：2000/2.2222 这种要进位，截断会少 1px）
            val width = (bitmap.height * aspect).roundToInt().coerceIn(1, bitmap.width)
            val x = (bitmap.width - width) / 2
            runCatching { Bitmap.createBitmap(bitmap, x, 0, width, bitmap.height) }.getOrNull()
        } else {
            // 太高：裁掉上下
            val height = (bitmap.width / aspect).roundToInt().coerceIn(1, bitmap.height)
            val y = (bitmap.height - height) / 2
            runCatching { Bitmap.createBitmap(bitmap, 0, y, bitmap.width, height) }.getOrNull()
        }
    }

    /** 两个尺寸的宽高比是否一致（1% 容差）—— 判断"能不能安全地按目标尺寸拉伸"。 */
    fun sameAspect(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
        if (a.first <= 0 || a.second <= 0 || b.first <= 0 || b.second <= 0) return false
        val left = a.first.toDouble() / a.second
        val right = b.first.toDouble() / b.second
        return kotlin.math.abs(left - right) / right < 0.01
    }
}
