package com.frederic22.hyperextreme.wallpaper

import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.frederic22.hyperextreme.hook.SpatialGlCore
import com.frederic22.hyperextreme.spatial.SpatialDepth
import com.frederic22.hyperextreme.spatial.SpatialLayers
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.tan

/**
 * **系统壁纸服务模式**（2026-10-30，v1.5.82）—— 免 root 的降级方案。
 *
 * ## 与 LSPosed 链的关系
 *
 * 两条链**互斥**，共用同一份层图缓存（`files/spatial/<key>/layers/`）与同一份
 * shader / 几何公式（[SpatialGlCore]）。区别只在"画到哪儿"：
 *
 * | | LSPosed 链（[com.frederic22.hyperextreme.hook.SpatialRenderer]） | 本类 |
 * |---|---|---|
 * | 画布 | 自建 `SurfaceControl`，reparent 到系统壁纸 leash | 系统给的 `Engine` surface |
 * | 生效范围 | 锁屏 / 桌面 / 全局可分别选（挂不同的 leash） | 由系统壁纸设置页决定（桌面/锁屏/同时） |
 * | 锁屏景深 | ✅ 主体压时钟 | ❌ 做不到（需要往 SystemUI 窗口插 View） |
 * | AOD / 解锁 / 模糊 | 自己适配（一堆时序坑） | **白捡**，系统把它当自己的壁纸 |
 * | root | 需要（`setprop` 走 su） | **不需要** |
 *
 * ## 为什么在独立进程
 *
 * `AndroidManifest` 里给了 `android:process=":wallpaper"`：
 * * GL 纹理常驻（约 27MB）不和 Compose UI 抢；
 * * 主进程跑 ONNX 推理时内存峰值很高，同进程会连带把壁纸挤掉。
 *
 * 代价是配置要走文件（[SpatialServiceTarget]），不能用 `SharedPreferences`。
 *
 * ## 生命周期要点（真机血泪）
 *
 * * **不可见时绝对不许 `eglSwapBuffers`** —— 系统不消费 buffer，swap 会阻塞 GL 线程，
 *   醒来后整个渲染卡死。`visible` 是硬闸。
 * * `onSurfaceDestroyed` 必须 `eglMakeCurrent(NO_SURFACE)` + `eglDestroySurface`，
 *   只 detach 不 destroy 会让下次 `eglCreateWindowSurface` 失败。
 * * `context` / 纹理跨 surface 重建保留（系统可能只是转屏/重配，不是真的销毁）。
 * * 传感器只在可见时注册（不可见 = 锁屏/全屏 app/灭屏），省电且天然对齐 AOD。
 */
class SpatialWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SpatialEngine()

    private inner class SpatialEngine : Engine() {

        private val thread = HandlerThread("he-spatial-wp").apply { start() }
        private var gl: Handler? = Handler(thread.looper)

        // ── EGL ──
        private var display: EGLDisplay? = null
        private var context: EGLContext? = null
        private var config: EGLConfig? = null
        private var surface: EGLSurface? = null

        // ── GL ──
        private var program = 0
        private var aPos = 0
        private var uTex = 0
        private var uDepth = 0
        private var uK = 0
        private var uSpan = 0
        private var uShift = 0
        private var uOpaque = 0
        private var uUseDepth = 0
        private var uRigidK = 0
        private var uDepthCh = 0
        private var uDkTh = 0
        private var uDim = 0
        // 显示区域（2026-11-05）：uv' = uv * uUvScale + uUvOffset
        private var uUvScale = 0
        private var uUvOffset = 0
        private var cropScale = 1f
        private var cropOffX = 0f
        private var cropOffY = 0f
        private var loadedCropStamp = Long.MIN_VALUE
        private var quad: FloatBuffer? = null

        // ── 内容 ──
        private var textures = IntArray(0)
        private var depthTex = 0
        private var layerCount = 0
        private var layerW = 0
        private var layerH = 0
        private var bodyK = 0.35f
        private var isPremultiplied = true
        private var loadedKey: String? = null
        private var loadedStamp = 0L

        // ── 画布 ──
        private var screenW = 0
        private var screenH = 0
        private var surfaceReady = false
        private var visible = false
        private var destroyed = false

        // ── 姿态 ──
        private var yaw = 0f
        private var pitch = 0f
        private var rateYaw = 0f
        private var ratePitch = 0f
        private var homing = false
        private var homeStartAt = 0L
        private var homeStartYaw = 0f
        private var homeStartPitch = 0f
        private var lastAt = 0L
        private var stopSince = 0L
        private var lastDrawAt = 0L
        private var lastDrawnYaw = 0f
        private var lastDrawnPitch = 0f
        private var hasDrawn = false
        private var frames = 0

        // ── 传感器 ──
        private var sm: SensorManager? = null
        private var gyroListener: SensorEventListener? = null

        // ─────────────────────────── 生命周期 ───────────────────────────

        override fun onCreate(holder: SurfaceHolder) {
            log("Engine onCreate preview=$isPreview")
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                runCatching { log("wallpaperFlags=${wallpaperFlags}") }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            val h = gl ?: return
            h.post {
                runCatching {
                    ensureEgl(holder)
                    ensureContent(force = false)
                    surfaceReady = true
                    // 首帧：避免系统先给一帧黑
                    if (visible) drawFrame()
                }.onFailure { log("onSurfaceCreated 失败：${it.javaClass.simpleName}: ${it.message}") }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            val h = gl ?: return
            h.post {
                screenW = width
                screenH = height
                log("surface ${width}x${height}")
                runCatching {
                    if (surfaceReady && visible) drawFrame()
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            val h = gl ?: return
            h.post {
                surfaceReady = false
                runCatching {
                    display?.let { d ->
                        EGL14.eglMakeCurrent(
                            d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                        )
                        surface?.let { s ->
                            if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, s)
                        }
                    }
                    surface = null
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            log("可见性 -> $visible")
            if (visible) {
                val h = gl ?: return
                h.post {
                    runCatching {
                        // 回到前台：配置可能变了（用户换了图），先对一次
                        ensureContent(force = false)
                        resetAttitudeIfNeeded()
                        if (surfaceReady) drawFrame()
                    }
                }
                registerSensor()
            } else {
                unregisterSensor()
            }
        }

        override fun onDestroy() {
            log("Engine onDestroy")
            destroyed = true
            visible = false
            unregisterSensor()
            gl?.post { runCatching { releaseGl() } }
            gl = null
            runCatching { thread.quitSafely() }
        }

        // ─────────────────────────── 传感器 ───────────────────────────

        private fun registerSensor() {
            if (gyroListener != null) return
            runCatching {
                val mgr = getSystemService(SENSOR_SERVICE) as SensorManager
                val gyro = mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
                    log("没有陀螺仪，静态显示")
                    return
                }
                val l = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        onGyro(event.values[0], event.values[1])
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                mgr.registerListener(l, gyro, SensorManager.SENSOR_DELAY_GAME)
                sm = mgr
                gyroListener = l
                log("gyro OK")
            }.onFailure { log("传感器注册失败：${it.message}") }
        }

        private fun unregisterSensor() {
            runCatching { gyroListener?.let { sm?.unregisterListener(it) } }
            gyroListener = null
            sm = null
            // 停下来就归位（下次可见时居中，不会带着旧角度醒来）
            lastAt = 0L
        }

        /**
         * 陀螺仪 → 姿态。逻辑与 `SpatialRenderer` 的 listener **同源**：
         * 一阶低通 → 积分 → 死区判停 → 归位（ease-out quartic 750ms）。
         *
         * 与 LSPosed 版的差别只有两点（都是环境带来的，不是逻辑差异）：
         * 不可见时传感器已注销（不需要 `aodFrozen`）；没有过渡期冻结。
         */
        private fun onGyro(gyroX: Float, gyroY: Float) {
            if (!visible || destroyed) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastAt == 0L) 20f else (now - lastAt).coerceIn(5L, 200L).toFloat()
            lastAt = now
            val a = dt / (dt + SpatialGlCore.GYRO_TAU_MS)
            rateYaw += (gyroY - rateYaw) * a
            ratePitch += (gyroX - ratePitch) * a
            val moving = abs(rateYaw) > SpatialGlCore.MOVE_EPS ||
                abs(ratePitch) > SpatialGlCore.MOVE_EPS

            if (homing) {
                if (moving) {
                    homing = false
                    stopSince = 0L
                } else {
                    val t = ((now - homeStartAt) / SpatialGlCore.HOME_DURATION_MS).coerceIn(0f, 1f)
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
                yaw = (yaw - rateYaw * (dt / 1000f))
                    .coerceIn(-SpatialGlCore.FULL_SCALE_RAD, SpatialGlCore.FULL_SCALE_RAD)
                pitch = (pitch + ratePitch * (dt / 1000f))
                    .coerceIn(-SpatialGlCore.FULL_SCALE_RAD, SpatialGlCore.FULL_SCALE_RAD)
                if (abs(yaw) >= SpatialGlCore.FULL_SCALE_RAD * 0.98f ||
                    abs(pitch) >= SpatialGlCore.FULL_SCALE_RAD * 0.98f
                ) {
                    startHoming(now)
                }
            } else {
                if (stopSince == 0L) stopSince = now
                if (now - stopSince >= SpatialGlCore.STOP_HOLD_MS && (yaw != 0f || pitch != 0f)) {
                    startHoming(now)
                }
            }
            if (++frames % 240 == 0) {
                log("tilt yaw=${"%.3f".format(yaw)} pitch=${"%.3f".format(pitch)}")
            }
            requestDraw()
        }

        private fun startHoming(now: Long) {
            homing = true
            homeStartAt = now
            homeStartYaw = yaw
            homeStartPitch = pitch
        }

        /** 不可见期间视角会停在原处；恢复可见时若已经归位过就不动，否则让它自然归位。 */
        private fun resetAttitudeIfNeeded() {
            if (yaw != 0f || pitch != 0f) startHoming(SystemClock.elapsedRealtime())
        }

        // ─────────────────────────── 请求重绘 ───────────────────────────

        private fun requestDraw(force: Boolean = false) {
            if (!visible || !surfaceReady || destroyed) return
            val now = SystemClock.elapsedRealtime()
            if (!force) {
                if (hasDrawn &&
                    abs(yaw - lastDrawnYaw) < SpatialGlCore.DRAW_EPS &&
                    abs(pitch - lastDrawnPitch) < SpatialGlCore.DRAW_EPS
                ) {
                    return
                }
                if (now - lastDrawAt < 16) return
            }
            lastDrawAt = now
            lastDrawnYaw = yaw
            lastDrawnPitch = pitch
            hasDrawn = true
            val h = gl ?: return
            h.post { runCatching { drawFrame() } }
        }

        // ─────────────────────────── EGL ───────────────────────────

        private fun ensureEgl(holder: SurfaceHolder) {
            if (display == null) {
                val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(d != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
                val ver = IntArray(2)
                check(EGL14.eglInitialize(d, ver, 0, ver, 1)) { "eglInitialize 失败" }
                display = d
                val attrs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                )
                val cfgs = arrayOfNulls<EGLConfig>(1)
                val n = IntArray(1)
                check(EGL14.eglChooseConfig(d, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) {
                    "eglChooseConfig 失败"
                }
                config = cfgs[0]
                val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                val c = EGL14.eglCreateContext(d, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
                check(c != null && c != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }
                context = c
                log("EGL 就绪")
            }
            val d = display ?: return
            surface?.let { old ->
                if (old != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(d, old)
                }
            }
            val s = EGL14.eglCreateWindowSurface(
                d, config, holder.surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 失败" }
            surface = s
            check(EGL14.eglMakeCurrent(d, s, s, context)) { "eglMakeCurrent 失败" }
            if (program == 0) buildProgram()
        }

        private fun buildProgram() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, SpatialGlCore.VERT)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, SpatialGlCore.FRAG)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val st = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
            check(st[0] == GLES20.GL_TRUE) { "link 失败：${GLES20.glGetProgramInfoLog(p)}" }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            program = p
            aPos = GLES20.glGetAttribLocation(p, "aPos")
            uTex = GLES20.glGetUniformLocation(p, "uTex")
            uDepth = GLES20.glGetUniformLocation(p, "uDepth")
            uK = GLES20.glGetUniformLocation(p, "uK")
            uSpan = GLES20.glGetUniformLocation(p, "uSpan")
            uShift = GLES20.glGetUniformLocation(p, "uShift")
            uOpaque = GLES20.glGetUniformLocation(p, "uOpaque")
            uUseDepth = GLES20.glGetUniformLocation(p, "uUseDepth")
            uRigidK = GLES20.glGetUniformLocation(p, "uRigidK")
            uDepthCh = GLES20.glGetUniformLocation(p, "uDepthCh")
            uDkTh = GLES20.glGetUniformLocation(p, "uDkTh")
            uDim = GLES20.glGetUniformLocation(p, "uDim")
            uUvScale = GLES20.glGetUniformLocation(p, "uUvScale")
            uUvOffset = GLES20.glGetUniformLocation(p, "uUvOffset")
            val verts = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f,
            )
            quad = ByteBuffer.allocateDirect(verts.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(verts); position(0) }
            log("program 就绪")
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "shader 编译失败：${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }

        private fun releaseGl() {
            runCatching {
                display?.let { d ->
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    surface?.let { if (it != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, it) }
                    context?.let { if (it != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d, it) }
                    EGL14.eglTerminate(d)
                }
            }
            surface = null
            context = null
            display = null
            program = 0
            textures.forEach { runCatching { GLES20.glDeleteTextures(1, intArrayOf(it), 0) } }
            textures = IntArray(0)
            if (depthTex != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(depthTex), 0) }
                depthTex = 0
            }
        }

        // ─────────────────────────── 内容 ───────────────────────────

        /**
         * 读 [SpatialServiceTarget] 的 key 并加载层图。
         *
         * `force=false` 时: 只有「key 变了」或「目标文件被重写过」才重载 —— 用户换图后
         * 系统不会通知壁纸进程，只能靠这个。
         */
        private fun ensureContent(force: Boolean) {
            val ctx = applicationContext
            val key = SpatialServiceTarget.readKey(ctx)
            if (key == null) {
                if (loadedKey != null) {
                    log("目标被清空 → 释放纹理")
                    dropTextures()
                }
                return
            }
            val stamp = SpatialServiceTarget.stamp(ctx)
            // 「显示区域」也可能单独变（用户只调了裁剪）：它的 mtime 一起纳入判据，
            // 否则 target 没重写就永远不重读（2026-11-05）。
            val cropStamp = com.frederic22.hyperextreme.spatial.SpatialCrop
                .fileOf(ctx, key).lastModified()
            if (!force && key == loadedKey && stamp == loadedStamp &&
                cropStamp == loadedCropStamp
            ) {
                return
            }
            // 先更新 crop（**不重载纹理**）：改裁剪只是换两个 uniform，
            // 走下面那条路会白读三张层图（每张上千像素），明显卡一下。
            val cropUpdated = cropStamp != loadedCropStamp
            if (cropUpdated) {
                val crop = com.frederic22.hyperextreme.spatial.SpatialCrop.read(ctx, key)
                cropScale = com.frederic22.hyperextreme.spatial.SpatialCrop.scaleOf(crop)
                cropOffX = com.frederic22.hyperextreme.spatial.SpatialCrop.offsetXOf(crop)
                cropOffY = com.frederic22.hyperextreme.spatial.SpatialCrop.offsetYOf(crop)
                loadedCropStamp = cropStamp
                log("显示区域：zoom=${crop.zoom} c=(${crop.cx}, ${crop.cy})")
            }
            // 只有 key / target 变了才需要重载层图；单纯 crop 变化到此为止
            // （调用方紧接着会 drawFrame，用新 uniform 出帧）。
            if (!force && key == loadedKey && stamp == loadedStamp) return
            val dir = File(SpatialDepth.cacheRoot(ctx), key)
            if (!SpatialLayers.hasLayers(dir)) {
                log("层图不存在：$key")
                loadedKey = key
                loadedStamp = stamp
                return
            }
            loadLayers(dir, key)
            loadedKey = key
            loadedStamp = stamp
        }

        private fun loadLayers(dir: File, key: String) {
            val layersDir = SpatialLayers.layersDir(dir)
            dropTextures()
            runCatching {
                val meta = File(layersDir, "meta.json")
                var count = SpatialGlCore.MAX_LAYERS
                if (meta.isFile) {
                    val j = org.json.JSONObject(meta.readText())
                    count = j.optInt("layers", SpatialGlCore.MAX_LAYERS)
                        .coerceIn(1, SpatialGlCore.MAX_LAYERS)
                    bodyK = j.optDouble("bodyK", 0.35).toFloat().coerceIn(0f, 1f)
                }
                val tex = IntArray(count)
                var loaded = 0
                for (i in 0 until count) {
                    val f = File(layersDir, "layer$i.png")
                    if (!f.isFile) break
                    val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: break
                    if (i == 0) {
                        layerW = bmp.width
                        layerH = bmp.height
                        isPremultiplied = bmp.isPremultiplied
                    }
                    tex[i] = uploadTexture(bmp)
                    bmp.recycle()
                    loaded++
                }
                check(loaded > 0) { "一张层图都没读到" }
                textures = if (loaded == count) tex else tex.copyOf(loaded)
                layerCount = loaded
                val d = File(layersDir, "depth.png")
                depthTex = if (d.isFile) {
                    // 必须显式 ARGB_8888：默认可能解成 RGB_565，G 通道只剩 6 bit
                    //（主体场精度掉到 1/64 ⇒ 位移出现台阶）。
                    val opt = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFile(d.absolutePath, opt)?.let { b ->
                        val t = uploadDepth(b); b.recycle(); t
                    } ?: 0
                } else 0
                log("层图 OK key=$key layers=$loaded ${layerW}x$layerH depth=${depthTex != 0}")
            }.onFailure { log("层图加载失败：${it.javaClass.simpleName}: ${it.message}") }
        }

        private fun dropTextures() {
            textures.forEach { runCatching { GLES20.glDeleteTextures(1, intArrayOf(it), 0) } }
            textures = IntArray(0)
            if (depthTex != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(depthTex), 0) }
                depthTex = 0
            }
            layerCount = 0
        }

        private fun uploadTexture(bmp: android.graphics.Bitmap): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            return t[0]
        }

        /**
         * 位移场上传：**L ← R（背景场）、A ← G（主体场）**，用 `GL_LUMINANCE_ALPHA`（省一半显存）。
         *
         * ⚠️ 两个必须对齐的点（2026-10-30 真机踩过，症状是「只有主体平移，背景不动」）：
         *
         * 1. **第二个字节取 G 通道，不是 A 通道** —— `depth.png` 是 RGBA 图，
         *    R = 背景场、**G = 主体场**、A 恒 255。取错的话主体场恒为 1
         *    ⇒ 主体层退化成整幅刚性平移、背景层按常量场几乎不动。
         * 2. **`GL_UNPACK_ALIGNMENT` 必须临时设成 1** —— 每行 2×w 字节（w=1022 ⇒ 2044）
         *    不是 4 的倍数，默认对齐 4 会让**整场错行**，读出来是乱码。
         */
        private fun uploadDepth(bmp: android.graphics.Bitmap): Int {
            val w = bmp.width
            val h = bmp.height
            val argb = IntArray(w * h)
            bmp.getPixels(argb, 0, w, 0, 0, w, h)
            val lum = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
            for (p in argb) {
                lum.put(((p shr 16) and 0xFF).toByte())   // L ← R = 背景场
                lum.put(((p shr 8) and 0xFF).toByte())    // A ← G = 主体场
            }
            lum.position(0)
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0,
                GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, lum,
            )
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            return t[0]
        }

        // ─────────────────────────── 绘制 ───────────────────────────

        private fun drawFrame() {
            if (!visible || !surfaceReady || destroyed) return
            if (program == 0 || screenW <= 0 || screenH <= 0 || layerCount == 0) return
            val d = display ?: return
            val s = surface ?: return
            if (!EGL14.eglMakeCurrent(d, s, s, context)) return
            drawScene()
            EGL14.eglSwapBuffers(d, s)
        }

        private fun drawScene() {
            GLES20.glViewport(0, 0, screenW, screenH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_BLEND)
            // 预乘纹理 + `glBlendFuncSeparate`：RGB 用 ONE、A 用 ONE/ONE_MINUS_SRC_ALPHA
            //（只为了让主体层的预乘 alpha 正确叠加；背景层本身就是不透明的）。
            val rgbSrc = if (isPremultiplied) GLES20.GL_ONE else GLES20.GL_SRC_ALPHA
            GLES20.glBlendFuncSeparate(
                rgbSrc, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(aPos)
            quad?.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform1i(uDepth, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            val g = SpatialGlCore.geometry(screenW, screenH, layerW, layerH)
            GLES20.glUniform2f(uK, g.kx, g.ky)
            GLES20.glUniform2f(uSpan, g.spanX, g.spanY)
            GLES20.glUniform2f(uUvScale, cropScale, cropScale)
            GLES20.glUniform2f(uUvOffset, cropOffX, cropOffY)

            val nx = (yaw / SpatialGlCore.FULL_SCALE_RAD).coerceIn(-1f, 1f)
            val np = (pitch / SpatialGlCore.FULL_SCALE_RAD).coerceIn(-1f, 1f)

            val hasDepth = depthTex != 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1f(uDkTh, SpatialGlCore.DK_TH)
            // 系统壁纸服务链由**系统**做 AOD 压暗（setWallPaperAnimProcess），这里恒 1.0。
            GLES20.glUniform1f(uDim, 1f)

            for (i in 0 until layerCount) {
                GLES20.glUniform2f(uShift, nx * SpatialGlCore.PAN_RAD / g.spanX, np * SpatialGlCore.PAN_RAD / g.spanY)
                if (hasDepth) {
                    GLES20.glUniform1f(uUseDepth, 1f)
                    GLES20.glUniform1f(uDepthCh, if (i == 0) 0f else 1f)
                    GLES20.glUniform1f(uRigidK, 1f)
                } else {
                    GLES20.glUniform1f(uUseDepth, 0f)
                    GLES20.glUniform1f(uDepthCh, 0f)
                    GLES20.glUniform1f(uRigidK, if (i == 0) 1f else bodyK)
                }
                GLES20.glUniform1f(uOpaque, if (i == 0) 1f else 0f)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun log(msg: String) = Log.i(TAG, msg)
    }

    private companion object {
        const val TAG = "SPATIAL_WP"
    }
}
