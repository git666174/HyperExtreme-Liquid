package com.frederic22.hyperextreme.spatial

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 单张候选图的**显示区域**参数（2026-11-04，独立编辑页 `SpatialCropActivity` 的产物）。
 *
 * ## 语义
 *
 * 渲染时 `SpatialGlCore.geometry()` 已经把图按 cover + `SPHERE_SAFE` 放大到刚好盖满屏幕。
 * 这里的 `zoom` 是在**那个基准之上**的额外放大，`cx/cy` 是**显示区域中心**在原图 uv 空间的位置
 * （0..1，0.5 = 正中）。
 *
 * 于是 shader 里只做一次仿射：
 * ```
 * uv' = uv * (1/zoom) + (c - 0.5/zoom)
 * ```
 * 与预览页用的是**同一个公式**，所以「预览里看到的 = 壁纸上显示的」。
 *
 * ## 为什么 zoom 有下限 1
 *
 * `zoom < 1` 等于让图比基准还小 ⇒ 转动（位移场 `uShift`）时边缘会露出底色。
 * 下限 1 顺便保证了「画面永远铺满」这条硬约束。
 */
internal object SpatialCrop {

    /** 额外放大的下限 = 基准本身（更小就会露边）。 */
    const val ZOOM_MIN = 1f

    /** 额外放大的上限（用户 2026-11-04 定 2×；再高会明显糊，层图分辨率是固定的）。 */
    const val ZOOM_MAX = 2f

    /** 默认 = 不调整（正中、不额外放大），与加这个功能之前完全一致。 */
    val DEFAULT = Crop()

    data class Crop(
        val cx: Float = 0.5f,
        val cy: Float = 0.5f,
        val zoom: Float = 1f,
    )

    /** 缓存目录里的文件名（跟着 `files/spatial/<key>/` 一起删，天然按图独立）。 */
    private const val NAME = "crop.json"

    fun fileOf(ctx: Context, key: String): File =
        File(SpatialDepth.cacheRoot(ctx), key).resolve(NAME)

    /** 读参数；没有文件 / 内容坏了都回默认（= 不调整）。 */
    fun read(ctx: Context, key: String): Crop = runCatching {
        val f = fileOf(ctx, key)
        if (!f.isFile) return DEFAULT
        val j = JSONObject(f.readText())
        clamp(
            Crop(
                cx = j.optDouble("cx", 0.5).toFloat(),
                cy = j.optDouble("cy", 0.5).toFloat(),
                zoom = j.optDouble("zoom", 1.0).toFloat(),
            ),
        )
    }.getOrDefault(DEFAULT)

    /** 写参数（先写 tmp 再 rename，读端永远看不到半截）。失败只返回 false。 */
    fun write(ctx: Context, key: String, crop: Crop): Boolean = runCatching {
        val c = clamp(crop)
        val dir = File(SpatialDepth.cacheRoot(ctx), key).apply { mkdirs() }
        val out = File(dir, NAME)
        val tmp = File(dir, "$NAME.tmp")
        val json = JSONObject()
            .put("cx", c.cx.toDouble())
            .put("cy", c.cy.toDouble())
            .put("zoom", c.zoom.toDouble())
            .toString()
        tmp.writeText(json)
        if (!tmp.renameTo(out)) {
            out.writeText(json)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /** 删掉参数（删图时不需要单独调，目录一起没了；这里给「恢复默认」用）。 */
    fun clear(ctx: Context, key: String) {
        runCatching { fileOf(ctx, key).delete() }
    }

    /**
     * 夹紧到**合法值域**（只防脏数据，不做几何约束）。
     *
     * 2026-11-04 改：原来这里用 `half = 0.5/zoom` 保证「整幅 uv 都在图内」，
     * 但那个约束**过强** —— 屏幕只看得到原图的一块（`Geometry.visibleHalfX`），
     * zoom = 1 时 `half = 0.5` 会把 `cx` 锁死在 0.5，横向完全拖不动。
     * 真正的边界见 [clampWith]（要几何信息，只有编辑页有）。
     */
    fun clamp(c: Crop): Crop = Crop(
        cx = c.cx.coerceIn(0f, 1f),
        cy = c.cy.coerceIn(0f, 1f),
        zoom = c.zoom.coerceIn(ZOOM_MIN, ZOOM_MAX),
    )

    /**
     * 按**屏幕实际可见范围 + 姿态位移余量**夹紧 —— 这才是「画面必须铺满」的正确边界。
     *
     * 可见 uv 范围是 `c ± (visibleHalf + shiftMargin) / zoom`，要全部落在 `[0, 1]` 内：
     *
     * ```
     * half = (visibleHalf + shiftMargin) / zoom
     * c ∈ [half, 1 − half]
     * ```
     *
     * 两项都随 zoom 缩小 ⇒ 放大之后可移动范围变大（符合直觉）。
     * `shiftMargin` 是留给姿态转动的：不留的话，把图推到边上再转动就会露黑边。
     *
     * 以 2802×4830 放进 1200×2670 为例：可见半宽 0.3459、位移余量 0.0358
     * ⇒ zoom = 1 时 `cx ∈ [0.3817, 0.6183]`，横向可动 ±11.8%（旧逻辑是 ±0）。
     */
    fun clampWith(c: Crop, visibleHalfX: Float, visibleHalfY: Float, shiftX: Float, shiftY: Float): Crop {
        val zoom = c.zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
        val halfX = ((visibleHalfX + shiftX) / zoom).coerceIn(0f, 0.5f)
        val halfY = ((visibleHalfY + shiftY) / zoom).coerceIn(0f, 0.5f)
        return Crop(
            cx = c.cx.coerceIn(halfX, 1f - halfX),
            cy = c.cy.coerceIn(halfY, 1f - halfY),
            zoom = zoom,
        )
    }

    /** shader 的 `uUvScale`：两个方向等比。 */
    fun scaleOf(c: Crop): Float = 1f / c.zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)

    /**
     * 属性用的紧凑编码：`"scale,offx,offy"`（2026-11-05）。
     *
     * **为什么必须走属性**：渲染器跑在 SystemUI 进程，那里的 `ctx.filesDir` 是 SystemUI
     * 自己的私有目录 ⇒ 读不到模块的 `crop.json`（真机实测：四次启动都读到默认值）。
     * 系统属性所有进程都能读，且和 active / key / scope 同批推送，天然原子。
     */
    fun encodeProp(c: Crop): String {
        val k = clamp(c)
        return "${1f / k.zoom},${k.cx - 0.5f / k.zoom},${k.cy - 0.5f / k.zoom}"
    }

    /** 解析 [encodeProp] 的产物；空 / 格式不对 → null（调用方回默认 = 不裁剪）。 */
    fun decodeProp(raw: String): FloatArray? {
        val parts = raw.trim().split(',')
        if (parts.size != 3) return null
        val s = parts[0].toFloatOrNull() ?: return null
        val ox = parts[1].toFloatOrNull() ?: return null
        val oy = parts[2].toFloatOrNull() ?: return null
        if (!s.isFinite() || !ox.isFinite() || !oy.isFinite()) return null
        return floatArrayOf(s, ox, oy)
    }

    /** shader 的 `uUvOffset`（x / y 分开）。 */
    fun offsetXOf(c: Crop): Float = c.cx - 0.5f / c.zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)

    fun offsetYOf(c: Crop): Float = c.cy - 0.5f / c.zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
}
