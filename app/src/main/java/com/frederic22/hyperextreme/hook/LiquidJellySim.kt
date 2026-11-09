package com.frederic22.hyperextreme.hook

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 「Q 弹 / 果冻」模式的**弹簧-阻尼粒子网格**（2026-11-10 重构）。
 *
 * ## 为什么和水波不是同一套代码
 *
 * * 水波解的是**标量高度场**：h(x,y,t) 自己按波动方程传播，关注「波怎么跑」；
 * * 果冻解的是**矢量位移场**：每个粒子有位置和速度，靠弹簧约束回弹，关注「形状怎么被捏」。
 *
 * 两者在 GPU 上的数据流确实同构（邻域依赖 + 时间步进 + 纹理回读），但数学对象不同：
 * 水波的状态是一张高度图，果冻的状态是一张**粒子位移图**。所以这里单独一份求解器。
 *
 * ## 为什么在 CPU 上跑
 *
 * 这个网格在真机上只有 24×53 ≈ 1300 个粒子、8 邻域弹簧（结构 + 对角）+ 面积压力，
 * 每帧两次半隐式欧拉：约 2 万次弹簧求值，单线程 1ms 内跑完。
 * 而 GLES2 的仿真状态只能存 RGBA8 —— 速度/位移这种连续量在 8 位里积分会像之前的水波
 * 那样抖成噪点。**CPU 用 float 积分、每帧只把位移场编码成一张小纹理上传**（约 5KB），
 * 精度和稳定性都远好于在 shader 里用 8 位状态做积分。
 *
 * ## 三个「果冻感」的来源（对照产品描述）
 *
 * 1. **非线性刚度**：`k = k0 · (1 + 3|strain|)` —— 小幅软、压狠了迅速变硬；
 * 2. **体积近似保持**：逐四边形算面积，压缩时给四个角点一个向外的压力（`pressure`），
 *    于是「按下去、旁边鼓起来」；
 * 3. **欠阻尼**：阻尼系数落在 ζ≈0.3，松手后有 1~2 次可感知的过冲。
 *
 * ## 状态与坐标
 *
 * 粒子位置是**屏幕 uv**（0..1，v=0 在屏幕底部，与渲染端一致）。网格边界粒子被锚定，
 * 相当于果冻「装在容器里」。位移场编码进 RGBA8：`R/G = 位移（±0.0625 uv）`。
 */
internal class LiquidJellySim {

    var nx = 24
        private set
    var ny = 53
        private set

    private var count = 0

    private lateinit var rx: FloatArray
    private lateinit var ry: FloatArray
    private lateinit var px: FloatArray
    private lateinit var py: FloatArray
    private lateinit var vx: FloatArray
    private lateinit var vy: FloatArray
    private lateinit var fx: FloatArray
    private lateinit var fy: FloatArray

    private var cellW = 1f
    private var cellH = 1f
    private var lastW = 0
    private var lastH = 0
    private var lastCols = 0

    private var bytes = ByteArray(0)
    private var buffer: ByteBuffer? = null

    /** 按屏幕比例重建网格；尺寸没变就什么都不做。 */
    fun ensure(screenW: Int, screenH: Int, cols: Int = 24) {
        if (screenW <= 0 || screenH <= 0) return
        if (screenW == lastW && screenH == lastH && cols == lastCols) return
        lastW = screenW
        lastH = screenH
        lastCols = cols
        // 横向粒子数由质感档位决定（柔和 16 / 自然 20 / 真实 24）
        nx = cols.coerceIn(12, 32)
        ny = (nx.toFloat() * screenH / screenW).toInt().coerceIn(16, 72)
        count = nx * ny
        cellW = 1f / (nx - 1)
        cellH = 1f / (ny - 1)
        rx = FloatArray(count)
        ry = FloatArray(count)
        px = FloatArray(count)
        py = FloatArray(count)
        vx = FloatArray(count)
        vy = FloatArray(count)
        fx = FloatArray(count)
        fy = FloatArray(count)
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                val k = j * nx + i
                rx[k] = i * cellW
                ry[k] = j * cellH
            }
        }
        bytes = ByteArray(count * 4)
        buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        reset()
    }

    fun reset() {
        if (count == 0) return
        for (k in 0 until count) {
            px[k] = rx[k]
            py[k] = ry[k]
            vx[k] = 0f
            vy[k] = 0f
        }
    }

    private fun isBorder(i: Int, j: Int): Boolean =
        i == 0 || j == 0 || i == nx - 1 || j == ny - 1

    /**
     * 按压：在触点附近给一个**朝触点**的速度冲量，把网格局部压瘪。
     *
     * 只给速度、不改位置 —— 位置交给弹簧和面积压力去推，rebound 才自然。
     */
    fun press(u: Float, v: Float, amp: Float) {
        if (count == 0) return
        val gx = u * (nx - 1)
        val gy = v * (ny - 1)
        val rad = 2.6f
        val r2 = rad * rad
        val force = amp * 0.35f
        for (j in 1 until ny - 1) {
            for (i in 1 until nx - 1) {
                val dx = gx - i
                val dy = gy - j
                val d2 = dx * dx + dy * dy
                if (d2 > r2 || d2 < 1e-4f) continue
                val d = kotlin.math.sqrt(d2)
                val w = exp(-d2 / r2)
                val k = j * nx + i
                vx[k] += dx / d * w * force
                vy[k] += dy / d * w * force
            }
        }
    }

    /**
     * 一步求解。
     *
     * @param dt 帧时长（秒）
     * @param stiffness 基础刚度 k0（1/s²，约 80~400 有果冻感）
     * @param damping 弹簧阻尼（1/s，约 3~20，越大越快停）
     * @param pressure 面积约束强度（0~120，越大「越不可压缩」）
     * @param gx/gy 倾斜带来的体积力（uv/s²，很小）
     */
    fun step(dt: Float, stiffness: Float, damping: Float, pressure: Float, gx: Float, gy: Float) {
        if (count == 0) return
        // 两个子步：显式积分在 k≈400 时，单步 dt=1/60 已经接近稳定边界
        val h = (dt * 0.5f).coerceIn(0.002f, 0.02f)
        repeat(2) { substep(h, stiffness, damping, pressure, gx, gy) }
    }

    private fun substep(
        h: Float,
        stiffness: Float,
        damping: Float,
        pressure: Float,
        gravityX: Float,
        gravityY: Float,
    ) {
        java.util.Arrays.fill(fx, 0f)
        java.util.Arrays.fill(fy, 0f)

        // ── 弹簧力（结构 + 对角）────────────────────────────────────────
        for (j in 1 until ny - 1) {
            for (i in 1 until nx - 1) {
                val k = j * nx + i
                var ax = 0f
                var ay = 0f
                for (oj in -1..1) {
                    for (oi in -1..1) {
                        if (oi == 0 && oj == 0) continue
                        val ni = i + oi
                        val nj = j + oj
                        if (ni < 0 || nj < 0 || ni >= nx || nj >= ny) continue
                        val n = nj * nx + ni
                        val dx = px[n] - px[k]
                        val dy = py[n] - py[k]
                        val len = hypot(dx, dy)
                        if (len < 1e-6f) continue
                        val rest = if (oi != 0 && oj != 0) {
                            hypot(cellW, cellH)
                        } else if (oi != 0) {
                            cellW
                        } else {
                            cellH
                        }
                        val strain = (len - rest) / rest
                        // 非线性刚度：压得越狠越硬（果冻的核心手感）
                        val kk = stiffness * (1f + 3f * abs(strain))
                        val fSpring = kk * (len - rest)
                        val ux = dx / len
                        val uy = dy / len
                        ax += fSpring * ux
                        ay += fSpring * uy
                        // 沿弹簧方向的相对速度阻尼
                        val rel = (vx[n] - vx[k]) * ux + (vy[n] - vy[k]) * uy
                        val fDamp = damping * rel
                        ax += fDamp * ux
                        ay += fDamp * uy
                    }
                }
                fx[k] = ax
                fy[k] = ay
            }
        }

        // ── 面积压力：压缩的格子把四角往外推（体积近似保持）─────────────
        if (pressure > 0f) {
            val restArea = cellW * cellH
            for (j in 0 until ny - 1) {
                for (i in 0 until nx - 1) {
                    val a = j * nx + i
                    val b = a + 1
                    val d = a + nx
                    val c = d + 1
                    // 鞋带公式（逆时针/顺时针同取绝对值）
                    val area = 0.5f * abs(
                        (px[a] * py[b] - px[b] * py[a]) +
                            (px[b] * py[c] - px[c] * py[b]) +
                            (px[c] * py[d] - px[d] * py[c]) +
                            (px[d] * py[a] - px[a] * py[d]),
                    )
                    val ratio = (area - restArea) / restArea
                    if (ratio >= -0.001f) continue
                    val cx = (px[a] + px[b] + px[c] + px[d]) * 0.25f
                    val cy = (py[a] + py[b] + py[c] + py[d]) * 0.25f
                    val push = pressure * (-ratio)
                    addPressure(a, cx, cy, push)
                    addPressure(b, cx, cy, push)
                    addPressure(c, cx, cy, push)
                    addPressure(d, cx, cy, push)
                }
            }
        }

        // ── 积分（半隐式欧拉）+ 边界锚定 ────────────────────────────────
        for (j in 1 until ny - 1) {
            for (i in 1 until nx - 1) {
                val k = j * nx + i
                var ax = fx[k] + gravityX
                var ay = fy[k] + gravityY
                vx[k] = ((vx[k] + ax * h) * 0.996f).coerceIn(-2.5f, 2.5f)
                vy[k] = ((vy[k] + ay * h) * 0.996f).coerceIn(-2.5f, 2.5f)
                px[k] += vx[k] * h
                py[k] += vy[k] * h
                // 位移上限：避免极端手势把网格撕开（视觉上也没人需要更大）
                px[k] = (px[k] - rx[k]).coerceIn(-0.08f, 0.08f) + rx[k]
                py[k] = (py[k] - ry[k]).coerceIn(-0.08f, 0.08f) + ry[k]
            }
        }
    }

    /** 面积压力：把压缩格子的一个角沿「离开形心」方向推开。 */
    private fun addPressure(idx: Int, cx: Float, cy: Float, push: Float) {
        val dx = px[idx] - cx
        val dy = py[idx] - cy
        val len = hypot(dx, dy)
        if (len < 1e-6f) return
        fx[idx] += push * dx / len
        fy[idx] += push * dy / len
    }

    /**
     * 把位移场编码成 RGBA8 并上传。
     *
     * `R/G = round(128 + disp · 2048)`：量程 ±0.0625 uv、步进 ≈0.0005 uv（约 0.5px），
     * 对形变场足够；`B/A` 留空。
     */
    fun upload(tex: Int) {
        val buf = buffer ?: return
        if (count == 0) return
        for (k in 0 until count) {
            val dx = px[k] - rx[k]
            val dy = py[k] - ry[k]
            val o = k * 4
            bytes[o] = (128f + dx * 2048f).toInt().coerceIn(0, 255).toByte()
            bytes[o + 1] = (128f + dy * 2048f).toInt().coerceIn(0, 255).toByte()
            bytes[o + 2] = 128.toByte()
            bytes[o + 3] = 255.toByte()
        }
        buf.clear()
        buf.put(bytes)
        buf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, nx, ny,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
        )
    }

    /** 当前最大位移（诊断用：能区分「没收到触摸」和「收到但没形变」）。 */
    fun maxDisplacement(): Float {
        var m = 0f
        for (k in 0 until count) {
            m = max(m, min(1f, abs(px[k] - rx[k]) + abs(py[k] - ry[k])))
        }
        return m
    }
}
