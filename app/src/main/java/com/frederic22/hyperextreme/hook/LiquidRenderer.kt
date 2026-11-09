package com.frederic22.hyperextreme.hook

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import com.frederic22.hyperextreme.data.LiquidFlag
import com.frederic22.hyperextreme.data.LiquidMask
import com.frederic22.hyperextreme.data.LiquidTune
import com.frederic22.hyperextreme.data.MaskPrefs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * 液态壁纸：**LSPosed 链正式渲染器**（2026-11-09，SystemUI 进程内）。
 *
 * ## 它做什么
 *
 * 把系统当前壁纸做成一池液体：自建 SurfaceControl → `reparent` 到锁屏壁纸的 leash 上，
 * 每帧先在 256 宽的 RGBA8 高度场上跑一遍波动方程，再用 ∇h 折射采样原图。
 * 点触、晃动、倾斜都会改变 h，画面就「活」了。
 *
 * 链路与 [SpatialRenderer] 逐项相同（那条已被真机验证）：
 *
 * | 环节 | 做法 |
 * |---|---|
 * | 画布 | `SurfaceControl.Builder` + `Surface` + `eglCreateWindowSurface` |
 * | 挂载 | `WindowManagerGlobal#getWallpaperByShowWhenLocked(0, locked)` → `getLeash()` |
 * | 线程 | 单条 `HandlerThread`，EGL context 绑线程，跨线程调用必崩 |
 * | 图源 | `WallpaperManager#getDrawable`（系统当前壁纸） |
 * | 触摸 | [LiquidTouch] 从 `keyguard_root_view` 旁听，不消费 |
 *
 * ## 与 SpatialRenderer 的三个关键差异
 *
 * 1. **不用传感器积分**：空间壁纸是「相机在球心转」，需要 yaw/pitch；液态只需要
 *    重力方向（低通后的加速度），倾角直接当液面基准，不需要归位动画。
 * 2. **没有 AOD / 解锁淡出适配**：本版只做「锁屏可见时出帧」，解锁瞬间停帧；
 *    leash 被系统隐藏，我们不做 alpha 动画（少一条会出 bug 的路径）。
 * 3. **不可见时绝不 swap**：与 SpatialRenderer 同一条铁律 —— 系统不消费 buffer 时
 *    `eglSwapBuffers` 会**阻塞 GL 线程**，醒来后整条链卡死。所以每帧画之前都重新
 *    问一次 `isKeyguardLocked && isInteractive`。
 *
 * ## 帧预算（1080×2400 参考）
 *
 * | 项 | 量 |
 * |---|---|
 * | sim pass | 256×569 ≈ 14.6 万像素 × 1 次 |
 * | draw pass | 全屏 1 次 + base 图 3 次采样（色散） |
 * | 常驻纹理 | base ≈ 10MB + sim 2×0.58MB |
 *
 * 静止 `[IDLE_MS]` 后停帧（保留最后一帧），触摸 / 晃动 / 倾斜变化会重新唤醒
 * —— 水面静止时逐帧重画是最蠢的耗电方式。
 *
 * ## 怎么开（当前版本还没有 App 界面）
 *
 * ```
 * adb shell setprop debug.hyperextreme.liquid_active 1
 * adb shell setprop debug.hyperextreme.liquid_mode 0     # 0 水波 / 1 果冻
 * adb shell setprop debug.hyperextreme.liquid_scope 0    # 0 锁屏 / 1 桌面 / 2 全局
 * adb shell setprop debug.hyperextreme.liquid_key ""     # 空 = 用系统当前壁纸
 * ```
 *
 * `active/key/scope/mode` 只在**启动时**读一次：三项任一变化都会重建渲染器
 * （SystemUI 每秒轮询一次属性）。换图 / 换模式没生效时，先 `active=0` 再 `=1`。
 */
internal object LiquidRenderer {

    private const val FRAME_MS = 16L

    /** 最后一次输入之后继续出帧多久（波要荡完）。 */
    private const val IDLE_MS = 3200L

    /** 兜底可见性轮询：`KeyguardLockedStateListener` 之外的保险（灭亮屏、异常状态）。 */
    private const val VISIBILITY_MS = 700L

    /** 触点振幅（h 的归一化量，1.0 = 满量程）。 */
    private const val AMP_DOWN = 0.50f
    private const val AMP_MOVE = 0.22f

    /** 图片缩到多宽：1080×2400 的壁纸 1080 足够，再大只是白占 18MB 显存。 */
    private const val MAX_BASE_WIDTH = 1080

    // ─────────────────────────── 状态（GL 线程为主） ───────────────────────────

    private var handler: Handler? = null
    private var thread: HandlerThread? = null

    @Volatile private var running = false
    private var runKey = ""
    private var runScope = -1
    private var runMode = -1
    private var params: LiquidGlCore.Params = LiquidGlCore.WATER

    private var appContext: Context? = null

    // EGL
    private var display: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null

    // 挂载点（锁屏 / 桌面各一份）
    private val mounts = ArrayList<Mount>()

    // GL
    private var simProgram = 0
    private var drawProgram = 0
    private var simPos = 0
    private var drawPos = 0
    private var sPrev = 0
    private var sMaskOn = 0
    private var sTexel = 0
    private var sC2 = 0
    private var sDamp = 0
    private var sMaskCenter = 0
    private var sMaskRadius = 0
    private var sTilt = 0
    private var sLevel = 0
    private var sAspect = 0
    private var sSplat0 = 0
    private var sSplat1 = 0
    private var dBase = 0
    private var dSim = 0
    private var dMaskOn = 0
    private var dTexel = 0
    private var dScreen = 0
    private var dImgScale = 0
    private var dMaskCenter = 0
    private var dMaskRadius = 0
    private var dRefract = 0
    private var dDisp = 0
    private var dSpec = 0
    private var dJelly = 0
    private var dLight = 0

    private var quad: FloatBuffer? = null

    private var baseTex = 0
    // 参数 / 选区（属性热更，见 refreshTune）
    private var tune: LiquidTune = LiquidTune.WATER
    private var maskCfg: LiquidMask = LiquidMask.FULL
    private var tuneRaw = ""
    private var maskRaw = ""
    private var frameNo = 0
    private val simTex = IntArray(2)
    private val simFbo = IntArray(2)
    private var simCur = 0
    private var simW = 0
    private var simH = 0

    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    private val imgScale = floatArrayOf(1f, 1f)

    // 计时 / 调度
    @Volatile private var frameScheduled = false

    @Volatile private var activeUntil = 0L
    @Volatile private var lastTouchAt = 0L

    // 传感器
    private var sensorManager: android.hardware.SensorManager? = null
    private var accelListener: android.hardware.SensorEventListener? = null

    @Volatile private var tiltX = 0f
    @Volatile private var tiltY = 0f
    @Volatile private var shakeEnergy = 0f

    // 锁屏状态
    private var keyguardManager: KeyguardManager? = null
    private var keyguardListener: KeyguardManager.KeyguardLockedStateListener? = null

    // 触摸（UI 线程写、GL 线程读）
    private val touches = ConcurrentLinkedQueue<FloatArray>()

    private var swapErrors = 0
    private var shownFrames = 0

    // ─────────────────────────── 生命周期 ───────────────────────────

    /** 是否在跑（触摸桥每来一个事件读一次，必须便宜）。 */
    val isRunning: Boolean get() = running

    fun start(ctx: Context?, key: String, scope: Int, mode: Int) {
        val normalizedScope = LiquidFlag.normalizeScope(scope)
        val normalizedMode = LiquidFlag.normalizeMode(mode)
        if (running && key == runKey && normalizedScope == runScope && normalizedMode == runMode) return
        if (ctx == null) {
            HookRuntime.log("LIQUID 启动失败：拿不到 Context")
            return
        }
        val app = ctx.applicationContext ?: ctx
        val h = ensureHandler()
        h.post {
            runCatching { startInternal(app, key, normalizedScope, normalizedMode) }
                .onFailure {
                    HookRuntime.log("LIQUID 启动异常：${it.javaClass.simpleName}: ${it.message}")
                    // 半途失败必须清掉已经建出来的 SC / EGL，否则会留一层僵尸在 SurfaceFlinger 里
                    runCatching { stopInternal("启动失败") }
                }
        }
    }

    /** 停止并拆掉所有层（幂等）。 */
    fun stop() {
        val h = handler ?: return
        h.post { runCatching { stopInternal("主动停止") } }
    }

    private fun ensureHandler(): Handler {
        handler?.let { return it }
        val t = HandlerThread("he-liquid-gl").apply { start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    // ─────────────────────────── 启动 ───────────────────────────

    private fun startInternal(ctx: Context, key: String, scope: Int, mode: Int) {
        stopInternal("重启")
        // 上一次会话残留的触点 / 姿态一律作废，否则新的一帧会先被「上一把的波纹」踢一下
        touches.clear()
        lastTouchAt = 0L
        tiltX = 0f
        tiltY = 0f
        val t0 = SystemClock.elapsedRealtime()
        appContext = ctx
        runKey = key
        runScope = scope
        runMode = mode
        tune = if (mode == LiquidFlag.MODE_JELLY) LiquidTune.JELLY else LiquidTune.WATER
        params = LiquidGlCore.params(mode, tune)
        maskCfg = LiquidMask.FULL
        tuneRaw = ""
        maskRaw = ""
        // 属性里可能已经有页面推来的参数（App 启动补推 / 刚拖过滑块）
        refreshTune()

        val dm = android.content.res.Resources.getSystem().displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        check(screenW > 0 && screenH > 0) { "屏幕尺寸非法 ${screenW}x$screenH" }

        initEgl()
        // 一、挂载目标（scope 决定挂哪条 leash）
        val wantLock = scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        val wantDesk = scope != MaskPrefs.SPATIAL_SCOPE_LOCK
        for (locked in booleanArrayOf(true, false)) {
            if (locked && !wantLock) continue
            if (!locked && !wantDesk) continue
            val m = runCatching { buildMount(locked) }.getOrNull()
            if (m == null) {
                HookRuntime.log("LIQUID 建挂载失败：locked=$locked")
                continue
            }
            mounts.add(m)
        }
        check(mounts.isNotEmpty()) { "没有可用的挂载目标（scope=$scope）" }
        val first = mounts[0].eglSurface ?: error("eglSurface 为空")
        check(EGL14.eglMakeCurrent(display, first, first, eglContext)) { "eglMakeCurrent 失败" }

        // 二、GL 资源（program / 纹理 / FBO / 顶点）
        initGl()

        // 三、图源 + 首帧（**必须在 reparent 之前**：此时 buffer 队列为空，swap 不阻塞）
        val bmp = loadBase(ctx, key)
        if (bmp == null) {
            // 没有图源时宁可不启动：一屏纯黑比「没有效果」更难排查
            HookRuntime.log("LIQUID 拿不到壁纸图（文件 / WallpaperManager 都失败），放弃启动")
            stopInternal("无图源")
            return
        }
        uploadBase(bmp)
        bmp.recycle()
        drawFrame(force = true)
        HookRuntime.log("LIQUID 首帧完成（swapErrors=$swapErrors，base=${baseTex != 0}）")

        // 四、挂到壁纸 leash
        for (m in mounts) {
            if (!reparent(m)) {
                HookRuntime.log("LIQUID 挂载失败：locked=${m.locked}")
            }
        }
        val mounted = mounts.filter { it.leash != null }
        if (mounted.isEmpty()) error("全部挂载失败")
        if (mounted.size != mounts.size) {
            for (m in mounts) if (m.leash == null) destroyMount(m)
            mounts.clear()
            mounts.addAll(mounted)
        }

        running = true
        setProp(LiquidFlag.PROP_RENDERING, "1")
        startSensor(ctx)
        watchKeyguard(ctx)
        handler?.postDelayed(visibilityTick, VISIBILITY_MS)
        // 锁屏态启动：立刻跑起来；桌面态启动：等 keyguard 回调 / 可见性轮询唤醒
        requestFrames(2500)
        HookRuntime.log(
            "LIQUID 渲染器已启动：key=$key mode=$mode scope=$scope，" +
                "屏幕 ${screenW}x$screenH，sim ${simW}x$simH，" +
                "base=${if (baseTex != 0) "${imgScale[0]}/${imgScale[1]}" else "无"}，" +
                "耗时 ${SystemClock.elapsedRealtime() - t0}ms",
        )
    }

    private fun initEgl() {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(d != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(d, ver, 0, ver, 1)) { "eglInitialize 失败" }
        display = d
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(d, cfgAttrs, 0, configs, 0, 1, num, 0) && num[0] > 0,
        ) { "eglChooseConfig 失败" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            d, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }
    }

    private fun buildMount(locked: Boolean): Mount {
        val d = display ?: error("display 为空")
        val cfg = eglConfig ?: error("eglConfig 为空")
        val name = if (locked) "he-liquid-lock" else "he-liquid-desk"
        val sc = android.view.SurfaceControl.Builder()
            .setName(name)
            .setBufferSize(screenW, screenH)
            .build()
        val surface = Surface(sc)
        val es = EGL14.eglCreateWindowSurface(d, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (es == null || es == EGL14.EGL_NO_SURFACE) {
            runCatching { surface.release() }
            error("eglCreateWindowSurface 失败")
        }
        return Mount(locked, sc, surface, es)
    }

    private fun reparent(m: Mount): Boolean {
        return runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal", false, javaClass.classLoader)
            val inst = wmg.getMethod("getInstance").invoke(null)
            val gw = wmg.getMethod(
                "getWallpaperByShowWhenLocked",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            val token = gw.invoke(inst, 0, m.locked) ?: return false
            val leash = token.javaClass.getMethod("getLeash").invoke(token)
                as? android.view.SurfaceControl ?: return false
            val txClass = Class.forName(
                "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
            )
            val tx = txClass.getConstructor().newInstance()
            txClass.getMethod(
                "reparent",
                android.view.SurfaceControl::class.java,
                android.view.SurfaceControl::class.java,
            ).invoke(tx, m.sc, leash)
            txClass.getMethod("show", android.view.SurfaceControl::class.java).invoke(tx, m.sc)
            txClass.getMethod("apply").invoke(tx)
            m.leash = leash
            HookRuntime.log(
                "LIQUID 挂载：locked=${m.locked} leash=@${Integer.toHexString(System.identityHashCode(leash))}",
            )
            true
        }.getOrElse {
            HookRuntime.log("LIQUID 挂载异常：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    private fun stopInternal(reason: String) {
        val wasRunning = running
        running = false
        activeUntil = 0L
        frameScheduled = false
        handler?.removeCallbacksAndMessages(null)
        stopSensor()
        removeKeyguardListener()

        val d = display
        if (d != null) {
            // 先切回任意一份 surface 再删 GL 对象（没有 current context 时 glDelete 是空操作）
            val any = mounts.firstOrNull()?.eglSurface
            if (any != null && any != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglMakeCurrent(d, any, any, eglContext) }
            }
            releaseGl()
            runCatching {
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
        }
        mounts.forEach { destroyMount(it) }
        mounts.clear()

        eglContext?.let { c ->
            d?.let { dd -> runCatching { EGL14.eglDestroyContext(dd, c) } }
        }
        if (d != null) {
            runCatching { EGL14.eglTerminate(d) }
        }
        display = null
        eglContext = null
        eglConfig = null
        appContext = null
        runKey = ""
        runScope = -1
        runMode = -1
        swapErrors = 0
        shownFrames = 0
        imgScale[0] = 1f
        imgScale[1] = 1f
        if (wasRunning) setProp(LiquidFlag.PROP_RENDERING, "0")
        HookRuntime.log("LIQUID 已停止（$reason）")
    }

    private fun destroyMount(m: Mount) {
        val d = display
        m.eglSurface?.let { es ->
            if (d != null) runCatching { EGL14.eglDestroySurface(d, es) }
        }
        m.eglSurface = null
        runCatching { m.surface.release() }
        runCatching {
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction", false, javaClass.classLoader)
            val tx = txClass.getConstructor().newInstance()
            txClass.getMethod("remove", android.view.SurfaceControl::class.java).invoke(tx, m.sc)
            txClass.getMethod("apply").invoke(tx)
        }
    }

    // ─────────────────────────── GL 初始化 ───────────────────────────

    private fun initGl() {
        simProgram = LiquidGlCore.program(LiquidGlCore.VERT, LiquidGlCore.SIM)
        drawProgram = LiquidGlCore.program(LiquidGlCore.VERT, LiquidGlCore.DRAW)
        check(simProgram != 0 && drawProgram != 0) { "shader 编译/链接失败" }

        simPos = GLES20.glGetAttribLocation(simProgram, "aPos")
        sPrev = GLES20.glGetUniformLocation(simProgram, "uPrev")
        sMaskOn = GLES20.glGetUniformLocation(simProgram, "uMaskOn")
        sTexel = GLES20.glGetUniformLocation(simProgram, "uTexel")
        sC2 = GLES20.glGetUniformLocation(simProgram, "uC2")
        sDamp = GLES20.glGetUniformLocation(simProgram, "uDamp")
        sMaskCenter = GLES20.glGetUniformLocation(simProgram, "uMaskCenter")
        sMaskRadius = GLES20.glGetUniformLocation(simProgram, "uMaskRadius")
        sTilt = GLES20.glGetUniformLocation(simProgram, "uTilt")
        sLevel = GLES20.glGetUniformLocation(simProgram, "uLevel")
        sAspect = GLES20.glGetUniformLocation(simProgram, "uAspect")
        sSplat0 = GLES20.glGetUniformLocation(simProgram, "uSplat0")
        sSplat1 = GLES20.glGetUniformLocation(simProgram, "uSplat1")

        drawPos = GLES20.glGetAttribLocation(drawProgram, "aPos")
        dBase = GLES20.glGetUniformLocation(drawProgram, "uBase")
        dSim = GLES20.glGetUniformLocation(drawProgram, "uSim")
        dMaskOn = GLES20.glGetUniformLocation(drawProgram, "uMaskOn")
        dTexel = GLES20.glGetUniformLocation(drawProgram, "uTexel")
        dScreen = GLES20.glGetUniformLocation(drawProgram, "uScreen")
        dImgScale = GLES20.glGetUniformLocation(drawProgram, "uImgScale")
        dMaskCenter = GLES20.glGetUniformLocation(drawProgram, "uMaskCenter")
        dMaskRadius = GLES20.glGetUniformLocation(drawProgram, "uMaskRadius")
        dRefract = GLES20.glGetUniformLocation(drawProgram, "uRefract")
        dDisp = GLES20.glGetUniformLocation(drawProgram, "uDisp")
        dSpec = GLES20.glGetUniformLocation(drawProgram, "uSpec")
        dJelly = GLES20.glGetUniformLocation(drawProgram, "uJelly")
        dLight = GLES20.glGetUniformLocation(drawProgram, "uLight")

        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        quad = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }

        simW = LiquidGlCore.SIM_WIDTH
        simH = max(2, (simW.toFloat() * screenH / screenW).toInt())
        for (i in 0..1) {
            simTex[i] = newTexture(simW, simH)
            simFbo[i] = newFbo(simTex[i])
        }
        // 初始状态 = 平静水面（h=0、v=0 编码后是 0.5）
        for (i in 0..1) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[i])
            GLES20.glViewport(0, 0, simW, simH)
            GLES20.glClearColor(0.5f, 0.5f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        LiquidGlCore.checkGl("initGl")
    }

    private fun newTexture(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        return t[0]
    }

    private fun newFbo(tex: Int): Int {
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0,
        )
        val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            HookRuntime.log("LIQUID FBO 不完整：0x${Integer.toHexString(st)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return f[0]
    }

    private fun releaseGl() {
        if (simTex[0] != 0) GLES20.glDeleteTextures(2, simTex, 0)
        if (simFbo[0] != 0) GLES20.glDeleteFramebuffers(2, simFbo, 0)
        if (baseTex != 0) GLES20.glDeleteTextures(1, intArrayOf(baseTex), 0)
        if (simProgram != 0) GLES20.glDeleteProgram(simProgram)
        if (drawProgram != 0) GLES20.glDeleteProgram(drawProgram)
        simTex[0] = 0; simTex[1] = 0
        simFbo[0] = 0; simFbo[1] = 0
        baseTex = 0
        simProgram = 0
        drawProgram = 0
    }

    // ─────────────────────────── 图源 ───────────────────────────

    /**
     * 当前版本：**系统壁纸**（`WallpaperManager#getDrawable`）。
     *
     * key 非空时先试 provider `content://…/liquid/<key>/base`（`LiquidStore` 存的自选素材），
     * 失败再退回系统壁纸。
     *
     * ⚠️ 锁屏与桌面用**两张不同壁纸**时，这里拿到的是系统给当前用户的默认那张 ——
     * 与锁屏实际显示的可能不是一张。P1 要让 App 侧把锁屏原图经 provider 推过来。
     */
    private fun loadBase(ctx: Context, key: String): Bitmap? {
        if (key.isNotEmpty()) {
            runCatching {
                // authority 用 [MaskProvider] 的常量：fork 版 applicationId 不同（`.liquid`），
                // 写死字符串会在那边直接 404。
                val uri = android.net.Uri.parse(
                    "content://${com.frederic22.hyperextreme.data.MaskProvider.AUTHORITY}/liquid/$key/base",
                )
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { return downscale(it) }
        }
        // **首选：系统真正在用的那张壁纸文件**。与 `crop_subject` 同目录，
        // `-rw-r--r-- system` ⇒ SystemUI 直接可读（路径表见 WallpaperSource.displayPaths）。
        // 比 WallpaperManager 可靠：MIUI 的壁纸服务不一定把它交给 WallpaperManager。
        val uid = com.frederic22.hyperextreme.data.WallpaperSource.currentUserId()
        for (path in com.frederic22.hyperextreme.data.WallpaperSource.displayPaths(uid)) {
            val file = java.io.File(path)
            if (!file.isFile || file.length() < 50_000L) continue
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: continue
            HookRuntime.log("LIQUID 图源=文件 $path（${bmp.width}x${bmp.height}）")
            return downscale(bmp)
        }
        // 兜底：WallpaperManager（可能是缩略图或默认壁纸，但总比没有强）
        val wm = runCatching { WallpaperManager.getInstance(ctx) }.getOrNull() ?: return null
        val drawable = runCatching { wm.drawable }.getOrNull() ?: return null
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: screenW
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: screenH
        val bmp = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(b))
            }
        }.getOrNull() ?: return null
        HookRuntime.log("LIQUID 图源=WallpaperManager（${w}x$h）")
        return downscale(bmp)
    }

    private fun downscale(src: Bitmap): Bitmap {
        if (src.width <= MAX_BASE_WIDTH) return src
        val scale = MAX_BASE_WIDTH.toFloat() / src.width
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, MAX_BASE_WIDTH, h, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun uploadBase(bmp: Bitmap) {
        baseTex = newTexture(bmp.width, bmp.height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)

        // CENTER_CROP + 安全余量：余量必须 ≥ 最大折射位移，否则边缘会拉丝。
        val s = max(screenW.toFloat() / bmp.width, screenH.toFloat() / bmp.height)
        val safe = 1.06f
        imgScale[0] = screenW / (bmp.width * s) / safe
        imgScale[1] = screenH / (bmp.height * s) / safe
        HookRuntime.log(
            "LIQUID base ${bmp.width}x${bmp.height} → imgScale=" +
                "${"%.3f".format(imgScale[0])}/${"%.3f".format(imgScale[1])}",
        )
    }

    // ─────────────────────────── 每帧 ───────────────────────────

    private val frameTick = object : Runnable {
        override fun run() {
            frameScheduled = false
            if (!running) return
            drawFrame(force = false)
            if (running && SystemClock.elapsedRealtime() < activeUntil) schedule()
        }
    }

    private val visibilityTick = object : Runnable {
        override fun run() {
            if (!running) return
            // 锁屏可见而当前没在出帧 ⇒ 唤醒（解锁回来画面可能是旧的，必须重画一帧）
            if (SystemClock.elapsedRealtime() >= activeUntil && hasVisibleMount()) {
                requestFrames(1200)
            }
            handler?.postDelayed(this, VISIBILITY_MS)
        }
    }

    private fun requestFrames(durationMs: Long) {
        if (!running) return
        val until = SystemClock.elapsedRealtime() + durationMs
        if (until > activeUntil) activeUntil = until
        schedule()
    }

    private fun schedule() {
        if (!running || frameScheduled) return
        val h = handler ?: return
        frameScheduled = true
        h.postDelayed(frameTick, FRAME_MS)
    }

    private fun hasVisibleMount(): Boolean {
        for (m in mounts) if (visibleNow(m.locked)) return true
        return false
    }

    private fun visibleNow(locked: Boolean): Boolean {
        val ctx = appContext ?: return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (!pm.isInteractive) return false
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return if (locked) km.isKeyguardLocked else !km.isKeyguardLocked
    }

    /**
     * 画一帧。
     *
     * @param force 忽略可见性（**只用于 reparent 之前的首帧**：没有父层、buffer 队列为空，
     *   swap 不会阻塞）。之后一律走可见性闸门。
     */
    private fun drawFrame(force: Boolean) {
        val d = display ?: return
        val c = eglContext ?: return
        if (mounts.isEmpty()) return
        frameNo++
        if (frameNo % 30 == 0) refreshTune()

        val targets = if (force) mounts else mounts.filter { visibleNow(it.locked) }
        if (targets.isEmpty()) return
        val first = targets[0].eglSurface ?: return
        if (!EGL14.eglMakeCurrent(d, first, first, c)) {
            swapErrors++
            logSwap("makeCurrent", once = swapErrors <= 3)
            return
        }

        stepSim()

        for (m in targets) {
            val es = m.eglSurface ?: continue
            if (!EGL14.eglMakeCurrent(d, es, es, c)) continue
            drawComposite()
            if (!EGL14.eglSwapBuffers(d, es)) {
                swapErrors++
                logSwap("swap", once = swapErrors <= 3)
            } else {
                shownFrames++
            }
        }
    }

    private fun logSwap(what: String, once: Boolean) {
        if (once) {
            HookRuntime.log(
                "LIQUID ${what}失败（第 $swapErrors 次）：0x${Integer.toHexString(EGL14.eglGetError())}",
            )
        }
    }

    /**
     * 参数 / 选区**热更新**（页面滑块只改属性，不重启渲染器）。
     *
     * 属性通道见 `LiquidFlag.PROP_TUNE / PROP_MASK`；`HookRuntime.systemProperty`
     * 自带 300ms 缓存，所以这里每 30 帧（~0.5s）读一次，开销可以忽略。
     */
    private fun refreshTune() {
        val rawTune = HookRuntime.systemProperty(LiquidFlag.PROP_TUNE).trim()
        if (rawTune != tuneRaw) {
            tuneRaw = rawTune
            LiquidTune.decode(rawTune)?.let {
                tune = it
                params = LiquidGlCore.params(runMode, it)
            }
        }
        val rawMask = HookRuntime.systemProperty(LiquidFlag.PROP_MASK).trim()
        if (rawMask != maskRaw) {
            maskRaw = rawMask
            LiquidMask.decode(rawMask)?.let { maskCfg = it }
        }
    }

    /** 模拟 pass：读上一帧 → 波动 → 注入 → 写另一张纹理。 */
    private fun stepSim() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[1 - simCur])
        GLES20.glViewport(0, 0, simW, simH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(simProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simCur])
        GLES20.glUniform1i(sPrev, 0)

        GLES20.glUniform2f(sTexel, 1f / simW, 1f / simH)
        GLES20.glUniform1f(sC2, params.c2)
        GLES20.glUniform1f(sDamp, params.damp)
        GLES20.glUniform1f(sMaskOn, if (maskCfg.on) 1f else 0f)
        GLES20.glUniform2f(sMaskCenter, 0.5f, 1f - maskCfg.centerY)
        GLES20.glUniform1f(sMaskRadius, maskCfg.radius)
        GLES20.glUniform2f(sTilt, tiltX, tiltY)
        GLES20.glUniform1f(sLevel, params.level)
        GLES20.glUniform1f(sAspect, screenH.toFloat() / screenW)

        val a = touches.poll()
        val b = if (a != null) touches.poll() else null
        val sigma = tune.sigma
        GLES20.glUniform4f(sSplat0, a?.get(0) ?: 0f, a?.get(1) ?: 0f, a?.get(2) ?: 0f, sigma)
        GLES20.glUniform4f(sSplat1, b?.get(0) ?: 0f, b?.get(1) ?: 0f, b?.get(2) ?: 0f, sigma)

        drawQuad(simPos)
        simCur = 1 - simCur
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** 合成 pass：∇h 折射采样 base。 */
    private fun drawComposite() {
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(drawProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTex)
        GLES20.glUniform1i(dBase, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simCur])
        GLES20.glUniform1i(dSim, 1)

        GLES20.glUniform2f(dTexel, 1f / simW, 1f / simH)
        GLES20.glUniform2f(dScreen, screenW.toFloat(), screenH.toFloat())
        GLES20.glUniform2f(dImgScale, imgScale[0], imgScale[1])
        GLES20.glUniform1f(dMaskOn, if (maskCfg.on) 1f else 0f)
        GLES20.glUniform2f(dMaskCenter, 0.5f, 1f - maskCfg.centerY)
        GLES20.glUniform1f(dMaskRadius, maskCfg.radius)
        GLES20.glUniform1f(dRefract, params.refract)
        GLES20.glUniform1f(dDisp, params.dispersion)
        GLES20.glUniform1f(dSpec, params.specular)
        GLES20.glUniform1f(dJelly, params.jelly)
        GLES20.glUniform3f(dLight, -0.35f, 0.55f, 0.75f)

        drawQuad(drawPos)
    }

    private fun drawQuad(pos: Int) {
        val q = quad ?: return
        if (pos < 0) return
        q.position(0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 8, q)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
    }

    // ─────────────────────────── 输入 ───────────────────────────

    /**
     * 触摸注入（由 [LiquidTouch] 在 UI 线程调用）。
     *
     * 坐标是**屏幕坐标**，这里转成 sim 的 uv：`v = 1 - y/H`（sim 纹理 v=0 在底部）。
     */
    fun onTouch(rawX: Float, rawY: Float, down: Boolean) {
        if (!running) return
        val w = screenW
        val hgt = screenH
        if (w <= 0 || hgt <= 0) return
        val now = SystemClock.elapsedRealtime()
        // 每帧最多消化 2 个触点（shader 只有两个槽位），所以这里也按 ~250Hz 节流，
        // 免得分发频率（120~240Hz）把队列灌爆、拖拽变成「一串断开的点」。
        if (now - lastTouchAt < 4) return
        lastTouchAt = now
        if (touches.size > 48) touches.clear()
        touches.add(
            floatArrayOf(
                (rawX / w).coerceIn(0f, 1f),
                (1f - rawY / hgt).coerceIn(0f, 1f),
                if (down) AMP_DOWN else AMP_MOVE,
            ),
        )
        requestFrames(IDLE_MS)
    }

    private fun startSensor(ctx: Context) {
        if (accelListener != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            ?: return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: run {
            HookRuntime.log("LIQUID 没有加速度计，只做触摸")
            return
        }
        val l = object : android.hardware.SensorEventListener {
            private var gx = 0f
            private var gy = 0f
            private var gz = 9.8f
            private var lastAt = 0L
            private var lastShakeAt = 0L
            private var primed = false

            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val now = SystemClock.elapsedRealtime()
                val dt = if (lastAt == 0L) 16f else (now - lastAt).coerceIn(4L, 100L).toFloat()
                lastAt = now
                val a = dt / (dt + 70f)
                gx += (event.values[0] - gx) * a
                gy += (event.values[1] - gy) * a
                gz += (event.values[2] - gz) * a
                if (!primed) {
                    primed = true
                    // 第一帧就按当前姿态对齐，避免开局整幅图「掉」一下
                    tiltX = (-gx / 4.9f).coerceIn(-1f, 1f)
                    tiltY = (gy / 4.9f).coerceIn(-1f, 1f)
                    return
                }
                val nx = (-gx / 4.9f).coerceIn(-1f, 1f)
                val ny = (gy / 4.9f).coerceIn(-1f, 1f)
                val moved = abs(nx - tiltX) > 0.004f || abs(ny - tiltY) > 0.004f
                tiltX = nx
                tiltY = ny
                if (moved) requestFrames(900)

                val hp = abs(event.values[0] - gx) + abs(event.values[1] - gy) +
                    abs(event.values[2] - gz)
                shakeEnergy = shakeEnergy * 0.9f + hp * 0.1f
                if (shakeEnergy > 3.0f && now - lastShakeAt > 320) {
                    lastShakeAt = now
                    splashRandom((shakeEnergy / 12f).coerceIn(0.15f, 0.6f))
                    requestFrames(2600)
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        runCatching {
            sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            sensorManager = sm
            accelListener = l
            HookRuntime.log("LIQUID 传感器已注册")
        }.onFailure { HookRuntime.log("LIQUID 传感器注册失败：${it.javaClass.simpleName}") }
    }

    private fun splashRandom(amp: Float) {
        val w = screenW
        val h = screenH
        if (w <= 0 || h <= 0) return
        touches.add(
            floatArrayOf(
                0.2f + Random.nextFloat() * 0.6f,
                0.2f + Random.nextFloat() * 0.6f,
                amp,
            ),
        )
    }

    private fun stopSensor() {
        val sm = sensorManager
        val l = accelListener
        if (sm != null && l != null) runCatching { sm.unregisterListener(l) }
        sensorManager = null
        accelListener = null
        shakeEnergy = 0f
    }

    private fun watchKeyguard(ctx: Context) {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        val l = KeyguardManager.KeyguardLockedStateListener { locked ->
            // 解锁瞬间立刻停帧：下一次 swap 之前就不会再进 drawFrame（缩小阻塞窗口）
            if (locked) {
                requestFrames(1800)
            } else {
                activeUntil = 0L
            }
        }
        runCatching {
            km.addKeyguardLockedStateListener(ctx.mainExecutor, l)
            keyguardManager = km
            keyguardListener = l
            HookRuntime.log("LIQUID 已监听锁屏状态")
        }.onFailure {
            HookRuntime.log("LIQUID 锁屏监听失败（靠可见性轮询兜底）：${it.javaClass.simpleName}")
        }
    }

    private fun removeKeyguardListener() {
        val km = keyguardManager
        val l = keyguardListener
        if (km != null && l != null) runCatching { km.removeKeyguardLockedStateListener(l) }
        keyguardManager = null
        keyguardListener = null
    }

    /** 写系统属性（反射，不 fork）。失败静默 —— 只是联动保护降级。 */
    private fun setProp(key: String, value: String) {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("set", String::class.java, String::class.java)
                .invoke(null, key, value)
        }
    }

    private class Mount(
        val locked: Boolean,
        val sc: android.view.SurfaceControl,
        val surface: Surface,
        var eglSurface: EGLSurface?,
    ) {
        var leash: android.view.SurfaceControl? = null
    }
}
