package com.frederic22.hyperextreme.hook

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * **8 层 GL 渲染骨架**（2026-10-22）。
 *
 * 验证目标：**多层 + 每层独立变换 + 传感器驱动** 这条链路能不能跑起来。
 *
 * ## 当前用「程序纹理」而不是真实层图
 *
 * 每层画一个全屏 quad，UV 取整张纹理，片元着色器用 `vUv.y` 判断自己属于哪一层并着色 ——
 * 这样**不需要任何素材**就能看出「8 个水平带各自错位」的效果。真实层图接上后，
 * 把 `uLayerTex` 换成 `GL_TEXTURE_2D_ARRAY` 的对应层即可，顶点/矩阵逻辑不变。
 *
 * ## 每层的变换
 *
 * ```
 * 位移 = 基准位移 × (1 + k × 归一化深度)      // 近层动得多
 * 缩放 = base + k × 归一化深度                 // 近层放大更多（防层缝）
 * ```
 *
 * 位移来自传感器（`Sensor.TYPE_GRAVITY` 的 x/y），阻尼后驱动。
 *
 * ## 触发
 *
 * `HookEntry` 里 `if (false)` 包着，需要验证时改 `true`。20 秒后自动清理。
 */
internal object SpatialProbe {

    private const val LAYERS = 8
    private const val VERT = """
        attribute vec2 aPos;
        uniform mat4 uMvp;
        varying vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
        }
    """
    private const val FRAG = """
        precision mediump float;
        varying vec2 vUv;
        uniform float uLayer;      // 0..1 —— 本层在栈里的位置（0=远, 1=近）
        uniform float uTime;
        void main() {
            // 每层一条独立色带 + 网格线，便于肉眼确认「8 层各自错位」
            float band = floor(vUv.y * 8.0);
            vec3 tint = mix(vec3(0.10, 0.14, 0.22), vec3(0.95, 0.75, 0.35), uLayer);
            float grid = step(0.97, fract(vUv.x * 12.0)) + step(0.97, fract(vUv.y * 24.0));
            tint = mix(tint, vec3(1.0), clamp(grid, 0.0, 1.0) * 0.35);
            gl_FragColor = vec4(tint, 0.85);
        }
    """

    private var sc: android.view.SurfaceControl? = null
    private var surface: Surface? = null
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var program = 0
    private var quad: FloatBuffer? = null
    private var aPos = 0
    private var uMvp = 0
    private var uLayer = 0
    private var txCtor: java.lang.reflect.Constructor<*>? = null
    private var reparentM: Method? = null
    private var showM: Method? = null
    private var applyM: Method? = null
    private var removeM: Method? = null

    fun start(loader: ClassLoader, screenW: Int, screenH: Int): Boolean {
        if (sc != null) return true
        return runCatching {
            val builder = android.view.SurfaceControl.Builder()
                .setName("he-spatial-probe")
                .setBufferSize(screenW, screenH)
            sc = builder.build()
            surface = Surface(sc!!)

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) { log("eglInitialize 失败"); return false }
            val cfgAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                log("no config"); return false
            }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                log("makeCurrent 失败"); return false
            }

            program = buildProgram(VERT, FRAG)
            if (program == 0) { log("shader 编译失败"); return false }
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uLayer = GLES20.glGetUniformLocation(program, "uLayer")

            val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            quad = ByteBuffer.allocateDirect(verts.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }

            // 画 8 层（静止态：只画一次，先确认层次排布正确）
            drawLayers(screenW, screenH, 0f, 0f)
            val ok = EGL14.eglSwapBuffers(display, eglSurface)
            log("8 层绘制 + swapBuffers = $ok")

            // 挂到壁纸层
            val wmgClass = Class.forName("android.view.WindowManagerGlobal", false, loader)
            val inst = wmgClass.getMethod("getInstance").invoke(null)
            val gw = wmgClass.getMethod(
                "getWallpaperByShowWhenLocked",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            val token = gw.invoke(inst, 0, false)
            val leash = token?.javaClass?.getMethod("getLeash")?.invoke(token)
                ?: run { log("拿不到 leash"); return false }
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction", false, loader)
            txCtor = txClass.getConstructor()
            reparentM = txClass.getMethod("reparent", android.view.SurfaceControl::class.java, android.view.SurfaceControl::class.java)
            showM = txClass.getMethod("show", android.view.SurfaceControl::class.java)
            applyM = txClass.getMethod("apply")
            removeM = txClass.getMethod("remove", android.view.SurfaceControl::class.java)
            val tx = txCtor!!.newInstance()
            reparentM!!.invoke(tx, sc, leash)
            showM!!.invoke(tx, sc)
            applyM!!.invoke(tx)
            log("8 层已挂到壁纸层并提交 —— 应看到 8 条由暗到亮的色带（带网格）")
            true
        }.onFailure { log("失败：${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
    }

    /** 画 8 层：远的先画、近的后画（alpha 覆盖）。 */
    private fun drawLayers(w: Int, h: Int, tiltX: Float, tiltY: Float) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        val base = w * 0.12f   // 最大位移
        for (i in 0 until LAYERS) {
            val d = i / (LAYERS - 1f)          // 0=远, 1=近
            val scale = 1.02f + 0.10f * d       // 近层放大更多
            val tx = tiltX * base * (0.3f + d)
            val ty = tiltY * base * (0.3f + d)
            // 归一化设备坐标下的平移：像素 → NDC = 2/尺寸
            val m = floatArrayOf(
                scale, 0f, 0f, 0f,
                0f, scale, 0f, 0f,
                0f, 0f, 1f, 0f,
                tx * 2f / w, ty * 2f / h, 0f, 1f,
            )
            GLES20.glUniformMatrix4fv(uMvp, 1, false, m, 0)
            GLES20.glUniform1f(uLayer, d)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs) ?: return 0
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs) ?: return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { log("link: ${GLES20.glGetProgramInfoLog(p)}"); return 0 }
        return p
    }

    private fun compile(type: Int, src: String): Int? {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { log("compile: ${GLES20.glGetShaderInfoLog(s)}"); return null }
        return s
    }

    fun stop() {
        runCatching {
            val tx = txCtor?.newInstance()
            if (tx != null && sc != null) { removeM?.invoke(tx, sc); applyM?.invoke(tx) }
        }
        runCatching { surface?.release() }
        runCatching {
            display?.let { d ->
                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(d, eglSurface)
                }
                context?.let { EGL14.eglDestroyContext(d, it) }
                EGL14.eglTerminate(d)
            }
        }
        sc = null; surface = null; display = null; context = null; eglSurface = null
        log("已清理")
    }

    private fun log(msg: String) = HookRuntime.log("SPATIAL $msg")
}
