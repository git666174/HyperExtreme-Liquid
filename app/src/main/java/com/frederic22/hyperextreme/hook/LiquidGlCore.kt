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
 * ## 为什么是速度式
 *
 * 只需要一张纹理（R=h、G=v）；阻尼直接乘在 v 上，不会像 `h' = 2h - h0 + c²∇²h`
 * 那样把阻尼变成「改波速」，参数更好调。
 *
 * ## 8 位高度场的两个真机教训（2026-11-09）
 *
 * 1. **高频噪声**：每帧把 h 写回 RGBA8 都会带 ±1/510 的量化误差，而波动方程
 *    对最短波长（交替正负）增益最大 ⇒ 这些误差会被放大成「满屏细密抖动」。
 *    解法是加一步**粘度**（[uVisc]）：对 h 做一次邻居平均插值，只压短波
 *    （λ=2 纹素每帧衰减 ~40%），大尺度波几乎不受影响（λ=64 纹素 ~0.2%/帧）。
 * 2. **永远停不下来**：`uDamp` 接近 1 时能量散得极慢，加上重力弹簧会持续注入能量，
 *    画面会一直「呼吸」。除了把默认阻尼调低，`LiquidRenderer` 还会在**无输入 2s 后
 *    把阻尼逐步压到 0.55**，强制收敛到平静水面。
 *
 * ## 稳定性
 *
 * 五点拉普拉斯显式积分要求 `c² ≤ 0.25`（归一化网格 dx=1）。水波 0.14、果冻 0.07。
 * **调大 c² 会直接炸成雪花**，不是「波更大」。
 */
internal object LiquidGlCore {

    /** 模拟宽度：256 已够（水面不需要清晰），再大只是白烧带宽。 */
    const val SIM_WIDTH = 256

    /** 一次最多注入两个触点。 */
    const val MAX_SPLATS = 2

    /**
     * 粘度（每帧把 h 向邻居平均拉多少）。
     *
     * 这是 8 位纹理下「画面像雪花」的解药，也是「水波太密」的手感旋钮：
     * 调大 ⇒ 只剩大波浪；调小 ⇒ 细节更多，但量化噪声会回来。0.10~0.18 是可用区间。
     */
    const val VISCOSITY = 0.14f

    /**
     * 一套物理 / 观感参数。水波与果冻的差别**全部在这几个数里**，
     * 不是两套代码 —— 这样切模式不会出现「换了条路径所以行为不一致」。
     */
    class Params(
        /** 波速平方（≤0.24，见类注释的稳定性）。 */
        val c2: Float,
        /** 每帧速度阻尼：越接近 1 越「荡得久」。 */
        val damp: Float,
        /** 液面跟随重力的强度：0 = 水永远平，1 = 完全跟着手机倾斜。 */
        val level: Float,
        /** 每 1.0 的「h/像素」梯度对应的**像素位移**。 */
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
     * 全部来自 `LiquidTune` —— 页面滑块改的就是这五个。
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

    /**
     * 水波默认（2026-11-09 按真机反馈调过一轮）：
     * 波速降下来、触点放大、阻尼加大、折射减弱 —— 用户反馈「太密、太吵」。
     */
    val WATER = Params(
        c2 = 0.14f,
        damp = 0.983f,
        level = 0.25f,
        refract = 3000f,
        dispersion = 0.08f,
        specular = 0.18f,
        jelly = 0f,
    )

    /** 果冻：低频、大尺度、回弹慢一点。 */
    val JELLY = Params(
        c2 = 0.07f,
        damp = 0.990f,
        level = 0.45f,
        refract = 4800f,
        dispersion = 0.03f,
        specular = 0.28f,
        jelly = 1f,
    )

    // ───────────────────────────── 着色器 ─────────────────────────────

    /** 全屏 quad。`vUv` 的 v=0 在屏幕底部（标准 GL 方向）。 */
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
     * * `uVisc` 是高频抑制，见类注释第 1 条；
     * * 重力液面用**软弹簧**把平均水面拉向倾斜平面（0.015 的系数，比原来的 0.02 更软，
     *   倾倒后约 0.8s 稳住，且不会持续给画面供能）；
     * * 选区 = 一张**屏幕空间**的遮罩纹理（涂抹产物），乘在 h/v 上。
     */
    const val SIM = """
        precision highp float;
        varying vec2 vUv;

        uniform sampler2D uPrev;
        uniform sampler2D uMaskTex;
        uniform vec2  uTexel;
        uniform float uC2;
        uniform float uDamp;
        uniform float uVisc;
        uniform vec2  uTilt;
        uniform float uLevel;
        uniform float uAspect;
        uniform vec4  uSplat0;
        uniform vec4  uSplat1;
        uniform float uMaskOn;

        float dec(float x) { return x * 2.0 - 1.0; }

        // 选区：屏幕空间遮罩（白 = 有液体）。uMaskOn=0 时直接返回 1。
        float maskAt(vec2 uv) {
            if (uMaskOn < 0.5) return 1.0;
            // Bitmap 第 0 行在顶部 ⇒ 纹理 v=0 是「屏幕上边」，采样要翻 y
            return texture2D(uMaskTex, vec2(uv.x, 1.0 - uv.y)).r;
        }

        // xy = uv, z = 振幅（>0 表示往下压）, w = sigma（以「屏幕宽度」为单位的半径）
        float splat(vec4 s) {
            if (s.z <= 0.0) return 0.0;
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
            float level = (uTilt.x * (vUv.x - 0.5) + uTilt.y * (vUv.y - 0.5)) * uLevel;
            v += (level - h) * 0.015;

            h += v;
            h -= splat(uSplat0) + splat(uSplat1);

            // 粘度：只压短波（量化噪声就在这个频段），见类注释第 1 条
            float avg4 = (hl + hr + hu + hd) * 0.25;
            h = mix(h, avg4, uVisc);

            float m = maskAt(vUv);
            h *= m;
            v *= m;

            h = clamp(h, -1.0, 1.0);
            v = clamp(v, -1.0, 1.0);
            gl_FragColor = vec4(h * 0.5 + 0.5, v * 0.5 + 0.5, 0.0, 1.0);
        }
    """

    /**
     * 合成 pass。梯度换成「每像素」口径，再乘 `uRefract` 得像素位移。
     */
    const val DRAW = """
        precision mediump float;
        varying vec2 vUv;

        uniform sampler2D uBase;
        uniform sampler2D uSim;
        uniform sampler2D uMaskTex;
        uniform vec2  uTexel;
        uniform vec2  uScreen;
        uniform vec2  uImgScale;
        uniform float uMaskOn;
        uniform float uRefract;
        uniform float uDisp;
        uniform float uSpec;
        uniform float uJelly;
        uniform vec3  uLight;

        float H(vec2 uv) { return texture2D(uSim, uv).r * 2.0 - 1.0; }

        float maskAt(vec2 uv) {
            if (uMaskOn < 0.5) return 1.0;
            // Bitmap 第 0 行在顶部 ⇒ 纹理 v=0 是「屏幕上边」，采样要翻 y
            return texture2D(uMaskTex, vec2(uv.x, 1.0 - uv.y)).r;
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

    /** 取一次 glGetError 并打日志。只在启动 / 首帧调用，不要逐帧调。 */
    fun checkGl(tag: String) {
        val e = GLES20.glGetError()
        if (e != 0) HookRuntime.log("LIQUID glError $tag=0x${Integer.toHexString(e)}")
    }
}
