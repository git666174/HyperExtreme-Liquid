package com.frederic22.hyperextreme.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLUtils
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面壁纸的视差绘制 —— 「视差效果覆盖桌面」第二步的正解（2026-10-21）。
 *
 * ## 为什么落在这一层
 *
 * 桌面壁纸是 `com.miui.miwallpaper` 的独立 Surface 画出来的，桌面进程（Flutter）只是显示它。
 * 换壁纸（`setBitmap`）会被 live wallpaper 顶掉、且效果退化成整图平移；改桌面 `WallpaperPainter`
 * 属于 Dart inline patch，代价高。**在 miwallpaper 的 GL 帧末尾追加两个 pass** 是唯一
 * 既能保留「背景 / 主体差速」又不碰用户壁纸的路。
 *
 * ## 怎么挂
 *
 * ```java
 * // ImageWallpaperRenderer.onDrawFrame()  —— 每帧唯一绘制入口
 * GLES20.glClearColor(0,0,0,1);
 * GLES20.glClear(GL_COLOR_BUFFER_BIT);
 * setGLViewport();
 * this.mAnimator.onDrawFrame();   // ← 画系统壁纸；swap 在外层 GL 线程
 * ```
 *
 * `onDrawFrame` 只负责画，**swapBuffers 在外层**。所以在 `proceed()` 之后追加绘制，
 * 这一帧就是「系统壁纸 + 我们的背景 + 我们的主体」，原渲染流程一行不改。
 *
 * 只对 `DesktopAnimImageWallpaperRenderer`（桌面）生效，锁屏的
 * `KeyguardAnimImageWallpaperRenderer` 完全不碰。
 *
 * ## 图从哪来
 *
 * 见 [com.frederic22.hyperextreme.data.ParallaxFeed]：root 把背景 / 主体推进 miwallpaper 的
 * files 目录，本类按文件 mtime 自动重载（换图不用重启进程）。
 *
 * ## 参数
 *
 * 第一版直接用与锁屏相同的默认值（`bgScale=1.10 / bgAmp=25px / subjectScale=1.15 / subjectAmp=45px`），
 * 传感器用同一套重力倾角 + 一阶低通（τ=70ms），够验证链路；参数化留给下一步。
 */
internal object WallpaperGlOverlay {

    private const val CLS_RENDERER = "com.miui.miwallpaper.opengl.ImageWallpaperRenderer"
    private const val CLS_DESKTOP_RENDERER =
        "com.miui.miwallpaper.container.openGL.DesktopAnimImageWallpaperRenderer"

    /** 开关的系统属性（与 ParallaxDesktopGuard / ParallaxCoverFlag 一致）。 */
    private const val PROP_FLAG = "debug.hyperextreme.parallax_cover"

    private const val BG_PATH = "/data/data/com.miui.miwallpaper/files/he_parallax_bg.png"
    private const val SUBJECT_PATH = "/data/data/com.miui.miwallpaper/files/he_parallax_subject.png"

    // 与 ParallaxBg 默认值对齐（锁屏那套的默认手感）。
    private const val BG_SCALE = 1.10f
    private const val BG_AMP_PX = 25f
    private const val SUBJECT_SCALE = 1.15f
    private const val SUBJECT_AMP_PX = 45f

    /** 重力满量程（m/s²），与 ParallaxBg.FULL_SCALE_X/Y 一致。 */
    private const val FULL_SCALE = 4.9f

    /** 低通时间常数（ms），与 ParallaxBg.TILT_TIME_CONSTANT_MS 一致。 */
    private const val TILT_TAU_MS = 70f

    private val logged = ConcurrentHashMap<String, Boolean>()

    /**
     * **每个 renderer 实例一个 Painter**。GL 的 program / texture 都是 **per-context** 的：
     * miwallpaper 里 `StreamAnimImageWallpaperRenderer` 会同时造锁屏与桌面两个 renderer，
     * 它们跑在各自的 GL context 上。共用一份纹理 ID 会得到「无效对象」——
     * 症状就是画出一块纯黑（v1.3.26 实测）。
     */
    private val painters = java.util.WeakHashMap<Any, Painter>()
    private val paintersLock = Any()

    private fun painterFor(renderer: Any): Painter = synchronized(paintersLock) {
        painters.getOrPut(renderer) { Painter() }
    }

    private fun log(msg: String) = HookRuntime.log("PGLO $msg")

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val cls = runCatching { Class.forName(CLS_RENDERER, false, loader) }.getOrNull()
        if (cls == null) {
            log("未找到 $CLS_RENDERER")
            return false
        }
        // 桌面渲染器是「只画桌面、不碰锁屏」的唯一判据。找不到就**不装**——
        // 否则基类 hook 会连锁屏壁纸一起画上我们的图。
        val desktopCls = runCatching { Class.forName(CLS_DESKTOP_RENDERER, false, loader) }.getOrNull()
        if (desktopCls == null) {
            log("未找到 $CLS_DESKTOP_RENDERER —— 不装（无法区分桌面/锁屏）")
            return false
        }
        val method = findMethod(cls, "onDrawFrame", 0)
        if (method == null) {
            log("无 onDrawFrame() —— 渲染器结构变了？")
            return false
        }
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // 桌面实例才画：锁屏用的是 KeyguardAnimImageWallpaperRenderer，不能碰。
                    val self = chain.getThisObject()
                    if (!desktopCls.isInstance(self)) return result
                    if (!Painter.enabled()) return result
                    runCatching {
                        painterFor(self).onFrame(self)
                    }.onFailure { err ->
                        if (logged.putIfAbsent("drawFail", true) == null) {
                            log("绘制异常：${err.javaClass.simpleName}: ${err.message}")
                        }
                    }
                    return result
                }
            })
            log("GL 层已挂 onDrawFrame（桌面渲染器=$desktopCls）")
            true
        }.getOrElse {
            log("挂 onDrawFrame 失败：$it")
            false
        }
    }

    private fun findMethod(cls: Class<*>, name: String, arity: Int): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == arity }
                ?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** 一帧的绘制器：GL 资源 + 纹理缓存 + 传感器。全部只在 GL 线程访问。 */
    private class Painter {

        private var program = 0
        private var aPos = -1
        private var aUv = -1
        private var uTex = -1
        private var uAlpha = -1

        private var bgTex = 0
        private var bgW = 0
        private var bgH = 0
        private var bgStamp = -1L

        private var subjTex = 0
        private var subjW = 0
        private var subjH = 0
        private var subjStamp = -1L

        private val buf: FloatBuffer =
            ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private var frame = 0
        private var drawCount = 0
        private var sensorStarted = false

        // ---- 传感器（重力倾角 + 一阶低通，参数对齐锁屏那套） ----
        @Volatile private var tiltX = 0f
        @Volatile private var tiltY = 0f
        private var baseX = Float.NaN
        private var baseY = Float.NaN
        private var lastAt = 0L

        fun onFrame(renderer: Any) {
            ensureSensor(renderer)
            ensureRefreshDriver(renderer)
            ensureGl()

            // 每 ~30 帧（半秒）看一次文件时间戳，换图自动重载。
            if (frame++ % 30 == 0) reloadTextures()

            val w = viewportW()
            val h = viewportH()
            if (w <= 0 || h <= 0) return

            // 保存会被我们改掉的 GL 状态。**必须逐项复原**：这是往系统的渲染循环里插帧，
            // 留一点脏状态就可能把系统壁纸画花（下一帧不一定会重新设置）。
            val prevProgram = intState(GLES20.GL_CURRENT_PROGRAM)
            val prevTex = intState(GLES20.GL_TEXTURE_BINDING_2D)
            val prevVbo = intState(GLES20.GL_ARRAY_BUFFER_BINDING)
            val prevFbo = intState(GLES20.GL_FRAMEBUFFER_BINDING)
            val blendWasOn = GLES20.glIsEnabled(GLES20.GL_BLEND)
            val depthWasOn = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
            val cullWasOn = GLES20.glIsEnabled(GLES20.GL_CULL_FACE)
            val scissorWasOn = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)

            // `mAnimator.onDrawFrame()` 可能把 framebuffer 停在离屏 FBO 上（模糊 / 玻璃效果）。
            // 不回到默认 framebuffer 0，我们画的就跑进那个 FBO，屏幕上什么都不会变。
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            // 系统可能留着 scissor 做局部刷新：不清掉的话我们的绘制会被裁成一块，
            // 裁剩的区域保留旧内容 —— 表现为屏幕上出现一块不刷新/发黑的矩形。
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // 用客户端顶点数组：必须先解绑 VBO，否则 glVertexAttribPointer 会把 buf
            // 当成「当前 VBO 的字节偏移」来解释（系统可能正绑着自己的 VBO）。
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            // 锁屏公式：sx = tiltX；背景取反（默认「主体与背景反向」），主体不取反。
            val sx = tiltX.coerceIn(-1f, 1f)
            val sy = tiltY.coerceIn(-1f, 1f)
            val bx = -sx
            val by = -sy

            // 调试：`debug.hyperextreme.pglo_mode`
            //   0 = 正常；1 = 只画半透明背景（验证顶点是否覆盖全屏）；2 = 只画背景
            val mode = propInt("debug.hyperextreme.pglo_mode", 0)
            val alpha = if (mode == 1) 0.5f else 1f

            if (bgTex != 0) {
                drawTexture(
                    tex = bgTex,
                    imgW = bgW,
                    imgH = bgH,
                    scale = BG_SCALE,
                    tx = bx * BG_AMP_PX,
                    ty = by * BG_AMP_PX,
                    vw = w,
                    vh = h,
                    alpha = alpha,
                )
            }
            if (subjTex != 0 && mode != 1 && mode != 2) {
                drawTexture(
                    tex = subjTex,
                    imgW = subjW,
                    imgH = subjH,
                    scale = SUBJECT_SCALE,
                    tx = sx * SUBJECT_AMP_PX,
                    ty = sy * SUBJECT_AMP_PX,
                    vw = w,
                    vh = h,
                    alpha = 1f,
                )
            }

            // 复原：不给下一帧的系统绘制留脏状态。
            // attrib 槽位**无条件关掉** —— 我们刚把 pointer 指向自己的 FloatBuffer，
            // 若留着 enabled，系统下一帧沿用这个 pointer 会读到我们的顶点数据（画出黑块/乱纹理）。
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, prevVbo)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex)
            GLES20.glUseProgram(prevProgram)
            if (!blendWasOn) GLES20.glDisable(GLES20.GL_BLEND)
            if (depthWasOn) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            if (cullWasOn) GLES20.glEnable(GLES20.GL_CULL_FACE)
            if (scissorWasOn) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo)

            // 帧计数：确认 miwallpaper 是**持续**渲染还是只在变化时渲染 ——
            // 后者意味着传感器驱动不会实时生效，得另外请求刷新。
            drawCount++
            if (drawCount == 1 || drawCount % 120 == 0) {
                val err = GLES20.glGetError()
                log(
                    "绘制第 $drawCount 帧（viewport=${w}x$h，背景=${bgW}x$bgH，" +
                        "主体=${if (subjTex != 0) "${subjW}x$subjH" else "无"}，" +
                        "prevFbo=$prevFbo，glError=0x${Integer.toHexString(err)}）",
                )
            }
        }

        // ---------------------------------------------------------------- GL 初始化

        private fun ensureGl() {
            if (program != 0) return
            val vs = compile(GLES20.GL_VERTEX_SHADER, VS)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FS)
            if (vs == 0 || fs == 0) {
                log("着色器编译失败")
                return
            }
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            if (ok[0] != GLES20.GL_TRUE) {
                log("program 链接失败：${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                program = 0
                return
            }
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
            log("GL program 就绪（aPos=$aPos aUv=$aUv uTex=$uTex uAlpha=$uAlpha）")
        }

        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] != GLES20.GL_TRUE) {
                log("着色器编译失败($type)：${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun intState(cap: Int): Int {
            val out = IntArray(1)
            GLES20.glGetIntegerv(cap, out, 0)
            return out[0]
        }

        // ---------------------------------------------------------------- 纹理

        private fun reloadTextures() {
            bgStamp = loadIfChanged(BG_PATH, bgStamp) { tex, tw, th ->
                bgTex = tex; bgW = tw; bgH = th
            }
            subjStamp = loadIfChanged(SUBJECT_PATH, subjStamp) { tex, tw, th ->
                subjTex = tex; subjW = tw; subjH = th
            }
        }

        /**
         * 文件 mtime 变了才重载。返回新 mtime（文件不存在返回 0）。
         *
         * 纹理上限保护：按 `inSampleSize` 把长边压到 2048 以内，避免大图爆纹理 / OOM。
         */
        private inline fun loadIfChanged(
            path: String,
            oldStamp: Long,
            assign: (tex: Int, w: Int, h: Int) -> Unit,
        ): Long {
            val file = File(path)
            val stamp = if (file.isFile) file.lastModified() else 0L
            if (stamp == oldStamp) return oldStamp
            if (stamp == 0L) {
                // 文件被删了：释放旧纹理。
                if (oldStamp != 0L) assign(0, 0, 0)
                return 0L
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return oldStamp
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2

            val bmp: Bitmap = runCatching {
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            }.getOrNull() ?: run {
                if (logged.putIfAbsent("decode:$path", true) == null) log("解码失败：$path")
                return oldStamp
            }
            val tex = upload(bmp)
            val tw = bmp.width
            val th = bmp.height
            bmp.recycle()
            if (tex == 0) return oldStamp
            assign(tex, tw, th)
            if (logged.putIfAbsent("tex:$path", true) == null) {
                log("纹理已加载：$path（${tw}x$th sample=$sample）")
            }
            return stamp
        }

        private fun upload(bmp: Bitmap): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            if (ids[0] == 0) return 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            return ids[0]
        }

        // ---------------------------------------------------------------- 单次绘制

        /**
         * 画一张铺满屏幕的纹理：先 centerCrop 适配，再绕屏幕中心缩放 [scale] 并平移 (tx, ty) 像素。
         *
         * 顶点直接算成 NDC，省掉一个 mat4 uniform（每帧只有 4 个顶点，CPU 算更省）。
         */
        private fun drawTexture(
            tex: Int,
            imgW: Int,
            imgH: Int,
            scale: Float,
            tx: Float,
            ty: Float,
            vw: Int,
            vh: Int,
            alpha: Float,
        ) {
            val (u0, v0, u1, v1) = centerCropUv(imgW, imgH, vw, vh)
            val xl = -scale + 2f * tx / vw
            val xr = scale + 2f * tx / vw
            val yt = scale - 2f * ty / vh
            val yb = -scale - 2f * ty / vh

            buf.clear()
            // TRIANGLE_STRIP：左上 → 左下 → 右上 → 右下
            buf.put(xl).put(yt).put(u0).put(v0)
            buf.put(xl).put(yb).put(u0).put(v1)
            buf.put(xr).put(yt).put(u1).put(v0)
            buf.put(xr).put(yb).put(u1).put(v1)
            buf.position(0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1f(uAlpha, alpha)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf.position(0))
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, buf.position(2))
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** centerCrop：图更宽就裁左右、更高就裁上下，保证铺满不变形。 */
        private fun centerCropUv(
            imgW: Int,
            imgH: Int,
            vw: Int,
            vh: Int,
        ): FloatArray {
            if (imgW <= 0 || imgH <= 0 || vw <= 0 || vh <= 0) return floatArrayOf(0f, 0f, 1f, 1f)
            val imgAspect = imgW.toFloat() / imgH
            val viewAspect = vw.toFloat() / vh
            return if (imgAspect > viewAspect) {
                // 图更宽 → 裁左右
                val uw = viewAspect / imgAspect
                val u0 = (1f - uw) / 2f
                floatArrayOf(u0, 0f, 1f - u0, 1f)
            } else {
                val vhRatio = imgAspect / viewAspect
                val v0 = (1f - vhRatio) / 2f
                floatArrayOf(0f, v0, 1f, 1f - v0)
            }
        }

        /** viewport 尺寸就是壁纸 surface 尺寸（`onSurfaceChanged` 里 glViewport 设的）。 */
        private val vpBuf = IntArray(4)

        private fun viewportW(): Int {
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vpBuf, 0)
            return vpBuf[2]
        }

        private fun viewportH(): Int {
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vpBuf, 0)
            return vpBuf[3]
        }

        /** 该 attrib 槽位当前是否开着（每帧保存一次，画完恢复）。 */
        private fun attribEnabled(index: Int): Boolean {
            if (index < 0) return false
            GLES20.glGetVertexAttribiv(index, GLES20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, vpBuf, 0)
            return vpBuf[0] != 0
        }

        // ---------------------------------------------------------------- 刷新驱动

        private var rendererRef: java.lang.ref.WeakReference<Any>? = null
        private var refreshStarted = false

        /**
         * 静态壁纸**只在变化时渲染一帧**（实测：hook 后只出现「绘制第 1 帧」）。
         * 传感器驱动的视差需要持续出帧，所以按 `debug.hyperextreme.pglo_refresh_ms`
         * 的间隔主动调 `renderer.refresh()` 请求重绘（默认 0 = 关闭）。
         *
         * `refresh()` 走 `CallBack#onRefresh` → 引擎的 requestRender → 同步 `onDrawFrame`，
         * 所以这里必须在**独立线程**里调，且间隔不能太小（每帧重绘有功耗代价）。
         */
        private fun ensureRefreshDriver(renderer: Any) {
            rendererRef = java.lang.ref.WeakReference(renderer)
            if (refreshStarted) return
            refreshStarted = true
            val interval = propInt("debug.hyperextreme.pglo_refresh_ms", 0)
            if (interval <= 0) {
                log("刷新驱动关闭（静态壁纸只出 1 帧；如需视差实时，调 pglo_refresh_ms）")
                return
            }
            Thread {
                while (true) {
                    try {
                        Thread.sleep(interval.toLong())
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (!enabled()) continue
                    val target = rendererRef?.get() ?: continue
                    runCatching {
                        target.javaClass.getMethod("refresh").invoke(target)
                    }
                }
            }.apply {
                isDaemon = true
                name = "PGLO-refresh"
            }.start()
            log("刷新驱动已启动（间隔 ${interval}ms）")
        }

        // ---------------------------------------------------------------- 传感器

        private fun ensureSensor(renderer: Any) {
            if (sensorStarted) return
            sensorStarted = true
            val ctx = contextOf(renderer) ?: run {
                log("拿不到 Context —— 视差静止")
                return
            }
            val mgr = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
                log("拿不到 SensorManager —— 视差静止")
                return
            }
            val gravity = mgr.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: run {
                log("没有 TYPE_GRAVITY —— 视差静止")
                return
            }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_GRAVITY) return
                    val v = event.values
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dt = if (lastAt == 0L) 20f else (now - lastAt).coerceIn(5L, 200L).toFloat()
                    lastAt = now
                    val nx = (if (v.isNotEmpty()) v[0] else 0f) / FULL_SCALE
                    val ny = (if (v.size > 2) v[2] else 0f) / FULL_SCALE
                    if (baseX.isNaN() || baseY.isNaN()) {
                        baseX = nx
                        baseY = ny
                        tiltX = 0f
                        tiltY = 0f
                        return
                    }
                    val alpha = dt / (TILT_TAU_MS + dt)
                    tiltX += ((nx - baseX) - tiltX) * alpha
                    tiltY += ((ny - baseY) - tiltY) * alpha
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val ok = runCatching {
                mgr.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_GAME)
            }.getOrDefault(false)
            if (ok) {
                log("传感器已接入（TYPE_GRAVITY）")
            } else {
                log("传感器注册失败 —— 视差静止")
            }
        }

        /** renderer 自带的 `mContext` 字段优先，拿不到再退 `ActivityThread.currentApplication()`。 */
        private fun contextOf(renderer: Any): Context? {
            var c: Class<*>? = renderer.javaClass
            while (c != null) {
                val f = runCatching { c.getDeclaredField("mContext") }.getOrNull()
                if (f != null) {
                    f.isAccessible = true
                    (f.get(renderer) as? Context)?.let { return it }
                    break
                }
                c = c.superclass
            }
            return HookRuntime.appContext()
        }

        companion object {
            private val propCache =
                java.util.concurrent.atomic.AtomicReference<Pair<Long, Boolean?>?>(null)
            private var lastLogged: Boolean? = null

            /** 读整型系统属性（调试开关用）。 */
            fun propInt(key: String, def: Int): Int = runCatching {
                (Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, key, def.toString()) as? String)?.toIntOrNull() ?: def
            }.getOrDefault(def)

            /** 开关：系统属性优先，读不到就当关（和 ParallaxDesktopGuard 同一通道）。 */
            fun enabled(): Boolean {
                val now = android.os.SystemClock.elapsedRealtime()
                propCache.get()?.let { (at, v) -> if (now - at < 500L) return v ?: false }
                val raw = runCatching {
                    Class.forName("android.os.SystemProperties")
                        .getMethod("get", String::class.java, String::class.java)
                        .invoke(null, PROP_FLAG, "") as? String
                }.getOrNull()
                val value = when (raw) {
                    "1" -> true
                    "0" -> false
                    else -> null
                }
                propCache.set(now to value)
                if (lastLogged != value) {
                    lastLogged = value
                    log("开关 -> $value")
                }
                return value ?: false
            }
        }
    }

    private const val VS = """
attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = aUv;
}
"""

    private const val FS = """
precision mediump float;
uniform sampler2D uTex;
uniform float uAlpha;
varying vec2 vUv;
void main() {
    gl_FragColor = texture2D(uTex, vUv) * uAlpha;
}
"""
}
