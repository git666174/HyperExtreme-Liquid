package com.frederic22.hyperextreme.hook

import android.graphics.Matrix
import android.os.SystemClock
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 桌面壁纸层的视差变换（2026-10-21，「视差效果覆盖桌面」第二步）。
 *
 * ## 为什么是这一层
 *
 * 桌面壁纸是 system_server 管的独立 SurfaceControl（`WallpaperWindowToken`），
 * 由 `com.miui.miwallpaper` 的 ImageWallpaper 绘制，**不在桌面进程里**。
 * SystemUI 自己就在用同一条通道做解锁动画（`KeyguardPanelViewController.setWallpaperScale`）：
 *
 * ```java
 * WindowManagerGlobal.getInstance().getWallpaperByShowWhenLocked(0, false).leash
 *   → SurfaceControl.Transaction.setMatrix(...)
 * ```
 *
 * `canShowWhenLocked=false` 拿到的就是**桌面壁纸**（true 是锁屏那张）。
 *
 * ## 为什么全反射
 *
 * 这些 API（`getWallpaperByShowWhenLocked` / `WallpaperAnimationTarget.leash` /
 * `Transaction.setMatrix`）都是 @hide，模块编译用的 SDK 里没有，只能反射。
 * Method/Field 只解析一次并缓存；leash 每秒最多重新取一次（壁纸可能被换）。
 */
internal object ParallaxDesktopLeash {

    private const val CLS_WMG = "android.view.WindowManagerGlobal"
    private const val CLS_TX = "android.view.SurfaceControl\$Transaction"
    private const val CLS_SC = "android.view.SurfaceControl"

    private var getInstance: Method? = null
    private var getWallpaper: Method? = null
    private var leashField: Field? = null
    private var txCtor: java.lang.reflect.Constructor<*>? = null
    private var setMatrix: Method? = null
    private var applyTx: Method? = null
    private var showTx: Method? = null

    /** 上次取 leash 时用的 `showWhenLocked` 参数（缓存一致性用）。 */
    private var leashShowWhenLocked: Boolean? = null

    private var leash: Any? = null
    private var leashAt = 0L
    private var logged = false

    /** leash 取不到的次数（诊断用，节流打印）。 */
    private var leashMissTick = 0

    /** 上一次校验父层的时间（已挂载时 1s 一次，避免每帧 binder 调用）。 */
    private var lastLeashCheckAt = 0L
    private const val LEASH_CHECK_INTERVAL_MS = 1000L

    /** 壁纸 layer 的 buffer 尺寸（缓存；换壁纸 / 转屏后由 [currentLeash] 刷新时一起失效）。 */
    private var surfaceW = 0
    private var surfaceH = 0

    /**
     * 反射取 `SurfaceControl#getWidth/getHeight`（API 30+）。
     *
     * 拿不到就返回 null，调用方退回屏幕尺寸 —— 那种情况下 pivot 会偏，
     * 但至少不会崩（实测桌面 surface 比屏幕小，必须用真实尺寸才对得上锁屏观感）。
     */
    private fun surfaceSize(sc: Any): Pair<Int, Int>? {
        if (surfaceW > 0 && surfaceH > 0) return surfaceW to surfaceH
        val resolved = runCatching {
            val w = sc.javaClass.getMethod("getWidth").invoke(sc) as Int
            val h = sc.javaClass.getMethod("getHeight").invoke(sc) as Int
            if (w > 0 && h > 0) w to h else null
        }.getOrNull()
        if (resolved != null) {
            surfaceW = resolved.first
            surfaceH = resolved.second
        }
        return resolved
    }

    private fun log(msg: String) = HookRuntime.log("PDL $msg")

    private fun ensureApi(loader: ClassLoader): Boolean {
        if (setMatrix != null && getWallpaper != null) return true
        val wmg = runCatching { Class.forName(CLS_WMG, false, loader) }.getOrNull() ?: return false
        val tx = runCatching { Class.forName(CLS_TX, false, loader) }.getOrNull() ?: return false
        val sc = runCatching { Class.forName(CLS_SC, false, loader) }.getOrNull() ?: return false
        getInstance = runCatching { wmg.getMethod("getInstance") }.getOrNull()
        getWallpaper = runCatching {
            wmg.getMethod(
                "getWallpaperByShowWhenLocked",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
        txCtor = runCatching { tx.getConstructor() }.getOrNull()
        setMatrix = runCatching {
            tx.getMethod("setMatrix", sc, Matrix::class.java, FloatArray::class.java)
        }.getOrNull()
        applyTx = runCatching { tx.getMethod("apply") }.getOrNull()
        showTx = runCatching { tx.getMethod("show", sc) }.getOrNull()
        return getInstance != null && getWallpaper != null && txCtor != null && setMatrix != null && applyTx != null
    }

    /**
     * 强制让父层可见。
     *
     * **为什么必须**：`getWallpaperByShowWhenLocked(0, false)` 拿到的是
     * `#41 ImageWallpaper`，而它在桌面态经常是 **`flags:1`（隐藏）** —— 桌面实际显示的是
     * 另一个 token（`#42 MiuiKeyguardPictorialWallpaper`）。层挂在隐藏的父层下 ⇒
     * **建了也看不见**，表现就是「桌面画面固定不动、调什么都没反应」，
     * 只能重启 miwallpaper 让壁纸服务重建、把 #41 重新变可见。
     * 这里在建层后对父层 `Transaction.show()` 一次，省掉那次重启。
     * 只在 SystemUI 内、桌面态执行（`applyDesktopLeash` 已按状态分流）。
     */
    private fun showLeash(sc: Any) {
        runCatching {
            val show = showTx ?: return
            val t = txCtor!!.newInstance()
            show.invoke(t, sc)
            applyTx!!.invoke(t)
        }.onFailure { log("父层 show 失败：$it") }
    }

    /** 现场可切换：`setprop debug.hyperextreme.parallax_leash_kwl 1` 用 `showWhenLocked=true` 的 token。 */
    private fun leashShowWhenLockedPref(): Boolean = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, "debug.hyperextreme.parallax_leash_kwl", "") as? String) == "1"
    }.getOrDefault(false)

    /** 每秒最多重新解析一次壁纸 leash（换壁纸后要重新拿）。 */
    private fun currentLeash(loader: ClassLoader, showWhenLocked: Boolean = false): Any? {
        val now = SystemClock.elapsedRealtime()
        if (now - leashAt < 1000L && leashShowWhenLocked == showWhenLocked) return leash
        leashAt = now
        leashShowWhenLocked = showWhenLocked
        leash = runCatching {
            val instance = getInstance!!.invoke(null) ?: return@runCatching null
            // **桌面态要用 `showWhenLocked = true` 的那个 token**：
            //   false → `#41 ImageWallpaper`（桌面态常 `flags:1` 隐藏 → 层挂上去看不见）
            //   true  → `#42 MiuiKeyguardPictorialWallpaper`（`isVisible=true`，桌面实际显示的就是它）
            // 拿不到再退回 false。
            val target = getWallpaper!!.invoke(instance, 0, showWhenLocked)
                ?: getWallpaper!!.invoke(instance, 0, false)
                ?: return@runCatching null
            if (leashField == null) {
                leashField = runCatching { target.javaClass.getField("leash") }.getOrNull()
            }
            val sc = leashField?.get(target)
            // **排除转场临时层（2026-10-21 关键坑）**：
            // `getWallpaperByShowWhenLocked` 有时返回
            // `Surface(name=WallpaperWindowToken{...#41)/@0x..._transition-leash#9288`
            // —— 那是 Activity 转场用的临时 leash，`flags:1`（隐藏）+ 常 `a:0`。
            // 层挂进去等于挂在一个看不见的容器里，之后既不显示、也管不了
            // （用户看到的就是「画面固定 + 关掉后还是我们的层」）。
            // 拿不到正常 token 时宁可这帧不建层，等下一次。
            if (sc != null && sc.toString().contains("transition-leash")) {
                if (leashMissTick++ % 100 == 0) {
                    log("ensure 跳过：拿到的是 transition-leash（转场临时层，隐藏）")
                }
                return@runCatching null
            }
            sc
        }.getOrNull()
        return leash
    }

    /**
     * 拿到桌面壁纸 leash，并确保两层自建 SurfaceControl 已挂上。
     *
     * **不再对壁纸层本身做任何变换**（2026-10-21 修正）。原因：
     * 子层挂在 leash 下会继承它的 matrix —— 一旦这里也设 `bgScale`/位移，
     * 就是「父层变换 × 子层变换」的叠乘（1.15×1.15≈1.32 → 观感「缩放太大」），
     * 位移也会被父层吃掉一部分（→ 差速看不出来）。
     * 更糟的是每帧抢着改壁纸 matrix，和系统自己的壁纸动画打架 —— **多任务抽搐就是这么来的**。
     *
     * 现在的分工：壁纸层保持单位矩阵、彻底不参与；两层各自走**绝对**变换
     * （与锁屏 View 的 `scaleX/translationX` 同一套数值）。
     *
     * @return true = leash 可用且两层已就位
     */
    fun ensure(
        loader: ClassLoader,
        screenW: Int,
        screenH: Int,
        bgBitmap: android.graphics.Bitmap? = null,
    ): Boolean {
        if (!ensureApi(loader)) {
            if (!logged) {
                logged = true
                log("API 不可用（getWallpaperByShowWhenLocked / Transaction.setMatrix）")
            }
            return false
        }
        // 已挂载时，父层检查节流到 1s 一次 —— `currentLeash()` 走
        // `getWallpaperByShowWhenLocked` 是 **binder 调用**，每帧跑（50Hz）纯属浪费。
        if (ParallaxSubjectLayer.isAttached()) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLeashCheckAt < LEASH_CHECK_INTERVAL_MS) return true
            lastLeashCheckAt = now
        }
        // **父层选择**：默认 `showWhenLocked = false` → `#41 ImageWallpaper`（桌面壁纸）。
        // 桌面态它 `flags:0` 可见、锁屏态隐藏 —— 正好符合「只在桌面显示」的需求。
        // （曾改成 `true` 挂 `#42 MiuiKeyguardPictorialWallpaper`，那是**锁屏画报壁纸**，
        //   桌面态被桌面窗口遮住 ⇒ 视差看不见，已回退。）
        // 现场可切换：`setprop debug.hyperextreme.parallax_leash_kwl 1` → 用 true。
        val sc = currentLeash(loader, leashShowWhenLockedPref())
        if (sc == null) {
            // 诊断（2026-10-21）：重启 SystemUI 后如果一直是 null，就是「桌面层级固定失效」
            // 的真因 —— 层根本没建出来，看到的是静态系统壁纸。节流打印，避免刷屏。
            if (leashMissTick++ % 100 == 0) {
                log("ensure 失败：leash=null（getWallpaperByShowWhenLocked 返回 null，第 $leashMissTick 次）")
            }
            return false
        }
        return runCatching {
            val (sw, sh) = surfaceSize(sc) ?: (screenW to screenH)
            // **父层变了就重建**（2026-10-21）。`getWallpaperByShowWhenLocked(0,false)` 会在
            // 两个 WallpaperWindowToken（#41 ImageWallpaper ⇄ #42 KeyguardPictorial）之间切换，
            // 而我们的层只 attach 一次 —— 父层换掉之后层还挂在被隐藏的那个 token 下，
            // 表现就是「画面固定在重启前的样子、模块怎么调都没反应」，只能重启 miwallpaper 恢复。
            if (ParallaxSubjectLayer.parentChanged(sc)) {
                log(
                    "父层已变（${ParallaxSubjectLayer.parentTag()} → ${sc}）—— 销毁并重建层",
                )
                ParallaxSubjectLayer.destroy()
            }
            if (!ParallaxSubjectLayer.isAttached()) {
                ParallaxSubjectLayer.attach(loader, sc, sw, sh, bgBitmap)
                // 只在建层这一下复位壁纸层，之后**不再碰**它的 matrix。
                resetToIdentity(sc)
                log("已接管（surface=${sw}x$sh，屏幕=${screenW}x$screenH，壁纸层复位为单位矩阵）")
            }
            true
        }.getOrElse {
            if (!logged) {
                logged = true
                log("ensure 失败：$it")
            }
            false
        }
    }

    /** 把壁纸层复位成单位矩阵（只做一次，避免和系统抢控制权）。 */
    private fun resetToIdentity(sc: Any) {
        runCatching {
            val m = Matrix()
            val values = FloatArray(9)
            m.getValues(values)
            val transaction = txCtor!!.newInstance()
            setMatrix!!.invoke(transaction, sc, m, values)
            applyTx!!.invoke(transaction)
        }
    }

    /** 恢复 identity（关闭开关 / 退出接管时调用）。 */
    fun reset(loader: ClassLoader, screenW: Int, screenH: Int) {
        // 重新取一次 leash：壁纸可能被换过，缓存的 leash 会失效。
        val sc = currentLeash(loader) ?: return
        resetToIdentity(sc)
    }
}
