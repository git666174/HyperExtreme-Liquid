package com.frederic22.hyperextreme.hook

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * **EGL / GL 能力探针**（2026-10-22，只读诊断）。
 *
 * 目的只有一个：确认 **SystemUI 进程里能不能建 EGL 上下文、能不能跑 GL**。
 * 这个结论决定空间壁纸走哪条路 ——
 * 3D 平面转动需要**透视投影**（8 自由度单应性），而 `SurfaceControl` 的
 * `matrix22_t` 只有 6 自由度（2×2 + 平移），**表达不了透视**，所以只能 GL。
 *
 * **不做任何渲染、不接入任何逻辑**，只建上下文 → 读字符串 → 全部销毁。
 * 日志前缀 `GPUPROBE`（ASCII，方便过滤）。
 */
internal object GpuProbe {

    fun run(tag: String) {
        Thread {
            runCatching { probe(tag) }.onFailure { log("异常：${it.javaClass.simpleName}: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    private fun probe(tag: String) {
        log("开始（$tag）")
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            log("eglGetDisplay 返回 NO_DISPLAY")
            return
        }
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            log("eglInitialize 失败 err=0x${EGL14.eglGetError().toString(16)}")
            return
        }
        log("EGL ${ver[0]}.${ver[1]} 初始化成功")

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            log("eglChooseConfig 失败（无可用 config）")
            EGL14.eglTerminate(display)
            return
        }

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx: EGLContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0,
        )
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            log("eglCreateContext 失败 err=0x${EGL14.eglGetError().toString(16)}")
            EGL14.eglTerminate(display)
            return
        }
        log("EGLContext 创建成功")

        val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surf: EGLSurface = EGL14.eglCreatePbufferSurface(display, configs[0], pbAttrs, 0)
        if (surf == EGL14.EGL_NO_SURFACE) {
            log("eglCreatePbufferSurface 失败 err=0x${EGL14.eglGetError().toString(16)}")
            EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
            return
        }
        if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) {
            log("eglMakeCurrent 失败 err=0x${EGL14.eglGetError().toString(16)}")
            EGL14.eglDestroySurface(display, surf)
            EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
            return
        }
        log("eglMakeCurrent 成功 —— **GL 可用**")

        log("GL_RENDERER=${GLES20.glGetString(GLES20.GL_RENDERER)}")
        log("GL_VENDOR=${GLES20.glGetString(GLES20.GL_VENDOR)}")
        log("GL_VERSION=${GLES20.glGetString(GLES20.GL_VERSION)}")
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        log(
            "扩展：ASTC=${ext.contains("texture_compression_astc")} " +
                "ETC2=${ext.contains("texture_compression_etc2")} " +
                "EGL_IMAGE_EXTERNAL=${ext.contains("OES_EGL_image_external")} " +
                "扩展串长度=${ext.length}",
        )
        val maxTex = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        log("GL_MAX_TEXTURE_SIZE=${maxTex[0]}")

        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        EGL14.eglDestroySurface(display, surf)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
        log("清理完成（$tag）")
    }

    private fun log(msg: String) = HookRuntime.log("GPUPROBE $msg")
}
