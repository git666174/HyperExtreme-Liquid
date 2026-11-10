package com.frederic22.hyperextreme.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import com.frederic22.hyperextreme.data.LiquidTune
import com.frederic22.hyperextreme.hook.LiquidGlCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 液态壁纸的**页面内实时预览**（2026-11-10）。
 *
 * ## 为什么不是截图
 *
 * 效果的核心是「参数 → 物理 → 光学」这条链，截图只能看静态一帧。这里用一个小号
 * GLSurfaceView 跑**同一套 shader**（[LiquidGlCore.SIM] / [LiquidGlCore.DRAW]），
 * 所以拖动滑块时预览会立刻跟着变，和锁屏上看到的是同一套代码。
 *
 * ## 尺寸
 *
 * 预览框约 360×240dp，仿真网格固定 96 宽（正式渲染是 144~288），画质略低但足够看手感；
 * 每次拖动只跑 96×~170 的高度场，功耗可以忽略，且只在页面可见时渲染。
 *
 * ## 交互
 *
 * 按住拖动 = 直接往高度场里丢一个高斯凹陷，和锁屏上的点触同一条路径。
 */
internal class LiquidPreviewView(context: Context) : GLSurfaceView(context) {

    private val previewRenderer = PreviewRenderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(previewRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val w = width.coerceAtLeast(1)
                    val h = height.coerceAtLeast(1)
                    val u = (e.x / w).coerceIn(0f, 1f)
                    val v = (1f - e.y / h).coerceIn(0f, 1f)
                    queueEvent { previewRenderer.splash(u, v) }
                    true
                }
                else -> true
            }
        }
    }

    private var lastBitmap: Bitmap? = null

    /** 底图（null = 保持上一张）。同一个对象不重复上传。 */
    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap === lastBitmap) return
        lastBitmap = bitmap
        queueEvent { previewRenderer.setBitmap(bitmap) }
    }

    /** 参数热更新（页面滑块直接调它）。 */
    fun setTune(tune: LiquidTune) = queueEvent { previewRenderer.tune = tune }

    /** 手动丢一个涟漪（进页面时用一次，让用户知道可以拖）。 */
    fun splash(u: Float, v: Float) = queueEvent { previewRenderer.splash(u, v) }

    // ─────────────────────────── 渲染器 ───────────────────────────

    private class PreviewRenderer : GLSurfaceView.Renderer {

        @Volatile var tune: LiquidTune = LiquidTune.WATER

        private var pendingBitmap: Bitmap? = null
        /** 最近一次上传成功的底图：EGL 上下文重建后要能恢复 */
        private var lastUploadedBitmap: Bitmap? = null
        private var bitmapStamp = 0

        private var simProgram = 0
        private var drawProgram = 0
        private val simTex = IntArray(2)
        private val simFbo = IntArray(2)
        private var simCur = 0
        private var baseTex = 0
        private var simW = 96
        private var simH = 170
        private var viewW = 1
        private var viewH = 1
        private var quad: FloatBuffer? = null
        private val splats = ArrayList<FloatArray>()
        private val imgScale = floatArrayOf(1f, 1f)

        // uniform 位置
        private var sPos = 0
        private var sPrev = 0
        private var sTexel = 0
        private var sC2 = 0
        private var sDamp = 0
        private var sVisc = 0
        private var sSettle = 0
        private var sTilt = 0
        private var sLevel = 0
        private var sAspect = 0
        private var sSplat0 = 0
        private var sSplat1 = 0
        private var sMaskOn = 0
        private var sMaskTex = 0
        private var dPos = 0
        private var dBase = 0
        private var dSim = 0
        private var dMask = 0
        private var dMaskOn = 0
        private var dTexel = 0
        private var dScreen = 0
        private var dImgScale = 0
        private var dRefract = 0
        private var dDisp = 0
        private var dSpec = 0
        private var dJelly = 0
        private var dFx = 0
        private var dSmooth = 0
        private var dLight = 0
        private var dDispTex = 0

        fun setBitmap(bitmap: Bitmap?) {
            pendingBitmap = bitmap
            bitmapStamp++
        }

        fun splash(u: Float, v: Float) {
            if (splats.size > 24) splats.clear()
            splats.add(floatArrayOf(u, v, 0.45f))
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            simProgram = LiquidGlCore.program(LiquidGlCore.VERT, LiquidGlCore.SIM)
            drawProgram = LiquidGlCore.program(LiquidGlCore.VERT, LiquidGlCore.DRAW)
            if (simProgram == 0 || drawProgram == 0) return

            sPos = GLES20.glGetAttribLocation(simProgram, "aPos")
            sPrev = GLES20.glGetUniformLocation(simProgram, "uPrev")
            sTexel = GLES20.glGetUniformLocation(simProgram, "uTexel")
            sC2 = GLES20.glGetUniformLocation(simProgram, "uC2")
            sDamp = GLES20.glGetUniformLocation(simProgram, "uDamp")
            sVisc = GLES20.glGetUniformLocation(simProgram, "uVisc")
            sSettle = GLES20.glGetUniformLocation(simProgram, "uSettle")
            sTilt = GLES20.glGetUniformLocation(simProgram, "uTilt")
            sLevel = GLES20.glGetUniformLocation(simProgram, "uLevel")
            sAspect = GLES20.glGetUniformLocation(simProgram, "uAspect")
            sSplat0 = GLES20.glGetUniformLocation(simProgram, "uSplat0")
            sSplat1 = GLES20.glGetUniformLocation(simProgram, "uSplat1")
            sMaskOn = GLES20.glGetUniformLocation(simProgram, "uMaskOn")
            sMaskTex = GLES20.glGetUniformLocation(simProgram, "uMaskTex")

            dPos = GLES20.glGetAttribLocation(drawProgram, "aPos")
            dBase = GLES20.glGetUniformLocation(drawProgram, "uBase")
            dSim = GLES20.glGetUniformLocation(drawProgram, "uSim")
            dMask = GLES20.glGetUniformLocation(drawProgram, "uMaskTex")
            dMaskOn = GLES20.glGetUniformLocation(drawProgram, "uMaskOn")
            dTexel = GLES20.glGetUniformLocation(drawProgram, "uTexel")
            dScreen = GLES20.glGetUniformLocation(drawProgram, "uScreen")
            dImgScale = GLES20.glGetUniformLocation(drawProgram, "uImgScale")
            dRefract = GLES20.glGetUniformLocation(drawProgram, "uRefract")
            dDisp = GLES20.glGetUniformLocation(drawProgram, "uDisp")
            dSpec = GLES20.glGetUniformLocation(drawProgram, "uSpec")
            dJelly = GLES20.glGetUniformLocation(drawProgram, "uJelly")
            dFx = GLES20.glGetUniformLocation(drawProgram, "uFx")
            dSmooth = GLES20.glGetUniformLocation(drawProgram, "uSmooth")
            dLight = GLES20.glGetUniformLocation(drawProgram, "uLight")
            dDispTex = GLES20.glGetUniformLocation(drawProgram, "uDispTex")

            val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            quad = ByteBuffer.allocateDirect(verts.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(verts)
                    position(0)
                }
            baseTex = 0
            // 上下文重建后上一张底图丢了：下一帧补传
            lastUploadedBitmap?.let {
                pendingBitmap = it
                bitmapStamp++
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
            simH = (simW.toFloat() * viewH / viewW).toInt().coerceIn(24, 240)
            releaseSim()
            for (i in 0..1) {
                simTex[i] = newTexture(simW, simH)
                simFbo[i] = newFbo(simTex[i])
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[i])
                GLES20.glViewport(0, 0, simW, simH)
                GLES20.glClearColor(0.5f, 0.5f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (simProgram == 0 || drawProgram == 0) return
            val q = quad ?: return
            val bmp = pendingBitmap
            if (bmp != null && bitmapStamp > 0) {
                pendingBitmap = null
                uploadBase(bmp)
            }
            stepSim(q)
            drawComposite(q)
        }

        private fun stepSim(q: FloatBuffer) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[1 - simCur])
            GLES20.glViewport(0, 0, simW, simH)
            GLES20.glUseProgram(simProgram)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simCur])
            GLES20.glUniform1i(sPrev, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            val t = tune
            GLES20.glUniform2f(sTexel, 1f / simW, 1f / simH)
            GLES20.glUniform1f(sC2, t.c2)
            GLES20.glUniform1f(sDamp, t.damp)
            GLES20.glUniform1f(sVisc, 0.10f + t.smooth * 0.55f)
            GLES20.glUniform1f(sSettle, 1f)
            GLES20.glUniform2f(sTilt, 0f, 0f)
            GLES20.glUniform1f(sLevel, t.level * 0.4f)
            GLES20.glUniform1f(sAspect, simH.toFloat() / simW)
            GLES20.glUniform1f(sMaskOn, 0f)
            GLES20.glUniform1i(sMaskTex, 1)

            val a = splats.getOrNull(0)
            val b = splats.getOrNull(1)
            val sigma = t.sigma * (1f + t.smooth * 0.8f)
            GLES20.glUniform4f(sSplat0, a?.get(0) ?: 0f, a?.get(1) ?: 0f, a?.get(2) ?: 0f, sigma)
            GLES20.glUniform4f(sSplat1, b?.get(0) ?: 0f, b?.get(1) ?: 0f, b?.get(2) ?: 0f, sigma)
            splats.clear()

            q.position(0)
            GLES20.glEnableVertexAttribArray(sPos)
            GLES20.glVertexAttribPointer(sPos, 2, GLES20.GL_FLOAT, false, 8, q)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(sPos)
            simCur = 1 - simCur
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        private fun drawComposite(q: FloatBuffer) {
            GLES20.glViewport(0, 0, viewW, viewH)
            GLES20.glUseProgram(drawProgram)
            val t = tune

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTex)
            GLES20.glUniform1i(dBase, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simCur])
            GLES20.glUniform1i(dSim, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glUniform1i(dMask, 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glUniform1i(dDispTex, 3)

            GLES20.glUniform2f(dTexel, 1f / simW, 1f / simH)
            GLES20.glUniform2f(dScreen, viewW.toFloat(), viewH.toFloat())
            GLES20.glUniform2f(dImgScale, imgScale[0], imgScale[1])
            GLES20.glUniform1f(dMaskOn, 0f)
            GLES20.glUniform1f(dRefract, t.refract)
            GLES20.glUniform1f(dDisp, 0.06f)
            GLES20.glUniform1f(dSpec, 0.22f)
            GLES20.glUniform1f(dJelly, 0f)
            GLES20.glUniform1f(dFx, 1f)
            GLES20.glUniform1f(dSmooth, t.smooth)
            GLES20.glUniform3f(dLight, -0.35f, 0.55f, 0.75f)

            q.position(0)
            GLES20.glEnableVertexAttribArray(dPos)
            GLES20.glVertexAttribPointer(dPos, 2, GLES20.GL_FLOAT, false, 8, q)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(dPos)
        }

        private fun uploadBase(bitmap: Bitmap) {
            lastUploadedBitmap = bitmap
            if (baseTex != 0) GLES20.glDeleteTextures(1, intArrayOf(baseTex), 0)
            baseTex = newTexture(bitmap.width, bitmap.height)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            val s = maxOf(
                viewW.toFloat() / bitmap.width,
                viewH.toFloat() / bitmap.height,
            )
            imgScale[0] = viewW / (bitmap.width * s) / 1.06f
            imgScale[1] = viewH / (bitmap.height * s) / 1.06f
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
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return f[0]
        }

        private fun releaseSim() {
            if (simTex[0] != 0) GLES20.glDeleteTextures(2, simTex, 0)
            if (simFbo[0] != 0) GLES20.glDeleteFramebuffers(2, simFbo, 0)
            simTex[0] = 0
            simTex[1] = 0
            simFbo[0] = 0
            simFbo[1] = 0
        }
    }
}
