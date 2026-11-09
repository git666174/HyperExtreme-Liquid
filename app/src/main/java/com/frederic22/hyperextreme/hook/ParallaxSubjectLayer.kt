package com.frederic22.hyperextreme.hook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Surface
import java.io.File
import java.lang.reflect.Method

/**
 * 桌面视差的两层自建 SurfaceControl（2026-10-21）。
 *
 * ## 层级
 *
 * ```
 * WallpaperWindowToken（系统壁纸）
 *   ├ SurfaceControl「he-parallax-bg」     ← 背景层（parallax_bg.png）
 *   └ SurfaceControl「he-parallax-subject」← 主体层（crop_subject）
 * 桌面窗口（图标 / 文件夹 / dock）         ← 仍在最上面，不挡
 * ```
 *
 * 两层都挂在壁纸 leash 下，层级天然正确（壁纸之上、图标之下），
 * 不需要 `TYPE_WALLPAPER` 窗口的 token（那条路 `BadTokenException` 走不通）。
 *
 * ## 四个踩过的坑（都实测过，别改回去）
 *
 * 1. **必须有 `Transaction.setBufferSize`**。
 *    `Builder.setBufferSize` 只是记录，真正分配 buffer 要用 Transaction 下发一次。
 *    不下发的话 layer 在 SurfaceFlinger 里是 `!handle`（无 buffer）→ 标记 invisible →
 *    **屏幕上根本没有我们的层**（看到的是系统壁纸自己）。
 * 2. **必须 `Transaction.show()`**。`Builder` 建出来的 layer 默认带 HIDDEN 标记
 *    （SurfaceFlinger 里 `flags:1`），不显式 show 就永远 invisible。
 * 3. **位移不要用 `setPosition` / matrix 的 translate**。
 *    它们走的是 **layer 自己的 buffer 坐标系**，而父层（壁纸层）带自己的 transform，
 *    子层会继承 —— 实测下发 `pos=(71,-35.5)`，SF 里显示成 `tx=25.13`（被乘 0.354）。
 *    锁屏那边是 `ImageView`，`translationX/Y` 本来就是**屏幕像素**。
 *    所以这里改成**把位图直接画到偏移后的目标矩形**（见 [redraw]），
 *    坐标系 100% 是我们的 canvas，与锁屏 View 逐像素同义。
 * 4. **两层各自的 pivot**。锁屏实测：背景 pivot=(600,1335)（屏幕中心），
 *    主体 pivot=(600,1068)≈屏高 40%（系统景深层自己的锚点）。用同一个 pivot 主体会偏大。
 *
 * ## 图源
 *
 * - 主体：`/data/system/theme_magic/users/0/wallpaper/crop_subject`（`-rwxrwxrwx system`，直接可读）
 * - 背景：由调用方传入（SystemUI 通过模块 provider `content://…/parallax` 读到的位图）
 */
internal object ParallaxSubjectLayer {

    private const val CROP_SUBJECT = "/data/system/theme_magic/users/0/wallpaper/crop_subject"
    private const val NAME = "he-parallax-subject"
    private const val NAME_BG = "he-parallax-bg"

    /**
     * 两次重绘的最小间隔（ms）。
     *
     * 16ms = 60fps 上限。锁屏那边是 `SENSOR_DELAY_GAME`（50Hz），我们跟着传感器节奏走即可，
     * 不再额外限流 —— 之前的 33ms（30fps）就是「帧率明显低于锁屏」的原因。
     * 现在是 **GPU 绘制**（`lockHardwareCanvas`），单帧成本 ~1ms，撑得住。
     */
    private const val REDRAW_MIN_INTERVAL_MS = 16L

    /** 合成器模式的下发节流：只发矩阵+位置，8ms（120Hz 上限）足够。 */
    private const val COMPOSITOR_MIN_INTERVAL_MS = 8L

    /**
     * 变换下发的独立线程（2026-10-21）。
     *
     * **为什么**：`Transaction.apply()` 要 binder 到 SurfaceFlinger，
     * 放在主线程会和 Flutter 桌面的输入/动画抢 CPU —— 桌面态主线程本来就比锁屏忙，
     * 偶尔排队就会让某一帧特别慢，那才是"卡"的体感来源。
     * 主线程只负责算值，反射 + 下发都在这个线程里做。
     * SurfaceFlinger 的 Transaction 是线程安全的，可以放心在工作线程用。
     */
    private val txExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "he-parallax-tx").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 2
        }
    }

    /** 位移变化小于这个像素数就不重绘（挡住传感器噪声）。 */
    private const val MIN_REDRAW_DELTA_PX = 0.5f

    val bgLayer = Layer()
    val subjectLayer = Layer()

    private var subjectBitmap: Bitmap? = null
    private var bgBitmap: Bitmap? = null

    private var txCtor: java.lang.reflect.Constructor<*>? = null
    private var scClsRef: Class<*>? = null
    private var txClsRef: Class<*>? = null
    private var setBufferSize: Method? = null
    private var applyTx: Method? = null
    private var setAlpha: Method? = null

    private var attached = false

    @Volatile
    private var failed = false
    private var logged = false
    private var currentAlpha = -1f

    /**
     * 建层时用的父层标识（`SurfaceControl.toString()`，含 native 地址）。
     *
     * **为什么需要**：`getWallpaperByShowWhenLocked(0, false)` 会在两个
     * `WallpaperWindowToken` 之间切换（实测 #41 `ImageWallpaper` ⇄ #42
     * `MiuiKeyguardPictorialWallpaper`）。我们的层一旦挂上去，`ensure` 里的
     * `if (!isAttached())` 就再也不会重挂 —— 父层换掉之后层还挂在**已被隐藏的那个 token**
     * 下面，画面就"固定在重启前的样子、怎么调都没反应"。
     * 每次 ensure 比对一次，变了就重建。
     */
    private var attachedParentTag: String? = null

    /** shutdown hook 只注册一次。 */
    private var shutdownHookAdded = false

    /**
     * 标定模式（2026-10-21）：暂停画布重绘，只下发 `setPosition`。
     *
     * 目的 = **单独测出父层把 position 改写成什么**（那个 0.354 系数），
     * 才能用逆矩阵把坐标系拉回屏幕。不暂停重绘的话，画布内的偏移和
     * layer 的 position 会混在一起，量不准。
     */
    var calibMode = false
        private set

    /**
     * 合成器模式的 buffer 余量（四边各留多少像素）。
     *
     * **为什么需要**：改成 `setMatrix` 缩放后，内容会超出 buffer 边界被裁掉。
     * 余量要覆盖「缩放外扩 + 位移」的最大值：
     *  - 缩放外扩：水平 `600×(1.15-1)=90`、垂直 `1335×0.15≈200`
     *  - 位移：`bgAmp=71`
     *  ⇒ 水平约 161、垂直约 271。取 280 留一点余量。
     */
    const val MARGIN = 280

    /** 合成器模式开关（`debug.hyperextreme.parallax_compositor=0` 可回退到画布重绘）。 */
    private fun compositorEnabled(): Boolean = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, "debug.hyperextreme.parallax_compositor", "") as? String) != "0"
    }.getOrDefault(true)

    /** 内容在 buffer 内的偏移（居中，= 实际余量）。主线程写、下发线程读，故 volatile。 */
    @Volatile
    private var contentOffX = 0f

    @Volatile
    private var contentOffY = 0f

    /** 现场微调偏移（`debug.hyperextreme.parallax_off_x/y`，单位屏幕像素），10s 缓存。 */
    private var offXCache = 0f
    private var offYCache = 0f
    private var offCacheAt = 0L

    private fun readOffset(): Pair<Float, Float> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - offCacheAt < 10_000L) return offXCache to offYCache
        offCacheAt = now
        runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            offXCache = (get.invoke(null, "debug.hyperextreme.parallax_off_x", "0") as? String)
                ?.toFloatOrNull() ?: 0f
            offYCache = (get.invoke(null, "debug.hyperextreme.parallax_off_y", "0") as? String)
                ?.toFloatOrNull() ?: 0f
        }
        return offXCache to offYCache
    }

    private fun applyTransform(
        layer: Layer,
        scale: Float,
        tx: Float,
        ty: Float,
        pivotX: Float,
        pivotY: Float,
    ) {
        val sc = layer.sc ?: return
        val sm = setMatrixM ?: return
        val sp = setPositionM ?: return
        // **下发放到独立线程**：主线程只算值，反射 + `Transaction.apply()`（binder 到 SF）
        // 都在 `txExecutor` 上做，避免和 Flutter 桌面抢主线程（2026-10-21）。
        txExecutor.execute {
            val m = android.graphics.Matrix()
            // **pivot 不要交给 matrix**：SurfaceFlinger 的 layer matrix 只有 2×2
            // （`matrix22_t`：dsdx/dtdx/dtdy/dsdy），**没有 tx/ty 分量** ——
            // `Matrix.setScale(s,s,px,py)` 把 pivot 编码成的 MTRANS 会被**静默丢弃**，
            // 缩放实际绕 buffer 原点做，内容就整体偏了 `pivot×(1−s)`。
            m.setScale(scale, scale)
            val v = FloatArray(9)
            m.getValues(v)
            runCatching {
                val t = txCtor!!.newInstance()
                sm.invoke(t, sc, m, v)
                // pivot 改由 position 表达。实际变换是 `screen = position + bufferCoord × scale`，
                // 期望是绕 pivot 缩放，所以：
                // position = pivot×(1−scale) − contentOff×scale（+ 传感器位移 + 现场微调）。
                // 全部量都是运行时的，**换分辨率 / 换机型自动成立**。
                val (ax, ay) = readOffset()
                val posX = pivotX * (1f - scale) - contentOffX * scale + tx + ax
                val posY = pivotY * (1f - scale) - contentOffY * scale + ty + ay
                sp.invoke(t, sc, posX, posY)
                applyTx!!.invoke(t)
            }.onFailure {
                if (!failed) {
                    failed = true
                    log("变换下发失败：$it")
                }
            }
        }
    }

    /**
     * 合成器模式：把内容**居中画一次**（之后只改变换，永不重绘）。
     *
     * buffer 比屏幕大 `2×MARGIN`，内容画在正中，四周留出余量给缩放和位移。
     */
    private fun paintContentOnce(
        layer: Layer,
        bitmap: Bitmap,
        screenW: Int,
        screenH: Int,
    ): Boolean {
        val surface = layer.surface ?: return false
        return runCatching {
            val canvas = try {
                surface.lockHardwareCanvas()
            } catch (_: Throwable) {
                surface.lockCanvas(null)
            }
            try {
                val vw = canvas.width
                val vh = canvas.height
                if (vw <= 0 || vh <= 0) return@runCatching false
                val kx = vw.toFloat() / (screenW + 2 * MARGIN)
                val ky = vh.toFloat() / (screenH + 2 * MARGIN)
                contentOffX = MARGIN * kx
                contentOffY = MARGIN * ky
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                // src 按**屏幕**尺寸 centerCrop，dst 落在 buffer 正中。
                val src = centerCropSrc(bitmap, screenW, screenH)
                val dst = android.graphics.RectF(
                    contentOffX,
                    contentOffY,
                    contentOffX + screenW * kx,
                    contentOffY + screenH * ky,
                )
                canvas.drawBitmap(bitmap, src, dst, paint)
                log(
                    "内容已居中绘制：buffer=${vw}x$vh 余量=$MARGIN 偏移=$contentOffX,$contentOffY",
                )
                true
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }.getOrDefault(false)
    }

    /**
     * 标定：暂停重绘，只把 `setPosition(x, y)` / `setMatrix(scale)` 下发给两层。
     *
     * 调用后读 `dumpsys SurfaceFlinger` 的 `toDisplayTransform`，
     * 用来确认 position 与 scale 的真实映射关系。
     */
    fun setPositionForCalibration(x: Float, y: Float, scale: Float = 1f, pivotX: Float = 0f, pivotY: Float = 0f) {
        if (!attached) return
        calibMode = true
        runCatching {
            val sp = setPositionM ?: run {
                log("标定失败：setPosition 不可用")
                return
            }
            val t = txCtor!!.newInstance()
            if (scale != 1f) {
                val m = android.graphics.Matrix()
                m.setScale(scale, scale, pivotX, pivotY)
                val v = FloatArray(9)
                m.getValues(v)
                val sm = setMatrixM
                bgLayer.sc?.let { sm?.invoke(t, it, m, v) }
                subjectLayer.sc?.let { sm?.invoke(t, it, m, v) }
            }
            bgLayer.sc?.let { sp.invoke(t, it, x, y) }
            subjectLayer.sc?.let { sp.invoke(t, it, x, y) }
            applyTx!!.invoke(t)
            log("标定：setPosition($x, $y) scale=$scale pivot=$pivotX,$pivotY 已下发")
        }.onFailure { log("标定失败：$it") }
    }

    /** 退出标定模式。 */
    fun exitCalibration() {
        calibMode = false
        runCatching {
            val sp = setPositionM ?: return
            val t = txCtor!!.newInstance()
            bgLayer.sc?.let { sp.invoke(t, it, 0f, 0f) }
            subjectLayer.sc?.let { sp.invoke(t, it, 0f, 0f) }
            applyTx!!.invoke(t)
            log("标定结束：position 归零")
        }
    }

    private var setPositionM: Method? = null
    private var setMatrixM: Method? = null

    /** 当前挂载的父层标识（没挂载时为 null）。 */
    fun parentTag(): String? = if (attached) attachedParentTag else null

    /**
     * 父层标识。
     *
     * **不能用 `toString()` 全串**：结尾是 `@0x<identityHashCode>`，
     * 而 `getWallpaperByShowWhenLocked` 每次返回**新的 Java 对象**（同一 native layer），
     * hash 每次都变 → 每秒误判「父层已变」→ 销毁重建 → **画面每秒闪一下**。
     *
     * **也不能混用两种来源**：`mNativeObject` 是私有字段，反射有概率被隐藏 API 限制挡住，
     * 一旦一边读到 `n:xxx`、另一边退回 `Surface(...)/@0x...`，**两种格式永不相等**，
     * 于是每次检测都判「变了」（实测 285 次/分钟）。
     *
     * 所以统一只取 `Surface(name=...#<layerId>)/` 这一段：
     * `#<layerId>` 才是层的真实身份，重建了才会变。
     */
    private fun leashTag(sc: Any): String = sc.toString().substringBeforeLast('@')

    /** 父层是否已经换了（需要重建层）。 */
    fun parentChanged(newLeash: Any): Boolean {
        if (!attached) return false
        val tag = attachedParentTag ?: return false
        return tag != leashTag(newLeash)
    }

    private fun log(msg: String) = HookRuntime.log("PSL $msg")

    /**
     * 建两层（背景带实时位图、主体从 crop_subject 读）。
     *
     * @param leash    桌面壁纸的 SurfaceControl（[ParallaxDesktopLeash] 已能拿到）
     * @param bgBitmap 视差背景图；为 null 时背景层留透明（之后可用 [setBackgroundBitmap] 补）
     */
    fun attach(
        loader: ClassLoader,
        leash: Any,
        width: Int,
        height: Int,
        bgBitmap: Bitmap?,
    ): Boolean {
        if (attached) return true
        if (failed) return false
        return runCatching {
            val scCls = Class.forName("android.view.SurfaceControl", false, loader)
            scClsRef = scCls
            val builderCls = Class.forName("android.view.SurfaceControl\$Builder", false, loader)
            val surfaceCls = Class.forName("android.view.Surface", false, loader)

            // ---- Transaction 方法（要先拿到：建层后立刻要用 setBufferSize） ----
            val txCls = Class.forName("android.view.SurfaceControl\$Transaction", false, loader)
            txCtor = txCls.getConstructor()
            txClsRef = txCls
            applyTx = txCls.getMethod("apply")
            setBufferSize = txCls.getMethod(
                "setBufferSize",
                scCls,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            setAlpha = txCls.getMethod("setAlpha", scCls, Float::class.javaPrimitiveType)
            setPositionM = runCatching {
                txCls.getMethod(
                    "setPosition",
                    scCls,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                )
            }.getOrNull()
            setMatrixM = runCatching {
                txCls.getMethod(
                    "setMatrix",
                    scCls,
                    android.graphics.Matrix::class.java,
                    FloatArray::class.java,
                )
            }.getOrNull()

            // ---- 1. 背景层（先建 → 在主体层之下） ----
            // buffer 比屏幕大 2×MARGIN：内容居中画，四周余量留给缩放与位移
            // （合成器模式下缩放由 setMatrix 做，超出 buffer 的部分会被裁掉）。
            val bufW = width + 2 * MARGIN
            val bufH = height + 2 * MARGIN
            val bgSc = createLayer(builderCls, scCls, leash, NAME_BG, bufW, bufH)
            bgLayer.sc = bgSc
            bgLayer.surface = surfaceCls.getConstructor(scCls).newInstance(bgSc) as Surface
            applyBufferSize(bgSc, bufW, bufH)

            // ---- 2. 主体层 ----
            val subjectFile = File(CROP_SUBJECT)
            val subjBmp = if (subjectFile.isFile) {
                runCatching { BitmapFactory.decodeFile(CROP_SUBJECT) }.getOrNull()
            } else {
                null
            }
            if (subjBmp == null) {
                log("没有可用的主体图（$CROP_SUBJECT）—— 只做背景层")
            } else {
                val subjSc = createLayer(builderCls, scCls, leash, NAME, bufW, bufH)
                subjectLayer.sc = subjSc
                subjectLayer.surface = surfaceCls.getConstructor(scCls).newInstance(subjSc) as Surface
                applyBufferSize(subjSc, bufW, bufH)
                subjectBitmap = subjBmp
            }

            this.bgBitmap = bgBitmap

            // **HIDDEN 标记必须显式去掉**（Builder 默认带 flags:1）。
            runCatching {
                val show = txCls.getMethod("show", scCls)
                val t = txCtor!!.newInstance()
                show.invoke(t, bgSc)
                subjectLayer.sc?.let { show.invoke(t, it) }
                applyTx!!.invoke(t)
            }.onFailure { log("show 失败：$it") }

            attached = true
            attachedParentTag = leashTag(leash)
            // **进程退出前自己把层删掉**（2026-10-21）。
            // `killall com.android.systemui` 发的是 SIGTERM，我们来得及跑 shutdown hook；
            // 不跑的话层会以 **`alpha=1` + `handleNotAlive`** 的状态留在 SurfaceFlinger 里，
            // **永久盖住壁纸** —— 用户关闭「视差效果覆盖桌面」后画面还是我们的层，
            // 只有重启 miwallpaper（整个 token 子层清空）才恢复。
            if (!shutdownHookAdded) {
                shutdownHookAdded = true
                runCatching {
                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            runCatching { destroy() }
                        },
                    )
                }.onFailure { log("shutdown hook 注册失败：$it") }
            }
            log(
                "两层已挂到壁纸 leash 下（${width}x$height，" +
                    "背景=${if (bgBitmap != null) "已画" else "待画"}，" +
                    "主体=${if (subjectBitmap != null) "已画" else "无"}）",
            )
            true
        }.getOrElse {
            failed = true
            log("建层失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /** 给 layer 真正分配 buffer（否则 SurfaceFlinger 里是 `!handle` → invisible）。 */
    private fun applyBufferSize(target: Any, width: Int, height: Int) {
        runCatching {
            val t = txCtor!!.newInstance()
            setBufferSize!!.invoke(t, target, width, height)
            applyTx!!.invoke(t)
        }.onFailure { log("setBufferSize 失败：$it") }
    }

    /** 背景位图晚到 / 换图时补上。 */
    fun setBackgroundBitmap(bmp: Bitmap) {
        bgBitmap = bmp
        // 强制下一次 redraw 生效（参数没变也要重画一次）。
        bgLayer.lastScale = Float.NaN
    }

    /**
     * 按锁屏那套 View 变换**重画一层**。
     *
     * dst 矩形 = 「centerCrop 后的 width×height 视口」按 [scale]（绕 [pivotX],[pivotY]）放大，
     * 再平移 ([tx],[ty]) —— 与锁屏 `ImageView` 的 `scaleX/scaleY` + `translationX/Y` 同义。
     */
    fun redraw(
        layer: Layer,
        bitmap: Bitmap?,
        scale: Float,
        tx: Float,
        ty: Float,
        pivotX: Float,
        pivotY: Float,
        width: Int,
        height: Int,
    ) {
        val bmp = bitmap ?: return
        // 标定模式：不重绘，只让 setPosition 单独生效（否则量不准）。
        if (calibMode) return
        // 位移/缩放没变就不动（静止时零开销）。
        // **必须带阈值**：传感器噪声让 tilt 每次采样都有 ~1e-5 的微变。
        if (layer.lastScale == scale &&
            kotlin.math.abs(layer.lastTx - tx) < MIN_REDRAW_DELTA_PX &&
            kotlin.math.abs(layer.lastTy - ty) < MIN_REDRAW_DELTA_PX
        ) {
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        // ---- 合成器模式（默认）：内容**只画一次**，之后每帧只下发 matrix + position ----
        // 零重绘、零纹理上传、零 buffer 提交，开销与锁屏 View 变换同构。
        if (compositorEnabled()) {
            if (!layer.contentPainted) {
                if (paintContentOnce(layer, bmp, width, height)) layer.contentPainted = true
            }
            // 只下发变换很便宜，节流可以放宽到 8ms（120Hz 上限）。
            if (now - layer.lastDrawAt < COMPOSITOR_MIN_INTERVAL_MS) return
            layer.lastScale = scale
            layer.lastTx = tx
            layer.lastTy = ty
            layer.lastDrawAt = now
            applyTransform(layer, scale, tx, ty, pivotX, pivotY)
            return
        }
        // ---- 兜底：旧的全屏重绘路径（`debug.hyperextreme.parallax_compositor=0`）----
        val surface = layer.surface ?: return
        // 时间节流：两次重绘至少隔 REDRAW_MIN_INTERVAL_MS（满分辨率绘制很贵）。
        if (now - layer.lastDrawAt < REDRAW_MIN_INTERVAL_MS) return
        layer.lastScale = scale
        layer.lastTx = tx
        layer.lastTy = ty
        layer.lastDrawAt = now
        runCatching {
            // **优先 lockHardwareCanvas**：GPU 绘制，lock 几乎不阻塞；
            // 软件 `lockCanvas` 要 CPU 画 1200×2670（~20ms）且在主线程**同步等 buffer**，
            // 一旦慢过帧间隔延迟就累积 —— 表现就是「画面跟不上手」。
            // 拿不到硬件 canvas 时回退软件版（用 try-catch 兜底）。
            var hw = true
            val canvas = try {
                surface.lockHardwareCanvas()
            } catch (_: Throwable) {
                hw = false
                surface.lockCanvas(null)
            }
            try {
                // **用 canvas 的真实尺寸确定坐标系**，不假设 buffer 尺寸。
                // `setBufferSize` 只是申请值，SurfaceFlinger 实际分配的 buffer 可能不同。
                // 画布有多大就以它为准，屏幕像素按比例映射进去。
                val vw = canvas.width
                val vh = canvas.height
                if (vw <= 0 || vh <= 0) return@runCatching
                val kx = vw.toFloat() / width
                val ky = vh.toFloat() / height
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                val src = centerCropSrc(bmp, vw, vh)
                val m = android.graphics.Matrix()
                m.setScale(scale, scale, pivotX * kx, pivotY * ky)
                m.postTranslate(tx * kx, ty * ky)
                val box = android.graphics.RectF(0f, 0f, vw.toFloat(), vh.toFloat())
                m.mapRect(box)
                canvas.drawBitmap(bmp, src, box, paint)
                if (!logged) {
                    logged = true
                    log(
                        "首帧重绘：画布=${vw}x$vh（屏幕=${width}x$height，比例 ${kx}x$ky），" +
                            "硬件画布=$hw，scale=$scale pivot=$pivotX,$pivotY pos=$tx,$ty",
                    )
                }
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }.onFailure {
            if (!failed) {
                failed = true
                log("重绘失败：$it")
            }
        }
    }

    /** centerCrop 源区域：图按比例放大到刚好盖住视口，再居中裁切。 */
    private fun centerCropSrc(bmp: Bitmap, width: Int, height: Int): Rect {
        val imgAspect = bmp.width.toFloat() / bmp.height
        val viewAspect = width.toFloat() / height
        return if (imgAspect > viewAspect) {
            val w = (bmp.height * viewAspect).toInt().coerceAtMost(bmp.width)
            val x = (bmp.width - w) / 2
            Rect(x, 0, x + w, bmp.height)
        } else {
            val h = (bmp.width / viewAspect).toInt().coerceAtMost(bmp.height)
            val y = (bmp.height - h) / 2
            Rect(0, y, bmp.width, y + h)
        }
    }

    private fun createLayer(
        builderCls: Class<*>,
        scCls: Class<*>,
        leash: Any,
        name: String,
        width: Int,
        height: Int,
    ): Any {
        val builder = builderCls.getConstructor().newInstance()
        builderCls.getMethod("setName", String::class.java).invoke(builder, name)
        builderCls.getMethod(
            "setBufferSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(builder, width, height)
        builderCls.getMethod("setFormat", Int::class.javaPrimitiveType)
            .invoke(builder, PixelFormat.TRANSLUCENT)
        builderCls.getMethod("setParent", scCls).invoke(builder, leash)
        runCatching {
            builderCls.getMethod("setHidden", Boolean::class.javaPrimitiveType)
                .invoke(builder, false)
        }
        return builderCls.getMethod("build").invoke(builder)
    }

    fun setAlphaTo(alpha: Float) {
        if (!attached) return
        // 状态没变就不下发（这函数每帧都会被调到）。
        if (currentAlpha == alpha) return
        currentAlpha = alpha
        runCatching {
            val t = txCtor!!.newInstance()
            setAlpha!!.invoke(t, bgLayer.sc, alpha)
            subjectLayer.sc?.let { setAlpha!!.invoke(t, it, alpha) }
            applyTx!!.invoke(t)
            log("两层 alpha → $alpha")
        }.onFailure { log("setAlpha 失败：$it") }
    }

    fun hide() = setAlphaTo(0f)

    fun show() = setAlphaTo(1f)

    /**
     * 真正销毁两层（`SurfaceControl.release()`）。
     *
     * `setAlpha(0)` 只是让它透明 —— **layer 还活着，还占着 12MB 的 BLAST buffer**，
     * 用户看到「关掉后还在」就是这个。下次开关打开时 [attach] 会重建。
     */
    fun destroy() {
        if (!attached) {
            // 即使没挂上也要清 failed —— 否则一次 attach 失败后，
            // attach() 里的 `if (failed) return false` 会把它永久挡在门外（层再也建不出来）。
            failed = false
            return
        }
        log("destroy 开始（bg=${bgLayer.sc != null}, subj=${subjectLayer.sc != null}）")
        runCatching {
            // 先把 alpha 归零再删，确保删除生效前不会留一帧亮着的层。
            runCatching {
                val t = txCtor!!.newInstance()
                setAlpha!!.invoke(t, bgLayer.sc, 0f)
                subjectLayer.sc?.let { setAlpha!!.invoke(t, it, 0f) }
                applyTx!!.invoke(t)
            }
            // **真实删层**：`Transaction.remove(sc)` 把 layer 从父层摘除并销毁。
            // `SurfaceControl.release()` 只是释放本地引用 —— 实测层仍留在 SurfaceFlinger 的树里
            // （`handleNotAlive`），每重启一次 SystemUI 就累积 2 个，十几个层各占 12MB。
            val remove = runCatching {
                txClsRef?.getMethod("remove", scClsRef)
            }.getOrNull()
            if (remove != null) {
                val t = txCtor!!.newInstance()
                bgLayer.sc?.let { remove.invoke(t, it) }
                subjectLayer.sc?.let { remove.invoke(t, it) }
                applyTx!!.invoke(t)
                log("两层已删除（Transaction.remove）")
            } else {
                // 旧 API 兜底。
                val release = scClsRef?.getMethod("release")
                release?.invoke(bgLayer.sc)
                release?.invoke(subjectLayer.sc)
                log("remove 不可用，退回 release")
            }
        }.onFailure { log("销毁失败：${it.javaClass.simpleName}: ${it.message}") }
        // Surface 对象本身也要释放，否则会泄漏。
        runCatching { bgLayer.surface?.release() }
        runCatching { subjectLayer.surface?.release() }
        bgLayer.sc = null
        bgLayer.surface = null
        bgLayer.lastScale = Float.NaN
        bgLayer.contentPainted = false
        subjectLayer.sc = null
        subjectLayer.surface = null
        subjectLayer.lastScale = Float.NaN
        subjectLayer.contentPainted = false
        attached = false
        attachedParentTag = null
        currentAlpha = -1f
        failed = false
    }

    fun isAttached(): Boolean = attached

    fun hasBackground(): Boolean = bgBitmap != null

    fun subjectBitmap(): Bitmap? = subjectBitmap

    fun backgroundBitmap(): Bitmap? = bgBitmap

    /** 一层的运行时状态（Surface + 上次绘制参数）。 */
    class Layer {
        var sc: Any? = null
        var surface: Surface? = null
        var lastScale = Float.NaN
        var lastTx = Float.NaN
        var lastTy = Float.NaN
        var lastDrawAt = 0L
        /** 合成器模式：内容是否已画过一次（画过就永不再画）。 */
        var contentPainted = false
    }
}
