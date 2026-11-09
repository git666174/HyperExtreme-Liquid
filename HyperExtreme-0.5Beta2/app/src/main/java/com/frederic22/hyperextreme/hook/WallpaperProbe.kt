package com.frederic22.hyperextreme.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 【只读探针】miwallpaper（`com.miui.miwallpaper`）的「是否同一张壁纸」判定。
 *
 * 目的（2026-10-21，用户要求）：在实现「视差覆盖桌面 + 解锁不缩放」之前，先确认
 * `WallpaperServiceController.A()/n()/b()` 在「同图 / 不同图」两组场景下的真实取值，
 * 以及 `DesktopImageEngineImpl.a0()`、`showWallpaperUnlockAnim()`、
 * `KeyguardImageEngineImpl.hideKeyguardWallpaper()` 的调用时序。
 *
 * **只读**：全部 `chain.proceed()` 后打印，不改返回值、不改状态。
 * 日志前缀 `WPROBE`（ASCII，方便 Git Bash 过滤）。
 *
 * 判定链（反编译结论，8583 对应版本）：
 * ```
 * A() = EffectUtils.e() || ( n() && b() )
 *   n(): 两张壁纸类型都支持 + 缓存位
 *   b(): 逐项比较（uri / 包名 / 文件 / 效果）
 * C(): 视频壁纸版（同一段视频 + 同效果）
 * ```
 */
internal object WallpaperProbe {

    private const val CLS_CONTROLLER = "com.miui.miwallpaper.manager.WallpaperServiceController"
    private const val CLS_DESKTOP =
        "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopImageEngineImpl"
    private const val CLS_KEYGUARD =
        "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardImageEngineImpl"

    /** 上次打印的值（只在变化时再打，避免高频刷屏）。 */
    private val lastValue = ConcurrentHashMap<String, Any?>()

    /** 每个探针的调用计数（每 200 次补一条，防"一直不变就完全看不到在跑"）。 */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    private fun log(msg: String) = HookRuntime.log("WPROBE $msg")

    private fun report(tag: String, value: Any?) {
        val prev = lastValue.put(tag, value)
        val n = counters.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
        if (prev != value || n % 200 == 0) {
            log("$tag = $value（第 $n 次）")
        }
    }

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        var ok = 0
        val controller = runCatching { Class.forName(CLS_CONTROLLER, false, loader) }.getOrNull()
        if (controller == null) {
            log("未找到 $CLS_CONTROLLER（miwallpaper 版本变了？）")
            return false
        }
        // 判定本体：无参 boolean
        for (name in listOf("A", "B", "C", "G", "n", "b")) {
            val m = runCatching { controller.getDeclaredMethod(name) }.getOrNull()
            if (m == null) {
                log("无方法 $name()（跳过）")
                continue
            }
            runCatching {
                module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        report("Controller.$name()", r)
                        return r
                    }
                })
                ok++
            }.onFailure { log("挂 Controller.$name 失败：$it") }
        }

        // 桌面引擎：a0() = 是否需要解锁动画；showWallpaperUnlockAnim() = 解锁动画入口
        val desktop = runCatching { Class.forName(CLS_DESKTOP, false, loader) }.getOrNull()
        if (desktop != null) {
            runCatching { desktop.getDeclaredMethod("a0") }.getOrNull()?.let { m ->
                runCatching {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val r = chain.proceed()
                            report("Desktop.a0()", r)
                            return r
                        }
                    })
                    ok++
                }.onFailure { log("挂 Desktop.a0 失败：$it") }
            }
            runCatching { desktop.getDeclaredMethod("showWallpaperUnlockAnim") }.getOrNull()?.let { m ->
                runCatching {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            log("Desktop.showWallpaperUnlockAnim() 被调用")
                            return chain.proceed()
                        }
                    })
                    ok++
                }.onFailure { log("挂 showWallpaperUnlockAnim 失败：$it") }
            }
        } else {
            log("未找到 $CLS_DESKTOP")
        }

        // 锁屏引擎：hideKeyguardWallpaper(boolean, int) —— 同一张时走特殊分支
        val keyguard = runCatching { Class.forName(CLS_KEYGUARD, false, loader) }.getOrNull()
        if (keyguard != null) {
            runCatching {
                keyguard.getDeclaredMethod("hideKeyguardWallpaper", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()?.let { m ->
                runCatching {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            log(
                                "Keyguard.hideKeyguardWallpaper(z=${chain.getArg(0)}, i=${chain.getArg(1)})",
                            )
                            return chain.proceed()
                        }
                    })
                    ok++
                }.onFailure { log("挂 hideKeyguardWallpaper 失败：$it") }
            }
            runCatching {
                keyguard.getDeclaredMethod(
                    "updateKeyguardWallpaperRatio",
                    Float::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                )
            }.getOrNull()?.let { m ->
                runCatching {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            log(
                                "Keyguard.updateKeyguardWallpaperRatio(f=${chain.getArg(0)}, j=${chain.getArg(1)})",
                            )
                            return chain.proceed()
                        }
                    })
                    ok++
                }.onFailure { log("挂 updateKeyguardWallpaperRatio 失败：$it") }
            }
        } else {
            log("未找到 $CLS_KEYGUARD")
        }

        log("安装完成：$ok 个挂点")
        return ok > 0
    }
}
