package com.frederic22.hyperextreme.hook

import com.frederic22.hyperextreme.data.MaskPrefs
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * **把系统壁纸的真实透明度桥给 SystemUI**（2026-10-28，跑在 `com.miui.miwallpaper` 进程）。
 *
 * ## 为什么需要
 *
 * 空间壁纸的 GL 层挂在锁屏壁纸的 leash 下。系统对这条 leash 只做 `setMatrix`（缩放），
 * **不带 alpha**；真正的淡出是 miwallpaper 对自己那层 Surface 做的 ——
 * 我们和它是**兄弟层**，所以解锁时我们的图不会跟着淡，直到 leash 不再呈现 = 硬切。
 *
 * 之前的做法是自己按固定时长猜（`SpatialRenderer.fadeOutOnUnlock`），起点和终点都能靠
 * 系统状态位对齐，但**中段曲线永远是猜的**。这里把真值直接接过来，不用再猜。
 *
 * ## 真值在哪（逆向实证）
 *
 * `com.miui.miwallpaper.wallpaperservice.UniversalWallpaper$UniversalEngine#h(float, SurfaceControl)`：
 *
 * ```java
 * SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
 * transaction.setAlpha(surfaceControl, f3);       // ← 系统给壁纸设的真实 alpha
 * transaction.apply();
 * Log: "updateAlphaBySC: SurfaceControl apply end alpha=" + f3
 * ```
 *
 * ⇒ 这是**唯一**给壁纸 Surface 设 alpha 的地方（全模块逆向里 `setAlpha` 只有这一处带 SurfaceControl）。
 *
 * ## 怎么跨进程
 *
 * 写系统属性 `debug.hyperextreme.spatial_wp_alpha`（int，0..1000 ≈ alpha×1000）：
 * 所有进程都能读、不需要 Context / 框架通道，与 `SpatialFlag` 同一套路。
 *
 * * 写：`SystemProperties.set`（反射，**不 fork**，共享内存级）；
 * * 读：SystemUI 侧 `HookRuntime.systemProperty`；
 * * **节流**：变化 < 5‰ 不写，避免每帧都抢属性锁。
 *
 * ## 开关
 *
 * 空间壁纸生效中、且 scope 不是「桌面」才写（桌面档我们的图在桌面 leash 上，解锁不动它）。
 * 读不到开关一律**不写** —— SystemUI 侧读到空值会自然退回"时间驱动 + 状态位兜底"。
 */
internal object SpatialWallpaperAlpha {

    private const val CLS_ENGINE =
        "com.miui.miwallpaper.wallpaperservice.UniversalWallpaper\$UniversalEngine"

    /** 系统壁纸真实 alpha ×1000。空 = 没写过（SystemUI 侧按"未知"处理）。 */
    const val PROP_ALPHA = "debug.hyperextreme.spatial_wp_alpha"

    private val logged = ConcurrentHashMap<String, Boolean>()

    @Volatile private var last = -1

    /** 改参数失败一次后永久停用（宁可只桥真值，也不冒"两次执行"的风险）。 */
    @Volatile private var overrideDisabled = false

    /**
     * 可以压 0 吗？两个条件都要：
     *
     * 1. **我们的图真的在显示**（SystemUI 渲染器 `startInternal` 成功后写 `rendering=1`）；
     * 2. **锁屏在前台**（`spatial_keyguard=1`）。
     *
     * 第 2 条是 2026-10-29 加的保险：桌面态也压 0 的话，一旦我们的桌面那份画慢了/没画好，
     * 露出来的就是一片黑；而锁屏态压 0 才有意义（消掉「我们的图淡出时和系统壁纸叠加」的重影）。
     */
    private fun rendering(): Boolean {
        if (HookRuntime.systemProperty(
                com.frederic22.hyperextreme.data.SpatialFlag.PROP_RENDERING,
            ).trim() != "1"
        ) {
            return false
        }
        // **注意取反**：只有**明确知道在桌面**（"0"）才不压。
        // 属性为空（刚开机 / SystemUI 重启后 `notifyKeyguardState` 因 mShowing 未变而早退）
        // 必须按"锁屏"处理 —— 否则锁屏重影会回归。
        return HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.SpatialFlag.PROP_KEYGUARD,
        ).trim() != "0"
    }

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_ENGINE, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_ENGINE")
            return false
        }
        // 按**签名**找，不认混淆名（`h` 是 R8 改过的）：
        // 参数 = (float alpha, SurfaceControl sc)
        val targets = cls.declaredMethods.filter {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                it.parameterTypes[1].name.endsWith("SurfaceControl")
        }
        if (targets.isEmpty()) {
            log("未找到 (float, SurfaceControl) 方法")
            return false
        }
        var ok = 0
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // ⚠️ 不假定下标：`getArgs()` 可能带接收者。按类型取第一个 Float。
                        val args = chain.getArgs()
                        val idx = args.indexOfFirst { it is Float }
                        if (idx < 0) return chain.proceed()
                        if (!enabled()) return chain.proceed()
                        val alpha = args[idx] as Float
                        // 1) **先把真值桥出去**（无论后面压不压，SystemUI 都要这个数）
                        publish(alpha)
                        // 2) **再把系统壁纸压 0** —— 只在我们的图真的在显示时
                        if (overrideDisabled || !rendering()) return chain.proceed()
                        if (args.size != 2) {
                            // 参数结构不认识（可能带接收者）⇒ 宁可不压，只桥真值
                            if (logged.putIfAbsent("argc", true) == null) {
                                log("参数个数 ${args.size} != 2 ⇒ 不改参数（只桥真值）")
                            }
                            return chain.proceed()
                        }
                        val newArgs = args.toMutableList().also { it[idx] = 0f }.toTypedArray()
                        return try {
                            chain.proceed(newArgs)
                        } catch (t: Throwable) {
                            // 抛异常说明原方法没执行 —— 安全回退，并**永久停止尝试**
                            overrideDisabled = true
                            log("改参数失败，永久退回只桥真值：${t.javaClass.simpleName}: ${t.message}")
                            chain.proceed()
                        }
                    }
                })
                ok++
            }.onFailure { log("挂 ${m.name} 失败：$it") }
        }
        if (ok > 0) {
            log("已挂上 $ok 个 (float, SurfaceControl) → 写 $PROP_ALPHA")
        }
        return ok > 0
    }

    /** 写属性（节流 5‰）。 */
    private fun publish(alpha: Float) {
        val v = (alpha.coerceIn(0f, 1f) * 1000).toInt()
        val prev = last
        if (prev >= 0 && kotlin.math.abs(v - prev) < 5) return
        last = v
        runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("set", String::class.java, String::class.java)
                .invoke(null, PROP_ALPHA, v.toString())
        }.onFailure {
            if (logged.putIfAbsent("setprop", true) == null) {
                log("写属性失败（${it.javaClass.simpleName}: ${it.message}）—— SystemUI 侧会退回时间驱动")
            }
        }
    }

    /** 与 SystemUI 侧同一判据：生效中且不是「桌面」档。读不到属性按关处理。 */
    private fun enabled(): Boolean {
        // 空间壁纸生效中
        val scope = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.SpatialFlag.PROP_SCOPE,
        ).trim().toIntOrNull() ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        val spatialOn = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.SpatialFlag.PROP_ACTIVE,
        ).trim() == "1" && scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        if (spatialOn) return true
        // **光栅壁纸也要**（2026-11-04）：它同样是 leash 的兄弟层，解锁时不会跟着淡，
        // 需要这条真值来对齐淡出曲线。
        val rScope = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_SCOPE,
        ).trim().toIntOrNull() ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        val rasterOn = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_ACTIVE,
        ).trim() == "1" && rScope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        return rasterOn
    }

    private fun log(msg: String) = HookRuntime.log("SPATIAL 壁纸alpha $msg")
}
