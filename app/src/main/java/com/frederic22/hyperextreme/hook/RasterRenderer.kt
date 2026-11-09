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
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 光栅壁纸的**渲染器**（2026-11-04）。
 *
 * ## 它画什么
 *
 * 组里第 0 张是**基点图**；用户左右转手机时，按角度在两两之间做**擦除**过渡
 * （公式见 [RasterGlCore]）。没有景深、没有位移场，只有两张贴图 + 一条带。
 *
 * ## 与空间壁纸的关系
 *
 * 完全独立的一条链：自己一个 GL 线程、自己一套挂载。**共用的只有思路** ——
 * `WindowManagerGlobal#getWallpaperByShowWhenLocked` 拿 leash、`reparent` 挂上去、
 * 锁屏/桌面/全局三档、AOD 压暗（shader 乘 `uDim`，`Transaction#setColor` 会发白）。
 *
 * ## 姿态：陀螺积分 + 静止门限
 *
 * 不归位 ⇒ 不能用"绝对基准"那套（`GAME_ROTATION_VECTOR` 在这台机器上根本不响应）。
 * 改用陀螺积分，靠 `|rate| < 0.01 rad/s 就不积分` 把漂移死死按住 —— 静止时不累加，
 * 就不会自己往前爬。这条与参考项目 `SUlTlUS/tianyinwallpaper` 一致，
 * 也是本项目空间壁纸链一直在跑的那条路。
 *
 * ## 基点
 *
 * **不做静止归位**（用户 2026-11-04）：转多少停多少，只把"停在半擦"补完。
 * 真正重设基点的时机只有三个，见 [onScreenOff] / [onEnterAod] / [onExitAod]。
 */
internal object RasterRenderer {

    private const val AUTHORITY = "com.frederic22.hyperextreme.liquid.provider"

    // ───────────────────────────── 线程 / 生命周期 ─────────────────────────────

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 当前渲染的组 id；null = 没在跑。主线程读、GL 线程写。 */
    @Volatile
    private var runningKey: String? = null

    @Volatile
    private var runningScope = -1

    /**
     * 本次运行是否启用「光栅壁纸锁屏景深」（`RasterFlag.PROP_LOCKDEPTH`）。
     *
     * **只在 [startInternal] 开头读一次**（语义同空间壁纸的 `runningLockDepth`）：
     * 生效中改开关不动当前渲染，下一次「应用」才随属性一起下发。
     */
    @Volatile
    private var runningLockDepth = false

    /** 景深层是否**真的**开着（读启动快照，不是属性本身 —— 与 `SpatialRenderer.lockDepthOn` 同义）。 */
    internal fun lockDepthOn(): Boolean = runningKey != null && runningLockDepth

    /**
     * 本次运行的**满量程角**（弧度）—— 2026-11-04 起由 `MaskPrefs.rasterMaxAngle`（15~45°）决定。
     *
     * 启动时按 `RasterFlag.PROP_PAN_DEG` 换算一次（与「生效范围」同语义：生效中改不生效）。
     * 默认 25° = 加这个选项之前的行为。
     */
    private var panMaxRad = RasterGlCore.PAN_MAX_RAD

    /**
     * 本次运行是否「停顿时就近归位」（`RasterFlag.PROP_SETTLE`）—— 2026-11-04。
     *
     * 开（默认）= 静止后补完当前一擦；关 = **停在两张之间不动**。
     * 同样只在 [startInternal] 读一次（与「生效范围 / 最大幅度角度」一个语义）。
     */
    private var settleOnIdle = true

    /** 给 [RasterOverlay] 的 AGSL 用：与 GL **同一个**压暗值（AOD 时两边必须同步）。 */
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
                    HookRuntime.log("RASTER 启动失败：${it.javaClass.simpleName}: ${it.message}")
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
    // 液态壁纸时间轴与倾角 uniform
    private var uTime = 0
    private var uPan = 0
    private var animStartAt = SystemClock.elapsedRealtime()
    // 60fps 自驱动液态节拍器：亮屏才跑，AOD/灭屏/过渡冻结时自动停
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

    /**
     * **后台预取**：把"下一张/上一张"的解码 + 裁剪提前做完。
     *
     * 实测（2026-11-04）一张 2000×3555 现解现裁要 **528ms**，而纹理是按需加载的 ——
     * 转到第二张时会卡半秒。这里在 GL 线程之外把它们先备好，`textureFor` 只做 upload。
     * 最多留 2 张（各 12.8MB），避免 SystemUI 堆内存被撑爆。
     */
    private val preloaded = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
    private val preloading = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** 每个槽的 cover 参数：`[scaleX, scaleY, offX, offY]`。 */
    private val slotUv = Array(3) { floatArrayOf(1f, 1f, 0f, 0f) }

    // ───────────────────────────── 姿态 / 状态 ─────────────────────────────

    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null

    /** 上一帧陀螺时间戳（ns）；0 = 还没收到过（或刚重置过）。 */
    private var lastGyroAt = 0L

    /** 低通后的角速度（rad/s，绕屏幕竖直轴；**正 = 向左转**）。 */
    private var gyroRate = 0f

    /** 诊断计数：每 60 次积分打一条日志。 */
    private var gyroFrames = 0

    /** 屏幕旋转缓存（陀螺读数是设备轴的，横屏要换轴）。 */
    private var displayRot = Surface.ROTATION_0
    private var rotAt = 0L

    /** 累计平移角（弧度，正 = 向左转）。**唯一的位置真相**。 */
    private var pan = 0f

    /** 最近一次运动方向（没有运动时保持上一次）。 */
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
    /** AOD 压暗系数（1 = 原亮度）。**volatile**：景深层 AGSL 在主线程读同一个值。 */
    @Volatile private var dim = 1f
    private var screenReceiver: BroadcastReceiver? = null
    private var resetRun: Runnable? = null

    // 绘制簿记
    private var dirty = true
    private var lastDrawnF = -1f
    private var lastDrawnDim = -1f

    // ─────────── 解锁淡出 / 复位（与 SpatialRenderer 同一套状态机，2026-11-04）───────────
    //
    // 光栅与空间壁纸在系统侧是**同一个处境**：我们的 SC 挂在壁纸 leash 上，是 miwallpaper
    // 那两个 Surface 的兄弟层。系统让壁纸退场时只给自己那层降 alpha，不辐射我们 ⇒ 必须
    // 自己补一条淡出。这一段逐条对齐 SpatialRenderer 的同名部分，唯一差别是"画什么"
    // （那边 8 层 + 位移场，这边两张图 + 擦除带）—— 状态机必须一模一样，少抄一条就是一个
    // 真机 bug。

    /** 锁屏组 SC 的当前 alpha（1 = 正常；解锁淡出到 0）。只在 GL 线程读写。 */
    private var currentAlpha = 1f

    private const val FADE_MS = 380f

    /** 正在跑的淡出（null = 未在淡出）。只在 GL 线程读写。 */
    private var fadeTick: Runnable? = null

    /**
     * 淡出**锁存窗口**：alpha 掉到 0 之后这段时间里，拒绝任何 raise。
     *
     * 为什么必须有（空间壁纸 2026-10-28 真机第二轮）：复位入口在解锁动画期间会被
     * occluded 抖动触发，概率性。系统在解锁流程里还会再调一次「壁纸状态 = true」，
     * 把 alpha 设回 1 ⇒ 闪回。纯时间锁不依赖任何外部信号，800ms 覆盖整段解锁动画
     * （实测 380~460ms）。
     */
    private var fadeDoneAt = 0L
    private const val FADE_LATCH_MS = 800L

    /** 锁存窗口内被挡下的 raise（窗口结束后补上）。只在 GL 线程读写。 */
    private var pendingRaise = false

    /** 已为 [pendingRaise] 排过补发（防重复 post）。只在 GL 线程读写。 */
    private var raiseScheduled = false

    /**
     * **共用 leash**：锁屏与桌面用同一张系统壁纸时，`(0,false)` 返回 null ——
     * 桌面那条 token 根本不存在，系统让桌面复用锁屏那条。
     *
     * 这是光栅最要命的一条（2026-11-04 用户实测「全局下锁屏一次回来桌面就没了」）：
     * 光栅**不换系统壁纸**，所以锁屏 / 桌面几乎永远共用一条 leash。此时把唯一那份
     * 淡到 0 = 把桌面壁纸也一起抹掉；回锁屏时复位又把它拉回 1 ⇒ 表现为「锁屏有、桌面空」。
     *
     * 共用时**不主动淡出**：系统自己会让这条 leash 连续过渡（桌面复用它），天然无缝。
     */
    @Volatile private var sharedLeash = false

    /**
     * **仅桌面档 + 共用 leash**（2026-11-05，问题一）。
     *
     * 同一张壁纸时系统只有锁屏那一条 token，仅桌面档又只有桌面一份 mount ——
     * 它必须挂到那条唯一的 leash 上，否则 `check(mounts.isNotEmpty())` 直接失败，
     * 表现为「点应用什么都不发生」。
     *
     * 代价：这一层在锁屏上也会被系统显示。所以另用 [setDeskOnlyAlpha] 按
     * `showingDesktop` 把它在锁屏时隐掉 —— 这才是"仅桌面"的语义。
     */
    @Volatile private var desktopOnlyShared = false

    /** 仅桌面档在共用 leash 上的当前 alpha（-1 = 还没设过，强制写一次）。 */
    private var deskOnlyAlpha = -1f

    /**
     * **当前该画桌面份吗**（与 `SpatialRenderer.showingDesktop` 同义）。
     *
     * 不能拿 `KeyguardManager.isKeyguardLocked` 每帧轮询反推：
     * * 启动瞬间它可能滞后 ⇒ 桌面份一次都不画（没有 buffer ⇒ 不显示）；
     * * 桌面稳态下若判成锁屏 ⇒ 每帧往**不可见的锁屏 leash** 写 ⇒ `eglSwapBuffers`
     *   阻塞 ⇒ GL 线程停摆 ⇒ 画面永久静止。
     *
     * 只由三个真实事件翻转：启动时读系统锁屏态 / 解锁淡出落地 / 回锁屏复位放行。
     */
    @Volatile private var showingDesktop = false

    /**
     * **过渡期冻结**（与 `SpatialRenderer.transitionFrozen` 同义）。
     *
     * 解锁退场这 280ms 里不采样、不重绘：任何逐帧更新都在和「切换」抢帧预算，
     * 而且此刻锁屏 leash 正被系统收起（不可见 ⇒ swap 阻塞）。
     * 冻结 ⇒ 零额外开销，切换前后画面完全一致。
     */
    @Volatile private var transitionFrozen = false
    @Volatile private var frozenAt = 0L

    /**
     * 冻结超时：灭屏时所有 surface 都不可见，`eglSwapBuffers` 会阻塞，解冻那行可能永远
     * 执行不到。兜底放在**传感器回调**里 —— 它跑在自己的线程，是唯一不受 GL 线程阻塞影响
     * 的节拍（空间壁纸 2026-10-29 真机实证）。
     */
    private const val FROZEN_TIMEOUT_MS = 2000L

    private fun freezeTransition() {
        frozenAt = SystemClock.elapsedRealtime()
        transitionFrozen = true
    }

    private fun unfreezeTransition() {
        transitionFrozen = false
        frozenAt = 0L
    }

    /** 屏幕是否亮着（灭屏时不做任何 swap —— 一定阻塞）。拿不到 Context 按"亮"处理。 */
    private fun screenOn(): Boolean = runCatching {
        val ctx = appCtx ?: return true
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)

    // 诊断（全局档双挂是否真的在更新）
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

        // 位置 + UV（Bitmap 第 0 行在顶部，GL 的 (0,0) 在左下 ⇒ v 要反过来）
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        quad = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }

        // 四、状态归零 + 首帧
        //
        // ⚠️ **必须画「所有」目标**（与 SpatialRenderer v1.5.73 同一条教训）：不能按
        // "当前显示哪一份"筛组。启动瞬间若判错，另一份的 SC 一次都没 swap 过（没有 buffer
        // ⇒ 不显示），真机现象是「点应用回到桌面看不到我们的图，进一次锁屏再回来才有」。
        // 此时 SC 还没 reparent（无父层、队列空），每份 swap 一次不会阻塞。
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
        // 「光栅壁纸锁屏景深」开关：**整条生命周期只读这一次**（语义同生效范围 ——
        // 生效中改开关不动当前渲染，下一次「应用」才随属性一起下发）。
        runningLockDepth = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_LOCKDEPTH,
        ).trim() == "1"
        HookRuntime.log("RASTER 锁屏景深=${if (runningLockDepth) 1 else 0}")

        // 「最大幅度角度」：同样**只读一次**（生效中改只写 prefs，下一次「应用」才下发）。
        val panDeg = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.RasterFlag.PROP_PAN_DEG,
        ).trim().toIntOrNull()?.coerceIn(15, 45) ?: 25
        panMaxRad = RasterGlCore.radOf(panDeg)
        HookRuntime.log("RASTER 最大幅度角度=${panDeg}°（满量程 ${"%.4f".format(panMaxRad)} rad）")

        // 「停顿时就近归位」：属性没写也按**开**（= 加这个选项之前的行为）。
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
            "RASTER 渲染器已启动：key=$key scope=$scope 图 ${images.size} 张，" +
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
        // 同一张壁纸时系统只有一条 token，桌面那条返回 null ⇒ 复用锁屏那条。
        // ⚠️ 但**不能挂两份**到同一条 leash 上：完全重叠，被遮住的那份 swap 会阻塞。
        // 这个标志必须留给淡出用（`fadeOutOnUnlock` 见到它就整段跳过）——
        // 光栅不换系统壁纸，所以这条分支是**常态**，不是边缘情况。
        val hasLockMount = mounts.any { it.locked }
        val shared = leashes[false] == null && mounts.any { !it.locked } && leashes[true] != null
        sharedLeash = shared
        // ⚠️ 只有「同时还有锁屏份」时才能丢桌面份；**仅桌面档没有第二份**，
        // 丢了就一个不剩 ⇒ check(mounts.isNotEmpty()) 直接失败 = 点应用没反应
        // （2026-11-05 问题一）。
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
                HookRuntime.log("RASTER 挂载失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }
        runCatching { txClass.getMethod("apply").invoke(tx) }

        // 仅桌面档挂在共用 leash 上：锁屏时必须自己隐掉，否则「仅桌面」变成「全局」。
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
        // GL 资源必须在**上下文还活着**时删；`eglTerminate` 之后 GL 已经没了。
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
        // 下次 startInternal 会用系统锁屏态重新初始化；这里只保证"停止后不残留旧的显示态"。
        showingDesktop = false
        RasterFlag.pushRendering(false)
        // 景深层（前景主体 View）是渲染器的**伴生状态** ⇒ 停渲染器必须一起收口，
        // 否则「取消应用」后主体会一直留在锁屏上（与空间壁纸同一条教训）。
        runCatching { RasterOverlay.onDisabled() }
        runningLockDepth = false
        settleOnIdle = true
        if (had) HookRuntime.log("RASTER 渲染器已停止（$reason）")
    }

    // ───────────────────────────── 姿态 ─────────────────────────────

    /**
     * 姿态源 = **陀螺仪积分**（2026-11-04 改，参考 `SUlTlUS/tianyinwallpaper`）。
     *
     * ⚠️ 之前用的是 `TYPE_GAME_ROTATION_VECTOR` 求"屏幕法线方位角"，真机上不响应。
     * 现在换成本项目**空间壁纸链已验证**的那条路（`TYPE_GYROSCOPE` + 低通 + 积分），
     * 与参考项目逐字同构：
     *
     * ```
     * ω = 竖屏取 values[1]，横屏按 displayRotation 换轴
     * rate += (ω − rate) · dt/(dt + τ)       // τ = 50ms
     * |rate| < GATE(0.01) ⇒ 不积分            // 静止时一滴都不累加 ⇒ 不漂
     * pan += rate · dt                        // 正 = 向左转（右手定则：+Z 转向 +X）
     * ```
     *
     * 不用绝对姿态的原因见 [RasterGlCore.GYRO_GATE] 的注释：不归位就不能靠"绝对基准"，
     * 而要靠"静止不积分"来防漂。两者只能选后者（绝对姿态那条路在本机不响应）。
     */
    private fun startSensor(ctx: Context) {
        runCatching {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (gyro == null) {
                HookRuntime.log("RASTER 没有陀螺仪，画面静止在基点")
                return
            }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val v = event.values
                    if (v.size < 2) return
                    // 回调在主线程：这里只投递，积分 / 绘制全部回 GL 线程。
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

    /** 屏幕旋转（横屏时陀螺要换轴）；500ms 缓存。 */
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

    /** GL 线程里处理一帧陀螺。 */
    private fun onGyro(x: Float, y: Float, ts: Long) {
        if (runningKey == null) return
        // 过渡期冻结：不积分、不重绘（见 transitionFrozen）。
        // `lastGyroAt = 0` 让恢复后的第一帧按默认 dt 走，避免拿一个巨大的 dt 积分。
        if (transitionFrozen) {
            // 超时自愈：GL 线程可能卡在 swap 上，这里是唯一能读到时间的外部节拍。
            val since = if (frozenAt > 0L) SystemClock.elapsedRealtime() - frozenAt else 0L
            if (since > FROZEN_TIMEOUT_MS) {
                unfreezeTransition()
                HookRuntime.log("RASTER 冻结超时 ${since}ms ⇒ 强制解冻（淡出被 swap 卡住）")
            } else {
                lastGyroAt = 0L
                return
            }
        }
        // ROTATION_0(竖屏)=Y；90=+X；180=−Y；270=−X（与参考项目同一张表）。
        val w = when (currentRotation()) {
            Surface.ROTATION_90 -> x
            Surface.ROTATION_180 -> -y
            Surface.ROTATION_270 -> -x
            else -> y
        }
        if (lastGyroAt == 0L) {
            lastGyroAt = ts
            return
        }
        val dt = (ts - lastGyroAt) / 1_000_000_000f
        lastGyroAt = ts
        // 时间跳变（休眠 / 首帧）直接丢，不要拿一个巨大的 dt 去积分。
        if (dt <= 0f || dt > 0.5f) return

        val a = dt / (dt + RasterGlCore.GYRO_TAU_S)
        gyroRate += (w - gyroRate) * a
        if (aodFrozen) return
        // 静止门限：低于它不积分 ⇒ 噪声不会累积成漂移。
        if (abs(gyroRate) < RasterGlCore.GYRO_GATE) return

        // 等"恢复后第一个运动方向"：等到了就直接开始擦回第一张。
        if (homeArmed && !homeRunning) {
            startHomeWipe(if (gyroRate > 0f) 1f else -1f)
            return
        }
        if (homeRunning) return

        val before = pan
        pan = (pan + gyroRate * dt).coerceIn(-panMaxRad, panMaxRad)
        val delta = pan - before
        if (abs(delta) < 1e-9f) return

        // 正在收尾补擦时用户又明显动了 ⇒ 立刻放弃收尾，重新跟手。
        if (snapping && abs(gyroRate) > 0.35f) cancelSnap()
        if (snapping) return

        lastMotionAt = SystemClock.elapsedRealtime()
        lastMoveDir = if (delta > 0f) 1f else -1f
        dirty = true
        renderIfNeeded()
        scheduleIdleCheck()

        if (++gyroFrames % 60 == 0) {
            HookRuntime.log(
                "RASTER gyro ω=${"%.2f".format(w)} rate=${"%.2f".format(gyroRate)} " +
                    "pan=${"%.3f".format(pan)}rad f=${"%.2f".format(fValue())} " +
                    "dir=${if (pan >= 0f) "+" else "-"} rot=${currentRotation()}",
            )
        }
    }

    /** 当前连续图号（0 .. N−1）。 */
    private fun fValue(): Float {
        val n = images.size
        if (n < 2) return 0f
        val step = panMaxRad / (n - 1)
        return (abs(pan) / step).coerceIn(0f, (n - 1).toFloat())
    }

    // ───────────────────────────── 收尾（补完这一擦） ─────────────────────────────

    private fun scheduleIdleCheck() {
        handler?.removeCallbacks(idleCheck)
        handler?.postDelayed(idleCheck, RasterGlCore.SNAP_DELAY_MS)
    }

    private val idleCheck = Runnable {
        if (runningKey == null || aodFrozen || homeRunning) return@Runnable
        // 用户关掉「停顿时就近归位」⇒ 就停在两张之间，不补这一擦（2026-11-04）。
        if (!settleOnIdle) return@Runnable
        if (SystemClock.elapsedRealtime() - lastMotionAt < RasterGlCore.SNAP_DELAY_MS) return@Runnable
        val f = fValue()
        val idx = f.toInt()
        if (idx >= images.size - 1) return@Runnable
        val frac = f - idx
        if (frac < 0.02f || frac > 0.98f) return@Runnable
        // 就近：过半就擦完，不到一半就退回去。
        val target = if (frac >= 0.5f) {
            (idx + 1).toFloat()
        } else {
            idx.toFloat()
        }
        val step = panMaxRad / (images.size - 1)
        startSnap(target * step * (if (pan >= 0f) 1f else -1f))
    }

    private fun startSnap(targetPan: Float) {
        snapping = true
        snapFrom = pan
        snapTo = targetPan
        snapStartAt = SystemClock.elapsedRealtime()
        handler?.post(snapTick)
        HookRuntime.log("RASTER 收尾：${"%.3f".format(snapFrom)} → ${"%.3f".format(snapTo)} rad")
    }

    private val snapTick = object : Runnable {
        override fun run() {
            if (!snapping) return
            val t = ((SystemClock.elapsedRealtime() - snapStartAt).toFloat() / RasterGlCore.SNAP_MS)
                .coerceIn(0f, 1f)
            // ease-out cubic：收尾要"停得住"，不能匀速撞线。
            val u = 1f - t
            val e = 1f - u * u * u
            pan = snapFrom + (snapTo - snapFrom) * e
            dirty = true
            renderIfNeeded()
            if (t >= 1f) {
                pan = snapTo
                snapping = false
                dirty = true
                renderIfNeeded(force = true)
            } else {
                handler?.postDelayed(this, 16)
            }
        }
    }

    private fun cancelSnap() {
        if (!snapping) return
        snapping = false
        handler?.removeCallbacks(snapTick)
    }

    // ───────────────────────────── 基点重置 ─────────────────────────────

    /**
     * 「擦回第一张」：AOD 与锁屏一致 / 解锁回锁屏时用（用户 2026-11-04）。
     *
     * 方向取**恢复后第一个运动方向**（`onYaw` 里等），超时用上一次的方向；
     * 跨多张也只擦**一次**（把当前这张整体擦到图 0），否则从第 4 张回第 1 张要擦三遍。
     */
    private fun armHomeWipe() {
        if (images.size < 2) {
            pan = 0f
            dirty = true
            renderIfNeeded(force = true)
            return
        }
        homeArmed = true
        homeArmRun?.let { handler?.removeCallbacks(it) }
        val r = Runnable {
            if (homeArmed && !homeRunning) startHomeWipe(lastMoveDir)
        }
        homeArmRun = r
        handler?.postDelayed(r, RasterGlCore.HOME_WAIT_MS)
        HookRuntime.log("RASTER 已待命：等恢复后的第一个运动方向再擦回第一张")
    }

    private fun startHomeWipe(dir: Float) {
        homeArmed = false
        homeArmRun?.let { handler?.removeCallbacks(it) }
        homeArmRun = null
        val f = fValue()
        homeFrom = f.roundToInt().coerceIn(0, images.size - 1)
        if (homeFrom == 0 && f < 0.5f) {
            pan = 0f
            dirty = true
            renderIfNeeded(force = true)
            return
        }
        homeDir = dir
        homeRunning = true
        homeProg = 0f
        homeStartAt = SystemClock.elapsedRealtime()
        HookRuntime.log("RASTER 擦回第一张：从 #$homeFrom，方向=${if (dir > 0) "右→左" else "左→右"}")
        handler?.post(homeTick)
    }

    private val homeTick = object : Runnable {
        override fun run() {
            if (!homeRunning) return
            val t = ((SystemClock.elapsedRealtime() - homeStartAt).toFloat() / RasterGlCore.HOME_WIPE_MS)
                .coerceIn(0f, 1f)
            val u = 1f - t
            homeProg = 1f - u * u * u
            dirty = true
            renderIfNeeded()
            if (t >= 1f) {
                homeRunning = false
                homeProg = 0f
                pan = 0f
                // 下一帧重新取时间基准，避免拿一个巨大的 dt 去积分。
                lastGyroAt = 0L
                gyroRate = 0f
                dirty = true
                renderIfNeeded(force = true)
                HookRuntime.log("RASTER 已回到基点（第 1 张）")
            } else {
                handler?.postDelayed(this, 16)
            }
        }
    }

    private fun cancelHome() {
        homeArmed = false
        homeRunning = false
        homeProg = 0f
        homeArmRun?.let { handler?.removeCallbacks(it) }
        homeArmRun = null
        handler?.removeCallbacks(homeTick)
    }

    /** 立刻重设基点（灭屏 / 进入自定义 AOD）：**无过渡**。 */
    private fun resetNow(reason: String) {
        // 灭屏路径可能在解锁退场途中到达（transitionFrozen 还开着）⇒ 先解冻，
        // 否则下面这帧会被 renderIfNeeded 的冻结闸挡掉，基点重置不生效。
        unfreezeTransition()
        cancelSnap()
        cancelHome()
        pan = 0f
        lastGyroAt = 0L
        gyroRate = 0f
        dirty = true
        renderIfNeeded(force = true)
        HookRuntime.log("RASTER 基点重设（$reason）")
    }

    // ───────────────────────────── 灭屏 / AOD ─────────────────────────────

    private fun watchScreen(ctx: Context) {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    // 灭亮屏后系统会停掉传感器，必须重新注册（空间壁纸踩过同一个坑）。
                    Intent.ACTION_SCREEN_ON -> handler?.post {
                        // 灭屏那一帧置的冻结必须在这里解开 —— 没有 AOD 的机型不会走 onExitAod。
                        aodFrozen = false
                        unfreezeTransition()
                        reregisterSensor(c ?: ctx)
                        // 灭屏期间不 swap，画面停在旧帧 ⇒ 亮屏后强制补一帧。
                        forceDraw()
                        handler?.removeCallbacks(liquidTick)
                        handler?.post(liquidTick)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            // 33+ 必须带 flag（与 SpatialRenderer 同一写法），否则部分 ROM 上注册直接抛异常。
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            screenReceiver = r
        }.onFailure { HookRuntime.log("RASTER 注册灭亮屏广播失败：${it.javaClass.simpleName}") }
    }

    private fun reregisterSensor(ctx: Context) {
        if (runningKey == null) return
        runCatching { sensorListener?.let { sensorManager?.unregisterListener(it) } }
        sensorListener = null
        sensorManager = null
        startSensor(ctx)
        lastGyroAt = 0L
        gyroRate = 0f
    }

    /**
     * 灭屏：延 700ms 再决定要不要"立刻重设"。
     *
     * 因为灭屏后可能马上进 AOD —— 全屏 AOD 要**留到退出时带过渡擦回**，不能在这里就归零。
     * 自定义 AOD / 没有 AOD 的情况，700ms 内收不到 AOD 回调 ⇒ 走"立刻重设"。
     */
    private fun onScreenOff() {
        handler?.post {
            if (runningKey == null) return@post
            aodFrozen = true
            resetRun?.let { handler?.removeCallbacks(it) }
            val r = Runnable {
                if (!fullAod) resetNow("灭屏")
            }
            resetRun = r
            handler?.postDelayed(r, 700L)
        }
    }

    /**
     * 进入 AOD。`black` 是系统给的目标压暗值：
     * * **1.0** = 自定义 AOD ⇒ 立刻重设基点（无过渡）；
     * * **0.5** = 与锁屏一致的全屏 AOD ⇒ 先只压暗，退出时再带过渡擦回。
     */
    fun onEnterAod(black: Float) {
        handler?.post {
            if (runningKey == null) return@post
            aodFrozen = true
            dim = (1f - black.coerceIn(0f, 1f))
            // 景深主体同步淡出（它在窗口里，AOD 时不该留在屏上）。
            runCatching { RasterOverlay.onEnterAod() }
            if (black >= 0.99f) {
                fullAod = false
                resetRun?.let { handler?.removeCallbacks(it) }
                resetRun = null
                resetNow("进入自定义 AOD")
            } else {
                fullAod = true
                resetRun?.let { handler?.removeCallbacks(it) }
                resetRun = null
            }
            dirty = true
            renderIfNeeded(force = true)
        }
    }

    /** 退出 AOD（回锁屏）：解冻 + 压暗复位 + **重注册传感器** + 补一帧；全屏 AOD 还要"带过渡擦回第一张"。 */
    fun onExitAod() {
        handler?.post {
            if (runningKey == null) return@post
            aodFrozen = false
            unfreezeTransition()
            dim = 1f
            // 景深主体淡回 1（幂等兜底，与空间壁纸同一处）。
            runCatching { RasterOverlay.onExitAod() }
            lastGyroAt = 0L
            gyroRate = 0f
            // 灭屏时系统会停掉这个非 wake-up 传感器，不重注册就一直静止（空间壁纸 2026-10-29 实证）。
            appCtx?.let { reregisterSensor(it) }
            forceDraw()
            handler?.removeCallbacks(liquidTick)
            handler?.post(liquidTick)
            if (fullAod) {
                fullAod = false
                armHomeWipe()
            }
        }
    }

    // ───────────────────────────── 解锁淡出 / 复位 ─────────────────────────────
    //
    // 四个必须同时成立的点（缺一条就是一个真机 bug，空间壁纸逐条踩过）：
    // 1. **共用 leash 不淡**：桌面复用它，淡到 0 = 桌面图消失（光栅 2026-11-04 的根因）；
    // 2. **灭屏触发的 showing→false 不淡**：那时所有 surface 不可见，swap 必阻塞；
    // 3. **淡出中被打断 ⇒ 只登记不复位**：否则 alpha 冻在 0.6，既不再降也不复位；
    // 4. **淡出落地后 800ms 锁存**：解锁动画期间的复位噪声会让图闪回来。

    /**
     * 解锁退场：启动 alpha 渐隐（**幂等**：重复调用不会从 1 重开）。
     *
     * 真值优先：`SpatialWallpaperAlpha` 把 miwallpaper 的 `Transaction#setAlpha` 实参
     * 经 `debug.hyperextreme.spatial_wp_alpha`（×1000）桥过来，读得到就跟随；
     * 读不到退回 380ms 线性。结束真值 = `SpatialUnlockFade.isFadingAway()` 落回 false。
     */
    fun fadeOutOnUnlock() {
        val h = handler ?: return
        h.post {
            if (runningKey == null) return@post
            // **AOD 直接解锁**（不经过锁屏）时系统只走
            // `startKeyguardExitAnimationByInteractiveOrFullAODUnlockAnimation`，
            // **不会**调 `doWallpaperBlackAnim(show=true)` ⇒ `onExitAod()` 不执行 ⇒
            // `dim` 停在 AOD 的 0.5。必须在这里无条件复位（2026-11-05 真机问题二）。
            // 放在所有分支之前：仅桌面档、共用 leash、正常淡出都要复位。
            val hadDim = dim < 0.999f
            if (hadDim) {
                dim = 1f
                dirty = true
                HookRuntime.log("RASTER 解锁：压暗复位 1.0（AOD 直接解锁不经过 onExitAod）")
            }
            // 冻结同样要解：AOD 里 aodFrozen=true，这条路径上没有 onExitAod 帮它解。
            // 两个标志都要清：aodFrozen 拦的是绘制，transitionFrozen 拦的是采样/重绘。
            aodFrozen = false
            unfreezeTransition()
            if (lockScs.isEmpty()) {
                // 仅桌面档（没有锁屏份）：不需要淡出。解锁后这份该显示 ⇒ alpha 1.0。
                setDeskOnlyAlpha(1f)
                // 补一帧让复位后的亮度落到桌面。
                if (hadDim && screenOn()) forceDraw()
                return@post
            }
            // 幂等：三个触发点（退场动画起点 / 锁屏状态跳变 / dismiss 动画）都会到，先到者生效。
            // 少了这句，第二次触发会把已淡到一半的 alpha 重置回 1（看得见的回弹）。
            if (fadeTick != null) return@post
            cancelFade()
            // 灭屏触发的 showing→false 不是解锁：那时 swap 必阻塞，会把 GL 线程卡死。
            if (!screenOn()) {
                HookRuntime.log("RASTER 解锁淡出 跳过（灭屏触发，非解锁）")
                return@post
            }
            // **景深主体同步退场**（2026-11-04）：它是窗口内的 View，系统那 200ms 的
            // self-blur ramp 照样糊到它 ⇒ 任何时候都要退。必须在 sharedLeash 分支**之前**：
            // 共用 leash 只是省掉 GL 那份的 alpha 淡出，前景主体照样要退。
            runCatching { RasterOverlay.fadeOutOnUnlock() }
            // **共用一条 leash ⇒ 不淡出**：它解锁后还要当桌面壁纸用，淡到 0 桌面就空了。
            // 系统自己会让这条 leash 连续过渡 ⇒ 本来就不需要切换，天然无缝。
            if (sharedLeash) {
                HookRuntime.log("RASTER 解锁淡出 跳过（共用一条 leash，交给系统连续过渡）")
                // 共用 leash 没有「桌面份接手」这一步，压暗复位后必须自己补一帧。
                if (hadDim && screenOn()) forceDraw()
                return@post
            }
            // 冻结整个过渡期（见 transitionFrozen）：不采样、不重绘。
            freezeTransition()
            // **把「冻结的这一帧」同时写进两份**：桌面那份的 buffer 队列里躺的是旧内容，
            // 而 SF 是 FIFO 从队头取 ⇒ 切换后先吐旧帧 = 用户看到的「景物换了位置」。
            val deskBefore = swapDeskFrames
            freezeFrameForBoth()
            val deskReady = swapDeskFrames > deskBefore
            val started = SystemClock.elapsedRealtime()
            // 见过 `mMiKeyguardFadingAway == true` 才认后面的 false 是"动画结束"，
            // 否则启动瞬间它可能还是初始的 false，会被误判成"立刻结束"。
            var sawFading = false
            var usedExternal = false
            var minExt = 1f
            // 从**当前值**起，不是从 1：淡出若被重复触发（两个入口 / 状态抖动），
            // 从 1 重开会让 alpha 跳回去 = 又一次闪回。
            var lastA = currentAlpha
            val r = object : Runnable {
                override fun run() {
                    val scs = lockScs
                    if (scs.isEmpty() || runningKey == null) {
                        fadeTick = null
                        return
                    }
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - started
                    val timeBased = (1f - elapsed.toFloat() / FADE_MS).coerceIn(0f, 1f)
                    // 只跟「系统真的在降」的区间（0.02..0.999）：稳定 1 = 还没开始，
                    // 稳定 0 = 壁纸未呈现，都不跟 —— 否则会被"恒 0"瞬间拉黑。
                    val extV = readSystemWallpaperAlpha()?.takeIf { it > 0.02f && it < 0.999f }
                    // ⚠️ 与**自己的历史值**取 min，不能和时间驱动比：后者会让我们恒 ≤ 系统，
                    // 于是总是先消失、露出系统壁纸（空间壁纸 2026-10-28 真机现象）。
                    var a = if (extV != null) minOf(lastA, extV) else minOf(lastA, timeBased)
                    if (extV != null) {
                        usedExternal = true
                        if (extV < minExt) minExt = extV
                    }
                    // **结束真值**：系统退场标志一落，立刻归 0 —— 不管 FADE_MS 估得准不准。
                    val fading = SpatialUnlockFade.isFadingAway()
                    if (fading == true) sawFading = true
                    val done = sawFading && fading == false
                    if (done) {
                        a = 0f
                        HookRuntime.log(
                            "RASTER 解锁淡出 系统动画结束（实测 ${elapsed}ms，FADE_MS=$FADE_MS，" +
                                "真值=${if (usedExternal) "miwallpaper alpha(最低 ${(minExt * 1000).toInt()}‰)" else "时间驱动"}）",
                        )
                    }
                    lastA = a
                    applyAlphaTo(scs, a)
                    if (a > 0.01f && !done) {
                        h.postDelayed(this, 16)
                    } else {
                        fadeTick = null
                        if (a <= 0.01f) {
                            // 淡出落地 ⇒ 开锁存窗口 + 翻转显示态（桌面份接手）。
                            // 必须在 forceDraw 之前翻 —— 它自己的 activeMountIsLocked() 也要读这个值。
                            fadeDoneAt = SystemClock.elapsedRealtime()
                            showingDesktop = true
                            // **先解冻，再补帧**：补帧可能阻塞（surface 不可见时），
                            // 顺序反了就会把冻结标志一起锁死 —— 那正是"亮屏后一直静止"的成因。
                            unfreezeTransition()
                            // 桌面份在淡出开始时就已经写好了（同一帧）⇒ 这里通常**不用再画**。
                            // 只有那次写失败、而且此刻亮屏（不阻塞）才兜底补一帧。
                            if (!deskReady && screenOn()) forceDraw()
                        }
                    }
                }
            }
            fadeTick = r
            h.post(r)
        }
    }

    /**
     * 回锁屏 / 重新亮屏：把 alpha 复位到 1。
     *
     * 三道闸（与 `SpatialRenderer.requestRaiseLocked` 逐条一致）：
     * 1. 淡出进行中 ⇒ **挂起，绝不打断**（打断会让 alpha 冻在半路 = 半透明的图闪回来）；
     * 2. 淡出落地后 [FADE_LATCH_MS] 内 ⇒ 挂起（覆盖整段解锁动画）；
     * 3. 放行时用**系统真实锁屏态**校正 [showingDesktop] —— 亮屏兜底入口在桌面态也会被调到。
     */
    fun resetAlpha() {
        val h = handler ?: return
        h.post { requestRaiseLocked(h) }
    }

    private fun requestRaiseLocked(h: Handler) {
        if (runningKey == null) return
        // 闸 1：淡出还在跑 —— 只登记，让它自己跑完（打断会让 alpha 冻在半路）。
        if (fadeTick != null) {
            pendingRaise = true
            scheduleRaisePump(h, FADE_MS.toLong() + 32)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val sinceFade = if (fadeDoneAt > 0L) now - fadeDoneAt else Long.MAX_VALUE
        // 闸 2：锁存窗口
        if (sinceFade < FADE_LATCH_MS) {
            pendingRaise = true
            scheduleRaisePump(h, FADE_LATCH_MS - sinceFade + 16)
            return
        }
        // 全部通过 ⇒ 放行
        pendingRaise = false
        raiseScheduled = false
        fadeDoneAt = 0L          // 锁存只对"最近一次淡出"有效，复位成功即作废
        // **显示态校正**：不能无条件设成"锁屏" —— `resetAlpha()` 还有一个"亮屏兜底"入口，
        // 它在**桌面态**也可能被调到；无条件切回锁屏会让 activeMountIsLocked() 立刻去写
        // 不可见的锁屏 leash（会阻塞）。
        val lockedNow = queryKeyguardLocked(null)
        showingDesktop = if (lockedNow != null) !lockedNow else false
        // **景深主体复位**（2026-11-04）：必须无条件调 —— GL 那份 alpha 可能本来就是 1，
        // 而主体已被上一次解锁淡到 0（那样回锁屏就永远没有前景主体）。
        runCatching { RasterOverlay.resetAlpha() }
        // 仅桌面档：回锁屏要把挂在共用 leash 上的桌面份隐掉。
        setDeskOnlyAlpha(if (showingDesktop) 1f else 0f)
        val scs = lockScs
        if (scs.isEmpty()) return
        if (currentAlpha < 0.99f) {
            val ext = readSystemWallpaperAlpha()
            HookRuntime.log(
                "RASTER 复位 alpha 放行（距淡出 ${sinceFade.coerceAtMost(99999)}ms，" +
                    "ext=${ext?.let { (it * 1000).toInt() } ?: -1}‰）",
            )
            applyAlphaTo(scs, 1f)
            // 复位只写了 alpha，内容还是淡出前那一帧 ⇒ 必须重画（否则锁屏静止时永远是旧图）。
            forceDraw()
        }
    }

    /** 排一次补发（**防重复 post**：同一个 pending 只排一次）。 */
    private fun scheduleRaisePump(h: Handler, delayMs: Long) {
        if (raiseScheduled) return
        raiseScheduled = true
        h.postDelayed({ pumpRaiseLocked(h) }, delayMs.coerceAtLeast(16L))
    }

    /** 补发泵：条件还不满足就再排一次，直到 [requestRaiseLocked] 真的放行。 */
    private fun pumpRaiseLocked(h: Handler) {
        raiseScheduled = false
        if (!pendingRaise) return
        requestRaiseLocked(h)
    }

    private fun cancelFade() {
        fadeTick?.let { handler?.removeCallbacks(it) }
        fadeTick = null
    }

    /** 锁屏组的所有 SC（淡出 / 复位只作用于它们；桌面档没有这一份 ⇒ 自动失效）。 */
    private val lockScs: List<android.view.SurfaceControl>
        get() = mounts.filter { it.locked }.map { it.sc }

    /** miwallpaper 的真实壁纸 alpha（0..1）；属性没写 / 非法返回 null。 */
    private fun readSystemWallpaperAlpha(): Float? {
        val raw = HookRuntime.systemProperty(SpatialWallpaperAlpha.PROP_ALPHA).trim()
        if (raw.isEmpty()) return null
        val v = raw.toIntOrNull() ?: return null
        if (v < 0 || v > 1000) return null
        return v / 1000f
    }

    /**
     * **密码页连续模糊**（2026-11-04，与 `SpatialRenderer.onKeyguardBlurArgs` 同一处口径）。
     *
     * 光栅与空间壁纸在系统侧是同一个处境：都挂在壁纸 leash 上、都是 miwallpaper 那两个
     * Surface 的兄弟层。`doBlurAndDim` 里两条路由系统景深开关决定：
     * * **景深开** ⇒ `setWallpaperSyncBlur` 是**窗口级**（糊窗口背后的全部）＋
     *   `applyDepthMiBlurEffect` 糊前景层 ⇒ 我们的图跟着糊，什么都不用做；
     * * **景深关** ⇒ 改走 `updateKeyguardWallpaperRatio` —— miwallpaper 只糊**它自己那个**
     *   BBQ 子层，我们是兄弟层 ⇒ **完全没被糊**（上滑进密码页时图还清清楚楚）。
     *
     * 所以直接复用空间壁纸那份"替系统补调"（两个 `public static`，与画什么无关）。
     * 只在「景深关 + 不在解锁退场」时调 —— 景深开时系统自己会调，重复调用 = 双重模糊。
     */
    fun onKeyguardBlurArgs(
        args: List<Any?>,
        depthEffect: Boolean?,
        fgLayer: android.view.View?,
    ) {
        if (runningKey == null) return
        val ratio = (args.getOrNull(0) as? Float) ?: return
        // 解锁退场那段 ratio 是 dismiss 模糊，不是"密码页连续模糊"；此时我们正在淡出，
        // 再糊一遍纯属浪费（空间壁纸 2026-10-30 的结论）。
        if (SpatialUnlockFade.isFadingAway() == true) return
        if (depthEffect != false || fgLayer == null) return
        SpatialRenderer.applySystemBlur(ratio, fgLayer)
    }

    /** 反射缓存：`SurfaceControl.Transaction#setAlpha` / `apply`。 */
    private val txAlpha by lazy {
        runCatching {
            val c = Class.forName(
                "android.view.SurfaceControl\$Transaction", false, javaClass.classLoader,
            )
            Triple(
                c,
                c.getMethod(
                    "setAlpha", android.view.SurfaceControl::class.java, Float::class.javaPrimitiveType,
                ),
                c.getMethod("apply"),
            )
        }.getOrNull()
    }

    /**
     * 把**一组** SC 的 alpha 一起设成 [target]（桌面份永远不在里面）。
     *
     * 锁存窗口内的 raise 在这里也会被拦 —— 不只 `resetAlpha` 一条路能到这儿。
     * 挡下的请求记 pending 并**自己调度补发**，否则万一这次是唯一的 raise，就永久停在 0。
     */
    private fun applyAlphaTo(scs: List<android.view.SurfaceControl>, target: Float) {
        if (scs.isEmpty()) return
        if (target > 0.01f && fadeDoneAt > 0L &&
            SystemClock.elapsedRealtime() - fadeDoneAt < FADE_LATCH_MS
        ) {
            pendingRaise = true
            handler?.let { scheduleRaisePump(it, 16L) }
            return
        }
        if (target == currentAlpha) return
        runCatching {
            val t = txAlpha ?: return
            val tx = t.first.getConstructor().newInstance()
            scs.forEach { t.second.invoke(tx, it, target) }
            t.third.invoke(tx)
            currentAlpha = target
        }.onFailure {
            HookRuntime.log("RASTER 设 alpha 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * **仅桌面档在共用 leash 上的可见性**（2026-11-05，问题一）。
     *
     * 这份层锁屏时也在屏幕上（系统只有一条 leash），所以锁屏必须 alpha 0、桌面 alpha 1。
     *
     * 不复用 [applyAlphaTo]：那是锁屏份的淡出通道，带 `currentAlpha` 与淡出锁存窗口；
     * 这里是一份"不该在锁屏出现"的桌面层，两个状态互斥、没有渐变。
     */
    private fun setDeskOnlyAlpha(target: Float, force: Boolean = false) {
        if (!desktopOnlyShared) return
        val t = target.coerceIn(0f, 1f)
        if (!force && kotlin.math.abs(t - deskOnlyAlpha) < 0.01f) return
        val scs = mounts.filter { !it.locked }.map { it.sc }
        if (scs.isEmpty()) return
        runCatching {
            val tx = txAlpha ?: return
            val obj = tx.first.getConstructor().newInstance()
            scs.forEach { tx.second.invoke(obj, it, t) }
            tx.third.invoke(obj)
            deskOnlyAlpha = t
            HookRuntime.log(
                "RASTER 仅桌面 alpha=${if (t > 0.5f) "1.0（桌面）" else "0.0（锁屏隐藏）"}",
            )
        }.onFailure {
            HookRuntime.log("RASTER 仅桌面 alpha 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // ───────────────────────────── 绘制 ─────────────────────────────

    private fun requestDraw(force: Boolean = false) {
        dirty = true
        renderIfNeeded(force = force)
    }

    /**
     * 画当前该画的那一组。
     *
     * ⚠️ **不可见的 leash 不消费 buffer ⇒ 对它 `eglSwapBuffers` 会阻塞**（空间壁纸
     * 2026-10-29 实测）。两份都画的话，只要有一条 leash 不在前台，整个 GL 线程就卡死：
     * 用户看到的是「解锁跳过去后画面是静止态，不再跟手」—— 不是内容旧，是**根本不再更新**。
     *
     * 所以：只画当下可见的那一份；切换的那一帧由 [freezeFrameForBoth] / [forceDraw] 补。
     */
    private fun renderIfNeeded(force: Boolean = false) {
        runningKey ?: return
        // 过渡期冻结：任何重绘都会和「切换」抢帧预算（见 transitionFrozen）。
        if (transitionFrozen) {
            if (frozenAt > 0L && SystemClock.elapsedRealtime() - frozenAt > FROZEN_TIMEOUT_MS) {
                unfreezeTransition()
            } else {
                return
            }
        }
        val f = fValue()
        if (!force && !dirty &&
            abs(f - lastDrawnF) < RasterGlCore.DRAW_EPS &&
            abs(dim - lastDrawnDim) < 1e-4f
        ) {
            return
        }
        dirty = false
        lastDrawnF = f
        lastDrawnDim = dim
        drawAllFrames()
    }

    /**
     * 现在该画哪一份。
     *
     * 只看"有没有桌面份"：共用 leash 时桌面份已被丢弃，只剩锁屏那份（也就是**唯一**那份），
     * 必须永远画它 —— 用 `showingDesktop` 去筛会筛出空组。
     */
    private fun activeMountIsLocked(): Boolean {
        if (mounts.none { !it.locked }) return true
        return !showingDesktop
    }

    /** 画当前组（共用 leash / 单档只有 1 份；全局非共用时也只有 1 份可见）。 */
    private fun drawAllFrames(): Boolean {
        val wantLocked = activeMountIsLocked()
        val group = mounts.filter { it.locked == wantLocked }.ifEmpty { mounts }
        if (group.isEmpty()) return false
        var ok = true
        for (m in group) if (!drawMount(m)) ok = false
        return ok
    }

    /**
     * **给每一个挂载目标各画一帧**（不做"当前该画哪一份"的筛选）。
     *
     * 两个调用点，理由同一条 —— 要求**所有** SC 的 buffer 里都有内容：
     * * [startInternal] 首帧：否则桌面态启动时桌面份可能一次都不画（没有 buffer ⇒ 不显示）；
     * * [freezeFrameForBoth]（解锁那一刻）：两份必须逐像素一致，切换才不会跳。
     *
     * **逐份容错**：某一份失败不再阻断其余份。
     */
    private fun drawEveryMount(): Boolean {
        var ok = true
        for (m in mounts) if (!drawMount(m)) ok = false
        return ok
    }

    /**
     * **把当前这一帧同时写进所有挂载目标**（淡出开始时调一次）。
     *
     * 两份内容因此**逐像素一致**，切换时 SF 取哪一帧都对 —— 这是「景物不跳」的全部依据。
     * 顺序是「锁屏份 → 桌面份」：万一桌面那份此刻不可见、swap 阻塞，至少锁屏已经写完。
     */
    private fun freezeFrameForBoth(): Long {
        val t0 = SystemClock.elapsedRealtime()
        drawEveryMount()
        val cost = SystemClock.elapsedRealtime() - t0
        if (cost > 60L) {
            HookRuntime.log("RASTER 冻结帧双写耗时 ${cost}ms（桌面份可能被阻塞）")
        }
        return cost
    }

    /**
     * **强制补画一帧**（绕过 `DRAW_EPS` 死区）。
     *
     * 用在四个切换点上，因为切换后要画的是「另一份」，而它停在旧的姿态上：
     * 解锁淡出落地 / 回锁屏复位 / 亮屏 / 退出 AOD。
     */
    private fun forceDraw() {
        if (runningKey == null) return
        val locked = activeMountIsLocked()
        val t0 = SystemClock.elapsedRealtime()
        // **同步**执行：调用方本来就在 handler 线程，再 post 一次只会多一帧延迟。
        drawAllFrames()
        val cost = SystemClock.elapsedRealtime() - t0
        HookRuntime.log(
            "RASTER 切换补帧：画${if (locked) "锁屏" else "桌面"}份 耗时 ${cost}ms" +
                "（锁屏帧 $swapLockFrames / 桌面帧 $swapDeskFrames / 失败 $swapErrors）",
        )
        lastDrawnF = fValue()
        lastDrawnDim = dim
        dirty = false
    }

    /** 画指定的一份（返回是否成功；失败计入 [swapErrors]）。 */
    private fun drawMount(m: Mount): Boolean {
        val d = display ?: return false
        val c = context ?: return false
        val es = m.eglSurface ?: return false
        val t0 = SystemClock.elapsedRealtime()
        if (!EGL14.eglMakeCurrent(d, es, es, c)) {
            swapErrors++
            if (!eglErrLogged) {
                eglErrLogged = true
                HookRuntime.log("RASTER eglMakeCurrent 失败（locked=${m.locked}）")
            }
            return false
        }
        drawScene()
        if (EGL14.eglSwapBuffers(d, es)) {
            if (m.locked) swapLockFrames++ else swapDeskFrames++
        } else {
            swapErrors++
        }
        val cost = SystemClock.elapsedRealtime() - t0
        if (cost > 60L && slowLogged < 8) {
            slowLogged++
            HookRuntime.log(
                "RASTER 绘制慢 ${cost}ms（锁屏帧 $swapLockFrames / 桌面帧 $swapDeskFrames / 失败 $swapErrors）",
            )
        }
        return true
    }

    private fun drawScene() {
        val key = runningKey ?: return
        val n = images.size
        if (n <= 0 || program == 0) return

        var fromIdx: Int
        var toIdx: Int
        var t: Float
        var dir: Float
        if (homeRunning) {
            fromIdx = homeFrom.coerceIn(0, n - 1)
            toIdx = 0
            t = homeProg
            dir = homeDir
        } else {
            val f = fValue()
            var idx = f.toInt()
            if (idx > n - 1) idx = n - 1
            var frac = f - idx
            if (idx >= n - 1) frac = 0f
            fromIdx = idx
            toIdx = (idx + 1).coerceAtMost(n - 1)
            if (toIdx == fromIdx) frac = 0f
            t = frac
            // 方向 = **累计角的符号**（参考项目同款），不是瞬时运动方向。
            // 同一侧活动时方向恒定；只有转过基点、到另一侧才翻 —— 而那时 t≈0、
            // 两边显示的是同一张整图 ⇒ 翻转无感知。之前的"段起点锁存 + 中途反向不翻"
            // 会让人往回推时擦除还按原方向跑（用户 2026-11-04 报的"和手反着来"）。
            dir = if (pan >= 0f) 1f else -1f
        }
        if (t <= 0.0005f) {
            toIdx = fromIdx
            t = 0f
        }

        // **景深层同帧同步**（2026-11-04）：把与背景**完全相同**的 (from, to, t, dir) 推给
        // [RasterOverlay] —— 两边各算一遍迟早漂移，主体就会和背景错位。
        // 没开景深时它自己空转（view 为 null 直接 return）。
        if (runningLockDepth) {
            runCatching { RasterOverlay.onSceneChanged(fromIdx, toIdx, t, dir) }
        }

        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        quad?.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad?.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)

        // ⚠️ 顺序不能反：先确保纹理在位（首次会现传并算出 cover 参数），再读 uv ——
        //    否则第一帧拿到的是恒等 uv，图会以拉伸的样子闪一下。
        // 顺手把邻居备好（已在缓存/在途是空操作）—— 下次转过去就不用现解。
        preload(key, fromIdx + 1)
        preload(key, fromIdx - 1)
        val texF = textureFor(key, fromIdx, toIdx)
        val texT = if (toIdx == fromIdx) texF else textureFor(key, toIdx, fromIdx)
        val uvF = uvOf(fromIdx)
        val uvT = uvOf(toIdx)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texF)
        GLES20.glUniform1i(uFromTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texT)
        GLES20.glUniform1i(uToTex, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform1f(uT, t)
        GLES20.glUniform1f(uDir, dir)
        GLES20.glUniform4f(uFromUv, uvF[0], uvF[1], uvF[2], uvF[3])
        GLES20.glUniform4f(uToUv, uvT[0], uvT[1], uvT[2], uvT[3])
        GLES20.glUniform1f(uBand, RasterGlCore.BAND)
        GLES20.glUniform1f(uDim, dim)
        val elapsedSec = ((SystemClock.elapsedRealtime() - animStartAt) % 3600_000L) / 1000f
        GLES20.glUniform1f(uTime, elapsedSec)
        GLES20.glUniform1f(uPan, pan)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    // ───────────────────────────── 纹理 ─────────────────────────────

    private fun uvOf(index: Int): FloatArray {
        for (i in slotTex.indices) if (slotIdx[i] == index) return slotUv[i]
        return floatArrayOf(1f, 1f, 0f, 0f)
    }

    /**
     * 拿到某一号的纹理；没有就现传。
     *
     * @param keep 另一个正在用的号 —— 不许把它的槽抢掉，否则同一帧里会来回重传。
     */
    private fun textureFor(key: String, index: Int, keep: Int): Int {
        for (i in slotTex.indices) {
            if (slotIdx[i] == index) return slotTex[i]
        }
        var target = -1
        for (i in slotTex.indices) if (slotIdx[i] < 0) { target = i; break }
        if (target < 0) {
            for (i in slotTex.indices) if (slotIdx[i] != keep) { target = i; break }
        }
        if (target < 0) target = 0
        if (slotTex[target] != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(slotTex[target]), 0) }
            slotTex[target] = 0
            slotIdx[target] = -1
        }
        val t0 = SystemClock.elapsedRealtime()
        val bmp = preloaded.remove(index) ?: loadBitmap(key, index) ?: return 0
        val tex = upload(bmp)
        val bw = bmp.width
        val bh = bmp.height
        bmp.recycle()
        // cover 已经在 [loadBitmap] 里**按 CPU 裁好**了 ⇒ 纹理就是屏幕尺寸，uv 恒等。
        // （shader 里的 uFromUv/uToUv 留着给将来做缩放动画，现在恒等。）
        slotUv[target] = floatArrayOf(1f, 1f, 0f, 0f)
        slotTex[target] = tex
        slotIdx[target] = index
        HookRuntime.log(
            "RASTER 纹理 #$index 上传完成 ${SystemClock.elapsedRealtime() - t0}ms ${bw}×$bh（已是屏幕尺寸）",
        )
        return tex
    }

    /**
     * 读第 index 张，**在 CPU 侧就按 cover 裁好、缩到屏幕尺寸**再返回。
     *
     * 为什么不在 shader 里裁（2026-11-04，用户「抗锯齿不太行」）：
     * 把 2000×3555 的原图直接传上去、只靠 GL 的 `GL_LINEAR` 缩小，等于**单层双线性 + 无 mipmap**，
     * 缩 1.67× 时采样不足 —— 边缘发毛、转动时闪。
     * 裁到屏幕尺寸后是 **1:1 采样**，天然没有 minification；顺带把显存从 28MB/张降到 12.8MB/张。
     */
    private fun loadBitmap(key: String, index: Int): Bitmap? = runCatching {
        val ctx = appCtx ?: return null
        val uri = Uri.parse("content://$AUTHORITY/raster/$key/$index")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null

        val tw = screenW
        val th = screenH
        // 先让解码器丢一轮像素：源图比目标大很多时，一次大比例双线性会糊。
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sw, sh, tw, th)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        if (src.width == tw && src.height == th) return src

        // cover：在源图里取"比例等于屏幕"的居中矩形，再画满目标位图。
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
        Canvas(out).drawBitmap(
            src,
            Rect(cx, cy, cx + cw, cy + ch),
            Rect(0, 0, tw, th),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        src.recycle()
        out
    }.getOrNull()

    /** 长边先降到目标的 1~2 倍之间（`inSampleSize` 只能是 2 的幂）。 */
    private fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
        var s = 1
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }

    /** 后台解码第 index 张（已在缓存/在途就跳过）。 */
    private fun preload(key: String, index: Int) {
        if (index < 0 || index >= images.size) return
        if (runningKey == null) return
        if (preloaded.containsKey(index) || !preloading.add(index)) return
        Thread {
            runCatching {
                val bmp = loadBitmap(key, index)
                if (bmp != null && runningKey != null) putPreloaded(index, bmp) else bmp?.recycle()
            }
            preloading.remove(index)
        }.apply { isDaemon = true }.start()
    }

    /** 只保留最近两张，多的立刻回收。 */
    private fun putPreloaded(index: Int, bmp: Bitmap) {
        preloaded[index] = bmp
        if (preloaded.size > 2) {
            for (k in preloaded.keys.toList()) {
                if (preloaded.size <= 2) break
                if (k != index) preloaded.remove(k)?.recycle()
            }
        }
    }

    private fun clearPreloaded() {
        for (b in preloaded.values) runCatching { b.recycle() }
        preloaded.clear()
        preloading.clear()
    }

    private fun upload(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val t = ids[0]
        if (t == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return t
    }

    // ───────────────────────────── 杂项 ─────────────────────────────

    /** 读 `meta.json` 的 items（顺序即格子顺序）。 */
    private fun readItems(ctx: Context, key: String): List<String> = runCatching {
        val uri = Uri.parse("content://$AUTHORITY/raster/$key/meta")
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return emptyList()
        val arr = org.json.JSONObject(text).optJSONArray("items") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    }.getOrElse {
        HookRuntime.log("RASTER meta 读取失败：${it.message}")
        emptyList()
    }

    /**
     * 启动瞬间「当前是不是桌面态」。
     *
     * 顺序：`KeyguardManager.isKeyguardLocked` → 属性 `debug.hyperextreme.spatial_keyguard`
     * → 保守按锁屏。属性通道的语义见 `SpatialWallpaperAlpha`：空值（刚开机 / SystemUI 重启）
     * **按锁屏**处理 —— 宁可多判一次锁屏，也不要让桌面份空着。
     */
    private fun readShowingDesktop(ctx: Context): Boolean {
        val locked = queryKeyguardLocked(ctx)
            ?: return HookRuntime.systemProperty(
                com.frederic22.hyperextreme.data.SpatialFlag.PROP_KEYGUARD,
            ).trim() == "0"
        return !locked
    }

    /** 系统当前是否锁屏；拿不到返回 null（调用方自己决定兜底）。 */
    private fun queryKeyguardLocked(ctx: Context?): Boolean? {
        val c = ctx ?: appCtx ?: return null
        return runCatching {
            (c.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        }.getOrNull()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs) ?: return 0
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs) ?: return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            HookRuntime.log("RASTER link 失败：${GLES20.glGetProgramInfoLog(p)}")
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) {
            HookRuntime.log("RASTER shader 编译失败：${GLES20.glGetShaderInfoLog(s)}")
            return null
        }
        return s
    }
}
