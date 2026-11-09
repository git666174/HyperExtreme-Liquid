package com.frederic22.hyperextreme.data

import kotlin.math.roundToInt

/**
 * 液态壁纸的**可调参数**（2026-11-09，P5）。
 *
 * ## 为什么是「一个字符串」
 *
 * 渲染器在 SystemUI 进程，读不到模块 prefs（陈旧快照 / openRemoteFile 读不到，见
 * `SpatialFlag` 的注释）。所以参数必须走**系统属性**，而属性值是字符串：
 * 这里把它编码成 `"refract,c2,damp,sigma,level"` 五个整数（×10/×100/×1000 定点），
 * 与 `SpatialFlag.PROP_CROP` 的编码思路一致。
 *
 * ## 为什么按模式各存一份
 *
 * 水波和果冻的手感差异全在这五个数里（波速、阻尼、触点半径）。共用一份的话，
 * 「切到果冻」会把用户刚在水波下调好的参数一起带过去，两种模式就不再有性格差异。
 * 所以 [MaskPrefs.liquidTune] 按 mode 分开存：切模式 = 载入该模式的参数。
 *
 * ## 取值范围（页面滑块与解码处共用这套口径）
 *
 * | 字段 | 含义 | 范围 |
 * |---|---|---|
 * | `refract` | 折射强度（px 位移） | 1500 ~ 8000 |
 * | `c2` | 波速平方（≤0.24，稳定上限） | 0.05 ~ 0.24 |
 * | `damp` | 每帧阻尼（越接近 1 荡得越久） | 0.960 ~ 0.999 |
 * | `sigma` | 触点高斯半径（屏宽比例） | 0.020 ~ 0.150 |
 * | `level` | 倾斜时液面跟随重力强度 | 0 ~ 1 |
 */
data class LiquidTune(
    val refract: Float,
    val c2: Float,
    val damp: Float,
    val sigma: Float,
    val level: Float,
) {

    /** 编码成 SystemUI 能读的属性值；解码在 [decode]。 */
    fun encode(): String = listOf(
        refract.roundToInt(),
        (c2 * 100f).roundToInt(),
        (damp * 1000f).roundToInt(),
        (sigma * 1000f).roundToInt(),
        (level * 100f).roundToInt(),
    ).joinToString(",")

    companion object {

        /** 水波默认：高频、中等阻尼。 */
        val WATER = LiquidTune(refract = 4200f, c2 = 0.18f, damp = 0.992f, sigma = 0.055f, level = 0.35f)

        /** 果冻默认：低频、低阻尼、大触点。 */
        val JELLY = LiquidTune(refract = 7000f, c2 = 0.09f, damp = 0.997f, sigma = 0.110f, level = 0.55f)

        /** 预设「柔和」：几乎不扰原图。 */
        val SOFT = LiquidTune(refract = 2800f, c2 = 0.12f, damp = 0.986f, sigma = 0.075f, level = 0.25f)

        /** 预设「强劲」：形变大、荡得久。 */
        val STRONG = LiquidTune(refract = 6500f, c2 = 0.22f, damp = 0.996f, sigma = 0.040f, level = 0.50f)

        /** 预设索引：0 柔和 / 1 标准 / 2 强劲。 */
        fun preset(mode: Int, index: Int): LiquidTune = when (index) {
            0 -> SOFT
            2 -> STRONG
            else -> if (mode == LiquidFlag.MODE_JELLY) JELLY else WATER
        }

        /** 解析失败返回 null（调用方保持原值）。 */
        fun decode(raw: String): LiquidTune? {
            if (raw.isBlank()) return null
            val p = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (p.size < 5) return null
            return LiquidTune(
                refract = p[0].coerceIn(1000f, 9000f),
                c2 = (p[1] / 100f).coerceIn(0.05f, 0.24f),
                damp = (p[2] / 1000f).coerceIn(0.960f, 0.999f),
                sigma = (p[3] / 1000f).coerceIn(0.020f, 0.150f),
                level = (p[4] / 100f).coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * 液态壁纸的**选区**（2026-11-09，P3 的「局部」模式）。
 *
 * 目前是**软椭圆**：中心在屏幕垂直方向 [centerY]、半径 [radius]（以屏宽为单位）。
 * 椭圆外高度场被压成 0（没有液体），边界用 smoothstep 羽化 —— 观感是「一滩水停在
 * 画面某处」，比硬边 mask 自然。
 *
 * 为什么先不做任意涂抹：Shader 里本来就有 mask 通道，接一张涂抹位图只是多一条
 * provider 路由；但「涂抹编辑器」本身是一整页 UI（笔刷 / 撤销 / 预览），
 * 放在参数调节这一版里会把改动面撑得太大。椭圆先让「局部」可用，后续换位图时
 * 只改 `maskAt` 的实现，页面与属性通道不用动。
 */
data class LiquidMask(
    val on: Boolean,
    /** 椭圆中心（相对屏幕高度的比例，0 = 顶部，1 = 底部）。 */
    val centerY: Float,
    /** 椭圆半径（以屏宽为单位；竖屏上 0.35 约等于半屏宽）。 */
    val radius: Float,
) {

    fun encode(): String =
        "${if (on) 1 else 0},${(centerY * 100f).roundToInt()},${(radius * 100f).roundToInt()}"

    companion object {

        /** 全图（关闭选区）。 */
        val FULL = LiquidMask(on = false, centerY = 0.5f, radius = 0.35f)

        fun decode(raw: String): LiquidMask? {
            if (raw.isBlank()) return null
            val p = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (p.size < 3) return null
            return LiquidMask(
                on = p[0] == 1,
                centerY = (p[1] / 100f).coerceIn(0f, 1f),
                radius = (p[2] / 100f).coerceIn(0.15f, 0.60f),
            )
        }
    }
}
