package com.frederic22.hyperextreme.hook

import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.SpatialFlag
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 「空间壁纸」的**解锁淡出 + alpha 复位**（2026-10-28）。两个方向共用**一个**信号源。
 *
 * ## 为什么需要淡出
 *
 * 系统原生的「锁屏 → 桌面」过渡由四件事组成，作用对象各不相同：
 *
 * | 环节 | 实现 | 作用范围 | 我们 |
 * |---|---|---|---|
 * | 模糊 | `ViewRootImpl.setWallpaperBlur(int)` | 窗口背后的壁纸层**整体** | ✅ 跟着糊 |
 * | 放大 | `setWallpaperScale(f, lock, desktop)` → `setMatrix(leash)` | leash 的**所有子层** | ✅ 跟着放 |
 * | zoom | `setWallpaperZoom` | 壁纸窗口 | ✅ |
 * | **淡出** | `hideKeyguardWallpaper` → `updateKeyguardWallpaperStateAnim` → **miwallpaper 跨进程** | **只有它自己那两个 Surface** | ❌ **不跟** |
 *
 * 前两者是「后处理 / 父层变换」，天然辐射到作为 leash 子层的我们；淡出是 miwallpaper 给自己
 * 那条 layer 降 alpha，我们是**兄弟层**，不在作用范围里 ⇒ 我们的图一直不透明，直到 leash
 * 不再呈现 = 用户看到的「硬切」。所以这里自己补一条 alpha 渐隐。
 *
 * ## 为什么挂 `KeyguardStateControllerImpl#notifyKeyguardState(boolean showing, boolean occluded)`
 *
 * 这一个方法同时给出两个方向的**真跳变**，而且不会误触发：
 *
 * | 跳变 | 含义 | 动作 |
 * |---|---|---|
 * | `false → true` | 锁屏出现（回锁屏 / 亮屏 / 启动） | `resetAlpha()` —— alpha 回 1 |
 * | `true → false` | 锁屏消失（**解锁**） | `fadeOutOnUnlock()` —— 380ms 淡到 0 |
 *
 * 方法体开头有 `if (mShowing == z && mOccluded == z2) return;`，只在真的变化时才往下走；
 * 我们再叠加「proceed 之前读到的 `mShowing` 必须是反向值」，把 occluded 反复横跳的噪声也排掉。
 *
 * ### 三个被否掉的候选（真机踩过，别再试）
 *
 * | 候选 | 为什么不行 |
 * |---|---|
 * | `updateKeyguardWallpaperStateAnim(false)` 做淡出触发 | **SystemUI 重启的初始化也会传 false** ⇒ 启动时就淡出一次、然后被复位拉回，出现"图闪一下"（2026-10-28 日志实锤：淡出后 388ms 被 false→true 复位） |
 * | `updateKeyguardWallpaperStateAnim(true)` 做复位 | `onStartedWakingUp`（亮屏）和解锁后都会传 true ⇒ 把刚淡出的 alpha 拉回 1，表现为"淡出结束后我们的图又冒出来" |
 * | `keyguard_foreground_layer` 被设 VISIBLE 做复位 | 解锁过程中 `isExitingToKeyguard` / `isUnoccludedAnimPlaying` 也会让它 VISIBLE ⇒ 同样误复位 |
 *
 * ## 兜底
 *
 * 灭屏 → 亮屏时 `showing` 一直是 true、`notifyKeyguardState` 会早退。若灭屏那条路径碰巧
 * 触发过淡出，alpha 会永久停在 0 ⇒ 再挂一条 `showWallpaperScreenOnAnim(true)`（只在亮屏时调）。
 *
 * ## 开关
 *
 * `SpatialFlag.PROP_ACTIVE == 1` **且** `PROP_SCOPE != SPATIAL_SCOPE_DESKTOP`
 * —— 只有「锁屏 / 全局」档才需要，桌面档我们的图在桌面 leash 上、解锁根本不动它。
 * 与视差完全独立：不读不写任何 `parallax*` 字段。
 */
internal object SpatialUnlockFade {

    private const val CLS_STATE = "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
    private const val METHOD_STATE = "notifyKeyguardState"
    private const val FIELD_SHOWING = "mShowing"
    /** 解锁退场动画的"进行中"标志：起点 true、结束 false。 */
    private const val FIELD_FADING = "mMiKeyguardFadingAway"

    private const val CLS_MANAGER = "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager"
    private const val CLS_MEDIATOR = "com.android.keyguard.injector.KeyguardViewMediatorInjector"
    private const val CLS_PANEL = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val METHOD_EXIT_ANIM =
        "startKeyguardExitAnimationByInteractiveOrFullAODUnlockAnimation"
    private const val METHOD_DISMISS_ANIM = "startUnlockKeyguardDismissAnim"
    private const val METHOD_SCREEN_ON = "showWallpaperScreenOnAnim"

    private val logged = ConcurrentHashMap<String, Boolean>()

    // ───────────── 淡出的**结束真值**：`KeyguardStateControllerImpl.mMiKeyguardFadingAway` ─────────────
    //
    // 系统在解锁退场动画**开始**时置 true（`KeyguardViewMediatorInjector` L534，正是我们的起点），
    // 动画**结束**时置 false。⇒ 淡出不必猜总时长：**它一变 false 就说明系统切完了，alpha 立刻归 0**。
    //
    // 这样 FADE_MS 只当"上限/斜率"，估长了也不会让图停在屏上；同时把实测差值打进日志，
    // 下次就能把 FADE_MS 设成真值（两者都对上时，兜底那一刀永远不会被触发）。
    @Volatile private var stateRef: java.lang.ref.WeakReference<Any>? = null
    @Volatile private var fadingField: java.lang.reflect.Field? = null

    /** 系统是否仍在跑解锁退场动画。null = 还没抓到实例 / 字段（调用方按"未知"处理）。 */
    fun isFadingAway(): Boolean? {
        val o = stateRef?.get() ?: return null
        val f = fadingField ?: return null
        return runCatching { f.getBoolean(o) }.getOrNull()
    }

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val dismissOk = installDismissAnimHook(module, loader)
        val exitOk = installExitAnimHook(module, loader)
        val stateOk = installStateHook(module, loader)
        val fallbackOk = installScreenOnFallback(module, loader)
        return dismissOk || exitOk || stateOk || fallbackOk
    }

    /**
     * **前景主体的淡出触发点：解锁动画的起点**（2026-10-30，v1.5.75）。
     *
     * `KeyguardPanelViewController#startUnlockKeyguardDismissAnim()` 正是系统启动那条
     * **200ms `keyguardBouncerFraction → 1` 模糊 ramp** 的方法（反编译 L7634-7653）。
     * 在它**入口**让前景主体开始退场，与系统加糊严格同帧；退场动画起点
     * （`startKeyguardExitAnimationBy...`）实测晚约 15ms，120ms 版仍能看到"糊一下"。
     *
     * 只驱动**前景主体**（[SpatialOverlay.fadeOutOnUnlock]），**不动 GL 锁屏份的 alpha** ——
     * 那份必须继续跟着 miwallpaper 的真值淡，提前淡会露出系统壁纸。
     *
     * 方法名即语义（unlock dismiss）⇒ 启动 / 灭屏都不会走它，不会误触发。
     */
    private fun installDismissAnimHook(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_PANEL, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_PANEL（前景主体改由退场动画起点触发）")
            return false
        }
        val m = cls.declaredMethods.firstOrNull { it.name == METHOD_DISMISS_ANIM }
        if (m == null) {
            log("未找到 $METHOD_DISMISS_ANIM（前景主体改由退场动画起点触发）")
            return false
        }
        return runCatching {
            m.isAccessible = true
            module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (enabled()) {
                        logOnce("fade-dismiss", "解锁动画开始 → 前景主体开始淡出")
                        SpatialOverlay.fadeOutOnUnlock()
                    }
                    return chain.proceed()
                }
            })
            log("已挂上（$METHOD_DISMISS_ANIM：解锁动画起点 → 前景主体淡出）")
            true
        }.getOrElse {
            log("挂 $METHOD_DISMISS_ANIM 失败：$it")
            false
        }
    }

    /**
     * **淡出的主触发点**：解锁退场动画的**起点**。
     *
     * `KeyguardViewMediatorInjector#startKeyguardExitAnimationByInteractiveOrFullAODUnlockAnimation`
     * 的方法体第三行就是 `hideKeyguardWallpaper(...)` —— 也就是系统真正开始壁纸退场的地方。
     * 比 `notifyKeyguardState(true→false)`（走 KeyguardStateController 的通知链）**更早**，
     * 而淡出要「和系统动画同帧开始」，早的这几十毫秒正是肉眼能看出的那一点延迟。
     *
     * 只在**解锁**时调（名字即语义）：启动 / 灭屏都不走它，所以不会像
     * `updateKeyguardWallpaperStateAnim(false)` 那样在 SystemUI 重启时误触发。
     */
    private fun installExitAnimHook(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_MEDIATOR, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_MEDIATOR（退回 notifyKeyguardState 触发）")
            return false
        }
        val m = cls.declaredMethods.firstOrNull {
            it.name == METHOD_EXIT_ANIM && it.parameterCount == 1
        }
        if (m == null) {
            log("未找到 $METHOD_EXIT_ANIM（退回 notifyKeyguardState 触发）")
            return false
        }
        return runCatching {
            m.isAccessible = true
            module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 光栅壁纸也吃这个时机（没在跑就是空操作，内部自判 runningKey）。
                    RasterRenderer.fadeOutOnUnlock()
                    if (enabled()) {
                        logOnce("fade-exit", "解锁退场动画开始 → 启动解锁淡出")
                        SpatialRenderer.fadeOutOnUnlock()
                    }
                    return chain.proceed()
                }
            })
            log("已挂上（$METHOD_EXIT_ANIM：解锁退场起点触发淡出）")
            true
        }.getOrElse {
            log("挂 $METHOD_EXIT_ANIM 失败：$it")
            false
        }
    }

    /** 主挂点：锁屏显示状态的真跳变，同时驱动淡出与复位。 */
    private fun installStateHook(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_STATE, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_STATE")
            return false
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == METHOD_STATE && it.parameterCount == 2
        }
        if (method == null) {
            log("未找到 $METHOD_STATE(2 参数)")
            return false
        }
        val showingField = runCatching {
            cls.getDeclaredField(FIELD_SHOWING).apply { isAccessible = true }
        }.getOrNull()
        // 顺手抓"淡出结束真值"用的字段（拿不到就只是没有兜底，不影响淡出本身）
        fadingField = runCatching {
            cls.getDeclaredField(FIELD_FADING).apply { isAccessible = true }
        }.getOrNull()
        if (fadingField == null) log("无 $FIELD_FADING 字段 ⇒ 淡出没有结束兜底，只能靠 FADE_MS")
        if (showingField == null) {
            // 读不到旧值就只能看"当前值"，会丢失跳变判据 —— 宁可不装，也不误触发。
            log("无 $FIELD_SHOWING 字段 ⇒ 放弃主挂点（宁可不动，也不误淡出）")
            return false
        }
        return runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val self = chain.getThisObject()
                    stateRef = java.lang.ref.WeakReference(self)
                    // proceed 之前读旧值：方法体本身会把 mShowing 覆盖成新值
                    val was = runCatching { showingField.getBoolean(self) }.getOrNull()
                    val result = chain.proceed()
                    val showing = chain.getArgs().filterIsInstance<Boolean>().firstOrNull()
                    if (was == null || showing == null) return result
                    // 光栅壁纸同样按"锁屏显示状态的真跳变"淡出 / 复位（与空间壁纸互不影响）。
                    if (showing != was) {
                        if (showing) RasterRenderer.resetAlpha() else RasterRenderer.fadeOutOnUnlock()
                    }
                    if (!enabled()) return result
                    when {
                        showing && !was -> {
                            setFlag(com.frederic22.hyperextreme.data.SpatialFlag.PROP_KEYGUARD, "1")
                            logOnce("reset", "锁屏出现（showing false→true）→ alpha 复位 1")
                            SpatialRenderer.resetAlpha()
                        }
                        !showing && was -> {
                            setFlag(com.frederic22.hyperextreme.data.SpatialFlag.PROP_KEYGUARD, "0")
                            logOnce("fade", "锁屏消失（showing true→false）→ 启动解锁淡出")
                            SpatialRenderer.fadeOutOnUnlock()
                        }
                    }
                    return result
                }
            })
            log("已挂上（notifyKeyguardState：true→false 淡出 / false→true 复位）")
            true
        }.getOrElse {
            log("挂 $METHOD_STATE 失败：$it")
            false
        }
    }

    /** 兜底：亮屏时无条件复位（防"灭屏那条路径触发过淡出 ⇒ 永久黑"）。 */
    private fun installScreenOnFallback(module: XposedModule, loader: ClassLoader): Boolean {
        val mgr = runCatching { Class.forName(CLS_MANAGER, false, loader) }.getOrNull()
        if (mgr == null) {
            log("未找到 $CLS_MANAGER（兜底复位不可用）")
            return false
        }
        val m = mgr.declaredMethods.firstOrNull {
            it.name == METHOD_SCREEN_ON && it.parameterCount == 1
        }
        if (m == null) {
            log("未找到 $METHOD_SCREEN_ON(1 参数)")
            return false
        }
        return runCatching {
            m.isAccessible = true
            module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 亮屏传 true、灭屏传 false —— 只在 true 时复位
                    if (chain.getArgs().filterIsInstance<Boolean>().firstOrNull() == true) {
                        RasterRenderer.resetAlpha()
                    }
                    if (chain.getArgs().filterIsInstance<Boolean>().firstOrNull() == true && enabled()) {
                        logOnce("reset-on", "亮屏 → alpha 复位 1")
                        SpatialRenderer.resetAlpha()
                    }
                    return chain.proceed()
                }
            })
            log("兜底复位已挂上（$METHOD_SCREEN_ON）")
            true
        }.getOrElse {
            log("挂 $METHOD_SCREEN_ON 失败：$it")
            false
        }
    }

    // 与 `SpatialForegroundHider` 同一判据：属性通道 + 300ms 缓存（此方法调用频繁）。
    @Volatile private var cacheAt = 0L
    @Volatile private var cacheValue = false
    private const val CACHE_MS = 300L

    private fun enabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cacheAt < CACHE_MS) return cacheValue
        val active = HookRuntime.systemProperty(SpatialFlag.PROP_ACTIVE).trim() == "1"
        val scope = HookRuntime.systemProperty(SpatialFlag.PROP_SCOPE).trim().toIntOrNull()
            ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        cacheValue = active && scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        cacheAt = now
        return cacheValue
    }

    private fun logOnce(key: String, msg: String) {
        if (logged.putIfAbsent(key, true) == null) log(msg)
    }

    /** 写系统属性（反射，不 fork）。失败静默 —— 只是让 miwallpaper 退回"不压 0"。 */
    private fun setFlag(key: String, value: String) {
        runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
        }
    }

    private fun log(msg: String) = HookRuntime.log("SPATIAL 解锁淡出 $msg")
}
