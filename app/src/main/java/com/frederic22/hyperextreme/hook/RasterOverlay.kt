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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 光栅壁纸「**锁屏景深**」的**前景主体层**（2026-11-04）。
 *
 * ## 它解决什么
 *
 * 光栅的两张图挂在壁纸 leash 上 —— 那条 leash 在 SurfaceFlinger 里比 SystemUI 窗口**低**
 * ⇒ 主体永远在时钟**下面**，看不出景深。系统的景深主体之所以能压住时钟，是因为它画在
 * `keyguard_foreground_layer`（`KeyguardPanelView` 的第 3 个子层，第 2 个是时钟）里。
 *
 * 所以这里把**当前这一帧的主体抠像**用 AGSL 画一遍、插进那一层 —— 位置、z 序、可见性
 * 全部跟着系统走。注入骨架与 [SpatialOverlay] 逐条相同（同一层、同一个系统主体、
 * 同一套 AOD / 解锁 / 缩放），差别只在**画什么**：
 *
 * | | 空间壁纸 | 光栅壁纸 |
 * |---|---|---|
 * | 数据 | `layers/layer1.webp` + `depth.png`（位移场） | `raster/<id>/subject/<序号>`（RGBA 抠像） |
 * | 几何 | 球面映射 + 逐像素位移 | **原样铺满**（与背景同一 cover 裁剪，1:1 采样） |
 * | 过渡 | 静止时不动 | 跟随擦除：`mix(from, to, a)`，公式与 [RasterGlCore.FRAG] 逐字一致 |
 *
 * ## 为什么用 AGSL 而不是第二份 GL
 *
 * 与空间壁纸同一条理由：不需要第二套 EGL surface（不会踩「不可见 surface 的
 * `eglSwapBuffers` 阻塞」），与时钟同窗口同一次绘制（没有跨层错位）。
 *
 * ## 主体图为什么必须 CPU 侧 cover 裁到屏幕
 *
 * 主体图是原图尺寸（实测 2802×4830），屏幕 1200×2670 —— 直接 `eval(coord)` 会把整张
 * 压进屏幕（比例不同 ⇒ 拉伸），而且与背景 GL 的 cover 裁剪不一致 ⇒ 错位。
 * 加载时按**与背景完全相同的 cover 矩形**裁到屏幕尺寸（见 [loadSubject]），
 * 之后 View 坐标与位图坐标 1:1，不需要在 shader 里插值。
 *
 * ## 没算过主体的图
 *
 * 该帧主体层**透明**（用户 2026-11-04 明确：等于没开景深）。用一个 1×1 全透明
 * BitmapShader 顶替，`mix` 自然产生淡入淡出，不会闪。
 */
internal object RasterOverlay {

    private const val AUTHORITY = "com.frederic22.hyperextreme.liquid.provider"

    /** 系统的前景层（`res/layout/keyguard_panel_view.xml` 第 3 个子层）。 */
    private const val ID_FOREGROUND_LAYER = "keyguard_foreground_layer"

    /** 系统景深主体（`keyguard_foreground_layer.xml` 里那个全屏 CENTER_CROP 的 ImageView）。 */
    private const val ID_DEDUCTED = "deducted_image_view"

    private const val SYSTEMUI_PKG = "com.android.systemui"

    /**
     * 擦除着色器 —— 与 [RasterGlCore.FRAG] **同一套公式**，只去掉 cover 参数
     * （主体图已经按同一 cover 裁到屏幕尺寸，1:1 采样）。
     *
     * ```
     * e    = dir > 0 ? uv.x : 1 − uv.x
     * edge = −B/2 + t × (1 + B)
     * a    = clamp((edge − e)/B + 0.5, 0, 1)
     * c    = mix(from, to, a)
     * ```
     *
     * `uScale = 主体位图尺寸 / View 尺寸`：正常情况下是 1（两边都是屏幕尺寸），
     * 留它是为了防止 View 与位图尺寸不一致时（系统改了前景层大小）出现错位。
     */
    private const val AGSL = """
        uniform shader uFrom;
        uniform shader uTo;
        uniform float2 uViewSize;
        uniform float2 uScale;
        uniform float uT;
        uniform float uDir;
        uniform float uBand;
        uniform float uDim;

        half4 main(float2 coord) {
            float2 uv = coord / uViewSize;
            // dir > 0（向左转）= 新图从左边进来；与 GL 那份**逐字一致**。
            float e = (uDir > 0.0) ? uv.x : (1.0 - uv.x);
            float edge = -uBand * 0.5 + uT * (1.0 + uBand);
            float a = clamp((edge - e) / uBand + 0.5, 0.0, 1.0);

            float2 p = coord * uScale;
            half4 cf = uFrom.eval(p);
            half4 ct = uTo.eval(p);
            half4 c = mix(cf, ct, half(a));
            // AOD 压暗：与 GL 同一个值（那边乘在片元里）；只乘 RGB，压暗 ≠ 变透明。
            return half4(c.rgb * half(uDim), c.a);
        }
    """

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var view: SubjectView? = null

    /** 已按哪个组 id 插进树里（**不等位图**：位图慢一步，插树要立刻幂等）。 */
    @Volatile private var installedKey: String = ""

    /** 被我们摘掉的系统景深主体（关闭时要按原位插回）。 */
    private var systemSubject: View? = null
    private var systemSubjectParent: ViewGroup? = null
    private var systemSubjectIndex = 0
    private var systemSubjectVisibility = View.VISIBLE

    /** 位图加载代号：换组 / 卸载时 +1，过期的后台结果直接丢弃。 */
    private val loadToken = AtomicInteger(0)

    private var fgLayerId = 0
    private var deductedId = 0

    /** 是否已排过一次 invalidate（合并同一帧内的多次场景更新）。只在主线程读写。 */
    private var invalidatePending = false

    /** 锁屏态主体该用的缩放（跟 leash 的 1.05 预放大，日 1.0）。 */
    @Volatile private var leashScale = 1f
    private const val AOD_FADE_MS = 300L
    private const val UNLOCK_FADE_MS = 40L
    @Volatile private var unlockFadeStarted = false

    /** 当前擦除场景（由 [RasterRenderer.drawScene] 每帧推过来）。 */
    @Volatile private var sceneFrom = 0
    @Volatile private var sceneTo = 0
    @Volatile private var sceneT = 0f
    @Volatile private var sceneDir = 1f

    private fun resolveIds(ctx: Context) {
        if (fgLayerId != 0 && deductedId != 0) return
        runCatching {
            fgLayerId = ctx.resources.getIdentifier(ID_FOREGROUND_LAYER, "id", SYSTEMUI_PKG)
            deductedId = ctx.resources.getIdentifier(ID_DEDUCTED, "id", SYSTEMUI_PKG)
            HookRuntime.log("RASTER 景深 id 解析 ⇒ 前景层=$fgLayerId，系统主体=$deductedId")
        }
    }

    /** 是否该接管前景层：**渲染器确实按这个配置起来了**（不是属性本身，语义同空间壁纸）。 */
    fun active(): Boolean = RasterRenderer.lockDepthOn()

    // ───────────────── 场景同步（GL 线程 → 主线程）─────────────────

    /**
     * 擦除场景变了（**由 `RasterRenderer.drawScene` 在 GL 线程调用**）。
     *
     * `from/to/t/dir` 与背景 GL 是**同一组值** —— 各算一遍迟早漂移，主体就会和背景错位。
     * 这里只记录 + 合并投递一次 invalidate；位图按需异步加载。
     */
    fun onSceneChanged(from: Int, to: Int, t: Float, dir: Float) {
        if (view == null) return
        if (sceneFrom == from && sceneTo == to &&
            kotlin.math.abs(sceneT - t) < 1e-4f && sceneDir == dir
        ) {
            return
        }
        sceneFrom = from
        sceneTo = to
        sceneT = t
        sceneDir = dir
        if (invalidatePending) return
        invalidatePending = true
        mainHandler.post {
            invalidatePending = false
            runCatching {
                val v = view ?: return@runCatching
                v.ensureBitmaps(from, to)
                v.invalidate()
            }
        }
    }

    // ───────────────── 解锁退场 / AOD / 缩放（与 SpatialOverlay 逐条同构）─────────────────

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

    /** 回锁屏 / 重新亮屏：把主体 alpha 复位到 1（幂等）。 */
    fun resetAlpha() {
        if (Looper.myLooper() == Looper.getMainLooper()) resetOnMain()
        else mainHandler.post { runCatching { resetOnMain() } }
    }

    private fun resetOnMain() {
        val v = view ?: return
        unlockFadeStarted = false
        v.animate().cancel()
        if (v.alpha < 0.99f) {
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

    /** 由 `SpatialForegroundHider` 在每次锁屏视图更新时下发（幂等）。 */
    fun setLeashScale(scale: Float) {
        if (kotlin.math.abs(scale - leashScale) < 0.001f) return
        leashScale = scale
        if (Looper.myLooper() == Looper.getMainLooper()) applyLeashScaleOnMain()
        else mainHandler.post { runCatching { applyLeashScaleOnMain() } }
    }

    private fun applyLeashScaleOnMain() {
        val v = view ?: return
        if (unlockFadeStarted) return
        v.scaleX = leashScale
        v.scaleY = leashScale
    }

    /** 进入 AOD：**只淡出，不动 scale**（理由同空间壁纸：系统已经把祖先缩了 5%）。 */
    fun onEnterAod() {
        if (Looper.myLooper() == Looper.getMainLooper()) enterAodOnMain()
        else mainHandler.post { runCatching { enterAodOnMain() } }
    }

    private fun enterAodOnMain() {
        val v = view ?: return
        unlockFadeStarted = true      // 与解锁淡出互斥：别让 40ms 那条把节奏抢走
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .setDuration(AOD_FADE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** 退出 AOD（回锁屏）：alpha 淡回 1。 */
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

    // ───────────────── 装配 / 拆卸 ─────────────────

    /**
     * 幂等装配：把我们的主体 View 挂进 `keyguard_foreground_layer` 的 index 0。
     *
     * **由 `SpatialForegroundHider` 在 `updateKeyguardElementsVisibility` 之后调用** ——
     * 系统每次算显隐都会走到那里，所以锁屏视图重建 / SystemUI 重启后能自己补回来。
     *
     * 与空间壁纸**互斥**：`keyguard_foreground_layer` 只有一个，谁在跑谁占。
     */
    fun ensure(host: ViewGroup) {
        if (!active()) return
        val key = RasterRenderer.currentKey() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) attach(host, key)
        else mainHandler.post { runCatching { attach(host, key) } }
    }

    /**
     * 把系统那个景深主体**从父容器摘掉**（理由见 `SpatialOverlay.detachSystemSubject`：
     * 设 INVISIBLE 会被系统的 folme 动画在下一帧翻回来）。
     */
    fun detachSystemSubject(host: ViewGroup): Boolean {
        val held = systemSubject
        if (held != null) {
            if (systemSubjectParent === host) return true
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
            HookRuntime.log("RASTER 景深 摘系统主体失败：${it.javaClass.simpleName}: ${it.message}")
            return false
        }
        systemSubject = v
        systemSubjectParent = parent
        systemSubjectIndex = idx
        HookRuntime.log("RASTER 景深 已摘掉系统主体（index=$idx，visibility=$systemSubjectVisibility）")
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
            HookRuntime.log("RASTER 景深 插回系统主体失败：${it.javaClass.simpleName}: ${it.message}")
        }
        HookRuntime.log("RASTER 景深 系统主体已插回（index=$systemSubjectIndex）")
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
            HookRuntime.log("RASTER 景深 插入前景层失败：${it.javaClass.simpleName}: ${it.message}")
            return
        }
        view = v
        installedKey = key
        v.groupKey = key
        unlockFadeStarted = false
        v.scaleX = leashScale
        v.scaleY = leashScale
        HookRuntime.log("RASTER 景深 前景主体已插入（key=$key，host=${host.javaClass.simpleName}）")
        // 出生就按当前场景预载一次（否则第一帧是空的，等下一次擦除才有图）。
        v.ensureBitmaps(sceneFrom, sceneTo)
    }

    private fun detach() {
        val v = view ?: return
        view = null
        installedKey = ""
        loadToken.incrementAndGet()
        runCatching { (v.parent as? ViewGroup)?.removeView(v) }
        v.releaseBitmaps()
    }

    /** 清场：移掉我们的 View + **把系统主体插回去**（幂等）。 */
    fun onDisabled() {
        if (view == null && installedKey.isEmpty() && systemSubject == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) disabledOnMain()
        else mainHandler.post { runCatching { disabledOnMain() } }
    }

    private fun disabledOnMain() {
        runCatching { detach() }
        runCatching { restoreSystemSubject() }
    }

    /**
     * 主体 View：全屏、透明底、内容全由 AGSL 算。
     *
     * 3 张位图缓存（上一张 / 当前 / 下一张）：擦除只在两两之间发生，来回转也不用重解码。
     */
    private class SubjectView(context: Context) : View(context) {

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var shader: RuntimeShader? = null
        private var shaderFailed = false

        /** 组 id —— 换组时旧位图必须整体丢弃。 */
        var groupKey: String = ""

        private val bitmaps = LinkedHashMap<Int, Bitmap>()
        private val shaders = HashMap<Int, BitmapShader>()

        /** 已确认"这张没有主体"的序号，避免反复请求 provider。 */
        private val missing = HashSet<Int>()

        /** 正在后台加载的序号。 */
        private val loading = HashSet<Int>()

        /** 1×1 全透明位图 —— 没算过主体的帧用它顶替，`mix` 自然淡入淡出。 */
        private var emptyShader: BitmapShader? = null

        private var lastLoggedFrom = -1
        private var lastLoggedTo = -1

        /** pivot 必须对齐系统壁纸 leash（与 `SpatialOverlay.SubjectView` 同一条）。 */
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w * 0.5f
            pivotY = h * 0.4f
        }

        private fun empty(): BitmapShader {
            emptyShader?.let { return it }
            val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                emptyShader = it
            }
        }

        private fun shaderOf(index: Int): BitmapShader = shaders[index] ?: empty()

        /** 确保 from / to 两张主体在位图缓存里；缺的异步加载。主线程调用。 */
        fun ensureBitmaps(from: Int, to: Int) {
            if (groupKey.isEmpty()) return
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            for (idx in intArrayOf(from, to)) {
                if (idx < 0 || shaders.containsKey(idx) || idx in missing || idx in loading) continue
                loading.add(idx)
                val token = loadTokenSnapshot()
                val ctx = context.applicationContext
                Thread {
                    val bmp = loadSubject(ctx, groupKey, idx, w, h)
                    mainHandler.post {
                        loading.remove(idx)
                        if (token != loadTokenSnapshot() || this !== view) {
                            bmp?.recycle()
                            return@post
                        }
                        if (bmp == null) {
                            missing.add(idx)
                        } else {
                            bitmaps[idx] = bmp
                            shaders[idx] = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                            evictIfNeeded()
                        }
                        invalidate()
                    }
                }.apply { isDaemon = true }.start()
            }
        }

        /** 只留 3 张（每张约 12.8MB）；超出优先丢**不在当前场景里**的最旧项。 */
        private fun evictIfNeeded() {
            if (bitmaps.size <= 3) return
            val keep = hashSetOf(sceneFrom, sceneTo)
            // ⚠️ 不能用 `keys.iterator().remove()` 再 `remove()` 一次：第二次拿到 null，
            // 位图就漏了 recycle（2026-11-04 写的时候差点踩到）。
            val victims = bitmaps.keys.filter { it !in keep }.take(bitmaps.size - 3)
            for (k in victims) {
                shaders.remove(k)
                bitmaps.remove(k)?.recycle()
            }
        }

        fun releaseBitmaps() {
            bitmaps.values.forEach { runCatching { it.recycle() } }
            bitmaps.clear()
            shaders.clear()
            missing.clear()
            loading.clear()
            emptyShader = null
            groupKey = ""
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // 首次布局完成后才拿得到尺寸（`attach` 里 width 还是 0）⇒ 在这里兜底触发一次加载，
            // 否则静止不动时主体永远是空的（场景没变 ⇒ 没人再调 ensureBitmaps）。
            ensureBitmaps(sceneFrom, sceneTo)
            // 一张都没有也画：empty 会让整层透明，等于没开（用户 2026-11-04 明确）。
            val fromS = shaderOf(sceneFrom)
            val toS = shaderOf(sceneTo)

            val s = shader ?: run {
                if (shaderFailed) return
                runCatching { RuntimeShader(AGSL) }
                    .onFailure {
                        shaderFailed = true
                        HookRuntime.log("RASTER 景深 AGSL 编译失败：${it.javaClass.simpleName}: ${it.message}")
                    }
                    .getOrNull()
                    ?.also { shader = it }
                    ?: return
            }

            runCatching {
                s.setInputShader("uFrom", fromS)
                s.setInputShader("uTo", toS)
                s.setFloatUniform("uViewSize", w, h)
                // 位图已按屏幕 cover 裁好 ⇒ 正常是 1:1；留这个比值是防止 View 尺寸被系统改。
                val bw = bitmaps[sceneFrom]?.width ?: bitmaps[sceneTo]?.width ?: 0
                val bh = bitmaps[sceneFrom]?.height ?: bitmaps[sceneTo]?.height ?: 0
                if (bw > 0 && bh > 0) {
                    s.setFloatUniform("uScale", bw / w, bh / h)
                } else {
                    s.setFloatUniform("uScale", 1f, 1f)
                }
                s.setFloatUniform("uT", sceneT)
                s.setFloatUniform("uDir", sceneDir)
                s.setFloatUniform("uBand", RasterGlCore.BAND)
                s.setFloatUniform("uDim", RasterRenderer.aodDimValue())
                paint.shader = s
                canvas.drawRect(0f, 0f, w, h, paint)

                if (lastLoggedFrom != sceneFrom || lastLoggedTo != sceneTo) {
                    lastLoggedFrom = sceneFrom
                    lastLoggedTo = sceneTo
                    HookRuntime.log(
                        "RASTER 景深 场景 #$sceneFrom→#$sceneTo t=${"%.3f".format(sceneT)} " +
                            "dir=${if (sceneDir > 0) "+" else "-"} " +
                            "有主体=${shaders.keys.sorted()}",
                    )
                }
            }.onFailure {
                if (!shaderFailed) {
                    shaderFailed = true
                    HookRuntime.log("RASTER 景深 AGSL 绘制失败：${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
    }

    /** 当前加载代号（切换组 / 卸载时递增，过期结果丢弃）。 */
    private fun loadTokenSnapshot(): Int = loadToken.get()

    /**
     * 读 `raster/<组id>/subject/<序号>` 并**按与背景相同的 cover 裁到屏幕尺寸**。
     *
     * 背景 GL 用 `RasterRenderer.loadBitmap` 做同一件事；主体图与原图同尺寸（用户
     * 2026-11-04 确认），所以两边算出来的 cover 矩形完全一致 ⇒ 主体与背景严丝合缝。
     *
     * 读不到（没算过主体）= 返回 null，上层按"透明"处理。
     */
    private fun loadSubject(ctx: Context, key: String, index: Int, tw: Int, th: Int): Bitmap? =
        runCatching {
            val uri = Uri.parse("content://$AUTHORITY/raster/$key/subject/$index")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sw = bounds.outWidth
            val sh = bounds.outHeight
            if (sw <= 0 || sh <= 0) return null

            // 先让解码器丢一轮像素（与背景同一套：源图比目标大很多时一次大比例双线性会糊）。
            var sample = 1
            while (sw / (sample * 2) >= tw && sh / (sample * 2) >= th) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            if (src.width == tw && src.height == th) return src

            val sAsp = tw.toFloat() / th
            val dAsp = src.width.toFloat() / src.height
            val cw: Int
            val ch: Int
            if (dAsp > sAsp) {
                ch = src.height
                cw = (src.height * sAsp).roundToInt().coerceIn(1, src.width)
            } else {
                cw = src.width
                ch = (src.width / sAsp).roundToInt().coerceIn(1, src.height)
            }
            val cx = (src.width - cw) / 2
            val cy = (src.height - ch) / 2
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            // 画到透明底上：主体的 alpha 必须原样保留（不能用不透明底）。
            Canvas(out).drawBitmap(
                src,
                android.graphics.Rect(cx, cy, cx + cw, cy + ch),
                android.graphics.Rect(0, 0, tw, th),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            src.recycle()
            out
        }.getOrNull()
}
