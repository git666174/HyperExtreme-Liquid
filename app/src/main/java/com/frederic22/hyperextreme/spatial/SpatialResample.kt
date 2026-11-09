package com.frederic22.hyperextreme.spatial

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * **PIL 风格重采样**：滤波器支撑随缩放比扩展（缩小时先低通再采样），可分离两遍。
 *
 * ## 为什么不用 `Bitmap.createScaledBitmap`
 *
 * Skia 的缩放（`SkFilterQuality` 的 low/medium/high）用的是**固定邻域**：
 * 双线性永远吃 2×2、双立方永远吃 4×4，**不按缩放比扩展支撑**。
 * 缩小超过 1.4 倍时这就不是低通滤波而是"抽样"，高频直接折返成混叠 ——
 * 表现是**细线断续成小方块**、边缘出现 1px 台阶。
 *
 * 实测（2026-11-01，`分类/缩放/test.jpg` 2000×3555 → 1022×1817，缩 1.96 倍，
 * 以 lanczos 为参照）：
 *
 * | 方法 | RMSE | lap_var |
 * |---|---|---|
 * | Skia 双线性（原实现） | 3.49 | 1570（混叠噪声） |
 * | Skia 双立方 | 4.68 | 2240（负瓣把混叠反相叠加，**更差**） |
 * | Skia 两级缩小 | 2.93 | 710 |
 * | 面积平均 | 2.09 | 799（最连贯，但偏糊） |
 * | **本实现（双线性）** | **2.78** | 480 |
 * | **本实现（双立方）** | **1.27** | 823 |
 *
 * 本实现与 PIL 的输出逐像素对比 RMSE 0.32（双线性）/ 0.33（双立方），是同一套算法。
 *
 * ⚠️ **2026-11-01 起项目固定用双立方**（用户指定）：层图的 RGB 是直接显示的，对锐度敏感。
 * 但注意"锐"和"对"要分开看 —— 双立方的负瓣只是提升了局部对比度，原图 1px 的细线缩到
 * 0.5px 之后信息已经丢了，`SpatialLayers.decodeToSize` 的输出上限是**深度图分辨率**，
 * 换插值只能让它"看起来更实"，不能凭空造细节。
 *
 * 另一条实测结论（避免以后再折腾）：**DA 的输入对锐度几乎不敏感**。
 * 同样四种输入缩到 1008 短边喂 DA V2，深度图两两 RMSE 只有 1~3（0..255 尺度），
 * bodyMask IoU 0.990~0.998；做 14×14 patch 化后输入侧的差异被抹掉 84%
 * （skia 4.22 → 0.67）—— 差别全在 1~2px 尺度，而 ViT 的 patch 是 14px。
 * 人为加锐（unsharp 90%）反而有害：深度 RMSE 8.56、直方图谷深变差。
 *
 * ## 开销
 *
 * 1022×1820 输出：双线性约 **60~120 ms**（每目标像素吃 ~4 个源像素 × 两遍），
 * 双立方约 **130~260 ms**（支撑翻倍 ⇒ ~8 个源像素）。
 * 中间缓冲 `IntArray(tw × sh)`，2000×3555 源 ⇒ 14.6MB。
 * 相对于 15~30 秒的深度推理可以忽略。
 */
internal object SpatialResample {

    /**
     * 解码阶段允许的最大像素数（32MB IntArray）。
     *
     * 以前 `inSampleSize` 是按「缩到接近目标」算的，等于把两倍以上的缩放交给跳采样 ——
     * 而 `BitmapFactory` 的 `inSampleSize` **不做低通**，本身就是混叠源。
     * 现在它只负责控制内存：解码后不超过这个数，剩下的交给 [bitmap] 做有抗混叠的缩放。
     */
    const val MAX_DECODE_PX = 8_000_000L

    /**
     * 把 [src] 重采样到 [tw]×[th]。
     *
     * @param cubic true = Catmull-Rom 双立方（更锐，支撑 2×scale）；false = 双线性（支撑 1×scale）。
     *   **默认双立方**（2026-11-01 用户指定）；双线性只在需要压耗时的地方显式指定。
     * @param recycleSrc 取完像素后是否立即回收 [src]（默认 true，省一块 4n 的峰值内存）
     */
    fun bitmap(src: Bitmap, tw: Int, th: Int, cubic: Boolean = true, recycleSrc: Boolean = true): Bitmap {
        val sw = src.width
        val sh = src.height
        if (sw == tw && sh == th) return src

        val px = IntArray(sw * sh)
        src.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (recycleSrc) src.recycle()

        // ── 水平一遍：sw → tw，中间 (tw × sh) ──
        val tapsX = buildTaps(sw, tw, cubic)
        val mid = IntArray(tw * sh)
        for (y in 0 until sh) {
            val srcRow = y * sw
            val dstRow = y * tw
            for (x in 0 until tw) {
                val t = tapsX[x]
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                val w = t.w
                var i = 0
                while (i < w.size) {
                    val c = px[srcRow + t.start + i]
                    val wt = w[i]
                    a += ((c ushr 24) and 0xFF) * wt
                    r += ((c shr 16) and 0xFF) * wt
                    g += ((c shr 8) and 0xFF) * wt
                    b += (c and 0xFF) * wt
                    i++
                }
                mid[dstRow + x] = (q(a) shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
            }
        }

        // ── 垂直一遍：sh → th，输出 (tw × th) ──
        val tapsY = buildTaps(sh, th, cubic)
        val out = IntArray(tw * th)
        for (j in 0 until th) {
            val t = tapsY[j]
            val dstRow = j * tw
            for (x in 0 until tw) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                val w = t.w
                var i = 0
                while (i < w.size) {
                    val c = mid[(t.start + i) * tw + x]
                    val wt = w[i]
                    a += ((c ushr 24) and 0xFF) * wt
                    r += ((c shr 16) and 0xFF) * wt
                    g += ((c shr 8) and 0xFF) * wt
                    b += (c and 0xFF) * wt
                    i++
                }
                out[dstRow + x] = (q(a) shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
            }
        }

        return Bitmap.createBitmap(out, tw, th, Bitmap.Config.ARGB_8888)
    }

    private fun q(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    /** 一个目标像素要吃的源像素：起点 + 归一化权重。 */
    private class Taps(val start: Int, val w: FloatArray)

    /**
     * 预计算权重表。
     *
     * 关键在 `fs = max(scale, 1)`：缩小时把核撑开，等效于先做低通再采样；
     * 放大时保持原支撑（标准插值）。Skia 缺的就是这一步。
     */
    private fun buildTaps(srcLen: Int, dstLen: Int, cubic: Boolean): Array<Taps> {
        val support = if (cubic) 2f else 1f
        val scale = srcLen.toFloat() / dstLen
        val fs = if (scale > 1f) scale else 1f
        val sup = support * fs
        val out = arrayOfNulls<Taps>(dstLen)
        var i = 0
        while (i < dstLen) {
            val center = (i + 0.5f) * scale
            var lo = floor(center - sup).toInt()
            var hi = ceil(center + sup).toInt()
            lo = lo.coerceIn(0, srcLen - 1)
            hi = hi.coerceIn(lo + 1, srcLen)
            val n = hi - lo
            val w = FloatArray(n)
            var sum = 0f
            var k = 0
            while (k < n) {
                val d = (lo + k + 0.5f - center) / fs
                val v = kernel(d, cubic)
                w[k] = v
                sum += v
                k++
            }
            if (sum > 1e-6f) {
                k = 0
                while (k < n) { w[k] /= sum; k++ }
            } else {
                k = 0
                while (k < n) { w[k] = 1f / n; k++ }
            }
            out[i] = Taps(lo, w)
            i++
        }
        @Suppress("UNCHECKED_CAST")
        return out as Array<Taps>
    }

    private fun kernel(d: Float, cubic: Boolean): Float {
        val x = if (d < 0f) -d else d
        if (!cubic) return if (x >= 1f) 0f else 1f - x
        val a = -0.5f
        return when {
            x <= 1f -> (a + 2f) * x * x * x - (a + 3f) * x * x + 1f
            x < 2f -> a * x * x * x - 5f * a * x * x + 8f * a * x - 4f * a
            else -> 0f
        }
    }

    /** 按 [MAX_DECODE_PX] 算解码跳采样倍数（只控内存，不做缩放）。 */
    fun decodeSample(w: Int, h: Int): Int {
        var sample = 1
        while ((w / sample).toLong() * (h / sample) > MAX_DECODE_PX) sample = sample shl 1
        return sample
    }
}
