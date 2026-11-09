package com.frederic22.hyperextreme.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 空间壁纸「**锁屏景深**」的**前景主体层**（2026-10-29，Step 4）。
 *
 * ## 它解决什么
 *
 * GL 的两层挂在壁纸 leash 上 —— 那条 leash 在 SurfaceFlinger 里比 SystemUI 窗口**低**
 * ⇒ 主体永远在时钟**下面**，看不出景深。系统的景深主体之所以能压住时钟，
 * 是因为它画在 `keyguard_foreground_layer`（`KeyguardPanelView` 的第 3 个子层，
 * 第 2 个是时钟）里。
 *
 * 所以这里把**主体**再用 AGSL 画一遍，插进那一层 —— 位置、z 序、可见性全部跟着系统走。
 *
 * ## 为什么用 AGSL 而不是第二份 GL
 *
 * * 不需要第二套 EGL surface ⇒ 不会踩「不可见 surface 的 `eglSwapBuffers` 阻塞」那个坑
 *   （共用 leash 那次 GL 线程停摆就是这个）；
 * * 与时钟**同窗口、同一次绘制** ⇒ 没有跨层错位；
 * * 系统自己在 `VideoDepthSurfaceHolder` 里就是用 `RuntimeShader` 做的，这条 API 路走得通。
 *
 * ## 几何必须与 GL 完全一致
 *
 * 球面映射 + `SAFE` 放大的算法在 [SpatialRenderer.geometry] 里只存一份，
 * 这里直接取，不另写一遍 —— 两边一旦漂移，主体与背景就会错位。
 *
 * ## 当前阶段（Step 5）
 *
 * 姿态已接：`uShift` 走 [SpatialRenderer.currentShift]，与 GL 同一公式；重绘由
 * [SpatialRenderer.requestDraw] 在死区 / 节流 / 冻结全部通过之后统一驱动
 * （见 [onAttitudeChanged]）⇒ 静止两边都不动、过渡期两边一起冻。
 */
internal object SpatialOverlay {

    private const val AUTHORITY = "com.frederic22.hyperextreme.liquid.provider"

    /** 系统的前景层（`res/layout/keyguard_panel_view.xml` 第 3 个子层）。 */
    private const val ID_FOREGROUND_LAYER = "keyguard_foreground_layer"

    /** 系统景深主体（`keyguard_foreground_layer.xml` 里那个全屏 CENTER_CROP 的 ImageView）。 */
    private const val ID_DEDUCTED = "deducted_image_view"

    private const val SYSTEMUI_PKG = "com.android.systemui"

    /**
     * AGSL 版本的场景着色器 —— 与 `SpatialRenderer.FRAG` **逐项对应**：
     *
     * ```
     * 屏幕像素 → NDC → 球面角度 atan(ndc·K) → q0 = 角度/span
     * q0 → uv0 → 取位移场（R = 背景场，G = 主体场）
     * 主体场做选择性融合（|Δk| 小于阈值才跟随背景）
     * q = q0 − uShift·k → 采样原图
     * ```
     *
     * 与 GL 的唯一差别是坐标入口：GL 拿的是顶点插值出来的 NDC，这里拿的是 Canvas 局部坐标，
     * 所以开头多一步 `coord → ndc`（y 翻转，屏幕坐标 y 向下、NDC y 向上）。
     */
    private const val AGSL = """
        uniform shader uTex;
        uniform shader uDepth;
        uniform float2 uTexSize;
        uniform float2 uDepthSize;
        uniform float2 uViewSize;
        uniform float2 uK;
        uniform float2 uSpan;
        uniform float2 uShift;
        uniform float uDkTh;
        uniform float uDim;
        // 显示区域（2026-11-05）：uv' = uv * uUvScale + uUvOffset。
        // ⚠️ **两路都必须变换**（深度场 + 颜色），只改一处会让主体轮廓与位移场错位 ——
        // 和 `SpatialGlCore.FRAG` 那边是同一条约束。
        uniform float2 uUvScale;
        uniform float2 uUvOffset;

        // **手写 4-tap 双线性**（2026-10-29，问题 3）。
        //
        // 为什么必须要：`BitmapShader` 作为 `RuntimeShader` 的 child 被 `eval()` 时，
        // 用的是**它自己的 sampling**（Java 构造没有 sampling 参数，native 侧是
        // `SkSamplingOptions()` ⇒ 默认 kNearest）；`Paint.FILTER_BITMAP_FLAG` 只对
        // "Paint 直接画 Bitmap" 生效，管不到这里。层图 1022 宽、屏幕 1200 ⇒ 放大 1.28×，
        // nearest 会出现"每 4 个源像素里 1 个被复制两次"的块状 = 用户说的"分辨率更低"。
        // 壁纸层那份是 GL，`GL_LINEAR` ⇒ 必须在这里补一份等价的插值，两边才对齐。
        //
        // ⚠️ **必须内联**：AGSL 不允许 `shader` 作为函数参数
        // （`parameters of type 'shader' not allowed`，2026-10-29 踩过），
        // 所以不能抽成 `texBilinear(shader, ...)`，只能写在 main 里。
        //
        // `-0.5`：GL 的 `texture2D` 在 uv=0 时落在纹素**边界**，双线性按 `uv*size-0.5`
        // 加权。不减去这半像素，整幅主体会偏 0.5px，与壁纸层那份对不上。
        half4 main(float2 coord) {
            float2 ndc = float2(coord.x / uViewSize.x * 2.0 - 1.0,
                                1.0 - coord.y / uViewSize.y * 2.0);
            float2 q0 = float2(atan(ndc.x * uK.x) / uSpan.x,
                               atan(ndc.y * uK.y) / uSpan.y);
            float2 uv0 = float2(q0.x * 0.5 + 0.5, 0.5 - q0.y * 0.5);
            uv0 = uv0 * uUvScale + uUvOffset;

            float2 duv = uv0 * uDepthSize;
            float kb = uDepth.eval(duv).r;
            float kf = uDepth.eval(duv).g;
            float g = 1.0 - clamp(abs(kb - kf) / max(uDkTh, 0.001), 0.0, 1.0);
            float k = clamp(mix(kf, kb, g), 0.0, 1.0);

            float2 q = q0 - uShift * k;
            float2 uv = float2(q.x * 0.5 + 0.5, 0.5 - q.y * 0.5);
            uv = uv * uUvScale + uUvOffset;
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                return half4(0.0);
            }
            // 4-tap 双线性（**内联**：AGSL 不允许 shader 作为函数参数）
            float2 p  = uv * uTexSize - 0.5;
            float2 i0 = floor(p);
            float2 f  = p - i0;
            float2 lo = float2(0.5, 0.5);
            float2 hi = uTexSize - lo;
            half4 c00 = uTex.eval(clamp(i0 + float2(0.5, 0.5), lo, hi));
            half4 c10 = uTex.eval(clamp(i0 + float2(1.5, 0.5), lo, hi));
            half4 c01 = uTex.eval(clamp(i0 + float2(0.5, 1.5), lo, hi));
            half4 c11 = uTex.eval(clamp(i0 + float2(1.5, 1.5), lo, hi));
            half4 ca = mix(c00, c10, half(f.x));
            half4 cb = mix(c01, c11, half(f.x));
            half4 c  = mix(ca, cb, half(f.y));
            // AOD 压暗（2026-10-30 定案：**必须有**）：主体压在时钟之上，背景暗了主体还亮
            // 会非常突兀。GL 两份走 SF 的 `setColor`（不改 buffer、不脏玻璃采样），
            // 本 View 在窗口内用不了 SC 属性 ⇒ 只能在这里乘；它也不在玻璃的采样路径上。
            return half4(c.rgb * half(uDim), c.a);
        }
    """

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var view: SubjectView? = null

    /** 已按哪个 key 插进树里（**不等位图**：位图慢一步，插树要立刻幂等）。 */
    @Volatile private var installedKey: String = ""

    /** 位图真的挂上去了的 key —— 与 [installedKey] 分开，否则加载期间会被反复重建。 */
    @Volatile private var loadedKey: String = ""

    /** 被我们摘掉的系统景深主体（关闭时要按原位插回）。 */
    private var systemSubject: View? = null
    private var systemSubjectParent: ViewGroup? = null
    private var systemSubjectIndex = 0
    private var systemSubjectVisibility = View.VISIBLE
    private val loadToken = AtomicBoolean(false)

    /** 是否已排过一次 invalidate（合并同一帧内的多次姿态更新）。只在主线程读写。 */
    private var invalidatePending = false

    private var fgLayerId = 0
    private var deductedId = 0

    private fun resolveIds(ctx: Context) {
        if (fgLayerId != 0 && deductedId != 0) return
        runCatching {
            fgLayerId = ctx.resources.getIdentifier(ID_FOREGROUND_LAYER, "id", SYSTEMUI_PKG)
            deductedId = ctx.resources.getIdentifier(ID_DEDUCTED, "id", SYSTEMUI_PKG)
            HookRuntime.log("SPATIAL 景深 id 解析 ⇒ 前景层=$fgLayerId，系统主体=$deductedId")
        }
    }

    /**
     * 是否该接管前景层。
     *
     * 三个条件同时成立：界面开关开着（属性） + 非桌面档 + **渲染器确实按这个配置起来了**
     * （`lockDepthOn` 读的是 `runningLockDepth`，不是属性 —— 生效中改开关不会立刻生效，
     * 这条语义与「生效范围」一致）。
     */
    fun active(): Boolean = SpatialRenderer.lockDepthOn()

    /**
     * 诊断用（问题一探针，v1.5.73）：前景主体的一行状态摘要。
     *
     * 只在**主线程**调（`doBlurAndDim` 的 hook 就在主线程），否则读到的是旧值。
     * 输出形如 `att=true vis=0 a=1.0`；没插进树时是 `none`。
     */
    internal fun probeState(): String {
        val v = view ?: return "none"
        return "att=${v.isAttachedToWindow} vis=${v.visibility} a=${"%.2f".format(v.alpha)}"
    }

    // ───────────────── 解锁退场（2026-10-30，v1.5.74，问题一）─────────────────
    //
    // ## 为什么主体必须自己淡出
    //
    // `KeyguardPanelViewController#startUnlockKeyguardDismissAnim` L7634-7653：直接解锁
    // （`!keyguardBouncerShowing`）时系统会把 `keyguardBouncerFraction` 用 **200ms** 拉到 1，
    // 每帧 `doBlurAndDim(value, false, 14)` ⇒ 景深开时 `applyDepthMiBlurEffect(ratio, 前景层)`
    // ⇒ `view.setMiSelfBlur(...)` 作用于**整棵前景层子树**，我们插进去的主体跟着糊。
    //
    // 系统自己不露馅，是因为它同时对自己的主体做了 `setDepthTransitionAlpha(0f)`；
    // 我们把系统主体摘掉了、换成了自己的，就必须自己补这一步。
    //
    // 时长 **40ms**（2026-10-30 用户实测调整：120ms 仍嫌"消失得不够早"）：
    // 系统 ramp 在 62ms 时 ratio 已到 0.5，此时我们必须已经基本透明。

    private const val UNLOCK_FADE_MS = 40L

    /** 已启动过退场淡出（幂等用；两个触发点先后到达时只让第一个生效）。 */
    @Volatile private var unlockFadeStarted = false

    /** 解锁退场：前景主体快速淡出（幂等，重复调用不会重启动画）。 */
    fun fadeOutOnUnlock() {
        if (Looper.myLooper() == Looper.getMainLooper()) fadeOutOnMain()
        else mainHandler.post { runCatching { fadeOutOnMain() } }
    }

    private fun fadeOutOnMain() {
        val v = view ?: return
        if (unlockFadeStarted) return
        unlockFadeStarted = true
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .setDuration(UNLOCK_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * 回锁屏 / 重新亮屏：把主体 alpha 复位到 1（幂等）。
     *
     * 由 [SpatialRenderer.requestRaiseLocked] 的放行路径调用 —— **必须无条件调**，
     * 不能挂在"锁屏份 alpha 需要复位"那个分支里：GL 的 alpha 可能本来就是 1，
     * 而主体已经被上一次解锁淡到 0 了（那样回锁屏就永远没有前景主体）。
     */
    fun resetAlpha() {
        if (Looper.myLooper() == Looper.getMainLooper()) resetOnMain()
        else mainHandler.post { runCatching { resetOnMain() } }
    }

    private fun resetOnMain() {
        val v = view ?: return
        unlockFadeStarted = false
        v.animate().cancel()
        if (v.alpha < 0.99f) {
            // **不要硬切**（2026-10-30 用户实测：从黑屏进锁屏时主体比背景先亮）。
            // 走 300ms 淡入，与系统壁纸的亮屏淡入同步；scale 一起走，避免单独跳一下。
            v.animate()
                .alpha(1f)
                .scaleX(leashScale)
                .scaleY(leashScale)
                .setDuration(AOD_FADE_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            return
        }
        v.scaleX = leashScale
        v.scaleY = leashScale
    }

    // ───────────────── AOD 进 / 出（2026-10-30，v1.5.76） ─────────────────
    //
    // ## 为什么锁屏态要跟 `leashScale`
    //
    // **只有「与锁屏一致」（`fullAodEnable()`）模式下**，系统才会在锁屏态给壁纸 leash
    // 常驻 `setMatrix(1.05)`（`linkageViewAnim` L1601-1607 的 `f = 1.05f`），我们的 GL 两份
    // 是它的子层 ⇒ 跟着放大 5%；本 View 在 `keyguardForegroundLayer`（窗口内）不吃 leash
    // 矩阵 ⇒ 差 5%、边缘一圈重影。**自定义 AOD 模式下系统从不调 `doWallpaperScaleAnim`**
    // ⇒ leash 就是 1.0，这里也必须 1.0（2026-10-30 用户实测：写死 1.05 会多放大）。
    //
    // 模式由 [SpatialForegroundHider] 每次 `updateKeyguardElementsVisibility` 时反射
    // `miuiFullAodManager.fullAodEnable()` 后通过 [setLeashScale] 下发；未确认前按 1.0。
    //
    // ## AOD 时只淡出、**不要自己缩放**
    //
    // 系统的 `doDeductedImageScaleAnim(f)` 会把 `animationViews`（含 `constraintLayout`
    // —— 本 View 的**祖先**）设成 `f − 0.05`：锁屏 1.00 / AOD 0.95。也就是说系统的 5%
    // 缩放我们**已经被动吃到**；再自己缩一次就是双重缩放（进出各多缩一圈，
    // 2026-10-30 用户实测"莫名多缩放一次"）。详见 [enterAodOnMain] 的注释。

    /**
     * 锁屏态主体该用的缩放：`fullAodEnable()` 为 true 时 **1.05**（跟 leash 的预放大），
     * 否则 **1.0**。默认 1.0 —— 宁可与背景差 5%，也不要凭空多放大。
     */
    @Volatile private var leashScale = 1f

    private const val AOD_FADE_MS = 300L

    /** 由 [SpatialForegroundHider] 在每次锁屏视图更新时下发（幂等）。 */
    fun setLeashScale(scale: Float) {
        if (kotlin.math.abs(scale - leashScale) < 0.001f) return
        leashScale = scale
        if (Looper.myLooper() == Looper.getMainLooper()) applyLeashScaleOnMain()
        else mainHandler.post { runCatching { applyLeashScaleOnMain() } }
    }

    private fun applyLeashScaleOnMain() {
        val v = view ?: return
        // 退场 / AOD 淡出期间不插手动 scale（那时整段交给退场逻辑，值会在下次复位时生效）
        if (unlockFadeStarted) return
        v.scaleX = leashScale
        v.scaleY = leashScale
    }

    /** 进入 AOD：**只淡出，不动 scale**（理由见 [enterAodOnMain]）。 */
    fun onEnterAod(black: Float) {
        if (Looper.myLooper() == Looper.getMainLooper()) enterAodOnMain(black)
        else mainHandler.post { runCatching { enterAodOnMain(black) } }
    }

    /**
     * 进入 AOD 只做淡出。
     *
     * ## 为什么**不能**再自己缩放一次（2026-10-30 v1.5.81 定案）
     *
     * 系统的 `doDeductedImageScaleAnim(f, …)` 会把 `animationViews` 里的每个 View 设成
     * **`f − 0.05`**（L2249-2258），而那个列表含 `constraintLayout` —— 它正是
     * `keyguard_foreground_layer` 的**祖先**（L6468 用 `constraintLayout.findViewById(...)` 取层）。
     * 所以本 View **已经被动吃到**系统的这 5%：
     *
     * | 状态 | `f` | 祖先 scale | leash（GL 两份） |
     * |---|---|---|---|
     * | 锁屏 | 1.05 | **1.00** | 1.05 |
     * | 全屏 AOD | 1.00 | **0.95** | 1.00 |
     *
     * 锁屏态之所以看不出问题，是祖先的 `1.05 − 0.05 = 1.00` 恰好把我们的 1.05 抵平；
     * 进 AOD 后祖先变 0.95，若再自己缩到 1.0，总缩放就成 `0.95 × 1.0 = 0.95`
     * （背景是 1.00）⇒ 主体比背景小 5%，进出各多缩一圈。
     *
     * 正解：AOD 时需要的自身 scale = `leash(1.00) / 祖先(0.95) = 1.0526`，而 [leashScale] = 1.05
     * 已经几乎精确 ⇒ **保持不动**就自动对齐。
     *
     * ⚠️ 例外：视频景深壁纸（`depthVideoEnable`）时系统改用
     * `fullAodAnimationViewsForVideoDepth`，那个列表**不含** `constraintLayout` ⇒ 那条路上
     * 祖先不缩，得自己缩。我们是静态图，走不到；将来支持视频壁纸时这里要加分支。
     *
     * @param black 系统 `doWallpaperBlackAnim` 的目标压暗值（全屏 0.5 / 自定义 1.0）。
     *   当前不用 —— 保留是为了上面那个视频壁纸分支。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun enterAodOnMain(black: Float) {
        val v = view ?: return
        unlockFadeStarted = true      // 与解锁淡出互斥：别让 40ms 那条把节奏抢走
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .setDuration(AOD_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * 退出 AOD（回锁屏）：alpha 淡回 1。
     *
     * `scale` 那一句是**幂等兜底**（某天真有残留被缩过也能自己拉回来）——
     * AOD 期间它从未被改过，所以动画到同一个值没有视觉变化，不构成"第二段缩放"。
     */
    fun onExitAod() {
        if (Looper.myLooper() == Looper.getMainLooper()) exitAodOnMain()
        else mainHandler.post { runCatching { exitAodOnMain() } }
    }

    private fun exitAodOnMain() {
        val v = view ?: return
        unlockFadeStarted = false
        v.animate().cancel()
        v.animate()
            .scaleX(leashScale)
            .scaleY(leashScale)
            .alpha(1f)
            .setDuration(AOD_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /**
     * 幂等装配：把我们的主体 View 挂进 `keyguard_foreground_layer` 的 index 0。
     *
     * **由 [SpatialForegroundHider] 在 `updateKeyguardElementsVisibility` 之后调用** ——
     * 系统每次算显隐都会走到那里，所以锁屏视图重建 / SystemUI 重启后能自己补回来。
     */
    fun ensure(host: ViewGroup) {
        if (!active()) return
        val key = SpatialRenderer.currentKey() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) attach(host, key)
        else mainHandler.post { runCatching { attach(host, key) } }
    }

    /**
     * 把系统那个景深主体**从父容器摘掉**（2026-10-29，问题 2）。
     *
     * ## 为什么不是设 INVISIBLE
     *
     * `updateKeyguardElementsVisibility` 里对前景层算完显隐后会启动一个 folme 动画，
     * 它的 `onBegin`（反编译里是 `KeyguardDepthInteractor$updateDepthVisibility$1`）
     * 在 `isActualDisplayDepth == true` 时执行 `imageView.setVisibility(0)` —— 我们
     * proceed 之后压一次，它下一帧就翻回来。真机表现就是「系统主体还在，要重启 SystemUI
     * 才隐藏」（重启后动画没跑，压制才碰巧保住）。
     *
     * 摘掉之后系统再怎么设 visibility 都不显示；关闭时按原 index + 原 visibility 插回，
     * 系统自己会重算显隐。
     */
    fun detachSystemSubject(host: ViewGroup): Boolean {
        val held = systemSubject
        if (held != null) {
            // ⚠️ 不能拿 `held.rootView` 比：`removeView` 之后它的 parent 为 null，
            // `rootView` 会返回**它自己** ⇒ 永远不等于窗口根 ⇒ 每次调用都误判成"树重建"，
            // 把引用清掉，关闭时就没法插回了（2026-10-29 实测踩到）。
            // 正确判据：我们记下的原父容器就是当前这一层 ⇒ 同一棵树、已经摘过。
            if (systemSubjectParent === host) return true
            // 父容器变了 = 锁屏树重建：旧引用属于废弃树，丢掉，去新树里重摘
            systemSubject = null
            systemSubjectParent = null
        }
        val ctx = host.context
        resolveIds(ctx)
        if (deductedId == 0) return false
        val v = host.rootView.findViewById<View>(deductedId) ?: return false
        val parent = v.parent as? ViewGroup ?: return false
        val idx = parent.indexOfChild(v)
        systemSubjectVisibility = v.visibility
        runCatching { parent.removeView(v) }.onFailure {
            HookRuntime.log("SPATIAL 景深 摘系统主体失败：${it.javaClass.simpleName}: ${it.message}")
            return false
        }
        systemSubject = v
        systemSubjectParent = parent
        systemSubjectIndex = idx
        HookRuntime.log("SPATIAL 景深 已摘掉系统主体（index=$idx，visibility=$systemSubjectVisibility）")
        return true
    }

    /** 关闭时按原 index 插回系统主体（visibility 恢复原值，之后由系统重算接管）。 */
    private fun restoreSystemSubject() {
        val v = systemSubject ?: return
        val p = systemSubjectParent
        systemSubject = null
        systemSubjectParent = null
        if (p == null) return
        runCatching {
            if (v.parent == null) {
                p.addView(v, systemSubjectIndex.coerceIn(0, p.childCount))
                v.visibility = systemSubjectVisibility
            }
        }.onFailure {
            HookRuntime.log("SPATIAL 景深 插回系统主体失败：${it.javaClass.simpleName}: ${it.message}")
        }
        HookRuntime.log("SPATIAL 景深 系统主体已插回（index=$systemSubjectIndex）")
    }

    /**
     * 姿态变了 ⇒ 让主体 View 重绘一帧（2026-10-29 Step 5）。
     *
     * **由 [SpatialRenderer.requestDraw] 在死区 / 节流 / 冻结全部通过之后调用** ——
     * 所以静止时完全不触发、过渡期两边一起冻，不会出现"背景停了主体还在动"。
     *
     * 合并投递：一帧内多次调用只 post 一次 invalidate（传感器 60Hz、绘制跟不上时会堆积）。
     */
    fun onAttitudeChanged() {
        if (view == null) return
        if (invalidatePending) return
        invalidatePending = true
        mainHandler.post {
            invalidatePending = false
            runCatching { view?.invalidate() }
        }
    }

    private fun attach(host: ViewGroup, key: String) {
        val cur = view
        if (cur != null && cur.parent === host && installedKey == key) return
        detach()

        val v = SubjectView(host.context)
        runCatching {
            host.addView(
                v,
                0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }.onFailure {
            HookRuntime.log("SPATIAL 景深 插入前景层失败：${it.javaClass.simpleName}: ${it.message}")
            return
        }
        view = v
        installedKey = key
        loadedKey = ""
        unlockFadeStarted = false
        // 出生即按 leash 的 1.05 摆放，避免插入后先露一帧"小一圈"的主体
        v.scaleX = leashScale
        v.scaleY = leashScale
        HookRuntime.log("SPATIAL 景深 前景主体已插入（key=$key，host=${host.javaClass.simpleName}）")
        loadBitmapsAsync(host.context, key, v)
    }

    private fun detach() {
        val v = view ?: return
        view = null
        installedKey = ""
        loadedKey = ""
        runCatching { (v.parent as? ViewGroup)?.removeView(v) }
        v.releaseBitmaps()
    }

    /**
     * 清场：移掉我们的 View + **把系统主体插回去**（2026-10-29，问题 1）。
     *
     * 必须由三个入口汇合到这里（都幂等）：
     * 1. `SpatialRenderer.stopInternal()` —— 渲染器停掉（关闭替换）；
     * 2. `SpatialForegroundHider` 在**闸门之前**（属性 active=0 时 `handle()` 根本不会进）；
     * 3. 开关从开到关、但渲染器还没重建的那一次 `updateKeyguardElementsVisibility`。
     */
    fun onDisabled() {
        if (view == null && installedKey.isEmpty() && systemSubject == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) disabledOnMain()
        else mainHandler.post { runCatching { disabledOnMain() } }
    }

    private fun disabledOnMain() {
        runCatching { detach() }
        runCatching { restoreSystemSubject() }
    }

    private fun loadBitmapsAsync(ctx: Context, key: String, target: SubjectView) {
        val token = loadToken
        token.set(true)
        Thread {
            val layer = runCatching {
                val uri = Uri.parse("content://$AUTHORITY/spatial/$key/layer1.png")
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            val depth = runCatching {
                val uri = Uri.parse("content://$AUTHORITY/spatial/$key/depth.png")
                val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
            }.getOrNull()

            mainHandler.post {
                if (!token.get() || view !== target) {
                    layer?.recycle(); depth?.recycle()
                    return@post
                }
                if (layer == null || depth == null) {
                    HookRuntime.log(
                        "SPATIAL 景深 位图读取失败（layer=${layer != null} depth=${depth != null}）",
                    )
                    return@post
                }
                target.setBitmaps(layer, depth)
                loadedKey = key
                HookRuntime.log(
                    "SPATIAL 景深 位图就绪 ${layer.width}×${layer.height}" +
                        "（depth ${depth.width}×${depth.height}）",
                )
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 主体 View：一个全屏、透明底的 `drawRect`，内容全部由 AGSL 算出来。
     *
     * 和 GL 里主体份用的是**同一份** layer1 + depth.png、同一套几何、同一套选择性融合。
     */
    private class SubjectView(context: Context) : View(context) {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var shader: RuntimeShader? = null
        private var shaderFailed = false

        private var layer: Bitmap? = null
        private var depth: Bitmap? = null
        private var texShader: BitmapShader? = null
        private var depthShader: BitmapShader? = null

        /** 首帧诊断：只打一次，确认 AGSL 真的画出来了（否则"没报错"和"没画"分不清）。 */
        private var drawnOnce = false

        /** 位移诊断：500ms 节流 + 只在真的变了时打，用来核对"主体有没有跟着姿态走"。 */
        private var lastShiftLogAt = 0L

        /**
         * **pivot 必须对齐系统壁纸 leash**（2026-10-30，v1.5.76）。
         *
         * `KeyguardPanelViewController#doDeductedImageScaleAnim` L2253-2254 给 leash 的
         * matrix 用的是 `MiuiConfigs.getLockScreenSize()` 的 **(50%, 40%)** 作 pivot。
         * View 默认绕中心 (50%,50%) 缩放 ⇒ 放大 1.05 时垂直会差
         * `0.1 × 屏高 × 0.05 ≈ 13px`，正好是主体与背景对不齐的量级。
         */
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w * 0.5f
            pivotY = h * 0.4f
        }
        private var lastShiftX = 0f
        private var lastShiftY = 0f

        fun setBitmaps(layer: Bitmap, depth: Bitmap) {
            releaseBitmaps()
            this.layer = layer
            this.depth = depth
            texShader = BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            depthShader = BitmapShader(depth, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            invalidate()
        }

        fun releaseBitmaps() {
            layer?.recycle()
            depth?.recycle()
            layer = null
            depth = null
            texShader = null
            depthShader = null
        }

        override fun onDraw(canvas: Canvas) {
            val tex = texShader ?: return
            val dep = depthShader ?: return
            val g = SpatialRenderer.geometry() ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val s = shader ?: run {
                if (shaderFailed) return
                runCatching { RuntimeShader(AGSL) }
                    .onFailure {
                        shaderFailed = true
                        HookRuntime.log("SPATIAL 景深 AGSL 编译失败：${it.javaClass.simpleName}: ${it.message}")
                    }
                    .getOrNull()
                    ?.also { shader = it }
                    ?: return
            }

            runCatching {
                s.setInputShader("uTex", tex)
                s.setInputShader("uDepth", dep)
                s.setFloatUniform("uTexSize", layer!!.width.toFloat(), layer!!.height.toFloat())
                s.setFloatUniform("uDepthSize", depth!!.width.toFloat(), depth!!.height.toFloat())
                s.setFloatUniform("uViewSize", w, h)
                s.setFloatUniform("uK", g[0], g[1])
                s.setFloatUniform("uSpan", g[2], g[3])
                // 显示区域：与 GL 同一份值（同一帧读一次，两路共用）
                val crop = SpatialRenderer.cropUniforms()
                s.setFloatUniform("uUvScale", crop[0], crop[0])
                s.setFloatUniform("uUvOffset", crop[1], crop[2])
                // 位移与 GL **同一公式**（[SpatialRenderer.currentShift]：nx·PAN_RAD/span）。
                // 这里自己再算一遍迟早漂移 —— 一律走它，主体才跟得住背景。
                val shift = SpatialRenderer.currentShift(g[2], g[3])
                s.setFloatUniform("uShift", shift[0], shift[1])
                s.setFloatUniform("uDkTh", SpatialRenderer.dkThreshold())
                // AOD 压暗：与 GL 同一份值（GL 走 setColor、这里走 shader）
                s.setFloatUniform("uDim", SpatialRenderer.aodDimValue())
                paint.shader = s
                canvas.drawRect(0f, 0f, w, h, paint)
                if (!drawnOnce) {
                    drawnOnce = true
                    HookRuntime.log(
                        "SPATIAL 景深 首帧已绘制 ${w.toInt()}×${h.toInt()}，" +
                            "k=(${"%.4f".format(g[0])},${"%.4f".format(g[1])}) " +
                            "span=(${"%.4f".format(g[2])},${"%.4f".format(g[3])}) " +
                            "shift=(${"%.5f".format(shift[0])},${"%.5f".format(shift[1])})",
                    )
                }
                // 位移诊断（500ms 节流 + 只报变化）：摇手机时应看到数值在动，
                // 静止归位后回到 0 —— 这是"主体跟姿态走"的唯一直接证据。
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - lastShiftLogAt > 500L &&
                    (kotlin.math.abs(shift[0] - lastShiftX) + kotlin.math.abs(shift[1] - lastShiftY)) > 1e-5f
                ) {
                    lastShiftLogAt = nowMs
                    lastShiftX = shift[0]
                    lastShiftY = shift[1]
                    HookRuntime.log(
                        "SPATIAL 景深 位移 shift=(${"%.5f".format(shift[0])},${"%.5f".format(shift[1])})",
                    )
                }
            }.onFailure {
                if (!shaderFailed) {
                    shaderFailed = true
                    HookRuntime.log("SPATIAL 景深 AGSL 绘制失败：${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
    }
}
