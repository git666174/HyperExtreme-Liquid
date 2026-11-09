package com.frederic22.hyperextreme.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.SpatialFlag
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 空间壁纸在锁屏生效时，**撤掉系统的景深前景层**（2026-10-28）。
 *
 * ## 为什么是「撤掉」而不是「盖住」
 *
 * 空间壁纸的 GL 层挂在**锁屏壁纸的 leash** 下（`getWallpaperByShowWhenLocked(0, true)`，
 * 2026-10-28 真机探针已验证锁屏可见、桌面不可见），它全屏不透明 ⇒ 系统锁屏壁纸已经被盖住。
 * 此时唯一还压在它上面的，是 SystemUI 窗口里的 `keyguard_foreground_layer`。
 *
 * 把那一层撤掉，露出来的就是我们的图 —— **视觉上等价于「我们的图盖住了主体」，但不用新建
 * 任何 View、不用 TextureView、不用额外的合成**。
 *
 * ## 层级依据（`res/layout/keyguard_panel_view.xml`，aapt2 解原 APK 得到）
 *
 * ```
 * KeyguardPanelView
 *  [0] FrameLayout                  keyguard_background_layer
 *  [1] KeyguardSignatureLayerView   keyguard_signature_layer
 *  [2] KeyguardClockContainer       miui_keyguard_clock_container   ← 时钟
 *  [3] include → keyguard_foreground_layer   ← 景深主体 + 色带 + 装饰（本类要撤的）
 *  [4] FrameLayout                  miui_keyguard_foreground_clock_container
 *  [5] KeyguardInfoLayerView        keyguard_info_layer
 * ```
 *
 * `keyguard_foreground_layer` 内部（`res/layout/keyguard_foreground_layer.xml`）：
 * `deducted_image_view`（主体，全屏 CENTER_CROP）+ `ColorRibbonsContainer`（景深色带）+ 装饰。
 * **里面没有时钟、没有通知** —— 时钟在 [2]、通知在窗口根的兄弟容器里，撤掉它不影响两者。
 *
 * ## 为什么 hook `updateKeyguardElementsVisibility()`
 *
 * 这一层的显隐由 `KeyguardPanelViewController#updateKeyguardElementsVisibility()` 统一设置
 * （真机反编译 L8060-8063：`keyguardForegroundLayer.setVisibility(... ? 0 : 4)`）。
 * 系统每次状态变化都会重算 ⇒ 我们在 `proceed()` **之后**压回去，**天然自愈**，
 * 不需要自己监听 `onAttachedToWindow`、也不需要维护"层丢了"的状态。
 *
 * 只在「系统认为该显示（VISIBLE）」时才压 —— 系统本来设 4 的场合我们不干预，
 * 亮灭屏 / 退出 / pre-hide 全部走系统原逻辑。
 *
 * ## 开关
 *
 * 两个属性都满足才压（属性通道与 `HookRuntime.systemProperty` 一致，300ms 缓存）：
 * * `SpatialFlag.PROP_ACTIVE == 1` —— 空间壁纸生效中；
 * * `SpatialFlag.PROP_SCOPE != SPATIAL_SCOPE_DESKTOP` —— 锁屏或全局档。
 *
 * **与视差完全独立**：不读不写任何 `parallax*` 字段，也不碰 `ParallaxBg` 的 View 树。
 */
internal object SpatialForegroundHider {

    private const val CLS_PANEL = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val METHOD = "updateKeyguardElementsVisibility"
    private const val FIELD_FOREGROUND = "keyguardForegroundLayer"

    /** 同一条日志只打一次（`updateKeyguardElementsVisibility` 调用很频繁）。 */
    private val logged = ConcurrentHashMap<String, Boolean>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ── 开关读取（300ms 缓存：方法调用频繁，属性反射不宜每次付） ──
    @Volatile private var cacheAt = 0L
    @Volatile private var cacheValue = false
    private const val CACHE_MS = 300L

    private fun enabled(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cacheAt < CACHE_MS) return cacheValue
        val active = HookRuntime.systemProperty(SpatialFlag.PROP_ACTIVE).trim() == "1"
        val scope = HookRuntime.systemProperty(SpatialFlag.PROP_SCOPE).trim().toIntOrNull()
            ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        val spatialOn = active && scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        // **光栅壁纸同样需要撤这一层**（2026-11-04）：它也挂在壁纸 leash 上、全屏不透明，
        // 系统那层 `keyguard_foreground_layer`（主体抠像 + 景深色带）会压在我们的图上面，
        // 看起来就是"旧壁纸的人浮在新壁纸上面"。它没有景深主体，所以走下面的"旧链"整层压掉。
        val rasterActive = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_ACTIVE,
        ).trim() == "1"
        val rasterScope = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_SCOPE,
        ).trim().toIntOrNull() ?: MaskPrefs.SPATIAL_SCOPE_LOCK
        val rasterOn = rasterActive && rasterScope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        val v = spatialOn || rasterOn
        if (v != cacheValue) {
            log("开关 -> $v（空间=$spatialOn[$active/$scope] 光栅=$rasterOn[$rasterActive/$rasterScope]）")
        }
        cacheAt = now
        cacheValue = v
        return v
    }

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_PANEL, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_PANEL")
            return false
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == METHOD && it.parameterCount == 0
        }
        if (method == null) {
            log("未找到 $METHOD()")
            return false
        }
        val ok = runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // **必须在 proceed 之后**：系统刚把 visibility 算完，我们才盖得住。
                    // 闸门之前先收口：属性 active=0（关闭替换）时 [handle] 根本不会进，
                    // 不清的话我们建的 View 会一直留在前景层里（2026-10-29 问题 1）。
                    if (!enabled()) {
                        SpatialOverlay.onDisabled()
                        return result
                    }
                    runCatching { handle(chain.getThisObject()) }
                        .onFailure { log("撤前景层失败：${it.javaClass.simpleName}: ${it.message}") }
                    return result
                }
            })
            true
        }.getOrElse {
            log("挂 $METHOD 失败：$it")
            false
        }
        if (ok) log("已挂上（锁屏/全局档时接管 keyguard_foreground_layer）")
        return ok
    }

    /**
     * 处理前景层：**两种模式**（2026-10-29）。
     *
     * | 模式 | 判据 | 行为 |
     * |---|---|---|
     * | 锁屏景深（新） | [SpatialOverlay.active] —— 渲染器确实按「开关=开」启动了 | 层保留 VISIBLE、只压系统那个 `deducted_image_view`，并把我们的主体 View 插进去 |
     * | 旧行为（默认） | 其余全部情况 | 整层压 INVISIBLE（与加这个开关之前逐字一致） |
     *
     * 只在「系统认为该显示（VISIBLE）」时才动手 —— 系统本来设 4 的场合我们不干预，
     * 亮灭屏 / 退出 / pre-hide 全部走系统原逻辑。
     */
    private fun handle(self: Any) {
        // **先同步 AOD 模式**（2026-10-30，v1.5.78）：只有「与锁屏一致」模式下系统才会
        // 把壁纸 leash 预放大到 1.05，AGSL 主体要跟着；自定义模式下 leash 是 1.0。
        // 放在 visibility 检查之前 —— 层不可见时也要把模式记下来。
        runCatching { syncLeashScale(self) }
        val field = self.javaClass.getDeclaredField(FIELD_FOREGROUND).apply { isAccessible = true }
        val layer = field.get(self) as? android.view.ViewGroup ?: return
        if (layer.visibility != View.VISIBLE) return

        if (SpatialOverlay.active() || RasterOverlay.active()) {
            // 新链：我们的主体顶替系统主体 ⇒ 把系统那个 ImageView **从父容器摘掉**（问题 2：
            // 设 INVISIBLE 会被系统的 folme onBegin 每帧翻回来），整层留着（它管着我们的
            // View 的显隐、模糊、解锁退场）。
            //
            // ⚠️ 两套壁纸**互斥**：`keyguard_foreground_layer` 只有一个，谁在跑谁占，
            //    另一条链必须当场收口 —— 否则两个主体会叠在同一层上（2026-11-04）。
            if (SpatialOverlay.active()) {
                SpatialOverlay.detachSystemSubject(layer)
                SpatialOverlay.ensure(layer)
                RasterOverlay.onDisabled()
            } else {
                RasterOverlay.detachSystemSubject(layer)
                RasterOverlay.ensure(layer)
                SpatialOverlay.onDisabled()
            }
            logOnceDepth()
            return
        }

        // 旧链：整层压掉
        SpatialOverlay.onDisabled()
        RasterOverlay.onDisabled()
        val view = layer as View
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.visibility = View.INVISIBLE
            logOnce()
        } else {
            mainHandler.post {
                runCatching {
                    if (view.visibility == View.VISIBLE) {
                        view.visibility = View.INVISIBLE
                        logOnce()
                    }
                }
            }
        }
    }

    private fun logOnce() {
        if (logged.putIfAbsent("hidden", true) == null) {
            log("锁屏景深关：景深前景层已撤（keyguard_foreground_layer → INVISIBLE）")
        }
    }

    /**
     * 反射 `miuiFullAodManager.fullAodEnable()`，据此决定 AGSL 主体的锁屏缩放。
     *
     * `fullAodEnable() = isDeviceSupport && mAodEnable && mAodFullScreenEnable && isFullAodSupport`
     * —— 只有它为 true 时 `linkageViewAnim` 才会调 `doWallpaperScaleAnim`（leash 1.05）。
     * 拿不到就**什么都不改**（保持当前值），不要猜。
     */
    private fun syncLeashScale(self: Any) {
        val full = runCatching {
            val f = self.javaClass.getDeclaredField("miuiFullAodManager").apply { isAccessible = true }
            val mgr = f.get(self) ?: return@runCatching null
            mgr.javaClass.getMethod("fullAodEnable").invoke(mgr) as? Boolean
        }.getOrNull() ?: return
        val scale = if (full) 1.05f else 1f
        // 两套壁纸（空间 / 光栅）共用同一层，缩放口径也必须一致 —— 谁在跑谁吃这个值。
        SpatialOverlay.setLeashScale(scale)
        RasterOverlay.setLeashScale(scale)
    }

    private fun logOnceDepth() {
        if (logged.putIfAbsent("depth", true) == null) {
            log("锁屏景深开：前景层保留，只压系统主体 + 插入我们自己的主体 View")
        }
    }

    private fun log(msg: String) = HookRuntime.log("SPATIAL 前景层 $msg")
}
