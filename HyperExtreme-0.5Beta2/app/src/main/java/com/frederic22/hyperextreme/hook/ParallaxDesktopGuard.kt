package com.frederic22.hyperextreme.hook

import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 「视差效果覆盖桌面」的**第一步：解锁动画抑制**（2026-10-21）。
 *
 * ## 为什么需要它
 *
 * 用户目标：开关打开后，从锁屏解锁到桌面时**不出现缩放/模糊**，看起来像同一张壁纸原地不动。
 * 原计划是「让系统以为两张壁纸相同」（`WallpaperServiceController.A()`），但探针实测（v1.3.12-probe）：
 * 即使真的使用同一张图、重启进程，`A()` / `b()` 仍然恒为 false —— 判定层不可用。
 * 所以改为**直接拦动画**，不依赖系统判定。
 *
 * ## 抑制清单
 *
 * | 进程 | 挂点 | 效果 |
 * | --- | --- | --- |
 * | miwallpaper | `KeyguardImageEngineImpl.updateKeyguardWallpaperRatio(float,long)` | 滑动解锁时锁屏壁纸不再跟随比例缩放（实测高频调用，是大头） |
 * | miwallpaper | `DesktopImageEngineImpl.showWallpaperUnlockAnim()` | 桌面壁纸的解锁动画整体跳过 |
 * | SystemUI | `KeyguardPanelViewController.setWallpaperScale(float, …)` | 去掉 1.05 缩放 |
 * | SystemUI | `setWallpaperZoom(float)` | 去掉 0.75 zoom |
 * | SystemUI | `startKeyguardWallpaperBlurAnim(boolean)` | 去掉解锁模糊 |
 *
 * **不动** `hideKeyguardWallpaper` —— 它负责真正隐藏锁屏壁纸，短接会露图。
 *
 * ## 开关读取
 *
 * 每次事件现读 `HookRuntime.remoteBoolean(KEY_PARALLAX_COVER_DESKTOP)`（框架通道 + prefs 原文），
 * 不缓存；开关默认关（读不到也按关处理）。短接日志前缀 `PARAX`（ASCII，方便过滤）。
 */
internal object ParallaxDesktopGuard {

    private const val CLS_CONTROLLER = "com.miui.miwallpaper.manager.WallpaperServiceController"
    private const val CLS_KEYGUARD_ENGINE =
        "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardImageEngineImpl"
    private const val CLS_DESKTOP_ENGINE =
        "com.miui.miwallpaper.wallpaperservice.impl.desktop.DesktopImageEngineImpl"

    /** 开关的系统属性通道（与 `ParallaxCoverFlag.PROP` 必须一致）。 */
    private const val PROP_FLAG = "debug.hyperextreme.parallax_cover"

    /** 每个挂点只在第一次真正短接时打一条日志，避免 updateKeyguardWallpaperRatio 高频刷屏。 */
    private val logged = ConcurrentHashMap<String, Boolean>()

    private fun enabled(): Boolean {
        // 属性通道最可靠：miwallpaper 里 remotePreferences / openRemoteFile / provider 全不通
        // （v1.3.15 诊断：原文 -1、remote null），系统属性是唯一不依赖 Context 的载体。
        val value = flagFromProp()
            ?: enabledFromProvider()
            ?: HookRuntime.remoteBoolean(MaskPrefs.KEY_PARALLAX_COVER_DESKTOP)
            ?: false
        if (lastEnabled.getAndSet(value) != value) {
            log("开关读取 -> $value")
        }
        return value
    }

    /** 属性读取的 500ms 缓存（`enabled()` 每帧调用，命令兜底不能每次都 fork）。 */
    private val propCache = java.util.concurrent.atomic.AtomicReference<Pair<Long, Boolean?>?>(null)
    private const val PROP_CACHE_MS = 500L

    /** 读 `debug.hyperextreme.parallax_cover`；返回 null 表示读不到（交给兜底通道）。 */
    private fun flagFromProp(): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        propCache.get()?.let { (at, v) -> if (now - at < PROP_CACHE_MS) return v }
        val raw = reflectProp() ?: execProp()
        if (logged.putIfAbsent("propValue", true) == null) {
            log("属性原文=\"$raw\"（key=$PROP_FLAG）")
        }
        val value = when (raw) {
            "1" -> true
            "0" -> false
            else -> null
        }
        propCache.set(now to value)
        return value
    }

    /** 反射 `SystemProperties.get`（hidden API，可能被拦）。 */
    private fun reflectProp(): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, PROP_FLAG, "") as? String
    } catch (t: Throwable) {
        if (logged.putIfAbsent("propReflectFail", true) == null) {
            log("属性反射失败：${t.javaClass.simpleName}: ${t.message}，改用 getprop")
        }
        null
    }

    /** 命令兜底：`getprop <key>`（普通 app 可用）。 */
    private fun execProp(): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", PROP_FLAG))
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        text
    }.getOrNull()

    private val lastEnabled = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)

    /** provider 读取的 500ms 缓存：`updateKeyguardWallpaperRatio` 每帧都会调 [enabled]。 */
    private val providerCache =
        java.util.concurrent.atomic.AtomicReference<Pair<Long, Boolean>?>(null)
    private const val PROVIDER_CACHE_MS = 500L

    private fun enabledFromProvider(): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        providerCache.get()?.let { (at, v) -> if (now - at < PROVIDER_CACHE_MS) return v }
        val context = HookRuntime.appContext() ?: return null
        val bundle = runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_PARALLAX_COVER,
                null,
                null,
            )
        }.getOrNull() ?: return null
        val value = bundle.getBoolean(MaskProvider.KEY_PARALLAX_COVER_DESKTOP, false)
        providerCache.set(now to value)
        return value
    }

    private fun log(msg: String) = HookRuntime.log("PARAX $msg")

    /** 装载一个「开关打开就不执行」的挂点（全部是 void 方法）。 */
    private fun mute(module: XposedModule, method: Method, tag: String): Boolean =
        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!enabled()) return chain.proceed()
                    if (logged.putIfAbsent(tag, true) == null) {
                        log("已抑制 $tag（视差效果覆盖桌面=开）")
                    }
                    return null
                }
            })
            true
        }.getOrElse {
            log("挂 $tag 失败：$it")
            false
        }

    /** 沿类层次逐级查找（`declaredMethods` 只看本类，方法可能声明在父类）。 */
    private fun findMethod(cls: Class<*>, name: String, arity: Int): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == arity }
                ?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** miwallpaper 进程：锁屏 / 桌面两个壁纸引擎。 */
    /** 把实例里所有 `SparseBooleanArray` 字段的 key=3（is same image wallpaper）写成 true。 */
    private fun forceSame(target: Any): Int {
        var touched = 0
        for (field in target.javaClass.declaredFields) {
            if (field.type != android.util.SparseBooleanArray::class.java) continue
            field.isAccessible = true
            val flags = field.get(target) as? android.util.SparseBooleanArray ?: continue
            flags.put(3, true)
            touched++
        }
        return touched
    }

    fun installWallpaper(module: XposedModule, loader: ClassLoader): Boolean {
        var ok = 0

        // 【真正的「同一张」判据，2026-10-21】
        // `WallpaperServiceController.d()` 里：
        //   zC = BitmapSignatureManager.a(1).c(BitmapSignatureManager.a(2));  // 锁屏 vs 桌面的位图签名
        //   f2167o.put(3, zC);                                                // 写入「is same image wallpaper」
        // `n()` / `A()` / `B()` / `dump` 全部读这个位；A() 之所以恒 false，就是因为这个位没人写 true。
        // 开关打开时，在 d() 执行后把这个位强制改成 true —— 比 hook A() 精准（A 根本不被调用），
        // 也比短接单个方法覆盖更全（DesktopImageEngineImpl.a0() 也读它）。
        val controller = runCatching { Class.forName(CLS_CONTROLLER, false, loader) }.getOrNull()
        if (controller == null) {
            log("未找到 $CLS_CONTROLLER")
        } else {
            findMethod(controller, "d", 0)?.let { m ->
                runCatching {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            if (!enabled()) return result
                            runCatching {
                                val touched = forceSame(chain.getThisObject())
                                if (logged.putIfAbsent("forceSame", true) == null) {
                                    log("d(): isSameImageWallpaper(key=3) 已强制 true（$touched 个字段）")
                                }
                            }.onFailure { log("改同图标志失败：$it") }
                            return result
                        }
                    })
                    ok++
                }.onFailure { log("挂 d() 失败：$it") }
            } ?: log("无 d() 方法")
        }
        // 诊断（一次性）：prefs 原文是否读得到、开关真实值 —— 用来区分「文件通道不可用」
        // 和「开关确实关着」这两种完全不同的失效原因。
        val text = HookRuntime.remotePrefsTextOrNull()
        log(
            "prefs 诊断：原文=${text?.length ?: -1} 字符，" +
                "含 key=${text?.contains(MaskPrefs.KEY_PARALLAX_COVER_DESKTOP)}，" +
                "remote=${HookRuntime.remoteBoolean(MaskPrefs.KEY_PARALLAX_COVER_DESKTOP)}，" +
                "provider=${enabledFromProvider()}（install 时 appContext 可能未就绪）",
        )

        val keyguard = runCatching { Class.forName(CLS_KEYGUARD_ENGINE, false, loader) }.getOrNull()
        if (keyguard == null) {
            log("未找到 $CLS_KEYGUARD_ENGINE")
        } else {
            findMethod(keyguard, "updateKeyguardWallpaperRatio", 2)?.let {
                if (mute(module, it, "Keyguard.updateKeyguardWallpaperRatio")) ok++
            } ?: log("无 updateKeyguardWallpaperRatio(float,long)")
        }

        val desktop = runCatching { Class.forName(CLS_DESKTOP_ENGINE, false, loader) }.getOrNull()
        if (desktop == null) {
            log("未找到 $CLS_DESKTOP_ENGINE")
        } else {
            findMethod(desktop, "showWallpaperUnlockAnim", 0)?.let {
                if (mute(module, it, "Desktop.showWallpaperUnlockAnim")) ok++
            } ?: log("无 showWallpaperUnlockAnim()")
        }

        // `d()` 只在壁纸变化时调用，进程刚起来时标志还是初始 false。
        // 开关打开时主动每 2 秒试着拿单例设一次，成功就退出（之后靠 d() hook 维持）。
        if (controller != null) {
            Thread {
                repeat(15) {
                    Thread.sleep(2000)
                    if (!enabled()) return@Thread
                    val instance = runCatching {
                        controller.getMethod("l").invoke(null)
                    }.getOrNull() ?: return@repeat
                    val touched = forceSame(instance)
                    if (touched > 0) {
                        log("主动设置 isSameImageWallpaper=true（$touched 个字段）")
                        return@Thread
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        log("miwallpaper 侧安装完成：$ok 个挂点")
        return ok > 0
    }
}
