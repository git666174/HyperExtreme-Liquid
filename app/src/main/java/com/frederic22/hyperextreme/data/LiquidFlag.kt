package com.frederic22.hyperextreme.data

import android.util.Log

/**
 * 液态壁纸开关的**跨进程传递**（2026-11-09 建立）。
 *
 * ## 为什么又是系统属性
 *
 * 与 [SpatialFlag] / [RasterFlag] 完全同一个理由（2026-10-23 真机实测）：
 * SystemUI 进程里 `getRemotePreferences` 是陈旧快照、`openRemoteFile` 读不到任何文件。
 * 属性所有进程都能读、不需要 Context —— 这是唯一被验证过的通道。
 *
 * ## 一组属性必须同批推送
 *
 * 渲染器启动时把 `active / key / scope / mode` 读成**一组**。任何一项晚半拍，
 * 装配就会用到新旧混合的参数（比如 mode 刚切、scope 还是旧的）。所以四条的写入
 * 走同一条 `su setprop` 脚本，由 shell 保证先后（与 [SpatialFlag.push] 一致）。
 *
 * ## 语义
 *
 * | 属性 | 值 | 含义 |
 * |---|---|---|
 * | [PROP_ACTIVE] | 0 / 1 | 是否启用 |
 * | [PROP_KEY] | 16 位 hex / 空 | 素材键（**只用于变化检测**；当前版本图源=系统壁纸） |
 * | [PROP_SCOPE] | 0 / 1 / 2 | 锁屏 / 桌面 / 全局（与 [MaskPrefs.SPATIAL_SCOPE_LOCK] 同值） |
 * | [PROP_MODE] | 0 / 1 | 水波 / 果冻 |
 *
 * **生效中改属性不会热更渲染**：`LiquidRenderer.start()` 的判据是四元组没变就直接返回，
 * 要换图 / 换模式必须让四元组之一变化（或先关再开）。
 */
object LiquidFlag {

    /** 1 = 生效，0 = 关闭。 */
    const val PROP_ACTIVE = "debug.hyperextreme.liquid_active"

    /** 素材键（16 位 hex）；空 = 直接用系统当前壁纸。 */
    const val PROP_KEY = "debug.hyperextreme.liquid_key"

    /** 生效范围：0 锁屏 / 1 桌面 / 2 全局。 */
    const val PROP_SCOPE = "debug.hyperextreme.liquid_scope"

    /** 物理模式：[MODE_WATER] 水波 / [MODE_JELLY] 果冻。 */
    const val PROP_MODE = "debug.hyperextreme.liquid_mode"

    /** 可调参数（`refract,c2,damp,sigma,level` 定点编码，见 [LiquidTune]）。 */
    const val PROP_TUNE = "debug.hyperextreme.liquid_tune"

    /** 选区（`on,stamp`，见 [LiquidMask]）。 */
    const val PROP_MASK = "debug.hyperextreme.liquid_mask"

    /** 质感档位：0 柔和 / 1 自然 / 2 真实（决定仿真分辨率与光学复杂度）。 */
    const val PROP_QUALITY = "debug.hyperextreme.liquid_quality"

    /**
     * 渲染器是否真的在出帧（1 = 已挂上并画过）。
     *
     * 用途与 [SpatialFlag.PROP_RENDERING] 相同：给后续「系统壁纸淡出」之类的联动
     * 提供硬前置 —— 我们没在显示时压系统壁纸 alpha 就是黑屏。
     */
    const val PROP_RENDERING = "debug.hyperextreme.liquid_rendering"

    const val MODE_WATER = 0
    const val MODE_JELLY = 1

    const val QUALITY_SOFT = 0
    const val QUALITY_NATURAL = 1
    const val QUALITY_REAL = 2

    /** 合法档位白名单（越界回落锁屏）。 */
    fun normalizeScope(scope: Int): Int = if (scope in 0..2) scope else MaskPrefs.SPATIAL_SCOPE_LOCK

    fun normalizeMode(mode: Int): Int = if (mode == MODE_JELLY) MODE_JELLY else MODE_WATER

    fun normalizeQuality(quality: Int): Int = if (quality in 0..2) quality else QUALITY_NATURAL

    /** 异步写属性；root 不可用时静默失败（界面状态仍然正确，只是不生效）。 */
    fun push(
        active: Boolean,
        key: String?,
        scope: Int,
        mode: Int,
        tune: LiquidTune = LiquidTune.WATER,
        mask: LiquidMask = LiquidMask.FULL,
        quality: Int = QUALITY_NATURAL,
    ) {
        Thread {
            val r = runCatching {
                RootShell.run(
                    "setprop $PROP_ACTIVE ${if (active) 1 else 0}; " +
                        "setprop $PROP_KEY ${key.orEmpty()}; " +
                        "setprop $PROP_SCOPE ${normalizeScope(scope)}; " +
                        "setprop $PROP_MODE ${normalizeMode(mode)}; " +
                        "setprop $PROP_TUNE ${tune.encode()}; " +
                        "setprop $PROP_MASK ${mask.encode()}; " +
                        "setprop $PROP_QUALITY ${normalizeQuality(quality)}",
                )
            }.getOrNull()
            if (r == null || !r.ok) {
                Log.w(
                    "HyperExtreme",
                    "LiquidFlag: setprop 失败（${r?.output?.take(160) ?: "异常"}）",
                )
            }
        }.start()
    }

    /**
     * 「先关 → 等一个轮询周期 → 再开」= 强制 SystemUI 重建渲染器。
     *
     * 与 [SpatialFlag.pushRestart] 同一个用途：四元组没变时 `start()` 会直接返回，
     * 换了素材但 key 不变就什么都不会发生。一条脚本里写完，避免两次 setprop 乱序。
     */
    fun pushRestart(
        active: Boolean,
        key: String?,
        scope: Int,
        mode: Int,
        tune: LiquidTune = LiquidTune.WATER,
        mask: LiquidMask = LiquidMask.FULL,
        quality: Int = QUALITY_NATURAL,
    ) {
        Thread {
            val script = "setprop $PROP_ACTIVE 0; " +
                "sleep 1.3; " +
                "setprop $PROP_ACTIVE ${if (active) 1 else 0}; " +
                "setprop $PROP_KEY ${key.orEmpty()}; " +
                "setprop $PROP_SCOPE ${normalizeScope(scope)}; " +
                "setprop $PROP_MODE ${normalizeMode(mode)}; " +
                "setprop $PROP_TUNE ${tune.encode()}; " +
                "setprop $PROP_MASK ${mask.encode()}; " +
                "setprop $PROP_QUALITY ${normalizeQuality(quality)}"
            val r = runCatching { RootShell.run(script) }.getOrNull()
            if (r == null || !r.ok) {
                Log.w(
                    "HyperExtreme",
                    "LiquidFlag: pushRestart 失败（${r?.output?.take(160) ?: "异常"}）",
                )
            }
        }.start()
    }
}
