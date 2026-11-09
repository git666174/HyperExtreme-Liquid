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
    /** 平滑度 0..1：越大越「稠」，高频杂波被压得越狠（粘度 + 梯度采样宽度）。 */
    val smooth: Float = 0.55f,
) {

    /** 编码成 SystemUI 能读的属性值；解码在 [decode]。 */
    fun encode(): String = listOf(
        refract.roundToInt(),
        (c2 * 100f).roundToInt(),
        (damp * 1000f).roundToInt(),
        (sigma * 1000f).roundToInt(),
        (level * 100f).roundToInt(),
        (smooth * 100f).roundToInt(),
    ).joinToString(",")

    companion object {

        /** 水波默认：高频、中等阻尼。 */
        val WATER = LiquidTune(refract = 1600f, c2 = 0.14f, damp = 0.983f, sigma = 0.085f, level = 0.25f, smooth = 0.60f)

        /** 果冻默认：低频、低阻尼、大触点。 */
        val JELLY = LiquidTune(refract = 4800f, c2 = 0.07f, damp = 0.990f, sigma = 0.140f, level = 0.45f, smooth = 0.45f)

        /** 预设「柔和」：几乎不扰原图。 */
        val SOFT = LiquidTune(refract = 1000f, c2 = 0.10f, damp = 0.975f, sigma = 0.120f, level = 0.15f, smooth = 0.80f)

        /** 预设「强劲」：形变大、荡得久。 */
        val STRONG = LiquidTune(refract = 3000f, c2 = 0.20f, damp = 0.990f, sigma = 0.060f, level = 0.40f, smooth = 0.30f)

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
                refract = p[0].coerceIn(500f, 9000f),
                c2 = (p[1] / 100f).coerceIn(0.05f, 0.24f),
                damp = (p[2] / 1000f).coerceIn(0.960f, 0.999f),
                sigma = (p[3] / 1000f).coerceIn(0.020f, 0.150f),
                level = (p[4] / 100f).coerceIn(0f, 1f),
                smooth = if (p.size >= 6) (p[5] / 100f).coerceIn(0f, 1f) else 0.55f,
            )
        }
    }
}

/**
 * 液态壁纸的**选区**（2026-11-09；同日从椭圆参数改成涂抹遮罩）。
 *
 * `on = true` 时渲染器通过 provider 拉 `liquid/<key>/mask`（页面里全屏涂抹生成，
 * 白色 = 有液体），按屏幕 uv 采样。
 *
 * `stamp` 是「这次涂抹的版本号」（保存时间秒）。渲染器在 SystemUI 进程里看不到
 * 模块文件，没法比对 mtime，所以由页面把版本号塞进属性：值一变就重新拉一次遮罩。
 */
data class LiquidMask(
    val on: Boolean,
    /** 涂抹保存时间戳（秒）。页面每次保存都换一个值，渲染器据此重新拉遮罩。 */
    val stamp: Int = 0,
) {

    fun encode(): String = "${if (on) 1 else 0},$stamp"

    companion object {

        /** 全图（关闭选区）。 */
        val FULL = LiquidMask(on = false, stamp = 0)

        fun decode(raw: String): LiquidMask? {
            if (raw.isBlank()) return null
            val p = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (p.isEmpty()) return null
            return LiquidMask(on = p[0] == 1, stamp = p.getOrElse(1) { 0 })
        }
    }
}
