package com.frederic22.hyperextreme.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 空间壁纸：**正式 GL 渲染器**（2026-10-22，SystemUI 进程内）。
 *
 * ## 它做什么
 *
 * 把 `files/spatial/<key>/layers/layer0…7.webp`（8 层 RGBA，alpha = 该层掩码）画进一个
 * 自建 SurfaceControl，再 `reparent` 到系统壁纸的 leash 上 —— 和 `SpatialProbe` 验证过的
 * 那条链路一模一样，只是这次用的是**真实层图**、并且由 App 的「立即替换」驱动。
 *
 * ## 线程
 *
 * 所有 GL 调用都 post 到**单条 `HandlerThread`**：EGL 上下文绑线程，跨线程调用必崩。
 * 主线程只负责 [start] / [stop]，立刻返回。
 *
 * ## 层图从哪来
 *
 * SystemUI 读不到模块私有目录，所以走后者的 ContentProvider：
 * `content://com.frederic22.hyperextreme.provider/spatial/<key>/layerN.webp`。
 *
 * ## 当前阶段（静态）
 *
 * 每层只按「近层略大」画一帧，用来确认 **8 层叠起来 = 原图**（每层 alpha 互补）。
 * 透视矩阵与传感器驱动是下一步的事（接口留在 [drawFrame] 里，`tilt` 目前恒为 0）。
 *
 * ## 已知前提
 *
 * * 父层是 `android.view.WindowManagerGlobal`（**不是** `WallpaperManager$WallpaperManagerGlobal`）；
 * * **锁屏态看不到**：壁纸 leash 在锁屏是 `flags:1` 隐藏，必须解锁到桌面态才可见；
 * * 退出时必须 `Transaction.remove`，否则层会一直留在 SurfaceFlinger 里。
 */
internal object SpatialRenderer {

    private const val AUTHORITY = "com.frederic22.hyperextreme.liquid.provider"

    /**
     * 挂哪条壁纸 leash —— `true` = 锁屏壁纸，`false` = 桌面壁纸。
     *
     * `WindowManagerGlobal#getWallpaperByShowWhenLocked(displayId, showWhenLocked)` 是 MIUI
     * 私有 API（AOSP 的 `WindowManagerGlobal` 里没有）。第二个参数选 token —— AOSP 的
     * `WallpaperController` 里锁屏 / 桌面就是两个 WallpaperWindowToken：
     * `hasTopShowWhenLockedWallpaper` / `hasTopHideWhenLockedWallpaper`。
     *
     * **2026-10-28 真机探针结论（不必重复验证）**：
     * * `(0, true)` 拿到的 token 非空、`getLeash()` 正常、`reparent` 成功；
     * * 挂上之后**锁屏可见、桌面不可见**，与 `(0, false)` 完全互补；
     * * 锁屏的时钟 / 通知 / 按钮全部不受影响（它们在 SystemUI 窗口内，比壁纸 leash 高）；
     * * 景深主体**仍然在**（同理在窗口内）⇒ 由 `SpatialForegroundHider` 撤掉。
     *
     * 读不到 scope（旧版本升上来）时按**锁屏**处理 —— 与用户 2026-10-28 定的默认档一致。
     */

    /**
     * 层数上限 / 球面几何 / 姿态量程 —— **全部来自 [SpatialGlCore]**（2026-10-30，v1.5.82）。
     *
     * 抽出去的原因：`wallpaper/SpatialWallpaperService`（系统壁纸服务链，免 root）要用
     * **同一份**数值与公式。这里保留同名别名 ⇒ 本文件内部所有引用一行都不用改。
     */
    private const val MAX_LAYERS = SpatialGlCore.MAX_LAYERS
    private const val SPHERE_HALF_V_RAD = SpatialGlCore.SPHERE_HALF_V_RAD
    private const val DRAW_EPS = SpatialGlCore.DRAW_EPS

    /**
     * 透视相机：FOV **60°**（手机壁纸的自然视角）。`CAM_DIST = 1/tan(30°)` ⇒ 世界坐标 ±1 铺满屏幕。
     *
     * ⚠️ 2026-10-23 定案：**所有层共用一个 z**（不再按 depthMean 拉开层间距）。
     * 因为透视下 z 差 = 缩放差（远层更小）⇒ 静止时层与层永远对不齐，
     * 而「层间拉开」改成靠**逐像素位移场**实现。
     */
    private const val FOV_DEG = 60f
    private const val CAM_DIST = 1.732f

    /** 见 [SpatialGlCore.SPHERE_SAFE]（1.15：位移余量 1.45 倍）。 */
    private const val SPHERE_SAFE = SpatialGlCore.SPHERE_SAFE

    /** 见 [SpatialGlCore.PAN_RAD]（100px @2640，两向各向同性）。 */
    private const val PAN_RAD = SpatialGlCore.PAN_RAD

    /** 见 [SpatialGlCore.DK_TH]（0.08：只有 Δk<0.08 的近深度区域才融合）。 */
    private const val DK_TH = SpatialGlCore.DK_TH

    /** 透视旋转的折减：只给一点点「整体跟着转」的几何感（0.35 ⇒ 约 1°）。 */
    private const val PERSPECTIVE_K = 0.35f

    /** 安全区：整体放大倍率。位移最大 54px(4.5%) ⇒ 每边 4.5% ⇒ 1.09 刚够。 */
    private const val SAFE = 1.09f

    /** 见 [SpatialGlCore.FULL_SCALE_RAD]（10°：手腕自然转动就能打到满幅）。 */
    private const val FULL_SCALE_RAD = SpatialGlCore.FULL_SCALE_RAD

    /** 见 [SpatialGlCore.GYRO_TAU_MS]。 */
    private const val GYRO_TAU_MS = SpatialGlCore.GYRO_TAU_MS

    /** 见 [SpatialGlCore.MOVE_EPS]（0.12 ≈ 7°/s，比陀螺静止噪声高 2~3 倍）。 */
    private const val MOVE_EPS = SpatialGlCore.MOVE_EPS

    /** 见 [SpatialGlCore.STOP_HOLD_MS]。 */
    private const val STOP_HOLD_MS = SpatialGlCore.STOP_HOLD_MS

    /** 见 [SpatialGlCore.HOME_DURATION_MS]（750ms，ease-out quartic）。 */
    private const val HOME_DURATION_MS = SpatialGlCore.HOME_DURATION_MS

    /**
     * **球面映射**（2026-10-23 用户指出「iPhone 那种更像球面旋转」）。
     *
     * 原来的做法是每层做「屏幕空间等量平移」，中心和边缘同速 ⇒ 观感是"整张图在平移"，平的。
     * 球面模型是：图像贴在球面上、相机在球心转，屏幕角 α 的点转过 θ 后落在 α+θ，
     * 投影回屏幕 = tan(α+θ) ⇒ **中心位移小、边缘位移大**（非线性），这才是"转动感"。
     *
     * 实现：顶点只铺满屏幕，UV 在 fragment 里逐像素算：
     * ```
     *   屏幕 NDC → 角度  atan(ndc · k)
     *   减去该层的转动角 uShift
     *   角度 → 纹理 UV  (q / span) * 0.5 + 0.5
     * ```
     */
    private const val VERT = SpatialGlCore.VERT

    /** 片元着色器（球面 uv + 逐像素位移 + AOD 压暗）见 [SpatialGlCore.FRAG]。 */
    private const val FRAG = SpatialGlCore.FRAG

    // ─────────────────── 密码页连续模糊（2026-10-29 定案：**不自绘**）───────────────────
    //
    // 这里曾经有一整条自绘后处理链（fullFbo → 1/2 降采样 → 三轮 9-tap 高斯 σ=25 → 压暗，
    // 约 120 行）。2026-10-29 用户实测推翻了两条前提，**整条链已删**：
    //
    // 1. 判据不是「全局档 + 同一张壁纸」，而是**系统景深开关**：
    //    · 景深开 ⇒ 系统走 `setWallpaperSyncBlur`（`ViewRootImpl#setWallpaperBlur`，**窗口级**，
    //      糊"窗口背后"的一切 ⇒ 我们挂在壁纸 leash 上的两层也跟着糊）
    //      ＋ `applyDepthMiBlurEffect`（前景层 self blur，含我们插进去的 AGSL 主体）；
    //    · 景深关 ⇒ 改走 `updateKeyguardWallpaperRatio`（miwallpaper 只糊它自己那个 BBQ 子层
    //      ⇒ 我们完全没被糊）。
    //    `setWallpaperBlur` 作用在窗口上，**与 leash 有几条无关** —— 旧判据站不住。
    // 2. 景深关时也不用自己画：那两个函数都是 `public static`，替系统调一次即可，
    //    口径与系统逐字一致（见 [applySystemBlur]）。
    //
    // 如果以后又想自绘：SF 侧 `setSelfBlurRadius` / `setEffect` 在 HyperOS 上对我们无效的
    // 三条实证仍在《999》§6.11，别再重试。

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 当前正在渲染的缓存键；null = 没在跑。volatile：主线程读、GL 线程写。 */
    @Volatile
    private var runningKey: String? = null

    /** 当前挂载所用的 scope（-1 = 未运行）。和 key 一样，变了就要重建。 */
    @Volatile
    private var runningScope: Int = -1

    /**
     * 本次运行是否启用「**空间壁纸锁屏景深**」（`SpatialFlag.PROP_LOCKDEPTH`）。
     *
     * **只在 [startInternal] 开头读一次**：用户定的是"与生效范围同语义 —— 生效中改不动渲染，
     * 下一次替换才生效"。所以整个生命周期内它是个常量，任何按它分支的代码都不需要考虑热切换。
     *
     * 关着（默认）⇒ 锁屏份只有 [KIND_BG] 一个挂载目标、画全部层 ⇒ **与加这个开关之前完全一致**。
     */
    @Volatile
    private var runningLockDepth = false

    /**
     * 一个**挂载目标** = 一条壁纸 leash 上的一份 SC + 它的 EGL 画布。
     *
     * ## `getWallpaperByShowWhenLocked(displayId, showWhenLocked)` 的探针结论（不必重验）
     *
     * 这是 MIUI 私有 API（AOSP 的 `WallpaperController` 里锁屏 / 桌面本来就是两个
     * `WallpaperWindowToken`：`hasTopShowWhenLockedWallpaper` / `hasTopHideWhenLockedWallpaper`）。
     * 2026-10-28 真机验证：
     *
     * * `(0, true)` 拿到的 token 非空、`getLeash()` 正常、`reparent` 成功；
     * * 挂上后**锁屏可见、桌面不可见**，与 `(0, false)` **完全互补**；
     * * 锁屏的时钟 / 通知 / 按钮全部不受影响（它们在 SystemUI 窗口内，比壁纸 leash 高）；
     * * 景深主体仍在（同理在窗口内）⇒ 由 [SpatialForegroundHider] 撤掉。
     *
     * 全局档有两个（锁屏 + 桌面），**共享同一个 [context]** ⇒ 纹理只存一份，
     * 内存代价只是 SurfaceFlinger 多一组 buffer（1022×2268×4×3 ≈ 27MB）；
     * 渲染代价是同一帧画两遍（姿态变化时才画，静止零开销）。
     *
     * 为什么必须两份都真画：解锁时锁屏那份淡出到 0、桌面那份就在下面。
     * 如果桌面那份是"上次留下的旧帧"，淡出一结束就会看出内容差异 = 跳一下。
     */
    private class Mount(
        /** true = 挂锁屏 leash（`showWhenLocked=true`）。**淡出只作用在这一份上**。 */
        val locked: Boolean,
        /**
         * 这一份画什么：`KIND_BG` = 只画 layer0（不透明底）；`KIND_SUBJECT` = 只画 layer1（透明底）。
         *
         * 2026-10-29 引入（锁屏景深 Step 3）：把"背景 + 主体"从一个 buffer 拆成两个 SC，
         * 才能让主体单独挂到比时钟更高的父层。**开关关着时永远只有 KIND_BG**，
         * 且它画的是全部层 ⇒ 与拆分之前逐像素一致。
         */
        val kind: Int,
        val sc: android.view.SurfaceControl,
        val surface: Surface,
        var eglSurface: EGLSurface? = null,
        /** 挂上之后的父层（`ImageWallpaper` 的 leash）；模糊探针要往它身上设。 */
        var leash: android.view.SurfaceControl? = null,
    )

    /** 当前挂载的目标（1 个 = 锁屏/桌面档；2 个 = 全局档）。 */
    private val mounts = ArrayList<Mount>(3)

    /**
     * 挂载目标的种类（2026-10-29，锁屏景深 Step 3）。
     *
     * `KIND_BG` 画**全部**层（= 拆分之前的行为，开关关着时只会有这一种）；
     * `KIND_SUBJECT` 只画 layer1（透明底），仅当「启用空间壁纸锁屏景深」开着且是锁屏份时才建。
     */
    private const val KIND_BG = 0
    private const val KIND_SUBJECT = 1

    /** [drawScene] 的"画哪些层"参数：-1 = 全部（背景份的默认行为）。 */
    private const val SCENE_ALL = -1

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var eglConfig: EGLConfig? = null

    /**
     * **共用 leash**：锁屏与桌面用同一张壁纸时，系统只有一条 WallpaperWindowToken，
     * `(0,false)` 返回 null，我们只能把桌面份也挂到锁屏那条 leash 上。
     *
     * 这种模式下有两件事必须做（2026-10-29 真机实证）：
     *
     * 1. **只保留一份 SC**。两份大小位置完全相同、互相**完全遮挡**，
     *    SurfaceFlinger 不会给被遮住那层消费 buffer ⇒ 它的 `eglSwapBuffers` 阻塞
     *    ⇒ GL 线程停摆 ⇒ 画面永久静止（用户看到的就是「同一张壁纸时才发生」）。
     * 2. **不主动淡出**。这条 leash 解锁后还要继续当桌面壁纸用，
     *    把它淡到 0 就等于把桌面也弄没了；而且系统自己会让它连续过渡 ⇒ 天然无缝。
     */
    @Volatile
    private var sharedLeash = false

    /**
     * **仅桌面档 + 共用 leash**（2026-11-05，问题一）。
     *
     * 同一张壁纸时桌面 token 为 null，仅桌面档又只有桌面一份 mount ——
     * 原来会被"共用 leash 丢弃桌面份"的规则丢掉，`mounted` 为空 ⇒ 渲染器直接失败，
     * 表现为「点应用不生效」。现在保留它并挂到唯一那条 leash 上，
     * 再由 [setDeskOnlyAlpha] 在锁屏时隐掉（否则"仅桌面"会变成"全局"）。
     */
    @Volatile
    private var desktopOnlyShared = false

    /** 仅桌面档在共用 leash 上的当前 alpha（-1 = 还没设过，强制写一次）。 */
    private var deskOnlyAlpha = -1f

    /**
     * **锁屏那一份**的 SC —— 解锁淡出 / 复位只作用于它。
     *
     * 桌面档没有这一份 ⇒ 淡出自动失效，不会误把桌面图一起淡掉（这正是我们要的：
     * 桌面档解锁时桌面图本就在显示，不该动它）。
     *
     * 2026-10-29 拆出主体份后，锁屏可能有两份（背景 + 主体）⇒ 这里返回**整组**，
     * 淡出 / 复位必须一起作用，否则会出现"背景淡走了、主体还留在屏上"。
     */
    private val lockScs: List<android.view.SurfaceControl>
        get() = mounts.filter { it.locked }.map { it.sc }
    private var program = 0
    private var quad: FloatBuffer? = null
    private var aPos = 0
    private var uK = 0
    private var uSpan = 0
    private var uShift = 0
    private var uUvScale = 0
    private var uUvOffset = 0
    private var uTex = 0
    private var uOpaque = 0
    private var uDepth = 0
    private var uUseDepth = 0
    private var uRigidK = 0
    private var uDepthCh = 0
    private var uDkTh = 0
    /** AOD 压暗 uniform（v1.5.80 起压暗回到绘制阶段）。 */
    private var uDimLoc = 0
    private var layerCount = 0
    private var textures = IntArray(0)

    /** 位移场纹理（layers/depth.png）。0 = 没加载到 ⇒ 退化成刚性位移。 */
    private var depthTex = 0
    private var screenW = 0
    private var screenH = 0
    private var layerW = 1
    private var layerH = 1

    /** 层图纹理是否预乘 alpha（BitmapFactory 默认 true）。决定 RGB 的混合因子。 */
    private var isPremultiplied = true

    /** 诊断开关：只画 L0（`setprop debug.hyperextreme.spatial_l0_only 1`）。
     *  用来区分「细线来自素材」还是「来自多层混合」：
     *  L0 单独就有线 ⇒ 素材问题；单独没有、叠上去才有 ⇒ 混合/alpha 问题。 */
    private var l0Only = false

    /** 上一帧真正画出去时的姿态；用来跳过「内容没变」的重绘。 */
    private var hasDrawn = false
    private var lastDrawnYaw = 0f
    private var lastDrawnPitch = 0f

    /** 每层质心（归一化 0..1）—— 层放大已删，暂留作诊断。 */
    private val centers = FloatArray(MAX_LAYERS * 2) { 0.5f }

    /** 每层平均深度（0..1，越接近 1 越近）—— 位移已改成逐像素，这里只留作诊断日志。 */
    private val depths = FloatArray(MAX_LAYERS) { 0.5f }

    /** meta.bodyK：**没有位移场时**主体层的兜底刚性系数（等于 K_MIN）。 */
    private var bodyK = 0.35f

    /** 是否正在归位（归位期间锁死输入，见 [startHoming]）。 */
    private var homing = false
    private var homeStartAt = 0L
    private var homeStartYaw = 0f
    private var homeStartPitch = 0f
    /** 陀螺仪连续低于阈值是从什么时候开始的。 */
    private var stopSince = 0L

    /** 当前倾角（弧度），由陀螺仪驱动。 */
    @Volatile
    private var yaw = 0f
    @Volatile
    private var pitch = 0f

    /** 平滑后的角速度（rad/s）—— 先低通再积分，和视差链一致。 */
    private var rateYaw = 0f
    private var ratePitch = 0f

    /** 上次"确实在转"的时刻（毫秒），用于静止判定 → 归位。 */

    private var sensorManager: android.hardware.SensorManager? = null
    private var gravityListener: android.hardware.SensorEventListener? = null

    /** 一帧最多画这么多次/秒（传感器 60Hz 也够用）。 */
    private var lastDrawAt = 0L

    fun isRunning(): Boolean = runningKey != null

    /** 给 [SpatialOverlay] 用：当前图层键（null = 没在跑）。 */
    internal fun currentKey(): String? = runningKey

    /**
     * 「锁屏景深」是否**真的**开着（2026-10-29）。
     *
     * 判据是 [runningLockDepth]（启动时读的属性快照）**而不是**属性本身 ——
     * 语义同「生效范围」：生效中改开关要等下一次替换，这里必须跟着走，
     * 否则会出现"界面刚打开、前景层立刻被接管"但渲染器还没按新参数重建的半新半旧状态。
     */
    internal fun lockDepthOn(): Boolean = runningKey != null && runningLockDepth

    // ── 显示区域（2026-11-04）──
    //
    // 由独立编辑页 `SpatialCropActivity` 写进 `files/spatial/<key>/crop.json`，
    // 这里**启动读一次**换算成 shader 的两个 uniform（uv' = uv * scale + offset）。
    // 与「生效范围 / 最大幅度角度」同语义：生效中改不重载，下一次替换才生效。
    @Volatile private var runningCropScale = 1f
    @Volatile private var runningCropOffX = 0f
    @Volatile private var runningCropOffY = 0f

    /** 选择性融合的深度差阈值 —— AGSL 侧必须与 GL 用同一个值。 */
    internal fun dkThreshold(): Float = DK_TH

    /**
     * 当前「显示区域」的三个 uniform：`[scale, offsetX, offsetY]`。
     *
     * 景深主体层（[SpatialOverlay]）必须用**同一组**值给自己的两路采样做仿射，
     * 否则背景被裁、主体不裁 ⇒ 主体整体错位。
     * 和 [geometry] 一样：同一帧只读一次，两个 uniform 共用。
     */
    internal fun cropUniforms(): FloatArray =
        floatArrayOf(runningCropScale, runningCropOffX, runningCropOffY)

    /** 当前姿态（弧度）：`[yaw, pitch]`。 */
    internal fun attitude(): FloatArray = floatArrayOf(yaw, pitch)

    /**
     * 当前姿态对应的 `uShift`（已按 span 归一化）—— 与 [drawScene] 里那行**同一公式**：
     *
     * ```
     * uShift = (nx · PAN_RAD / spanX, np · PAN_RAD / spanY)
     * ```
     *
     * 主体 View（AGSL）必须用这个值，不能自己算 —— 一处口径不同就是主体与背景错位。
     */
    internal fun currentShift(spanX: Float, spanY: Float): FloatArray {
        val nx = (yaw / FULL_SCALE_RAD).coerceIn(-1f, 1f)
        val np = (pitch / FULL_SCALE_RAD).coerceIn(-1f, 1f)
        return floatArrayOf(nx * PAN_RAD / spanX, np * PAN_RAD / spanY)
    }

    /**
     * 几何参数：`[kX, kY, spanX, spanY, screenW, screenH]`；未启动 / 尺寸未知返回 null。
     *
     * ⚠️ 这里与 [drawScene] 里那段**必须是同一份算法**（2026-10-29）。
     * 主体的 AGSL 版本靠它算球面映射，两边一旦各写一遍就会漂移，主体与背景错位。
     *
     * 2026-10-30（v1.5.82）：算法本体搬到 [SpatialGlCore.geometry] —— 现在
     * **本文件两处 + 系统壁纸服务链**三方共用同一份，物理上不可能再漂。
     */
    internal fun geometry(): FloatArray? {
        val w = screenW
        val h = screenH
        val lw = layerW
        val lh = layerH
        if (w <= 0 || h <= 0 || lw <= 0 || lh <= 0) return null
        val g = SpatialGlCore.geometry(w, h, lw, lh)
        return floatArrayOf(g.kx, g.ky, g.spanX, g.spanY, w.toFloat(), h.toFloat())
    }

    // ───────────────────── 解锁淡出（2026-10-28） ─────────────────────
    //
    // **为什么需要自己补**：系统原生的「锁屏 → 桌面」过渡有四件事，作用对象各不相同 ——
    //
    // | 环节 | 实现 | 作用范围 | 我们 |
    // |---|---|---|---|
    // | 模糊 | `ViewRootImpl.setWallpaperBlur(int)` | 窗口背后的壁纸层**整体** | ✅ 跟着糊 |
    // | 放大 | `setWallpaperScale` → `setMatrix(leash)` | leash 的**所有子层** | ✅ 跟着放 |
    // | zoom | `setWallpaperZoom` | 壁纸窗口 | ✅ |
    // | 淡出 | `hideKeyguardWallpaper` → `updateKeyguardWallpaperStateAnim` → **miwallpaper 跨进程** | **只有它自己那两个 Surface** | ❌ **不跟** |
    //
    // 前两者是「后处理 / 父层变换」，天然辐射到我们；淡出是 miwallpaper 给自己那条 layer 降 alpha，
    // 我们是 leash 的兄弟层，不在它的作用范围里 ⇒ 我们的图一直不透明，直到 leash 不再呈现 = **硬切**。
    //
    // 所以这里按视差 `gateFadeTick` 的同构形态补一个：从「壁纸开始退场」起把 SC 的 alpha 降到 0。
    // 只当**斜率 / 上限**用：真正的结束由 `SpatialUnlockFade.isFadingAway()`（系统的
    // `mMiKeyguardFadingAway` 落回 false）兜底 ⇒ 估长了不会让图停在屏上。
    // 380 = 用户实测「完成时机正确」的那一版；等日志里的"实测 ms"回来再对齐真值。
    private const val FADE_MS = 380L
    private const val FADE_STEP_MS = 16L

    /** 正在跑的淡出（null = 未在淡出）。只在 GL 线程读写。 */
    private var fadeTick: Runnable? = null

    /**
     * **淡出锁存窗口**：alpha 掉到 0 之后的这段时间里，拒绝任何 raise。
     *
     * 为什么需要（2026-10-28 真机第二轮）：复位入口（`notifyKeyguardState` 的
     * false→true、`showWallpaperScreenOnAnim`）在**解锁动画期间**会被 occluded 抖动
     * 触发，概率性。原本想用「miwallpaper 桥过来的 ext < 0.5 就別复位」——
     * **判据本身不成立**：系统在解锁流程里会再调一次
     * `updateKeyguardWallpaperStateAnim(true)`（就是踩过的那个 true），那一瞬
     * 它把 alpha 设回 1 ⇒ ext 回到 1 ⇒ 判据放行 ⇒ 闪回。
     *
     * 所以改用**纯时间锁**：不依赖任何外部信号。淡出完成后的 800ms 覆盖整段解锁
     * 动画（实测 380~460ms），窗口内到达的 raise 一律**挂起**，窗口结束再补 ——
     * 既不会闪回，也不会「快速锁屏时永久黑」。
     */
    private var fadeDoneAt = 0L
    private const val FADE_LATCH_MS = 800L

    /**
     * **过渡期冻结**（2026-10-29 用户定案）。
     *
     * 解锁退场这段（约 280ms）里：
     * * **锁屏份定死在它最后一帧**（不再重绘）；
     * * **桌面份的第一帧在切换那一刻画一次**（[forceDraw]）；
     * * 中间**不响应任何传感器运动**（姿态不积分、不重绘）。
     *
     * 取舍理由：这段时间任何逐帧更新都在和「切换」抢同一帧预算 —— 用户实测
     * 「无缝了，但能感觉到掉帧」。冻结 ⇒ **零额外开销**、且切换前后画面完全一致
     * （桌面第一帧用的就是冻结住的那个姿态）⇒ 绝对无跳。
     * 代价：这 280ms 内画面不跟手，之后立刻恢复（陀螺仪增量积分，
     * 丢掉的这点转动由「静止归位」在 500ms 内自然抹平）。
     *
     * **volatile**：传感器回调在自己的线程上读它。
     */
    @Volatile private var transitionFrozen = false

    /** 冻结开始时刻（0 = 未冻结）。配合 [FROZEN_TIMEOUT_MS] 做超时自愈。 */
    @Volatile private var frozenAt = 0L

    // ───────────────────────── AOD（2026-10-30，v1.5.76） ─────────────────────────
    //
    // 触发点在 [SpatialAod]（hook `doWallpaperBlackAnim`）。这里只负责三件事：
    //   1. 进 AOD 时**先归位再冻结**（AOD 里不跟传感器，但不能停在偏着的姿态上）；
    //   2. 自己补压暗 —— 系统的压暗走壁纸专有通道，不辐射我们的子层；
    //   3. 出 AOD 时复位亮度 / 解冻 / 重注册传感器。
    //
    // 归位用 **handler 节拍**驱动，不用传感器：灭屏时系统会把这个非 wake-up 传感器停掉
    // （《07》§7.13 已实证），那时候靠传感器推进的归位会永远走不到终点。

    /** AOD 期间冻结：不积分、不重绘。 */
    @Volatile private var aodFrozen = false

    /** 当前压暗系数（1 = 原亮度，0 = 全黑）。只在 GL 线程读写。 */
    private var aodDim = 1f

    /** AOD 归位驱动（null = 没有在跑）。只在 GL 线程读写。 */
    private var aodHomeTick: Runnable? = null

    /**
     * **归位 / 压暗动画的代际号**（2026-11-05 v1.17.6）。
     *
     * 为什么需要：两条动画都是 handler tick，`removeCallbacks` 只能摘掉**还没出队**的那条；
     * 只要 tick 已经出队正在跑，取消就落空，它会在结尾把 `aodFrozen` 又写成 true、
     * 把 `aodDim` 又拉回 0.5。真机日志里就是这个顺序：
     *
     * ```
     * 54.459  解锁：压暗复位 1.0          ← fadeOutOnUnlock 把 aodFrozen 设成 false
     * 54.612  AOD 归位完成 → 冻结         ← 归位 tick 后落地，又设回 true ⇒ 传感器冻死
     * ```
     *
     * ⇒ 取消时自增代际，tick 每次进 `run()` 先比对；过期直接退出、**不写任何状态**。
     */
    private var aodGen = 0

    /** 压暗渐变的 tick（null = 没在跑）。只在 GL 线程读写。 */
    private var dimTick: Runnable? = null

    /** 压暗动画的代际号（语义同 [aodGen]）。 */
    private var dimGen = 0

    private const val AOD_ANIM_MS = 300L
    private const val AOD_STEP_MS = 16L

    /**
     * 冻结超时：**灭屏时所有 surface 都不可见，`eglSwapBuffers` 会阻塞**，
     * 于是淡出 runnable 卡在补帧里、解冻那行永远执行不到
     * ⇒ 亮屏后锁屏「保持静止且不恢复」（2026-10-29 真机实测）。
     *
     * 兜底放在**传感器回调**里：它跑在自己的线程，是唯一不受 GL 线程阻塞影响的节拍。
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
        val ctx = HookRuntime.appContext() ?: return true
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)

    /** 淡出锁存窗口内被挡下的 raise（窗口结束后补上）。只在 GL 线程读写。 */
    private var pendingRaise = false

    /** 已为 [pendingRaise] 排过补发（防重复 post）。只在 GL 线程读写。 */
    private var raiseScheduled = false

    /** 解锁退场：启动 alpha 渐隐（**幂等**：重复调用会从 1 重新开始）。 */
    fun fadeOutOnUnlock() {
        val h = handler ?: return
        h.post {
            if (runningKey == null) return@post
            // **AOD 归位 / 压暗动画必须先作废**（2026-11-05 v1.17.6，真机日志实证）：
            // 这两条 tick 是 handler 异步节拍，归位一次要跑几百毫秒到几秒（GL 忙时被拉长）。
            // AOD 直接解锁不经过 `onExitAod()` ⇒ 没人取消它们 ⇒ 归位落地时会把刚复位的
            // `aodFrozen` 又写成 true（**传感器从此不恢复**，直到下次进出 AOD），
            // 压暗 tick 落地则会把亮度再拉回 0.5（偶发压暗回弹）。
            cancelAodHomeLocked()
            cancelDimAnimLocked()
            // **AOD 直接解锁**（不经过锁屏）走的是
            // `startKeyguardExitAnimationByInteractiveOrFullAODUnlockAnimation`，
            // 不会调 `doWallpaperBlackAnim(show=true)` ⇒ `onExitAod()` 不执行 ⇒
            // 压暗与 AOD 冻结都停在原地。必须在这里无条件复位（2026-11-05 真机问题二）。
            val hadDim = aodDim < 0.999f
            if (hadDim) {
                applyDimLocked(1f)
                HookRuntime.log("SPATIAL 解锁：压暗复位 1.0（AOD 直接解锁不经过 onExitAod）")
            }
            aodFrozen = false
            // 幂等：两个触发点（退场动画起点 / 锁屏状态跳变）都会到，先到者生效。
            // 少了这句，第二次触发会把已淡到一半的 alpha 重置回 1（看得见的回弹）。
            if (fadeTick != null) return@post
            cancelFadeLocked()
            // 仅桌面档（没有锁屏份）：不需要淡出。解锁后这份该显示 ⇒ alpha 1.0。
            if (lockScs.isEmpty()) {
                setDeskOnlyAlpha(1f)
                if (screenOn()) forceDraw()
                return@post
            }
            // **灭屏触发的 showing→false 不是解锁**，不能走淡出：
            // 那时所有 surface 都不可见，下面任何一次 swap 都会阻塞并把 GL 线程卡死。
            if (!screenOn()) {
                HookRuntime.log("SPATIAL 解锁淡出 跳过（灭屏触发，非解锁）")
                return@post
            }
            // **前景主体同步退场**（2026-10-30，v1.5.74，问题一）。
            //
            // 必须在 sharedLeash 分支**之前**：共用 leash 只是省掉 GL 锁屏份的 alpha 淡出
            // （系统连续过渡），但前景主体是窗口内的 View，照样会被系统那 200ms 的
            // self-blur ramp 糊到 ⇒ 它任何时候都要退。
            SpatialOverlay.fadeOutOnUnlock()
            // 共用一条 leash ⇒ 不淡出：它解锁后还要当桌面壁纸用，淡到 0 桌面就空了。
            // 系统自己会让这条 leash 连续过渡（桌面复用它）⇒ 本来就不需要切换，天然无缝。
            if (sharedLeash) {
                HookRuntime.log("SPATIAL 解锁淡出 跳过（共用一条 leash，交给系统连续过渡）")
                return@post
            }
            // 冻结整个过渡期（见 transitionFrozen）：不采样、不重绘
            freezeTransition()
            // **把「冻结的这一帧」同时写进两份**（2026-10-29 用户定案的关键一步）。
            //
            // 为什么必须提前写：桌面那份的 buffer 队列里躺的是旧内容，而 SF 是 FIFO
            // 从队头取 ⇒ 切换后先吐旧帧 = 用户看到的「景物换了位置」。
            // 等淡出结束再补（原来的 forceDraw）**已经晚了**：那一刻系统正开始显示桌面层。
            //
            // 现在在淡出开始的同一帧里写两份、内容完全一致 ⇒ 切换时 SF 无论取哪帧都对，
            // 成本只是**多画一次**（不是每 64ms 一次），几乎无感。
            val deskBefore = swapDeskFrames
            freezeFrameForBoth()
            val deskReady = swapDeskFrames > deskBefore
            val started = SystemClock.elapsedRealtime()
            // 见过 `mMiKeyguardFadingAway == true` 才认后面的 false 是"动画结束"，
            // 否则启动瞬间它可能还是初始的 false，会被误判成"立刻结束"。
            var sawFading = false
            var usedExternal = false
            var minExt = 1f
            // **从当前值起**，不是从 1：淡出若被重复触发（两个入口 / 状态抖动），
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
                    // **优先跟随系统真值**（miwallpaper 桥过来的壁纸 alpha）。
                    // 拿到就完全用它 —— 曲线、起止全对齐，不用再猜。
                    val timeBased = (1f - elapsed.toFloat() / FADE_MS).coerceIn(0f, 1f)
                    val ext = readWallpaperAlpha()
                    // 只有系统**真的在降**（<0.999）才跟随 —— 否则说明 miwallpaper 没调 setAlpha
                    // （属性还停在 1），继续按时间驱动，别被"恒 1"卡死。
                    // 跟随期间取 **min**：只降不升，保证不会因为两条曲线错拍而回弹。
                    // 区间保护：只认 **0.02..0.999** 之间的值 = 系统正在做过渡。
                    // 稳定在 1（锁屏正常）或稳定在 0（壁纸未呈现）都不跟 —— 否则会被"恒 0"瞬间拉黑。
                    val following = ext != null && ext > 0.02f && ext < 0.999f
                    // ⚠️ 必须和**自己的历史值**比，不能和时间驱动比：
                    // `min(timeBased, ext)` 会让我们的 alpha 恒 ≤ 系统壁纸的 alpha
                    // ⇒ 我们总是先消失、露出系统壁纸（2026-10-28 真机现象）。
                    // 改成 min(lastA, ext)：严格跟随系统，同时只降不升（防回弹）。
                    // **单调下降**：无论数据来自真值还是时间，都不允许超过上一帧
                    // —— 这条保证「淡出一旦开始就只会变淡」，重复触发也回不去。
                    var a = if (following) minOf(lastA, ext!!) else minOf(lastA, timeBased)
                    if (following) {
                        usedExternal = true
                        if (ext!! < minExt) minExt = ext!!
                    }
                    // **结束真值**：系统退场的标志一落，立刻归 0 —— 不管 FADE_MS 估得准不准。
                    val fading = SpatialUnlockFade.isFadingAway()
                    if (fading == true) sawFading = true
                    val done = sawFading && fading == false
                    if (done) {
                        a = 0f
                        HookRuntime.log(
                            "SPATIAL 解锁淡出 系统动画结束（实测 ${elapsed}ms，FADE_MS=$FADE_MS，" +
                                "真值=${if (usedExternal) "miwallpaper alpha(最低 ${(minExt * 1000).toInt()}‰)" else "时间驱动"}，" +
                                "口径=起点→fadingAway=false，${mountStats()}）",
                        )
                    }
                    lastA = a
                    applyAlphaTo(scs, a)
                    if (a > 0.01f && !done) {
                        h.postDelayed(this, FADE_STEP_MS)
                    } else {
                        fadeTick = null
                        if (a <= 0.01f) {
                            // 淡出落地 ⇒ 开锁存窗口（见 FADE_LATCH_MS）
                            fadeDoneAt = SystemClock.elapsedRealtime()
                            // **桌面份接手**（2026-10-30，v1.5.73）：从这里起"当前显示的是桌面"，
                            // 之后所有 requestDraw 都画桌面组。必须在 forceDraw 之前翻 ——
                            // forceDraw 自己的 activeMountIsLocked() 也要读这个值。
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
     * **进入 AOD**（2026-10-30，v1.5.76）。由 [SpatialAod] 在 `doWallpaperBlackAnim(z=false)` 里调。
     *
     * 三件事，顺序不能换：
     * 1. **先归位**（[startAodHomingLocked]）—— AOD 里不再跟传感器，但不能停在偏着的姿态；
     * 2. **压暗**到 `1 - black`：全屏 AOD 是 0.5（`getDefaultWallpaperBlack()`），
     *    自定义 AOD 是 0（= 全黑）；
     * 3. **归位完成后才冻结**（在归位 runnable 里翻 [aodFrozen]），期间保持重绘。
     *
     * @param black 系统 `doWallpaperBlackAnim` 的第二个参数（0 = 不压，1 = 全黑）
     */
    fun onEnterAod(black: Float) {
        val h = handler ?: return
        h.post {
            if (runningKey == null) return@post
            // 归位期间必须能画，先把冻结解开（重复进 AOD / 连续两次调用也安全）
            aodFrozen = false
            startAodHomingLocked(h)
            animateDimLocked(h, 1f - black.coerceIn(0f, 1f), AOD_ANIM_MS)
        }
    }

    /**
     * **退出 AOD**（回锁屏）。由 [SpatialAod] 在 `doWallpaperBlackAnim(z=true)` 里调。
     *
     * 亮度复位 → 解冻 → 重注册传感器（灭屏时被系统停掉了，不重注册就一直静止）→ 补一帧。
     */
    fun onExitAod() {
        val h = handler ?: return
        h.post {
            if (runningKey == null) return@post
            cancelAodHomeLocked()
            aodFrozen = false
            animateDimLocked(h, 1f, AOD_ANIM_MS)
            HookRuntime.appContext()?.let { reregisterSensor(it) }
            forceDraw()
            HookRuntime.log("SPATIAL AOD 退出：亮度复位 + 解冻")
        }
    }

    /**
     * AOD 归位：用 handler 节拍把姿态抹回 0，**不依赖陀螺仪**（灭屏时它会被系统停掉）。
     *
     * 曲线与传感器里的归位逐字一致（ease-out quartic，750ms），只是驱动源换成了时间。
     */
    private fun startAodHomingLocked(h: Handler) {
        cancelAodHomeLocked()
        // 已经在中位：不用动画，直接冻结
        if (kotlin.math.abs(yaw) < 1e-4f && kotlin.math.abs(pitch) < 1e-4f) {
            yaw = 0f
            pitch = 0f
            aodFrozen = true
            HookRuntime.log("SPATIAL AOD 归位：本来就在中位 ⇒ 直接冻结")
            return
        }
        homing = true
        homeStartAt = SystemClock.elapsedRealtime()
        homeStartYaw = yaw
        homeStartPitch = pitch
        val gen = ++aodGen
        val tick = object : Runnable {
            override fun run() {
                // 已被取消 / 被新一次归位顶掉 ⇒ 立刻退出，**绝不写 aodFrozen**
                if (gen != aodGen) return
                if (runningKey == null) {
                    aodHomeTick = null
                    return
                }
                val t = ((SystemClock.elapsedRealtime() - homeStartAt) / HOME_DURATION_MS)
                    .coerceIn(0f, 1f)
                // ease-out quartic：t=0.25 走 68%，t=0.5 走 94% —— 与传感器里的归位同一曲线
                val u = 1f - t
                val u2 = u * u
                val eased = 1f - u2 * u2
                yaw = homeStartYaw * (1f - eased)
                pitch = homeStartPitch * (1f - eased)
                if (t >= 1f) {
                    yaw = 0f
                    pitch = 0f
                    homing = false
                    requestDraw(force = true)
                    aodFrozen = true
                    aodHomeTick = null
                    HookRuntime.log("SPATIAL AOD 归位完成 → 冻结（不再响应传感器）")
                    return
                }
                requestDraw(force = true)
                h.postDelayed(this, AOD_STEP_MS)
            }
        }
        aodHomeTick = tick
        h.post(tick)
    }

    private fun cancelAodHomeLocked() {
        aodGen++
        aodHomeTick?.let { handler?.removeCallbacks(it) }
        aodHomeTick = null
        // 归位没跑完就取消 ⇒ 别让 `homing` 继续锁死输入；传感器路径会按「静止归位」自己接上。
        homing = false
    }

    /** 作废正在跑的压暗渐变（解锁 / 出 AOD 时调用，避免旧目标值把亮度再拉回去）。 */
    private fun cancelDimAnimLocked() {
        dimGen++
        dimTick?.let { handler?.removeCallbacks(it) }
        dimTick = null
    }

    /** 压暗渐变（handler 节拍，[AOD_STEP_MS] 一步）。 */
    private fun animateDimLocked(h: Handler, target: Float, durationMs: Long) {
        // 同一时刻只允许一条渐变：否则进/出 AOD 挨得近时两条 tick 会互相覆盖，终值不可预期。
        cancelDimAnimLocked()
        val from = aodDim
        if (kotlin.math.abs(target - from) < 0.001f) {
            applyDimLocked(target)
            return
        }
        val gen = dimGen
        val t0 = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                if (gen != dimGen) return
                if (runningKey == null) {
                    dimTick = null
                    return
                }
                val t = ((SystemClock.elapsedRealtime() - t0).toFloat() / durationMs)
                    .coerceIn(0f, 1f)
                applyDimLocked(from + (target - from) * t)
                if (t < 1f) h.postDelayed(this, AOD_STEP_MS) else dimTick = null
            }
        }
        dimTick = tick
        h.post(tick)
    }

    /**
     * 设置 AOD 压暗系数（v1.5.80 定案：**绘制阶段**乘 `uDim`）。
     *
     * ## 为什么不用 `Transaction#setColor`
     *
     * 它是"合成阶段乘色、buffer 不动"，语义上更接近系统的 `setWallPaperAnimProcess`
     * （所以时钟的柔光玻璃采样源保持原亮度）。但 HyperOS 上实测**两版都不能用**：
     * * 2 参（无 dataspace）：`d=0` 能黑，`d=0.5` 反而**发白**（色彩空间错位）；
     * * 3 参（`DATASPACE_SRGB`）：**仍然发白**（v1.5.79 实测）。
     *
     * ⇒ 退回 shader 乘色。语义确定：预乘 RGB × d，alpha 不变（压暗 ≠ 变透明）。
     * **已知代价**：buffer 被改写 ⇒ 时钟柔光玻璃采样到的背景偏暗，与系统行为有差。
     * 用户已接受（"时钟暗点就暗点吧"）。
     *
     * 另外还有 300ms 内逐帧重绘（约 18 帧），之后静止照旧零开销。
     */
    private fun applyDimLocked(dim: Float) {
        val v = dim.coerceIn(0f, 1f)
        if (kotlin.math.abs(v - aodDim) < 1e-4f) return
        aodDim = v
        // 内容变了 ⇒ 必须重绘；`requestDraw` 顺带把 AGSL 主体也 invalidate 一次，
        // 它从 [aodDimValue] 读同一个值，两边亮度永远同步。
        requestDraw(force = true)
    }

    /** 给 [SpatialOverlay] 的 AGSL 用：与 GL **同一个**压暗值。 */
    internal fun aodDimValue(): Float = aodDim

    /**
     * 回锁屏 / 重新亮屏：把 alpha 复位到 1。
     *
     * **调用方是 [SpatialForegroundHider]**（「系统把 `keyguard_foreground_layer` 设成
     * VISIBLE」= 锁屏正在显示）与 [SpatialUnlockFade]（`notifyKeyguardState` 的
     * false→true / 亮屏兜底）。**不要**挂到 `updateKeyguardWallpaperStateAnim(true)`：
     * 亮屏和解锁后都会传 true，会把刚淡出的 alpha 拉回来（2026-10-28 真机踩过）。
     */
    fun resetAlpha() {
        val h = handler ?: return
        h.post { requestRaiseLocked(h) }
    }

    /**
     * 复位请求（**必须在 `handler` 线程上**）。三道闸，全部通过才真的 raise。
     *
     * ## 闸 1：淡出进行中 ⇒ **挂起，绝不打断**
     *
     * 这是 2026-10-28 第二轮「概率性闪回」的真凶：老代码一进来就 `cancelFadeLocked()`，
     * 而复位入口在解锁动画期间会被 occluded 抖动触发（概率性）。淡出被砍在**半路**，
     * alpha 冻在 0.6~0.8，既不继续降也不复位 ⇒ 画面上一张半透明的图"闪"回来且停住。
     *
     * ⇒ 现在改成：淡出没跑完就**只登记**（[pendingRaise]），让它跑到底再补。
     *
     * ## 闸 2：淡出锁存窗口（[FADE_LATCH_MS]）
     *
     * 淡出落地后的 800ms 覆盖整段解锁动画（实测 380~460ms），期间一律挂起。
     *
     * ## 闸 3：`ext` 判据 **已删除**（2026-10-29）
     *
     * 曾经用「miwallpaper 桥过来的 ext < 0.5 ⇒ 系统壁纸还藏着 ⇒ 别复位」，实测
     * **ext 恒 ≈2‰**（系统给那条 Surface 的 alpha 从头到尾就是 ~0，跟"是否显示"无关）
     * ⇒ 该闸恒真、永久锁死，回锁屏也永不复位（黑屏）。已删，只留日志参考。
     */
    private fun requestRaiseLocked(h: android.os.Handler) {
        // 闸 1：淡出还在跑 —— 只登记，让它自己跑完
        if (fadeTick != null) {
            pendingRaise = true
            scheduleRaisePump(h, FADE_MS + 32)
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
        // **显示态校正**（2026-10-30，v1.5.73）：优先用系统真实锁屏态，
        // 不能无条件设成"锁屏" —— `resetAlpha()` 还有一个"亮屏兜底"入口
        // （`showWallpaperScreenOnAnim(true)`），它在**桌面态**也可能被调到；
        // 无条件切回锁屏会让 activeMountIsLocked() 立刻去写不可见的锁屏 leash（会阻塞）。
        val lockedNow = queryKeyguardLocked(null)
        showingDesktop = if (lockedNow != null) !lockedNow else false
        // **前景主体复位**（2026-10-30，v1.5.74）：必须无条件调 —— GL 锁屏份的 alpha
        // 可能本来就是 1（`currentAlpha < 0.99f` 不成立），而主体已被上一次解锁淡到 0。
        SpatialOverlay.resetAlpha()
        // 仅桌面档：回锁屏要把挂在共用 leash 上的桌面份隐掉。
        setDeskOnlyAlpha(if (showingDesktop) 1f else 0f)
        val scs = lockScs
        if (scs.isEmpty()) return
        if (currentAlpha < 0.99f) {
            val ext = readWallpaperAlpha()
            HookRuntime.log(
                "SPATIAL 复位 alpha 放行（距淡出 ${sinceFade.coerceAtMost(99999)}ms，" +
                    "ext=${ext?.let { (it * 1000).toInt() } ?: -1}‰）",
            )
            applyAlphaTo(scs, 1f)
            // 复位只写了 alpha，内容还是淡出前那一帧 ⇒ 必须重画（否则锁屏静止时永远是旧图）
            forceDraw()
        }
    }

    /** 排一次补发（**防重复 post**：同一个 pending 只排一次）。 */
    private fun scheduleRaisePump(h: android.os.Handler, delayMs: Long) {
        if (raiseScheduled) return
        raiseScheduled = true
        h.postDelayed({ pumpRaiseLocked(h) }, delayMs.coerceAtLeast(16L))
    }

    /** 补发泵：条件还不满足就再排一次，直到 [requestRaiseLocked] 真的放行。 */
    private fun pumpRaiseLocked(h: android.os.Handler) {
        raiseScheduled = false
        if (!pendingRaise) return
        requestRaiseLocked(h)
    }

    private fun cancelFadeLocked() {
        fadeTick?.let { handler?.removeCallbacks(it) }
        fadeTick = null
    }

    /** `Transaction.setAlpha` / `apply` —— 反射缓存（`SurfaceControl.Transaction` 的方法编译期不可见）。 */
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
     * 读 miwallpaper 桥过来的系统壁纸真实 alpha（0..1）。读不到 / 非法 → null。
     *
     * 来源见 [SpatialWallpaperAlpha]：`UniversalWallpaper$UniversalEngine#h(float, SurfaceControl)`
     * 里 `setAlpha` 的真值，经 `debug.hyperextreme.spatial_wp_alpha`（int ×1000）传过来。
     */
    private fun readWallpaperAlpha(): Float? {
        val raw = HookRuntime.systemProperty(SpatialWallpaperAlpha.PROP_ALPHA).trim()
        if (raw.isEmpty()) return null
        val v = raw.toIntOrNull() ?: return null
        if (v !in 0..1000) return null
        return v / 1000f
    }

    /** 当前 SC 的 alpha（只在 GL 线程读写）。用于**幂等**：值没变就不提交 Transaction。 */
    private var currentAlpha = 1f

    /**
     * **当前该画桌面份吗**（2026-10-30，v1.5.73）。
     *
     * ## 为什么必须独立成状态位
     *
     * 原先 [activeMountIsLocked] 用 `currentAlpha > 0.5f` 反推"现在显示哪一份" ——
     * 这个反推在两个地方是错的：
     *
     * 1. **启动瞬间** `currentAlpha` 恒为 1（默认值，还没人设过）⇒ 判成"锁屏在显示"
     *    ⇒ 全局档在**桌面态**启动时只画锁屏组，桌面份的 SC **一次都没 swap 过**
     *    （没有 buffer ⇒ 不显示）。真机现象：点「立即替换」回到桌面看不到我们的层，
     *    要进一次锁屏再回桌面才好 —— 因为解锁那一刻 `freezeFrameForBoth()` 无条件
     *    遍历所有 mounts，桌面份才第一次被写入。
     * 2. **桌面稳态**下 `currentAlpha` 一直是 1（淡出只降一次、之后没人再改）⇒ 判据
     *    永久是"锁屏" ⇒ 每帧都往**不可见的锁屏 leash** 写。不可见 leash 不消费 buffer
     *    ⇒ `eglSwapBuffers` 阻塞（见《07》§7.13.4 ⑪）⇒ GL 线程停摆。
     *
     * 现在只由三个真实事件翻转：启动时读系统锁屏态 / 解锁淡出落地 / 回锁屏复位放行。
     */
    @Volatile private var showingDesktop = false

    /** 写系统属性（反射，不 fork）。失败静默 —— 只是保护机制降级。 */
    private fun setProp(key: String, value: String) {
        runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
        }
    }

    /**
     * 把**一组** SC 的 alpha 一起设成 [alpha]。
     *
     * 2026-10-29：拆分出主体份后锁屏组有两份，必须一起设 —— 只设第一份会出现
     * "背景淡走了、主体还留在屏上"。用一个 Transaction 提交整组，保证原子。
     *
     * 幂等判据仍是整组共用一个 [currentAlpha]（两份永远同步设、同步清）。
     */
    private fun applyAlphaTo(scs: List<android.view.SurfaceControl>, alpha: Float) {
        if (scs.isEmpty()) return
        // 锁存窗口内**任何** raise 都拒绝（不止 resetAlpha 一条路能到这里）。
        // 挡下的请求记 pending，**并自己调度补发** —— 否则万一这次是唯一的 raise，
        // 就永久停在 0（锁屏黑屏）。
        if (alpha > 0.01f && fadeDoneAt > 0L &&
            SystemClock.elapsedRealtime() - fadeDoneAt < FADE_LATCH_MS
        ) {
            pendingRaise = true
            handler?.let { scheduleRaisePump(it, 16L) }
            return
        }
        if (alpha == currentAlpha) return
        runCatching {
            val t = txAlpha ?: return
            val tx = t.first.getConstructor().newInstance()
            scs.forEach { t.second.invoke(tx, it, alpha) }
            t.third.invoke(tx)
            currentAlpha = alpha
        }.onFailure { HookRuntime.log("SPATIAL 设 alpha 失败：${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * **仅桌面档在共用 leash 上的可见性**（2026-11-05，问题一）。
     *
     * 这份层锁屏时也在屏幕上（系统只有一条 leash），所以锁屏 alpha 0、桌面 alpha 1。
     * 不复用 [applyAlphaTo]：那个通道带 `currentAlpha` 与淡出锁存窗口，是给锁屏份用的；
     * 这里是一份"不该在锁屏出现"的桌面层，两态互斥、没有渐变。
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
                "SPATIAL 仅桌面 alpha=${if (t > 0.5f) "1.0（桌面）" else "0.0（锁屏隐藏）"}",
            )
        }.onFailure {
            HookRuntime.log("SPATIAL 仅桌面 alpha 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** **启动（幂等）**：key 与 scope 都没变就直接返回；任一变化都要重建（挂载点会变）。 */
    fun start(ctx: Context?, key: String, scope: Int) {
        if (key == runningKey && scope == runningScope) return
        if (ctx == null) {
            HookRuntime.log("SPATIAL 启动失败：拿不到 Context")
            return
        }
        val app = ctx.applicationContext ?: ctx
        val h = ensureHandler()
        h.post {
            runCatching { startInternal(app, key, scope) }
                .onFailure { HookRuntime.log("SPATIAL 启动异常：${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** 停止并拆掉所有层（幂等）。 */
    fun stop() {
        val h = handler ?: return
        h.post { runCatching { stopInternal("主动停止") } }
    }

    private fun ensureHandler(): Handler {
        handler?.let { return it }
        val t = HandlerThread("he-spatial-gl").apply { start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    // ───────────────────────── GL 线程内 ─────────────────────────

    private fun startInternal(ctx: Context, key: String, scope: Int) {
        stopInternal("重启")
        val t0 = SystemClock.elapsedRealtime()

        // 「空间壁纸锁屏景深」开关：**整条生命周期只读这一次**（语义同 scope —— 生效中改
        // 不动渲染，下一次替换才随属性一起下发）。关着时下面的挂载目标与拆分之前完全相同。
        runningLockDepth = HookRuntime.systemProperty(
            com.frederic22.hyperextreme.data.SpatialFlag.PROP_LOCKDEPTH,
        ).trim() == "1"
        HookRuntime.log(
            "SPATIAL 锁屏景深=${if (runningLockDepth) 1 else 0}" +
                "（${com.frederic22.hyperextreme.data.SpatialFlag.PROP_LOCKDEPTH}）",
        )

        // 「显示区域」从**系统属性**读（`SpatialFlag.PROP_CROP` = "scale,offx,offy"）。
        //
        // ⚠️ **不能读 `crop.json`**：本类跑在 SystemUI 进程，`ctx.filesDir` 是 SystemUI
        // 自己的私有目录，读不到模块的文件 —— 2026-11-05 真机实测连四次启动都拿到
        // 默认值（zoom=1.00 c=(0.500,0.500)），而这里写的日志当时也是这么打的。
        // 属性所有进程都能读，并且与 active / key / scope 同一批推送，天然是同一次配置。
        val cropVals = com.frederic22.hyperextreme.spatial.SpatialCrop.decodeProp(
            HookRuntime.systemProperty(com.frederic22.hyperextreme.data.SpatialFlag.PROP_CROP),
        )
        runningCropScale = cropVals?.get(0) ?: 1f
        runningCropOffX = cropVals?.get(1) ?: 0f
        runningCropOffY = cropVals?.get(2) ?: 0f
        HookRuntime.log(
            "SPATIAL 显示区域：scale=${"%.4f".format(runningCropScale)} " +
                "offset=(${"%.4f".format(runningCropOffX)}, ${"%.4f".format(runningCropOffY)})" +
                if (cropVals == null) "（属性缺失，按不裁剪处理）" else "",
        )

        val dm = android.content.res.Resources.getSystem().displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // 一、EGL 基础 + 每个挂载目标一份 SC / Surface / 画布
        //
        // **display 与 context 各只有一份**：全局档两条 leash 共享它 ⇒ 纹理、shader
        // 全部只存一份（SurfaceControl 只是把同一份内容呈现到不同父层）。
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
        check(EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig 失败"
        }
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )

        // 三档 → 要建几份：
        //   锁屏(0)  = 只挂 (0,true)
        //   桌面(1)  = 只挂 (0,false)（2026-10-28 之前的唯一行为）
        //   全局(2)  = 两条都挂，**同一帧各画一遍** ⇒ 解锁时硬切落点是我们自己的桌面层
        val wantLock = scope != com.frederic22.hyperextreme.data.MaskPrefs.SPATIAL_SCOPE_DESKTOP
        val wantDesk = scope != com.frederic22.hyperextreme.data.MaskPrefs.SPATIAL_SCOPE_LOCK

        /** 建一份挂载目标（SC + Surface + EGL 画布）。 */
        fun buildMount(locked: Boolean, kind: Int): Mount {
            val name = when {
                kind == KIND_SUBJECT -> "he-spatial-lock-subject"
                locked -> "he-spatial-lock"
                else -> "he-spatial-desk"
            }
            val b = android.view.SurfaceControl.Builder()
                .setName(name)
                .setBufferSize(screenW, screenH)
            // 主体份是**透明底**：必须显式声明非不透明，否则 SF 按不透明层处理、
            // 忽略 buffer 的 alpha ⇒ 整块黑底盖住背景（Step 3 的"一格不差"会当场破功）。
            if (kind == KIND_SUBJECT) b.setOpaque(false)
            val mc = b.build()
            val ms = Surface(mc)
            val mes = EGL14.eglCreateWindowSurface(
                display, eglConfig, ms, intArrayOf(EGL14.EGL_NONE), 0,
            )
            return Mount(locked, kind, mc, ms, mes)
        }
        for (locked in booleanArrayOf(true, false)) {
            if (locked && !wantLock) continue
            if (!locked && !wantDesk) continue
            // 每个档位先建「背景份」（画全部层）；开关开着时锁屏档**再建一份主体**（只画 layer1）。
            // 桌面档永远只有背景份 —— 桌面没有时钟，多一份没有任何收益。
            mounts.add(buildMount(locked, KIND_BG))
            if (locked && runningLockDepth) mounts.add(buildMount(locked, KIND_SUBJECT))
        }
        check(mounts.isNotEmpty()) { "没有可挂载的目标（scope=$scope）" }
        check(EGL14.eglMakeCurrent(display, mounts[0].eglSurface, mounts[0].eglSurface, context)) {
            "eglMakeCurrent 失败"
        }
        HookRuntime.log(
            "SPATIAL EGL 就绪：scope=$scope 挂载目标 ${mounts.size} 个" +
                "（${mounts.joinToString { "${if (it.locked) "锁屏" else "桌面"}/${if (it.kind == KIND_SUBJECT) "主体" else "背景"}" }}）",
        )

        program = buildProgram(VERT, FRAG)
        check(program != 0) { "shader 编译失败" }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uK = GLES20.glGetUniformLocation(program, "uK")
        uSpan = GLES20.glGetUniformLocation(program, "uSpan")
        uShift = GLES20.glGetUniformLocation(program, "uShift")
        uUvScale = GLES20.glGetUniformLocation(program, "uUvScale")
        uUvOffset = GLES20.glGetUniformLocation(program, "uUvOffset")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uOpaque = GLES20.glGetUniformLocation(program, "uOpaque")
        uDepth = GLES20.glGetUniformLocation(program, "uDepth")
        uUseDepth = GLES20.glGetUniformLocation(program, "uUseDepth")
        uRigidK = GLES20.glGetUniformLocation(program, "uRigidK")
        uDepthCh = GLES20.glGetUniformLocation(program, "uDepthCh")
        uDkTh = GLES20.glGetUniformLocation(program, "uDkTh")
        uDimLoc = GLES20.glGetUniformLocation(program, "uDim")

        // 位置 + UV：Bitmap 第 0 行在顶部，而 GL 的 (0,0) 在左下 ⇒ UV 的 y 要反过来
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        quad = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }

        // 二、加载 8 张层图 + 读质心
        readMeta(ctx, key)
        var loaded = 0
        for (i in 0 until layerCount) {
            val bmp = loadLayer(ctx, key, i)
            if (bmp == null) {
                HookRuntime.log("SPATIAL 层 $i 读取失败（key=$key）")
                continue
            }
            if (i == 0) {
                layerW = bmp.width
                layerH = bmp.height
            }
            textures[i] = uploadTexture(bmp)
            bmp.recycle()
            loaded++
        }
        check(loaded == layerCount && layerCount > 0) { "层图只加载到 $loaded/$layerCount 张" }

        // 位移场：读不到就退化成"每层刚性位移"（等于旧行为，不至于整幅不动）
        depthTex = loadDepth(ctx, key)
        HookRuntime.log(
            "SPATIAL depth ${if (depthTex != 0) "OK" else "MISSING -> 退化刚性"} layers=$layerCount",
        )

        // 三、每份各画一帧并提交（首帧之后由传感器驱动，且只在姿态真的变了时才重绘）
        //
        // ⚠️ **必须画「所有」目标**，不能再用 drawAllFrames()（2026-10-30，v1.5.73）。
        // 那个函数会按「当前显示哪一份」筛组，而启动瞬间 `currentAlpha` 恒为 1 ⇒ 会被判成
        // "锁屏在显示" ⇒ 全局档在**桌面态**启动时桌面份一次都没 swap 过（没有 buffer 就不显示）
        // ⇒ 真机现象：「点立即替换回到桌面看不到我们的层，进一次锁屏再回来才有」。
        // 此时 SC 还没 reparent（无父层、buffer 队列空），每份 swap 一次不会阻塞。
        showingDesktop = readShowingDesktop(ctx)
        sharedLeash = false
        desktopOnlyShared = false
        deskOnlyAlpha = -1f
        check(drawEveryMount()) { "swapBuffers 失败" }
        HookRuntime.log(
            "SPATIAL 首帧：所有挂载目标各写一帧（当前视为${if (showingDesktop) "桌面" else "锁屏"}态）",
        )
        hasDrawn = true
        lastDrawnYaw = yaw
        lastDrawnPitch = pitch

        // 四、挂到壁纸 leash（每个目标挂自己那条；探针结论见本文件顶部 `Mount` 的 KDoc）
        //
        // **逐条容错**（2026-10-29）：单条挂不上不许拖垮整体。
        // 起因是真机实测「锁屏与桌面用同一张壁纸」时 `(0,false)` 返回 **null**
        // —— 那种组合下系统只建一条 WallpaperWindowToken（锁屏那条），桌面复用它。
        // 原来的写法直接 `error(...)`，而 `apply()` 在循环之后 ⇒ **整批回滚**，
        // 表现为「全局/桌面档完全不生效（连锁屏都没有）」。
        val wmgClass = Class.forName("android.view.WindowManagerGlobal", false, javaClass.classLoader)
        val inst = wmgClass.getMethod("getInstance").invoke(null)
        val gw = wmgClass.getMethod(
            "getWallpaperByShowWhenLocked",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        val txClass = Class.forName("android.view.SurfaceControl\$Transaction", false, javaClass.classLoader)
        val tx = txClass.getConstructor().newInstance()
        val reparent = txClass.getMethod(
            "reparent", android.view.SurfaceControl::class.java, android.view.SurfaceControl::class.java,
        )
        val show = txClass.getMethod("show", android.view.SurfaceControl::class.java)

        /** 某条 leash（拿不到就 null，**不抛**）。 */
        fun leashOf(locked: Boolean): Any? {
            val token = runCatching { gw.invoke(inst, 0, locked) }.getOrNull() ?: return null
            return runCatching { token.javaClass.getMethod("getLeash").invoke(token) }.getOrNull()
        }

        val leashLock = leashOf(true)
        // 仅桌面档 + 共用 leash：只有桌面一份，**不能丢**（2026-11-05 问题一）。
        val hasLockMount = mounts.any { it.locked }
        var fallbackToLock = false
        val mounted = ArrayList<Mount>(mounts.size)
        for (m in mounts) {
            var leash = leashOf(m.locked)
            if (leash == null && !m.locked && leashLock != null) {
                // 同一张壁纸：桌面没有独立 leash，系统让桌面复用锁屏那条。
                sharedLeash = true
                if (hasLockMount) {
                    // 还有锁屏份：不能再挂一份上去（两份完全重叠，被遮住的那份 swap 会阻塞）。
                    fallbackToLock = true
                    HookRuntime.log(
                        "SPATIAL 共用 leash：桌面 token 不存在 → 只保留锁屏那一份" +
                            "（两份挂同一 leash 会互相遮挡并阻塞 swap）",
                    )
                    continue
                }
                // 仅桌面档：没有第二份可保留 ⇒ 挂到唯一这条 leash 上，锁屏时再隐掉。
                desktopOnlyShared = true
                leash = leashLock
                HookRuntime.log("SPATIAL 共用 leash：仅桌面档 → 保留桌面份并挂到唯一 leash")
            }
            if (leash == null) {
                HookRuntime.log("SPATIAL 挂载跳过：showWhenLocked=${m.locked} 既没有 token 也没有可回退的 leash")
                continue
            }
            val hash = Integer.toHexString(System.identityHashCode(leash))
            runCatching {
                reparent.invoke(tx, m.sc, leash)
                show.invoke(tx, m.sc)
                m.leash = leash as? android.view.SurfaceControl
                mounted.add(m)
                HookRuntime.log("SPATIAL 挂载：scope=$scope showWhenLocked=${m.locked} leash=@$hash")
            }.onFailure {
                HookRuntime.log(
                    "SPATIAL 挂载失败：showWhenLocked=${m.locked} ${it.javaClass.simpleName}: ${it.message}",
                )
            }
        }
        runCatching { txClass.getMethod("apply").invoke(tx) }

        // 没挂上的 mount 直接销毁，并从列表里摘掉（否则渲染/清理会引用未挂载的 SC）
        if (mounted.size != mounts.size) {
            val drop = mounts.filter { it !in mounted }
            HookRuntime.log("SPATIAL 挂载结果：成功 ${mounted.size}/${mounts.size}，丢弃 ${drop.size} 个")
            runCatching {
                val tx2 = txClass.getConstructor().newInstance()
                val remove = txClass.getMethod("remove", android.view.SurfaceControl::class.java)
                for (m in drop) {
                    runCatching { remove.invoke(tx2, m.sc) }
                    runCatching { m.surface.release() }
                    m.eglSurface?.let { es ->
                        display?.let { d -> runCatching { EGL14.eglDestroySurface(d, es) } }
                    }
                    m.eglSurface = null
                }
                txClass.getMethod("apply").invoke(tx2)
            }
            mounts.clear()
            mounts.addAll(mounted)
        }
        check(mounts.isNotEmpty()) { "全部目标挂载失败（scope=$scope）" }
        if (desktopOnlyShared) {
            HookRuntime.log(
                "SPATIAL 仅桌面档：挂到共用 leash 后按锁屏态设 alpha" +
                    "（当前=${if (showingDesktop) "桌面 1.0" else "锁屏 0.0"}）",
            )
            setDeskOnlyAlpha(if (showingDesktop) 1f else 0f, force = true)
        }
        if (fallbackToLock) {
            HookRuntime.log(
                "SPATIAL 提示：当前壁纸组合下锁屏/桌面共用一条 leash ⇒ 只挂一份、" +
                    "不主动淡出（系统连续过渡，天然无缝）",
            )
        }

        l0Only = runCatching {
            val v = Runtime.getRuntime().exec(arrayOf("getprop", "debug.hyperextreme.spatial_l0_only"))
                .inputStream.bufferedReader().use { it.readText() }.trim()
            v == "1" || v == "true"
        }.getOrDefault(false)
        if (l0Only) HookRuntime.log("SPATIAL 诊断模式：只画 L0")

        runningKey = key
        runningScope = scope
        startSensor(ctx)
        watchScreenOn(ctx)
        // 告诉 miwallpaper「我们的图真的在显示了」—— 它据此决定淡出时能否把系统壁纸压 0。
        setProp(com.frederic22.hyperextreme.data.SpatialFlag.PROP_RENDERING, "1")
        HookRuntime.log(
            "SPATIAL 渲染器已启动：key=$key，$layerCount 层，层图 ${layerW}×$layerH，屏幕 ${screenW}×$screenH，"
                +                 "耗时 ${SystemClock.elapsedRealtime() - t0}ms，" +
                "锁屏景深=${if (runningLockDepth) 1 else 0}，" +
                "挂载=${mounts.joinToString { "${if (it.locked) "锁屏" else "桌面"}/${if (it.kind == KIND_SUBJECT) "主体" else "背景"}" }}",
        )
    }

    /**
     * 姿态驱动：**陀螺仪 + 一阶低通 + 静止归位**，参数对齐视差链（`ParallaxBg.onGyro`）。
     *
     * ⚠️ 2026-10-23 踩过两个坑：
     * 1. 一开始用 `TYPE_GRAVITY` —— 绕重力轴转手机时重力方向不变，**yaw 永远是 0**；
     * 2. 后来改成"陀螺仪积分 + 每帧 ×0.98"，手感很硬（帧率相关、没有低通、噪声直接进位移）。
     *
     * 现在的模型与视差完全一致：
     * ```
     * α = dt/(dt+50ms);  rate += (ω-rate)*α;  yaw += rate*dt/满量程
     * ```
     * 静止 200ms 后按 `exp(-dt/250ms)` 缓动回中。
     */
    /** 亮屏广播接收器：灭屏后陀螺仪会被系统停掉，必须在亮屏时**重新注册**。 */
    private var screenReceiver: BroadcastReceiver? = null

    /**
     * **灭亮屏后传感器会失效**（2026-10-29 真机实证）。
     *
     * 日志特征：`tilt` 每 2.3s 照常打印（= 50Hz 回调仍在跑），但 `yaw=0.000 rateY=0.00`
     * —— 回调在空转，陀螺仪读数恒 0。系统在灭屏时停掉了这个非 wake-up 传感器，
     * 亮屏后**不会自动恢复**，必须重新 `registerListener`。
     *
     * 表现就是用户说的「灭亮屏后锁屏一直静止」，而且和壁纸档位无关。
     */
    private fun watchScreenOn(ctx: Context) {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != Intent.ACTION_SCREEN_ON) return
                HookRuntime.log("SPATIAL 亮屏 → 重新注册传感器")
                handler?.post {
                    // **亮屏兜底解冻**（2026-11-05 v1.17.6，与光栅 `RasterRenderer.watchScreen` 对齐）：
                    // 亮屏意味着 AOD 一定结束了 ⇒ 任何残留的冻结 / 归位 / 压暗动画都该作废。
                    // 少了这句，只要某条解锁路径没走到 `onExitAod()`、而 `fadeOutOnUnlock()` 又被
                    // 后落地的归位 tick 覆盖，传感器就会一直冻着，直到下一次进出 AOD。
                    cancelAodHomeLocked()
                    cancelDimAnimLocked()
                    aodFrozen = false
                    unfreezeTransition()
                    reregisterSensor(c ?: ctx)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            screenReceiver = r
        }.onFailure { HookRuntime.log("SPATIAL 注册亮屏广播失败：${it.javaClass.simpleName}") }
    }

    /** 摘掉旧的监听器再走一遍 [startSensor]（内部会重建 listener 并重新注册）。 */
    private fun reregisterSensor(ctx: Context) {
        runCatching { gravityListener?.let { sensorManager?.unregisterListener(it) } }
        gravityListener = null
        sensorManager = null
        startSensor(ctx)
    }

    private fun startSensor(ctx: Context) {
        runCatching {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val gyro = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
            if (gyro == null) {
                HookRuntime.log("SPATIAL 没有陀螺仪，静止显示")
                return
            }
            val listener = object : android.hardware.SensorEventListener {
                private var lastAt = 0L
                private var frames = 0

                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    // 过渡期冻结：不积分、不重绘（见 transitionFrozen）。
                    // `lastAt = 0` 让恢复后的第一帧按默认 dt 走，避免拿一个巨大的 dt 积分。
                    if (transitionFrozen) {
                        // 超时自愈：GL 线程可能卡在 swap 上，这里是唯一能读到时间的外部节拍
                        val since = if (frozenAt > 0L) SystemClock.elapsedRealtime() - frozenAt else 0L
                        if (since > FROZEN_TIMEOUT_MS) {
                            unfreezeTransition()
                            HookRuntime.log("SPATIAL 冻结超时 ${since}ms ⇒ 强制解冻（淡出被 swap 卡住）")
                            // 不 return：这次采样照常处理，立刻恢复跟手
                        } else {
                            lastAt = 0L
                            return
                        }
                    }
                    // AOD 期间彻底静止（2026-10-30，v1.5.76）：归位已经跑完，这里不再积分、
                    // 不再重绘。`lastAt = 0` 让回锁屏后的第一帧按默认 dt 走。
                    if (aodFrozen) {
                        lastAt = 0L
                        return
                    }
                    val now = SystemClock.elapsedRealtime()
                    val dt = if (lastAt == 0L) 20f else (now - lastAt).coerceIn(5L, 200L).toFloat()
                    lastAt = now
                    // 一阶低通：陀螺原始读数噪声大，先平滑再积分（防噪声积分漂移）
                    val alpha = dt / (dt + GYRO_TAU_MS)
                    rateYaw += (event.values[1] - rateYaw) * alpha
                    ratePitch += (event.values[0] - ratePitch) * alpha
                    val moving = kotlin.math.abs(rateYaw) > MOVE_EPS ||
                        kotlin.math.abs(ratePitch) > MOVE_EPS

                    if (homing) {
                        // **归位中（方案 A：锁死输入）**。只有用户「明显又开始转」才中断，
                        // 否则 500ms 内一路回中 —— 手一直倾斜也不会出现 0→顶→回→0 的抖动。
                        if (moving) {
                            homing = false
                            stopSince = 0L
                        } else {
                            val t = ((now - homeStartAt) / HOME_DURATION_MS).coerceIn(0f, 1f)
                            // ease-out quartic：t=0.25 走 68%，t=0.5 走 94% —— 前段更快、尾部更滑
                            val u = 1f - t
                            val u2 = u * u
                            val eased = 1f - u2 * u2
                            yaw = homeStartYaw * (1f - eased)
                            pitch = homeStartPitch * (1f - eased)
                            if (t >= 1f) {
                                yaw = 0f
                                pitch = 0f
                                homing = false
                            }
                            requestDraw()
                            return
                        }
                    }

                    if (moving) {
                        stopSince = 0L
                        // 积分成角度（归一化到 ±满量程）
                        yaw = (yaw - rateYaw * (dt / 1000f)).coerceIn(-FULL_SCALE_RAD, FULL_SCALE_RAD)
                        pitch = (pitch + ratePitch * (dt / 1000f)).coerceIn(-FULL_SCALE_RAD, FULL_SCALE_RAD)
                        // **碰到安全区（满量程 98%）→ 立刻归位**
                        if (kotlin.math.abs(yaw) >= FULL_SCALE_RAD * 0.98f ||
                            kotlin.math.abs(pitch) >= FULL_SCALE_RAD * 0.98f
                        ) {
                            startHoming(now)
                        }
                    } else {
                        if (stopSince == 0L) stopSince = now
                        // **检测到停止（连续 60ms 低于阈值）→ 立刻归位**
                        if (now - stopSince >= STOP_HOLD_MS && (yaw != 0f || pitch != 0f)) {
                            startHoming(now)
                        }
                    }
                    if (++frames % 120 == 0) {
                        HookRuntime.log(
                            "SPATIAL tilt yaw=${"%.3f".format(yaw)} pitch=${"%.3f".format(pitch)}" +
                                " rateY=${"%.2f".format(rateYaw)} homing=$homing",
                        )
                    }
                    requestDraw()
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(
                listener, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME,
            )
            sensorManager = sm
            gravityListener = listener
            HookRuntime.log(
                "SPATIAL gyro OK fullScale=${Math.toDegrees(FULL_SCALE_RAD.toDouble()).toInt()}deg tau=${GYRO_TAU_MS}ms",
            )
        }.onFailure { HookRuntime.log("SPATIAL 传感器注册失败：${it.message}") }
    }

    /**
     * 传感器回调 → 让 GL 线程重绘一帧。
     *
     * ⚠️ **内容没变就不画**（2026-10-23 用户实测「视图帧率低 + 系统动画迟钝」）：
     * 陀螺仪 `SENSOR_DELAY_GAME` 是 50Hz，**静止时也一直在回调**。原来每帧无条件重绘，
     * 等于每秒让系统合成器重组我们的图层 50 次 —— 壁纸自己掉帧，系统动画也跟着迟钝。
     * 只有 yaw/pitch 真的变了才画；静止时完全停笔。
     *
     * `DRAW_EPS = 1e-4 rad` 对应屏幕位移约 0.04px，肉眼绝对看不出。
     */
    /** 开始归位：记录起点，之后 500ms 内按 ease-out cubic 回到 0。 */
    private fun startHoming(now: Long) {
        homing = true
        homeStartAt = now
        homeStartYaw = yaw
        homeStartPitch = pitch
    }

    /**
     * 请求重绘。
     *
     * `force=true`：**忽略「姿态没变」和 16ms 节流**。密码页的模糊比例是逐帧变的，
     * 姿态可能一动不动，但画面必须跟着 ratio 更新（而且最后一次变化不能被节流丢掉，
     * 否则会停在旧的模糊程度上）。
     */
    private fun requestDraw(force: Boolean = false) {
        // 过渡期冻结：任何重绘都会和「切换」抢帧预算（见 transitionFrozen）
        if (transitionFrozen) {
            if (frozenAt > 0L && SystemClock.elapsedRealtime() - frozenAt > FROZEN_TIMEOUT_MS) {
                unfreezeTransition()
            } else {
                return
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (hasDrawn &&
                kotlin.math.abs(yaw - lastDrawnYaw) < DRAW_EPS &&
                kotlin.math.abs(pitch - lastDrawnPitch) < DRAW_EPS
            ) {
                return
            }
            if (now - lastDrawAt < 16) return
        }
        lastDrawAt = now
        lastDrawnYaw = yaw
        lastDrawnPitch = pitch
        hasDrawn = true
        // **前景主体跟着同一个节拍重绘**（2026-10-29 Step 5）。
        // 放在死区与节流之后 ⇒ 静止时两边都不动；冻结期上面已经 return ⇒ 两边一起冻。
        runCatching { SpatialOverlay.onAttitudeChanged() }
        handler?.post {
            if (runningKey == null) return@post
            runCatching { drawAllFrames() }
        }
    }

    private fun stopInternal(reason: String) {
        val id = runningKey
        // 传感器先摘（否则回调还会往已销毁的 GL 上 post）
        runCatching { gravityListener?.let { sensorManager?.unregisterListener(it) } }
        runCatching { screenReceiver?.let { HookRuntime.appContext()?.unregisterReceiver(it) } }
        screenReceiver = null
        gravityListener = null
        sensorManager = null
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
        runCatching { if (textures.any { it != 0 }) GLES20.glDeleteTextures(layerCount, textures, 0) }
        runCatching { if (depthTex != 0) GLES20.glDeleteTextures(1, intArrayOf(depthTex), 0) }
        depthTex = 0
        // 后处理资源（FBO / 纹理 / 程序）必须在**上下文还活着**时删 —— 也就是在下面
        // `eglMakeCurrent(NO_SURFACE)` 之前。
        java.util.Arrays.fill(textures, 0)
        runCatching { mounts.forEach { runCatching { it.surface.release() } } }
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
        mounts.clear()
        if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
        cancelFadeLocked()
        fadeDoneAt = 0L
        pendingRaise = false
        raiseScheduled = false
        sharedLeash = false
        desktopOnlyShared = false
        deskOnlyAlpha = -1f
        unfreezeTransition()
        // AOD 状态一起收口：归位 / 压暗两条 runnable 必须摘掉，否则会往已销毁的 GL 上 post。
        cancelAodHomeLocked()
        cancelDimAnimLocked()
        aodFrozen = false
        aodDim = 1f
        currentAlpha = 1f
        // 下次 startInternal 会用系统锁屏态重新初始化；这里只保证"停止后不残留旧的显示态"。
        showingDesktop = false
        display = null; context = null; eglConfig = null; program = 0
        setProp(com.frederic22.hyperextreme.data.SpatialFlag.PROP_RENDERING, "0")
        // 前景主体 View + 被摘掉的系统主体都是这个渲染器的伴生状态 ⇒ 停渲染器必须一起收口
        //（2026-10-29 问题 1：否则「关闭替换」后上层主体层会一直留在锁屏上）。
        // onDisabled 内部幂等，且自己会切主线程。
        runCatching { SpatialOverlay.onDisabled() }
        if (id != null) HookRuntime.log("SPATIAL 渲染器已停止（$reason）")
        runningKey = null
        runningScope = -1
    }

    /**
     * 画一帧。
     *
     * ## 三层变换，由外到内
     *
     * 1. **center-crop**（用户 2026-10-23 指定「不拉伸，居中填满」）：
     *    按 `coverX/coverY` 把图放大到刚好盖满屏幕，溢出的部分裁掉；
     * 2. **安全区** `SAFE=1.25`：整体再放大，留出转动余量（1.25 倍 ≈ 可转 5.9°）；
     * 3. **每层放大遮盖** `1.06 + 0.12 × 深度`，**绕该层自己的质心**（绕画面中心盖不住洞）。
     *
     * 再乘 `P(透视) × RY(yaw) × RX(pitch)` —— 远层 z 更远，转动时位移更小，天然产生景深。
     */
    /** eglMakeCurrent 失败只报一次，别刷屏。 */
    private var eglErrLogged = false

    // ── 诊断（全局档双挂是否真的在更新）──────────────────────────────────────
    // 现象（2026-10-29 用户实测）：解锁切过去显示的是**静止态** ⇒ 桌面那份疑似只有首帧。
    // 两种可能：① `eglSwapBuffers` 对**不可见 leash** 阻塞（SF 不消费 buffer）；
    //          ② makeCurrent 切过去了但驱动没真正切。
    // 用「两份各自成功 swap 的帧数 + 单次耗时」区分。
    private var swapLockFrames = 0
    private var swapDeskFrames = 0
    /** 主体份（`KIND_SUBJECT`）出过的帧数 —— 单独计，否则锁屏帧数会翻倍看不出问题。 */
    private var swapSubjectFrames = 0
    private var swapErrors = 0
    private var slowLogged = 0

    /**
     * 给**每个挂载目标**各画一帧（全局档两份）。
     *
     * 两份**必须同帧同参**：共用一个 context、一套 uniform、同一帧姿态 ⇒ 内容逐像素相同。
     * 这正是「全局档无缝」的全部依据 —— 解锁动画结束时系统从锁屏 leash 切到桌面 leash，
     * 落点是我们自己的另一份图，只要内容一致，切的那一帧看不出任何变化。
     *
     * 反面做法（只画当前可见那份、切换前补一帧）省一半片元，但切换瞬间另一份是旧帧 ⇒ 会跳。
     * 片元开销只在**姿态真变**时发生（`DRAW_EPS` 死区，静止零开销），不值得为它冒跳帧风险。
     */
    /**
     * 启动瞬间「当前是不是桌面态」（2026-10-30，v1.5.73）。
     *
     * 顺序：`KeyguardManager.isKeyguardLocked` → 属性 `debug.hyperextreme.spatial_keyguard` → 保守按锁屏。
     * 属性通道的语义见 [SpatialWallpaperAlpha]：空值（刚开机 / SystemUI 重启）**按锁屏**处理 ——
     * 宁可多判一次锁屏，也不要让桌面份空着（那正是本函数的修复目标）。
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
        val c = ctx ?: HookRuntime.appContext() ?: return null
        return runCatching {
            (c.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
                .isKeyguardLocked
        }.getOrNull()
    }

    /**
     * 现在该画哪一份。
     *
     * ## 为什么不是两份都画（2026-10-29 实测修正）
     *
     * **不可见的 leash 不消费 buffer** ⇒ 对它的 `eglSwapBuffers` 会阻塞。两份都画的话，
     * 只要有一条 leash 不在前台，整个 GL 线程就卡死：用户看到的现象是「解锁跳过去后
     * 画面是静止态，不再跟手」—— 不是内容旧，是**根本不再更新**。
     *
     * ⇒ 只画当下可见的那一份；切换的那一帧由 [forceDraw] 强制补，保证内容是最新的。
     *
     * ## 判据（2026-10-30 改，v1.5.73）
     *
     * 用**独立状态位** [showingDesktop]，不再拿锁屏份的 alpha 反推 ——
     * 那个反推在"启动瞬间"（alpha 恒 1）和"桌面稳态"（淡出后 alpha 恒 0 但判据仍按 1 读）
     * 都会判错，具体症状见 [showingDesktop] 的 KDoc。
     */
    private fun activeMountIsLocked(): Boolean {
        // 只看"有没有桌面份"：拆分后锁屏组可能是 2 份（背景 + 主体），
        // 用 mounts.size 判断会把锁屏档误判成"双份"。
        if (mounts.none { !it.locked }) return true
        // 过渡期整段冻结（不重绘），这里只服务两个稳态。
        return !showingDesktop
    }

    /**
     * **给每一个挂载目标各画一帧**（不做任何"当前该画哪一份"的筛选）。
     *
     * 两个调用点，理由是同一条 —— 要求**所有** SC 的 buffer 里都有内容：
     *
     * * [startInternal] 首帧：否则全局档在桌面态启动时，桌面份可能一次都不画
     *   （见 [showingDesktop] 的第 1 条）；
     * * [freezeFrameForBoth]（解锁那一刻）：两份必须逐像素一致，切换才不会跳。
     *
     * **逐份容错**：某一份 `eglMakeCurrent` 失败不再阻断其余份 —— 原来用 `break`，
     * 排在后面的桌面份会被连带丢掉（2026-10-30，v1.5.73）。
     */
    private fun drawEveryMount(): Boolean {
        var ok = true
        for (m in mounts) {
            if (drawMount(m) < 0L) ok = false
        }
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
            HookRuntime.log("SPATIAL 冻结帧双写耗时 ${cost}ms（桌面份可能被阻塞）")
        }
        return cost
    }


    // ── 问题一探针（2026-10-30，v1.5.73，**只观测、不修复**）──────────────────────
    //
    // 要证明的事：**直接解锁**（不经 bouncer）时，系统对 `keyguardForegroundLayer`
    // 施加了一段 self blur ramp，而我们插在这层里的 AGSL 主体被动跟着糊。
    //
    // 系统侧依据（反编译 `KeyguardPanelViewController`）：
    //   startUnlockKeyguardDismissAnim() L7634-7653
    //     if (!keyguardBouncerShowing && isOnKeyguard() && contentState==1 && mUseWallpaperBlur)
    //        ValueAnimator.ofFloat(keyguardBouncerFraction, 1.0f)   // 200ms
    //        → doBlurAndDim(value, false, 14)   // z3=true ⇒ applyDepthMiBlurEffect(ratio, 前景层)
    //
    // 判读方法：解锁瞬间应看到 ratio 从 0 爬向 1（约 200ms）、fading=1、overlay 仍 att/vis=0；
    // 同时 lockAlpha（GL 锁屏份）在往 0 走 —— 那就是「GL 已淡走、AGSL 主体还在被糊」的窗口。
    // 若 ratio 全程 0 却仍看到模糊，则本假设不成立，要另找来源。
    private var probeAt = 0L
    private var probeRatio = -1f
    private var probeFading: Boolean? = null
    private var probeCount = 0

    private fun probeUnlockBlur(
        ratio: Float,
        depthEffect: Boolean?,
        fgLayer: android.view.View?,
        fading: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (kotlin.math.abs(ratio - probeRatio) < 0.02f && fading == probeFading) return
        if (now - probeAt < 40L) return
        probeAt = now
        probeRatio = ratio
        probeFading = fading
        probeCount++
        // currentAlpha 在 GL 线程写、这里在主线程读 —— 诊断用途，允许读到上一帧的值。
        HookRuntime.log(
            "SPATIAL 模糊探针 #$probeCount t=$now ratio=${"%.3f".format(ratio)}" +
                " depth=${depthEffect?.let { if (it) 1 else 0 } ?: -1}" +
                " fading=${if (fading) 1 else 0}" +
                " fgVis=${fgLayer?.visibility ?: -1}" +
                " lockAlpha=${"%.2f".format(currentAlpha)}" +
                " overlay=[${SpatialOverlay.probeState()}]",
        )
    }

    /**
     * `KeyguardPanelViewController#doBlurAndDim(ratio, z, z2, z3)` 的入口（主线程，proceed 之后调）。
     *
     * 这是系统唯一写 `keyguardBlurRatio` 的地方：上滑进密码 / 图案页时逐帧给，
     * 退出时归 0。我们直接拿它当自己的模糊进度 —— **天然跟手、天然对齐系统的起止**。
     *
     * `probe=3` 时额外打一行四参日志（`z2` 决定系统要不要 `setWallpaperBlur`，
     * `z3` 决定系统主体层要不要 `applyDepthMiBlurEffect`），只用于诊断。
     */
    fun onKeyguardBlurArgs(
        args: List<Any?>,
        depthEffect: Boolean?,
        fgLayer: android.view.View?,
    ) {
        val ratio = (args.getOrNull(0) as? Float) ?: return

        // 解锁退场标志：一次拿到，探针与"要不要补调"共用同一个判断。
        val fadingAway = SpatialUnlockFade.isFadingAway() == true

        // 问题一探针（只观测，不改行为）：记录解锁退场的模糊 ramp 与前景主体的关系。
        runCatching { probeUnlockBlur(ratio, depthEffect, fgLayer, fadingAway) }

        // ── 景深关 ⇒ 替系统补调它自己的两个模糊函数 ───────────────────────────
        //
        // ⚠️ **解锁退场期间不补调**（2026-10-30，v1.5.74，问题一）：
        // 这段 ratio 是系统 `startUnlockKeyguardDismissAnim` 里那条 200ms 的
        // `keyguardBouncerFraction → 1` ramp（解锁 dismiss 模糊），**不是**"密码页连续模糊"。
        // 补调只会把本该退场的主体再糊一遍；主体这时已由 `SpatialOverlay.fadeOutOnUnlock()`
        // 淡到 0，补调纯属浪费，还会让 fgLayer 的 self blur 在解锁后被系统重算时多一次抖动。
        if (!fadingAway && depthEffect == false && fgLayer != null) {
            applySystemBlur(ratio, fgLayer)
        }
        // 模糊完全交给系统：景深开它自己会糊，景深关时上面补调。
        // 这里不再有任何"自己画"的路径。四参诊断日志（原 probe=3）随探针一起删掉了，
        // 需要时在 [applySystemBlur] 的日志里就能看出 ratio 与开关状态。
    }

    /** 补调日志的"上一次状态"（on/off），避免逐帧刷屏。 */
    private var blurLogged = ""

    /**
     * 反射缓存的系统方法（首次调用时按 **SystemUI 的 classLoader** 解析；模块自己的
     * loader 看不到 `com.miui.keyguard.*` 与 `com.android.keyguard.*`）。
     */
    @Volatile private var blurLoader: ClassLoader? = null
    @Volatile private var depthBlurMethod: java.lang.reflect.Method? = null
    @Volatile private var syncBlurMethod: java.lang.reflect.Method? = null

    /**
     * **替系统补调它自己的两个模糊函数**（2026-10-29，验证中）。
     *
     * ⚠️ 2026-11-04：改成 `internal`，**光栅壁纸也走这一份**（`RasterRenderer.onKeyguardBlurArgs`
     * 无条件转发过来）。理由见下：它俩在系统侧是同一个处境 —— 都挂在壁纸 leash 上、
     * 都是 miwallpaper 那两个 Surface 的兄弟层。反射解析缓存也就只需要一份。
     *
     * ## 为什么需要
     *
     * `KeyguardPanelViewController#doBlurAndDim(ratio, z, z2, z3)` 里两条路都由
     * `getDepthEffectEnable()` 控制：
     *
     * * **景深开** ⇒ `setWallpaperSyncBlur(ratio)`（`ViewRootImpl#setWallpaperBlur`，
     *   **窗口级** ⇒ 糊"窗口背后"的全部内容，含我们挂在壁纸 leash 上的背景份与主体份）
     *   ＋ `applyDepthMiBlurEffect(ratio, foregroundLayer)`（这一层的 self blur，
     *   含我们插进去的 AGSL 主体 —— 它是子 View，跟着一起糊）；
     * * **景深关** ⇒ 改走 `updateKeyguardWallpaperRatio(ratio)` —— miwallpaper 只糊
     *   **它自己那个 BBQ 子层**，我们是兄弟层 ⇒ 完全没被糊。
     *
     * 这两个函数都是 `public static`，所以不骗 `getDepthEffectEnable()`（那会连带改掉
     * AOD 动画参数、生物识别判断等 4 处无关行为），只在景深关时替它调一次 ——
     * 口径与系统**逐字一致**，自绘那套（FBO + 三轮高斯 + sigma 25）就可以退役。
     *
     * ⚠️ 只在景深**关**时调。景深开时系统自己会调，重复调用 = 双重模糊。
     */
    internal fun applySystemBlur(ratio: Float, fgLayer: android.view.View) {
        val v = ratio.coerceIn(0f, 1f)
        // 首次（或换了 loader）时解析一次；之后直接用缓存的 Method。
        // ⚠️ **不能用 `fgLayer.javaClass.classLoader`**：那是个 `FrameLayout`，
        // 属于 framework ⇒ classLoader 是 bootstrap，看不到 `com.miui.keyguard.*`。
        // 必须用 SystemUI 自己的 Context classLoader（2026-10-29 实测踩到：
        // 用错 loader 时两个方法都解析成 null，日志是 `selfBlur=false wallpaperSync=false`）。
        val cl = HookRuntime.appContext()?.classLoader ?: fgLayer.javaClass.classLoader
        if (cl != null && cl !== blurLoader) {
            blurLoader = cl
            depthBlurMethod = runCatching {
                Class.forName("com.miui.keyguard.utils.MiuiKeyguardUtils", false, cl).getMethod(
                    "applyDepthMiBlurEffect",
                    Float::class.javaPrimitiveType, android.view.View::class.java,
                )
            }.getOrNull()
            syncBlurMethod = runCatching {
                Class.forName(
                    "com.android.keyguard.panel.KeyguardPanelViewController", false, cl,
                ).getMethod(
                    "setWallpaperSyncBlur",
                    Float::class.javaPrimitiveType, android.view.View::class.java,
                )
            }.getOrNull()
            HookRuntime.log(
                "SPATIAL 模糊 方法解析：selfBlur=${depthBlurMethod != null} " +
                    "wallpaperSync=${syncBlurMethod != null}",
            )
        }

        // 1) 前景层 self blur（我们插进去的主体是这一层的子 View ⇒ 一起糊）
        runCatching {
            depthBlurMethod?.invoke(null, v, fgLayer)
        }.onFailure {
            if (blurLogged != "err-depth") {
                blurLogged = "err-depth"
                HookRuntime.log(
                    "SPATIAL 模糊 补调 applyDepthMiBlurEffect 失败：${it.javaClass.simpleName}: ${it.message}",
                )
            }
        }

        // 2) 窗口级壁纸模糊 ⇒ 糊窗口背后的全部内容（= 我们的背景份 + 主体份）
        runCatching {
            syncBlurMethod?.invoke(null, v, fgLayer)
        }.onFailure {
            if (blurLogged != "err-window") {
                blurLogged = "err-window"
                HookRuntime.log(
                    "SPATIAL 模糊 补调 setWallpaperSyncBlur 失败：${it.javaClass.simpleName}: ${it.message}",
                )
            }
        }

        val tag = if (v > 0.004f) "on" else "off"
        if (blurLogged != tag) {
            blurLogged = tag
            HookRuntime.log("SPATIAL 模糊 景深关 ⇒ 已替系统补调（ratio=${"%.3f".format(v)}，$tag）")
        }
    }


    /** 画指定的一份（返回耗时 ms；失败返回 -1）。 */
    private fun drawMount(m: Mount): Long {
        val d = display ?: return -1
        val c = context ?: return -1
        val es = m.eglSurface ?: return -1
        val t0 = SystemClock.elapsedRealtime()
        if (!EGL14.eglMakeCurrent(d, es, es, c)) {
            swapErrors++
            if (!eglErrLogged) {
                eglErrLogged = true
                HookRuntime.log("SPATIAL eglMakeCurrent 失败（locked=${m.locked}）")
            }
            return -1
        }
        // 画哪些层：主体份只画 layer1；背景份默认只画 layer0，但**如果主体份没挂上**
        // （挂载失败被丢弃 / 开关关着），必须退回"画全部层"，否则主体凭空消失。
        val only = when {
            m.kind == KIND_SUBJECT -> KIND_SUBJECT
            mounts.none { it.locked == m.locked && it.kind == KIND_SUBJECT } -> SCENE_ALL
            else -> KIND_BG
        }
        drawScene(only)
        if (EGL14.eglSwapBuffers(d, es)) {
            // 主体份单独计，不并进锁屏帧 —— 否则拆分后"锁屏帧"直接翻倍，日志看不出异常。
            if (m.kind == KIND_SUBJECT) swapSubjectFrames++
            else if (m.locked) swapLockFrames++ else swapDeskFrames++
        } else {
            swapErrors++
        }
        val cost = SystemClock.elapsedRealtime() - t0
        if (cost > 60L && slowLogged < 8) {
            slowLogged++
            HookRuntime.log(
                "SPATIAL 绘制慢 ${cost}ms（锁屏帧 $swapLockFrames / 桌面帧 $swapDeskFrames / " +
                    "主体帧 $swapSubjectFrames / 失败 $swapErrors）",
            )
        }
        return cost
    }

    /**
     * 画当前该画的那一组。
     *
     * 拆分后锁屏组可能有**两份**（背景 + 主体）⇒ 必须**都画**，且按 `kind` 升序
     * （先背景后主体，与它们的 z 序一致）。只画一份会出现"主体区域是上一帧的旧内容"。
     */
    private fun drawAllFrames(): Boolean {
        val wantLocked = activeMountIsLocked()
        val group = mounts.filter { it.locked == wantLocked }.ifEmpty { mounts }
        if (group.isEmpty()) return false
        for (m in group.sortedBy { it.kind }) {
            if (drawMount(m) < 0L) return false
        }
        return true
    }

    /**
     * **强制补画一帧**（绕过 `DRAW_EPS` 死区）。
     *
     * 用在两个切换点上，因为切换后要画的是「另一份」，而它停在旧的姿态上：
     * * 解锁淡出结束 → 切去画桌面份（锁屏份那一刻 alpha=0，看不见）
     * * 回锁屏复位 → 切回画锁屏份（否则锁屏会一直显示淡出前的最后一帧）
     */
    private fun forceDraw() {
        if (runningKey == null) return
        val locked = activeMountIsLocked()
        val t0 = SystemClock.elapsedRealtime()
        // **同步**执行：调用方（淡出 runnable / 复位）本来就在 handler 线程，
        // 再 post 一次只会多一帧延迟 —— 而这一帧正好是"闪"的窗口。
        // 同步补一帧给前景主体：切换 / 复位后两边的姿态必须一致，否则会出现
        // "背景已经是新姿态、主体还停在旧姿态"的一帧错位。
        runCatching { SpatialOverlay.onAttitudeChanged() }
        runCatching { drawAllFrames() }
        val cost = SystemClock.elapsedRealtime() - t0
        // 诊断：这一次补帧**是不是被 swap 阻塞了**。
        //   耗时小（<15ms）⇒ 渲染本身没问题，跳一定来自几何/系统动画；
        //   耗时长（几十 ms）⇒ 卡在等 SF 消费（说明那份 SC 此刻不可见、队列积压）。
        HookRuntime.log(
            "SPATIAL 切换补帧：画${if (locked) "锁屏" else "桌面"}份 耗时 ${cost}ms" +
                "（锁屏帧 $swapLockFrames / 桌面帧 $swapDeskFrames / 失败 $swapErrors）",
        )
        lastDrawnYaw = yaw
        lastDrawnPitch = pitch
        hasDrawn = true
        lastDrawAt = SystemClock.elapsedRealtime()
    }

    /** 双挂诊断快照（供淡出结束时打印 —— 那时正好能看出桌面那份有没有在更新）。 */
    private fun mountStats(): String =
        "锁屏帧 $swapLockFrames / 桌面帧 $swapDeskFrames / 主体帧 $swapSubjectFrames / 失败 $swapErrors"


    /**
     * 画场景。
     *
     * @param only `SCENE_ALL` = 全部层（背景份，= 拆分之前的唯一行为）；
     *   `KIND_BG` = 只画 layer0（不透明底）；`KIND_SUBJECT` = 只画 layer1（透明底）。
     *   参数取值恰好等于层索引，所以循环里直接 `i != only` 过滤。
     */
    private fun drawScene(only: Int = SCENE_ALL) {
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        // ⚠️ 必须用 glBlendFuncSeparate（2026-10-23 用户实测「细线处能隐约看到桌面壁纸」）：
        //   glBlendFunc 会把**同一个因子同时用于 RGB 和 alpha**，于是 alpha 通道算成
        //   `dst.a = src.a×src.a + dst.a×(1−src.a)` —— 多乘了一次 src.a。
        //   轮廓过渡带里 L1(1−s) 和 L2(s) 都是半透明，三层叠下来 dst.a 掉到 0.6 左右
        //   ⇒ 系统合成时透出 40% 桌面壁纸，就是那条细线。
        //
        // 另外纹理本身**已经是预乘的**（BitmapFactory 默认 inPremultiplied=true，
        // 解码出来的 RGB 已经乘过 alpha），所以 RGB 用 GL_ONE 而不是 GL_SRC_ALPHA，
        // 否则边缘会被乘两次、发暗。
        val rgbSrc = if (isPremultiplied) GLES20.GL_ONE else GLES20.GL_SRC_ALPHA
        GLES20.glBlendFuncSeparate(
            rgbSrc, GLES20.GL_ONE_MINUS_SRC_ALPHA,           // RGB：预乘就直接叠，否则乘 alpha
            GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,    // A：覆盖度累加，永不超过 1
        )
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPos)
        quad?.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1i(uDepth, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // 球面参数：屏幕垂直半角 = SPHERE_HALF_V_DEG（30° ⇒ 边缘/中心位移比 ≈ 1.10，
        // 轻微的球面感，但不至于鱼眼）。图像水平半角 = 垂直半角 × 图像宽高比。
        // 球面参数：屏幕 + 层图尺寸 → uK / uSpan。算法与 AGSL 主体、系统壁纸服务链
        // **同一份**（[SpatialGlCore.geometry]）：cover + 安全区，两向同乘一个倍数
        // ⇒ spanX/spanY 恒等于 imgAspect，不会把图拉长（2026-10-24 修）。
        val g = SpatialGlCore.geometry(screenW, screenH, layerW, layerH)
        GLES20.glUniform2f(uK, g.kx, g.ky)
        GLES20.glUniform2f(uSpan, g.spanX, g.spanY)
        // 显示区域（2026-11-04）：uv' = uv * scale + offset，深度场与颜色共用。
        GLES20.glUniform2f(uUvScale, runningCropScale, runningCropScale)
        GLES20.glUniform2f(uUvOffset, runningCropOffX, runningCropOffY)

        // 归一化姿态 −1..1（相对满量程）
        val nx = (yaw / FULL_SCALE_RAD).coerceIn(-1f, 1f)
        val np = (pitch / FULL_SCALE_RAD).coerceIn(-1f, 1f)

        // 位移场绑到 GL_TEXTURE1（uTex 占 0）。缺深度图时 hasDepth=false ⇒ 全走刚性，
        // 退化成旧行为（不至于整幅不动）。
        val hasDepth = depthTex != 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // 选择性融合的阈值（主体层用；背景层不读这个 uniform）
        GLES20.glUniform1f(uDkTh, DK_TH)

        // AOD 压暗：只乘 RGB（压暗 ≠ 变透明）。GL 线程读 [aodDim]，改值时靠 requestDraw 重绘。
        GLES20.glUniform1f(uDimLoc, aodDim.coerceIn(0f, 1f))

        val drawCount = if (l0Only && only == SCENE_ALL) 1 else layerCount
        for (i in 0 until drawCount) {           // 0 = 背景，先画
            // 拆分后：背景份只画 layer0、主体份只画 layer1（透明底）。合成结果与单 buffer 时一致 ——
            // 因为 layer0 不透明、layer1 走预乘 alpha 叠在它上面，数学上等价于两层各自出一张图再合成。
            if (only != SCENE_ALL && i != only) continue
            // **满量程**位移；k 在片元里算（背景逐像素，主体刚性）。
            // 除以 span 把「弧度」换算到 q0 空间 ⇒ 两向真实角位移相等 ⇒ 各向同性。
            GLES20.glUniform2f(uShift, nx * PAN_RAD / g.spanX, np * PAN_RAD / g.spanY)
            if (hasDepth) {
                // 两层都逐像素：背景取 .r（已背景化），主体取 .a（原始深度）
                GLES20.glUniform1f(uUseDepth, 1f)
                GLES20.glUniform1f(uDepthCh, if (i == 0) 0f else 1f)
                GLES20.glUniform1f(uRigidK, 1f)
            } else {
                // 没有位移场：背景整幅刚性；主体退回 meta.bodyK（比整层跟着走好）
                GLES20.glUniform1f(uUseDepth, 0f)
                GLES20.glUniform1f(uDepthCh, 0f)
                GLES20.glUniform1f(uRigidK, if (i == 0) 1f else bodyK)
            }
            // 第 0 层（背景）强制不透明，保证任何位置都有实底
            GLES20.glUniform1f(uOpaque, if (i == 0) 1f else 0f)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(aPos)
    }

    // ───────────────────────── 4×4 矩阵（column-major，和 GL 一致） ─────────────────────────

    private fun translate(x: Float, y: Float, z: Float) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, y, z, 1f,
    )

    private fun scaleM(x: Float, y: Float, z: Float) = floatArrayOf(
        x, 0f, 0f, 0f,
        0f, y, 0f, 0f,
        0f, 0f, z, 0f,
        0f, 0f, 0f, 1f,
    )

    private fun rotY(a: Float): FloatArray {
        val c = kotlin.math.cos(a)
        val s = kotlin.math.sin(a)
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun rotX(a: Float): FloatArray {
        val c = kotlin.math.cos(a)
        val s = kotlin.math.sin(a)
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, c, s, 0f,
            0f, -s, c, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / kotlin.math.tan(Math.toRadians(fovYDeg.toDouble() / 2.0)).toFloat()
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, 2f * far * near / (near - far), 0f,
        )
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[c * 4 + k]
                r[c * 4 + row] = sum
            }
        }
        return r
    }

    /**
     * 从 `layers/meta.json` 读**层数**与每层质心（归一化）。
     *
     * 层数现在是自适应的（主体 1 + 背景 2/4/6），所以不能写死 —— 这个函数同时负责
     * 设定 [layerCount]、分配 [textures]、填 [centers]。读不到就按上限 8 层试。
     */
    private fun readMeta(ctx: Context, key: String) {
        var count = MAX_LAYERS
        runCatching {
            val uri = Uri.parse("content://$AUTHORITY/spatial/$key/meta.json")
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text == null) {
                HookRuntime.log("SPATIAL meta MISSING -> fallback $MAX_LAYERS")
                return@runCatching
            }
            val json = org.json.JSONObject(text)
            count = json.optInt("layers", MAX_LAYERS).coerceIn(1, MAX_LAYERS)
            bodyK = json.optDouble("bodyK", 0.35).toFloat().coerceIn(0f, 1f)
            val arr = json.optJSONArray("layerInfo") ?: return@runCatching
            for (i in 0 until minOf(count, arr.length())) {
                val o = arr.getJSONObject(i)
                centers[i * 2] = o.optDouble("cx", 0.5).toFloat()
                centers[i * 2 + 1] = o.optDouble("cy", 0.5).toFloat()
                // 深度均值（0..1）—— 只用于日志
                depths[i] = o.optDouble("depthMean", Double.NaN).let {
                    if (it.isNaN()) i / (count - 1).coerceAtLeast(1).toFloat()
                    else it.toFloat()
                }
            }
            // 用 ASCII 打关键字段：中文行在 LSPosed 日志里偶尔 grep 不到，排查时容易误判
            HookRuntime.log(
                "SPATIAL meta OK layers=$count depth=" +
                    (0 until count).joinToString(",") { "%.2f".format(depths[it]) },
            )
        }.onFailure { HookRuntime.log("SPATIAL meta FAIL ${it.javaClass.simpleName}: ${it.message}") }
        layerCount = count
        textures = IntArray(count)
    }

    private fun loadLayer(ctx: Context, key: String, index: Int): Bitmap? = runCatching {
        val uri = Uri.parse("content://$AUTHORITY/spatial/$key/layer$index.png")
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?.also { bmp ->
                if (index == 0) {
                    isPremultiplied = bmp.isPremultiplied
                    HookRuntime.log("SPATIAL 纹理 premultiplied=${bmp.isPremultiplied}")
                }
            }
    }.getOrNull()

    /** 位移场：双通道 GL_LUMINANCE_ALPHA（R→L 背景场，G→A 主体场）。读不到返回 0。 */
    private fun loadDepth(ctx: Context, key: String): Int {
        val bmp = runCatching {
            val uri = Uri.parse("content://$AUTHORITY/spatial/$key/depth.png")
            // 必须显式 ARGB_8888：默认可能解成 RGB_565，G 通道只剩 6 bit
            //（主体场精度会掉到 1/64 ⇒ 位移台阶 1.2px）
            val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        }.getOrNull() ?: return 0
        val tex = runCatching { uploadDepth(bmp) }.getOrDefault(0)
        bmp.recycle()
        return tex
    }

    private fun uploadDepth(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        // L ← R（背景场），A ← G（主体场）。用 GL_LUMINANCE_ALPHA 而非 RGBA：省一半显存。
        val lum = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) {
            val p = argb[i]
            lum.put(((p shr 16) and 0xFF).toByte())
            lum.put(((p shr 8) and 0xFF).toByte())
        }
        lum.position(0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // CLAMP：位移采样可以贴边，越界处的 uv 判定在片元里已经做了
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0,
            GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, lum,
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        return ids[0]
    }

    private fun uploadTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
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
        if (ok[0] == 0) {
            HookRuntime.log("SPATIAL link 失败：${GLES20.glGetProgramInfoLog(p)}")
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
        if (ok[0] == 0) {
            HookRuntime.log("SPATIAL shader 编译失败：${GLES20.glGetShaderInfoLog(s)}")
            return null
        }
        return s
    }
}
