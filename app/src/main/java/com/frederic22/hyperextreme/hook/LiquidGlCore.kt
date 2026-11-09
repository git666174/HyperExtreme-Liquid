package com.frederic22.hyperextreme.hook

import android.opengl.GLES20

/**
 * 液态壁纸的**着色器 / 物理常量 / GL 小工具**（2026-11-09）。
 *
 * 与 [SpatialGlCore] / [RasterGlCore] 同一套约定：两条链（如果以后有系统壁纸服务版）
 * 只用这一份文本，数值敏感的东西不各写一遍。
 *
 * ## 一帧两 pass
 *
 * ```
 * ① sim   ：256 × N 的 RGBA8 ping-pong，R = 高度 h，G = 速度 v（都按 ±1 编码成 0..1）
 * ② draw  ：全屏 quad，由 ∇h 折射采样原图 + 色散 + 高光
 * ```
 *
 * **为什么是速度式而不是「两帧高度」式**：只需要一张纹理（R=h、G=v），
 * 8 位精度下少一张纹理就少一半带宽；而且阻尼直接乘在 v 上，不会像
 * `h' = 2h - h0 + c²∇²h` 那样把阻尼变成「改波速」，参数更好调。
 *
 * ## 为什么 GLES2 也能跑
 *
 * 不依赖浮点纹理、不依赖顶点纹理采样、不依赖 MRT：
 * * h/v 用 RGBA8 的 R/G 两通道存，±1 映射到 0..1（用 `*2-1` 解码）；
 * * 折射全部在 fragment 里做 UV 偏移，顶点着色器只负责铺一个全屏 quad；
 * * 只在 draw pass 里采样 base，sim pass 采样自己的上一帧 + mask。
 *
 * ## 稳定性（必读）
 *
 * 五点拉普拉斯的显式积分要求 `c² ≤ 0.25`（归一化网格 dx=1）。水波取 0.18、
 * 果冻取 0.09，都留了余量。**调大 c² 会直接炸成满屏雪花**，不是「波更大」。
 */
internal object LiquidGlCore {

    /** 模拟宽度：256 已经够（水面不需要清晰），再大只是白烧带宽。 */
    const val SIM_WIDTH = 256

    /** 一次最多注入两个触点 —— 手指最多两根，多出来的丢弃，不影响观感。 */
    const val MAX_SPLATS = 2

    /**
     * 一套物理 / 观感参数。水波与果冻的差别**全部在这几个数里**，
     * 不是两套代码 —— 这样切模式不会出现「换了条路径所以行为不一致」。
     */
    class Params(
        /** 波速平方（≤0.25，见类注释的稳定性）。 */
        val c2: Float,
        /** 每帧速度阻尼：越接近 1 越「荡得久」。 */
        val damp: Float,
        /** 液面跟随重力的强度：0 = 水永远平，1 = 完全跟着手机倾斜。 */
        val level: Float,
        /** 每 1.0 的「h/像素」梯度对应的**像素位移**（4000 上下是 OriginOS 那种克制感）。 */
        val refract: Float,
        /** 色散：R/G/B 三通道错位比例。 */
        val dispersion: Float,
        /** 高光强度。 */
        val specular: Float,
        /** 1 = 果冻模式，draw pass 里额外做一次径向挤压。 */
        val jelly: Float,
    )

    /**
     * 把「模式骨架」和「用户调参」合成一份运行参数。
     *
     * 骨架（色散/高光/果冻挤压）跟模式走；五个手感量（折射/波速/阻尼/触点/液面）
     * 全部来自 [LiquidTune] —— 页面滑块改的就是这五个。
     */
    fun params(mode: Int, tune: com.frederic22.hyperextreme.data.LiquidTune): Params {
        val base = if (mode == com.frederic22.hyperextreme.data.LiquidFlag.MODE_JELLY) JELLY else WATER
        return Params(
            c2 = tune.c2,
            damp = tune.damp,
            level = tune.level,
            refract = tune.refract,
            dispersion = base.dispersion,
            specular = base.specular,
            jelly = base.jelly,
        )
    }

    /** 水波：高频、中等阻尼、边缘起涟漪。 */
    val WATER = Params(
        c2 = 0.18f,
        damp = 0.992f,
        level = 0.35f,
        refract = 4200f,
        dispersion = 0.10f,
        specular = 0.22f,
        jelly = 0f,
    )

    /** 果冻：低频、低阻尼、大尺度整体形变 + 径向挤压。 */
    val JELLY = Params(
        c2 = 0.09f,
        damp = 0.997f,
        level = 0.55f,
        refract = 7000f,
        dispersion = 0.04f,
        specular = 0.35f,
        jelly = 1f,
    )

    // ───────────────────────────── 着色器 ─────────────────────────────

    /**
     * 全屏 quad。`vUv` 的 **v=0 在屏幕底部**（标准 GL 方向）。
     *
     * sim 纹理与 vUv 同一方向写、同一方向读，所以两边天然一致；
     * 唯一需要翻的是 base 图（Bitmap 第 0 行是顶部），在 DRAW 里用 `1.0 - uv.y` 处理。
     */
    const val VERT = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    /**
     * 模拟 pass。
     *
     * * `uPrev` 的 R/G 是上一帧的 h/v（`*2-1` 解码）；
     * * 重力液面用**软弹簧**把平均水面拉向倾斜平面，而不是直接加到 h 上 ——
     *   直接加会让「倾斜」和「波动」混在一起，松手后永远回不到平面；
     * * 选区外 `h`、`v` 一起清零：只清 h 的话速度会把波又推回选区里。
     */
    const val SIM = """
        precision highp float;
        varying vec2 vUv;

        uniform sampler2D uPrev;
        uniform vec2  uTexel;
        uniform float uC2;
        uniform float uDamp;
        uniform vec2  uTilt;
        uniform float uLevel;
        uniform float uAspect;
        uniform vec4  uSplat0;
        uniform vec4  uSplat1;
        uniform float uMaskOn;
        uniform vec2  uMaskCenter;
        uniform float uMaskRadius;

        float dec(float x) { return x * 2.0 - 1.0; }

        // 软椭圆选区（见 LiquidMask）：1 = 全图；椭圆外压到 0（没有液体）。
        // v 轴乘 aspect 才是屏幕上的正圆 —— 与 splat 的修正同一个理由。
        float maskAt(vec2 uv) {
            if (uMaskOn < 0.5) return 1.0;
            vec2 d = (uv - uMaskCenter) * vec2(1.0, uAspect) / max(uMaskRadius, 1e-3);
            return 1.0 - smoothstep(0.82, 1.0, length(d));
        }

        // xy = uv, z = 振幅（>0 表示往下压）, w = sigma（以「屏幕宽度」为单位的半径）
        float splat(vec4 s) {
            if (s.z <= 0.0) return 0.0;
            // uv 两轴的像素跨度不同（竖屏 H≈2.2W），v 轴要乘 aspect 才是屏幕上的正圆
            vec2 d = (vUv - s.xy) * vec2(1.0, uAspect);
            return s.z * exp(-dot(d, d) / max(s.w * s.w, 1e-6));
        }

        void main() {
            vec4 prev = texture2D(uPrev, vUv);
            float h = dec(prev.r);
            float v = dec(prev.g);

            float hl = dec(texture2D(uPrev, vUv - vec2(uTexel.x, 0.0)).r);
            float hr = dec(texture2D(uPrev, vUv + vec2(uTexel.x, 0.0)).r);
            float hu = dec(texture2D(uPrev, vUv - vec2(0.0, uTexel.y)).r);
            float hd = dec(texture2D(uPrev, vUv + vec2(0.0, uTexel.y)).r);
            float lap = hl + hr + hu + hd - 4.0 * h;

            v = (v + uC2 * lap) * uDamp;

            // 液面基准：倾斜时水面保持水平（屏幕上是线性斜面），软弹簧拽过去。
            // 系数 0.02 是「倾倒后 ~0.5s 稳住」的手感；调大容易和波动方程打架（整幅持续呼吸）。
            float level = (uTilt.x * (vUv.x - 0.5) + uTilt.y * (vUv.y - 0.5)) * uLevel;
            v += (level - h) * 0.02;

            h += v;
            h -= splat(uSplat0) + splat(uSplat1);

            float m = maskAt(vUv);
            h *= m;
            v *= m;

            h = clamp(h, -1.0, 1.0);
            v = clamp(v, -1.0, 1.0);
            gl_FragColor = vec4(h * 0.5 + 0.5, v * 0.5 + 0.5, 0.0, 1.0);
        }
    """

    /**
     * 合成 pass。
     *
     * * 梯度用中心差分，**一个 sim 纹素**为步长，再除回像素口径（`uScreen`）——
     *   否则竖屏上横向位移会比纵向弱一倍多；步长越大越「橡皮泥」，越小越「玻璃」，
     *   改步长要连 `refract` 一起调；
     * * 色散不是简单加饱和：三通道采样位置错开，边缘自然出彩边；
     * * 高光用梯度当法线（`-gpx, 1`），比单独存一张法线图便宜得多。
     */
    const val DRAW = """
        precision mediump float;
        varying vec2 vUv;

        uniform sampler2D uBase;
        uniform sampler2D uSim;
        uniform vec2  uTexel;
        uniform vec2  uScreen;
        uniform vec2  uImgScale;
        uniform float uMaskOn;
        uniform vec2  uMaskCenter;
        uniform float uMaskRadius;
        uniform float uRefract;
        uniform float uDisp;
        uniform float uSpec;
        uniform float uJelly;
        uniform vec3  uLight;

        float H(vec2 uv) { return texture2D(uSim, uv).r * 2.0 - 1.0; }

        // 与 SIM 里同一套软椭圆选区；这里的 aspect 从 uScreen 现算。
        float maskAt(vec2 uv) {
            if (uMaskOn < 0.5) return 1.0;
            float aspect = uScreen.y / uScreen.x;
            vec2 d = (uv - uMaskCenter) * vec2(1.0, aspect) / max(uMaskRadius, 1e-3);
            return 1.0 - smoothstep(0.82, 1.0, length(d));
        }

        void main() {
            float hl = H(vUv - vec2(uTexel.x, 0.0));
            float hr = H(vUv + vec2(uTexel.x, 0.0));
            float hu = H(vUv - vec2(0.0, uTexel.y));
            float hd = H(vUv + vec2(0.0, uTexel.y));
            float h  = H(vUv);

            // 梯度换成「每像素」口径：uv 两轴的像素跨度不同，直接对 uv 求导会让
            // 竖屏上的横向位移比纵向弱一倍多。这里除回像素，再乘 uRefract 得像素位移。
            vec2 gpx = vec2(
                (hr - hl) / (2.0 * uScreen.x * uTexel.x),
                (hd - hu) / (2.0 * uScreen.y * uTexel.y)
            );

            float m = maskAt(vUv);

            // CENTER_CROP + 安全余量（CPU 算好），再翻 y：Bitmap 第 0 行在顶部。
            vec2 uv = (vUv - 0.5) * uImgScale + 0.5;
            uv.y = 1.0 - uv.y;

            // 果冻模式：整幅图跟着 h 做一次径向挤压（整体「捏一下」的观感）
            if (uJelly > 0.5) {
                uv = (uv - 0.5) * (1.0 + h * 0.06) + 0.5;
            }

            vec2 d = gpx * uRefract / uScreen * m;
            vec3 col;
            col.r = texture2D(uBase, uv + d * (1.0 + uDisp)).r;
            col.g = texture2D(uBase, uv + d).g;
            col.b = texture2D(uBase, uv + d * (1.0 - uDisp)).b;

            vec3 n = normalize(vec3(-gpx * 120.0, 1.0));
            float spec = pow(max(dot(n, normalize(uLight)), 0.0), 28.0);
            col += vec3(spec * uSpec);

            gl_FragColor = vec4(col, 1.0);
        }
    """

    // ───────────────────────────── GL 小工具 ─────────────────────────────

    /** 编译 + 链接；失败返回 0 并把 GL 日志打到模块日志里（与 SpatialProbe 同一套）。 */
    fun program(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs) ?: return 0
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs) ?: return 0
        val p = GLES20.glCreateProgram()
        if (p == 0) {
            HookRuntime.log("LIQUID glCreateProgram 失败")
            return 0
        }
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            HookRuntime.log("LIQUID program link 失败：${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        // shader 挂到 program 上之后就可以删（引用计数在 program 里）
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun shader(type: Int, src: String): Int? {
        val s = GLES20.glCreateShader(type)
        if (s == 0) {
            HookRuntime.log("LIQUID glCreateShader($type) 失败")
            return null
        }
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            HookRuntime.log("LIQUID shader 编译失败：${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return null
        }
        return s
    }

    /**
     * 取一次 glGetError 并打日志。只在启动 / 首帧调用 —— **不要逐帧调**，
     * `glGetError` 会强制同步管线，等于把 60fps 砍半。
     */
    fun checkGl(tag: String) {
        val e = GLES20.glGetError()
        if (e != 0) HookRuntime.log("LIQUID glError $tag=0x${Integer.toHexString(e)}")
    }
}
