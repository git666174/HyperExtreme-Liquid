package com.frederic22.hyperextreme.data

/**
 * 光栅壁纸开关的**跨进程传递**（2026-11-04）。
 *
 * 与 [SpatialFlag] 同一套路 —— SystemUI 里读模块 prefs 不可靠（实测 `getRemotePreferences`
 * 是陈旧快照、`openRemoteFile` 读不到文件），所以只认**系统属性**。`debug.` 前缀不持久化，
 * 重启手机后会丢，靠 `App` 启动时重新 push 一次补回。
 *
 * 三条一起推、一起读：渲染器装配时要拿到**一致的一组**（active / key / scope），
 * 分两个通道就会出现"active 已刷新、scope 还是旧的"半新半旧状态。
 *
 * ⚠️ 与空间壁纸的属性**完全独立**（`raster_*` vs `spatial_*`）：两套壁纸互斥，
 * 但共用同一条 GL 挂载思路，各自一条轮询、各自一个渲染器。
 */
object RasterFlag {

    /** 1 = 生效，0 = 关闭。 */
    const val PROP_ACTIVE = "debug.hyperextreme.raster_active"

    /** 正在应用的组合 id（`files/raster/<id>/` 的目录名）。关闭时置空。 */
    const val PROP_KEY = "debug.hyperextreme.raster_key"

    /** 生效范围：0 锁屏 / 1 桌面 / 2 全局（与 [MaskPrefs.rasterScope] 同值域）。 */
    const val PROP_SCOPE = "debug.hyperextreme.raster_scope"

    /**
     * 是否启用「光栅壁纸锁屏景深」：1 = 开，0 = 关（2026-11-04）。
     *
     * 语义与空间壁纸的 `spatial_lockdepth` **完全一致**：渲染器在 [startInternal] 开头
     * **只读一次**，生效中改开关只写 prefs，下一次「应用」才随属性一起下发。
     */
    const val PROP_LOCKDEPTH = "debug.hyperextreme.raster_lockdepth"

    /**
     * 「最大幅度角度」（度，15..45，默认 25）—— 2026-11-04。
     *
     * 与 [PROP_SCOPE] 同语义：渲染器启动时**只读一次**，生效中改只写 prefs。
     * 读不到 / 非法时按 25（= 加这个选项之前的行为）。
     */
    const val PROP_PAN_DEG = "debug.hyperextreme.raster_pan_deg"

    /**
     * 「停顿时就近归位」：1 = 开（默认），0 = 关（2026-11-04）。
     *
     * 关掉后静止不再补完当前一擦，用户可以**停在两张之间**的过渡态。
     * 与 [PROP_PAN_DEG] 同语义：渲染器启动时**只读一次**，读不到按开处理。
     */
    const val PROP_SETTLE = "debug.hyperextreme.raster_settle"

    /** 渲染器是否**真的**在跑（1 = 已挂上并出过帧）。排查「点了应用但没反应」用。 */
    const val PROP_RENDERING = "debug.hyperextreme.raster_rendering"

    /** 异步写属性；root 不可用时静默失败（界面状态仍然正确，只是不生效）。 */
    fun push(
        active: Boolean,
        key: String?,
        scope: Int,
        lockDepth: Boolean = false,
        panDeg: Int = 25,
        settle: Boolean = true,
    ) {
        val script = "setprop $PROP_ACTIVE ${if (active) 1 else 0}; " +
            "setprop $PROP_KEY ${key.orEmpty()}; " +
            "setprop $PROP_SCOPE $scope; " +
            "setprop $PROP_LOCKDEPTH ${if (lockDepth) 1 else 0}; " +
            "setprop $PROP_PAN_DEG $panDeg; " +
            "setprop $PROP_SETTLE ${if (settle) 1 else 0}"
        Thread {
            val r = runCatching { RootShell.run(script) }.getOrNull()
            if (r == null || !r.ok) {
                android.util.Log.w("HyperExtreme", "RasterFlag: setprop 失败（${r?.output?.take(160) ?: "异常"}）")
            }
        }.start()
    }

    /** 渲染器自报"正在出帧"（由 `RasterRenderer` 调；属性只用于诊断）。 */
    internal fun pushRendering(on: Boolean) {
        Thread {
            runCatching { RootShell.run("setprop $PROP_RENDERING ${if (on) 1 else 0}") }
        }.start()
    }
}
