package com.frederic22.hyperextreme.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RasterFlag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 光栅壁纸（已升级为 OriginOS 液态流体壁纸）的**渲染器**。
 */
internal object RasterRenderer {

    private const val AUTHORITY = "com.frederic22.hyperextreme.provider"

    // ───────────────────────────── 线程 / 生命周期 ─────────────────────────────

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 当前渲染的组 id；null = 没在跑。主线程读、GL 线程写。 */
    @Volatile
    private var runningKey: String? = null

    @Volatile
    private var runningScope = -1

    @Volatile
    private var runningLockDepth = false

    internal fun lockDepthOn(): Boolean = runningKey != null && runningLockDepth

    private var panMaxRad = RasterGlCore.PAN_MAX_RAD

    private var settleOnIdle = true

    internal fun aodDimValue(): Float = dim

    private var appCtx: Context? = null

    fun isRunning(): Boolean = runningKey != null

    internal fun currentKey(): String? = runningKey

    /** 由轮询线程调用；已经在跑同一个组同一档就直接返回。 */
    fun start(ctx: Context, key: String, scope: Int) {
        val h = ensureHandler()
        val app = ctx.applicationContext
        h.post {
            if (runningKey == key && runningScope == scope) return@post
            if (runningKey != null) stopInternal("重建")
            runCatching { startInternal(app, key, scope) }
                .onFailure {
                    HookRuntime.log("RASTER 启动失败：${it.javaClass.simpleName}:${it.message}")
                    runCatching { stopInternal("启动失败") }
                }
        }
    }

    fun stop() {
        handler?.post { stopInternal("关闭") }
    }

    private fun ensureHandler(): Handler {
        handler?.let { return it }
        val t = HandlerThread("he-raster-gl").also { it.start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    // ───────────────────────────── GL / 挂载 ─────────────────────────────

    private class Mount(
        val locked: Boolean,
        val sc: android.view.SurfaceControl,
        val surface: Surface,
        var eglSurface: EGLSurface? = null,
    )

    private val mounts = ArrayList<Mount>(2)
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var screenW = 0
    private var screenH = 0

    private var program = 0
    private var quad: FloatBuffer? = null
    private var aPos = 0
    private var aUv = 0
    private var uFromTex = 0
    private var uToTex = 0
    private var uT = 0
    private var uDir = 0
    private var uBand = 0
    private var uDim = 0
    private var uFromUv = 0
    private var uToUv = 0

    // 新增：液态壁纸时间轴与倾角 uniform + 60fps 自驱动节拍器
    private var uTime = 0
    private var uPan = 0
    private var animStartAt = SystemClock.elapsedRealtime()
    private val liquidTick = object : Runnable {
        override fun run() {
            if (runningKey == null || aodFrozen || transitionFrozen || !screenOn()) return
            dirty = true
            renderIfNeeded(force = true)
            handler?.postDelayed(this, 16L)
        }
    }

    /** 组内图片文件名（顺序 = 浮页格子顺序，第 0 张就是基点图）。 */
    private var images: List<String> = emptyList()

    /** 3 槽纹理环存：够用「上一张 / 当前 / 下一张」，反向擦除不用重传。 */
    private val slotTex = IntArray(3)
    private val slotIdx = IntArray(3) { -1 }

    private val preloaded = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
    private val preloading = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** 每个槽的 cover 参数：`[scaleX, scaleY, offX, offY]`。 */
    private val slotUv = Array(3) { floatArrayOf(1f, 1f, 0f, 0f) }

    // ───────────────────────────── 姿态 / 状态 ─────────────────────────────

    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null

    private var lastGyroAt = 0L
    private var gyroRate = 0f
    private var gyroFrames = 0

    private var displayRot = Surface.ROTATION_0
    private var rotAt = 0L

    private var pan = 0f
    private var lastMoveDir = 1f
    private var lastMotionAt = 0L

    // 停在半擦时的收尾动画
    private var snapping = false
    private var snapFrom = 0f
    private var snapTo = 0f
    private var snapStartAt = 0L
    private var snapRun: Runnable? = null

    // AOD / 解锁后「擦回第一张」
    private var homeArmed = false
    private var homeRunning = false
    private var homeProg = 0f
    private var homeDir = 1f
    private var homeFrom = 0
    private var homeStartAt = 0L
    private var homeRun: Runnable? = null
    private var homeArmRun: Runnable? = null

    // AOD / 灭屏
    private var aodFrozen = false
    private var fullAod = false
    @Volatile private var dim = 1f
    private var screenReceiver: BroadcastReceiver? = null
    private var resetRun: Runnable? = null

    // 绘制簿记
    private var dirty = true
    private var lastDrawnF = -1f
    private var lastDrawnDim = -1f

    // ─────────── 解锁淡出 / 复位 ───────────

    private var currentAlpha = 1f
    private const val FADE_MS = 380f
    private var fadeTick: Runnable? = null
    private var fadeDoneAt = 0L
    private const val FADE_LATCH_MS = 800L
    private var pendingRaise = false
    private var raiseScheduled = false

    @Volatile private var sharedLeash = false
    @Volatile private var desktopOnlyShared = false
    private var deskOnlyAlpha = -1f
    @Volatile private var showingDesktop = false

    @Volatile private var transitionFrozen = false
    @Volatile private var frozenAt = 0L
    private const val FROZEN_TIMEOUT_MS = 2000L

    private fun freezeTransition() {
        frozenAt = SystemClock.elapsedRealtime()
        transitionFrozen = true
    }

    private fun unfreezeTransition() {
        transitionFrozen = false
        frozenAt = 0L
    }

    private fun screenOn(): Boolean = runCatching {
        val ctx = appCtx ?: return true
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)

    // 诊断
    private var swapLockFrames = 0
    private var swapDeskFrames = 0
    private var swapErrors = 0
    private var slowLogged = 0
    private var eglErrLogged = false

    // ───────────────────────────── 启动 ─────────────────────────────

    private fun startInternal(ctx: Context, key: String, scope: Int) {
        val t0 = SystemClock.elapsedRealtime()
        appCtx = ctx
        val dm = android.content.res.Resources.getSystem().displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        images = readItems(ctx, key)
        check(images.isNotEmpty()) { "组内没有图片（key=$key）" }
        HookRuntime.log("RASTER 内容：$key 共 ${images.size} 张")

        // 一、EGL
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize 失败" }
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, num, 0) && num[0] > 0,
        ) { "eglChooseConfig 失败" }
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )

        // 二、按 scope 建挂载目标（锁屏 / 桌面 / 全局两条）
        val wantLock = scope != MaskPrefs.SPATIAL_SCOPE_DESKTOP
        val wantDesk = scope != MaskPrefs.SPATIAL_SCOPE_LOCK
        for (locked in booleanArrayOf(true, false)) {
            if (locked && !wantLock) continue
            if (!locked && !wantDesk) continue
            val name = if (locked) "he-raster-lock" else "he-raster-desk"
            val sc = android.view.SurfaceControl.Builder()
                .setName(name)
                .setBufferSize(screenW, screenH)
                .build()
            val surface = Surface(sc)
            val es = EGL14.eglCreateWindowSurface(
                display, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            mounts.add(Mount(locked, sc, surface, es))
        }
        check(mounts.isNotEmpty()) { "没有可挂载的目标（scope=$scope）" }
        check(EGL14.eglMakeCurrent(display, mounts[0].eglSurface, mounts[0].eglSurface, context)) {
            "eglMakeCurrent 失败"
        }

        // 三、着色器
        program = buildProgram(RasterGlCore.VERT, RasterGlCore.FRAG)
        check(program != 0) { "shader 编译失败" }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uFromTex = GLES20.glGetUniformLocation(program, "uFrom")
        uToTex = GLES20.glGetUniformLocation(program, "uTo")
        uT = GLES20.glGetUniformLocation(program, "uT")
        uDir = GLES20.glGetUniformLocation(program, "uDir")
        uBand = GLES20.glGetUniformLocation(program, "uBand")
        uDim = GLES20.glGetUniformLocation(program, "uDim")
        uFromUv = GLES20.glGetUniformLocation(program, "uFromUv")
        uToUv = GLES20.glGetUniformLocation(program, "uToUv")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uPan = GLES20.glGetUniformLocation(program, "uPan")
        animStartAt = SystemClock.elapsedRealtime()

        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        quad = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }

        // 四、状态归零 + 首帧
        pan = 0f
        lastMoveDir = 1f
        lastGyroAt = 0L
        gyroRate = 0f
        dim = 1f
        currentAlpha = 1f
        showingDesktop = readShowingDesktop(ctx)
        sharedLeash = false
        desktopOnlyShared = false
        deskOnlyAlpha = -1f
        unfreezeTransition()
        fadeDoneAt = 0L
        pendingRaise = false
        raiseScheduled = false
        swapLockFrames = 0
        swapDeskFrames = 0
        swapErrors = 0
        slowLogged = 0
        eglErrLogged = false

        runningLockDepth = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_LOCKDEPTH,
        ).trim() == "1"
        HookRuntime.log("RASTER 锁屏景深=${if (runningLockDepth) 1 else 0}")

        val panDeg = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_PAN_DEG,
        ).trim().toIntOrNull()?.coerceIn(15, 45) ?: 25
        panMaxRad = RasterGlCore.radOf(panDeg)
        HookRuntime.log("RASTER 最大幅度角度=${panDeg}°（满量程 ${"%.4f".format(panMaxRad)} rad）")

        settleOnIdle = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_SETTLE,
        ).trim() != "0"
        HookRuntime.log("RASTER 停顿时就近归位=${if (settleOnIdle) 1 else 0}")

        dirty = true
        runningKey = key
        runningScope = scope
        drawEveryMount()
        HookRuntime.log(
            "RASTER 首帧完成：${mounts.size} 个挂载目标（当前视为" +
                "${if (showingDesktop) "桌面" else "锁屏"}态），" +
                "耗时 ${SystemClock.elapsedRealtime() - t0}ms",
        )

        // 五、挂到壁纸 leash
        attachToLeash(scope)

        startSensor(ctx)
        watchScreen(ctx)
        handler?.removeCallbacks(liquidTick)
        handler?.post(liquidTick)
        RasterFlag.pushRendering(true)
        HookRuntime.log(
            "RASTER 液态渲染器已启动：key=$key scope=$scope 图 ${images.size} 张，" +
                "屏幕 ${screenW}×$screenH",
        )
    }

    /** 把每个挂载目标 reparent 到系统的壁纸 leash（逐条容错，挂不上的丢弃）。 */
    private fun attachToLeash(scope: Int) {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal", false, javaClass.classLoader)
        val inst = wmgClass.getMethod("getInstance").invoke(null)
        val gw = wmgClass.getMethod(
            "getWallpaperByShowWhenLocked",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        val txClass = Class.forName(
            "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
        )
        val tx = txClass.getConstructor().newInstance()
        val reparent = txClass.getMethod(
            "reparent", android.view.SurfaceControl::class.java, android.view.SurfaceControl::class.java,
        )
        val show = txClass.getMethod("show", android.view.SurfaceControl::class.java)

        fun leashOf(locked: Boolean): Any? {
            val token = runCatching { gw.invoke(inst, 0, locked) }.getOrNull() ?: return null
            return runCatching { token.javaClass.getMethod("getLeash").invoke(token) }.getOrNull()
        }

        val leashes = HashMap<Boolean, Any?>()
        leashes[true] = leashOf(true)
        leashes[false] = leashOf(false)
        val hasLockMount = mounts.any { it.locked }
        val shared = leashes[false] == null && mounts.any { !it.locked } && leashes[true] != null
        sharedLeash = shared
        val dropDesk = shared && hasLockMount
        desktopOnlyShared = shared && !hasLockMount

        val kept = ArrayList<Mount>(mounts.size)
        for (m in mounts) {
            if (dropDesk && !m.locked) {
                HookRuntime.log("RASTER 共用 leash：桌面份丢弃（挂两份会互相遮挡并阻塞 swap）")
                continue
            }
            val leash = leashes[m.locked] ?: leashes[true] ?: continue
            runCatching {
                reparent.invoke(tx, m.sc, leash)
                show.invoke(tx, m.sc)
                kept.add(m)
                HookRuntime.log(
                    "RASTER 挂载：showWhenLocked=${m.locked} leash=@" +
                        Integer.toHexString(System.identityHashCode(leash)),
                )
            }.onFailure {
                HookRuntime.log("RASTER 挂载失败：${it.javaClass.simpleName}:${it.message}")
            }
        }
        runCatching { txClass.getMethod("apply").invoke(tx) }

        if (desktopOnlyShared) {
            HookRuntime.log(
                "RASTER 共用 leash：仅桌面档 → 保留桌面份挂到唯一 leash，" +
                    "按锁屏态设 alpha（当前=${if (showingDesktop) "桌面 1.0" else "锁屏 0.0"}）",
            )
            setDeskOnlyAlpha(if (showingDesktop) 1f else 0f, force = true)
        }
        if (kept.size != mounts.size) {
            val drop = mounts.filter { it !in kept }
            runCatching {
                val tx2 = txClass.getConstructor().newInstance()
                val remove = txClass.getMethod("remove", android.view.SurfaceControl::class.java)
                for (m in drop) {
                    runCatching { remove.invoke(tx2, m.sc) }
                    runCatching { m.surface.release() }
                    display?.let { d ->
                        m.eglSurface?.let { es -> runCatching { EGL14.eglDestroySurface(d, es) } }
                    }
                    m.eglSurface = null
                }
                txClass.getMethod("apply").invoke(tx2)
            }
            mounts.clear()
            mounts.addAll(kept)
        }
        check(mounts.isNotEmpty()) { "全部目标挂载失败（scope=$scope）" }
    }

    // ───────────────────────────── 停止 ─────────────────────────────

    private fun stopInternal(reason: String) {
        val had = runningKey != null
        runningKey = null
        runningScope = -1
        runCatching { sensorListener?.let { sensorManager?.unregisterListener(it) } }
        runCatching { screenReceiver?.let { appCtx?.unregisterReceiver(it) } }
        sensorListener = null
        sensorManager = null
        screenReceiver = null
        cancelSnap()
        cancelHome()
        resetRun?.let { handler?.removeCallbacks(it) }
        resetRun = null
        handler?.removeCallbacks(liquidTick)

        runCatching {
            if (mounts.isNotEmpty()) {
                val txClass = Class.forName(
                    "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
                )
                val tx = txClass.getConstructor().newInstance()
                val remove = txClass.getMethod("remove", android.view.SurfaceControl::class.java)
                for (m in mounts) remove.invoke(tx, m.sc)
                txClass.getMethod("apply").invoke(tx)
            }
        }
        runCatching {
            for (i in slotTex.indices) {
                if (slotTex[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(slotTex[i]), 0)
                slotTex[i] = 0
                slotIdx[i] = -1
                slotUv[i] = floatArrayOf(1f, 1f, 0f, 0f)
            }
            if (program != 0) GLES20.glDeleteProgram(program)
        }
        program = 0
        runCatching {
            display?.let { d ->
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                mounts.forEach { m ->
                    m.eglSurface?.let { es ->
                        if (es != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, es)
                    }
                    m.eglSurface = null
                }
                context?.let { EGL14.eglDestroyContext(d, it) }
                EGL14.eglTerminate(d)
            }
        }
        clearPreloaded()
        runCatching { mounts.forEach { runCatching { it.surface.release() } } }
        mounts.clear()
        display = null
        context = null
        eglConfig = null
        quad = null
        appCtx = null
        dirty = true
        lastDrawnF = -1f
        lastDrawnDim = -1f
        aodFrozen = false
        fullAod = false
        dim = 1f
        currentAlpha = 1f
        cancelFade()
        fadeDoneAt = 0L
        pendingRaise = false
        raiseScheduled = false
        sharedLeash = false
        desktopOnlyShared = false
        deskOnlyAlpha = -1f
        unfreezeTransition()
        showingDesktop = false
        RasterFlag.pushRendering(false)
        runCatching { RasterOverlay.onDisabled() }
        runningLockDepth = false
        settleOnIdle = true
        if (had) HookRuntime.log("RASTER 渲染器已停止（$reason）")
    }

    // ───────────────────────────── 姿态 ─────────────────────────────

    private fun startSensor(ctx: Context) {
        runCatching {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (gyro == null) {
                HookRuntime.log("RASTER 没有陀螺仪，画面仅播放液态流动")
                return
            }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val v = event.values
                    if (v.size < 2) return
                    val x = v[0]
                    val y = v[1]
                    val ts = event.timestamp
                    handler?.post { onGyro(x, y, ts) }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
            sensorManager = sm
            sensorListener = listener
            HookRuntime.log("RASTER 姿态源 = ${gyro.name}（陀螺积分，满量程 25°）")
        }.onFailure { HookRuntime.log("RASTER 传感器注册失败：${it.message}") }
    }

    private fun currentRotation(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - rotAt < 500L) return displayRot
        rotAt = now
        displayRot = runCatching {
            val dm = appCtx?.getSystemService(Context.DISPLAY_SERVICE)
                as? android.hardware.display.DisplayManager
            dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: Surface.ROTATION_0
        return displayRot
    }

    private fun onGyro(x: Float, y: Float, ts: Long) {
        if (runningKey == null) return
        if (transitionFrozen) {
            val since = if (frozenAt > 0L) SystemClock.elapsedRealtime() - frozenAt else 0L
            if (since > FROZEN_TIMEOUT_MS) {
                unfreezeTransition()
                Hook