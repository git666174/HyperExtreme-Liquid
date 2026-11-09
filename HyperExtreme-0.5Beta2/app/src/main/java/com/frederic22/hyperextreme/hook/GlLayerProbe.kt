package com.frederic22.hyperextreme.hook

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.lang.reflect.Method

/**
 * **GL 最小渲染探针**（2026-10-22，只验证不接功能）。
 *
 * 目的：确认「SystemUI 进程里能不能用一个自建 SurfaceControl + GL 画出东西并显示在屏幕上」。
 * 这是空间壁纸渲染管线的**唯一前置条件** —— 如果这步不通，整条路要改架构。
 *
 * ## 只做五件事
 *
 * 1. `SurfaceControl.Builder` 建一个层（名字 `he-gl-probe`）；
 * 2. `Surface(sc)` → EGL `createWindowSurface`；
 * 3. `glClearColor` 刷成**半透明洋红** + `glClear` + `eglSwapBuffers`；
 * 4. `Transaction.reparent(sc, 壁纸层)` + `show` + `apply`；
 * 5. 屏幕左半边应该出现一块洋红色 —— 看到就说明 GL 管线成立。
 *
 * **画满屏但用 alpha 0.5**，这样既能看到又不会完全挡住宿主画面。
 * `stop()` 会把层移除并销毁所有 EGL 资源。
 */
internal object GlLayerProbe {

    private var sc: android.view.SurfaceControl? = null
    private var surface: Surface? = null
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var parentToken: Any? = null
    private var txCtor: java.lang.reflect.Constructor<*>? = null
    private var reparentM: Method? = null
    private var showM: Method? = null
    private var applyM: Method? = null
    private var removeM: Method? = null

    fun start(loader: ClassLoader, screenW: Int, screenH: Int): Boolean {
        if (sc != null) return true
        return runCatching {
            // ---- 1. 建层（尺寸取屏幕一半，放在左上角，便于辨认）----
            val w = screenW / 2
            val h = screenH / 2
            val builder = android.view.SurfaceControl.Builder()
                .setName("he-gl-probe")
                .setBufferSize(w, h)
            sc = builder.build()
            surface = Surface(sc!!)
            log("SurfaceControl 已建（${w}x$h）")

            // ---- 2. EGL ----
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) { log("no display"); return false }
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
                log("no window config"); return false
            }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) { log("createContext 失败"); return false }
            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                log("eglCreateWindowSurface 失败 err=0x${EGL14.eglGetError().toString(16)}")
                return false
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                log("makeCurrent 失败"); return false
            }
            log("EGL window surface 就绪")

            // ---- 3. 画一帧：半透明洋红 ----
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(1f, 0f, 1f, 0.5f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val ok = EGL14.eglSwapBuffers(display, eglSurface)
            log("glClear + swapBuffers = $ok")

            // ---- 4. 挂到壁纸层下并显示 ----
            val wmgClass = Class.forName("android.view.WindowManagerGlobal", false, loader)
            val inst = wmgClass.getMethod("getInstance").invoke(null)
            val gw = wmgClass.getMethod(
                "getWallpaperByShowWhenLocked",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            val token = gw.invoke(inst, 0, false)
            parentToken = token
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
            log("已挂到壁纸层并提交 —— 屏幕**左上角**应出现半透明洋红块")
            true
        }.onFailure { log("失败：${it.javaClass.simpleName}: ${it.message}") }.getOrDefault(false)
    }

    fun stop() {
        runCatching {
            val tx = txCtor?.newInstance()
            if (tx != null && sc != null) {
                removeM?.invoke(tx, sc)
                applyM?.invoke(tx)
            }
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

    private fun log(msg: String) = HookRuntime.log("GLPROBE $msg")
}
