package com.frederic22.hyperextreme.hook

/**
 * 光栅壁纸（已升级为 OriginOS 液态流体壁纸）的**渲染常量与着色器**。
 *
 * 在原版「两张全屏图 + 陀螺仪角度控制 + 锁屏/桌面/AOD 全套生命周期」的基础上，
 * 引入 Domain Warping（双层域扭曲流体）与流体波峰高光：
 * - 组内 1 张图时：对原图与 OriginOS 流体色板进行实时液态扭曲与陀螺仪推浪交互；
 * - 组内多张图时：左右倾斜手机以液态翻涌边界在多张图之间丝滑过渡。
 */
internal object RasterGlCore {

    const val PAN_MAX_RAD = 0.4363323f

    fun radOf(deg: Int): Float = Math.toRadians(deg.toDouble()).toFloat()

    const val GYRO_TAU_S = 0.05f
    const val GYRO_GATE = 0.01f
    const val BAND = 0.25f

    const val SNAP_DELAY_MS = 80L
    const val SNAP_MS = 250L
    const val HOME_WIPE_MS = 450L
    const val HOME_WAIT_MS = 1500L
    const val DRAW_EPS = 1e-4f

    const val VERT = """
attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val FRAG = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform vec4 uFromUv;
uniform vec4 uToUv;
uniform float uT;
uniform float uDir;
uniform float uBand;
uniform float uDim;
uniform float uTime;
uniform float uPan;

// 2D 伪随机哈希与平滑噪声
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i + vec2(0.0, 0.0)), hash(i + vec2(1.0, 0.0)), u.x),
        mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}

// 分形布朗运动（3 层叠加构造丝绸流体纹理）
float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.8, -0.6, 0.6, 0.8);
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p = rot * p * 2.02;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 uv = vUv;
    // 修正宽高比让流体漩涡呈自然圆形
    vec2 p = vec2(uv.x, uv.y * 2.1) * 2.4;

    // 结合陀螺仪倾角 uPan 与时间 uTime 产生液态推浪偏移
    vec2 gyroPush = vec2(uPan * 0.85, sin(uPan * 2.0) * 0.35);

    // 第一层域扭曲 q
    vec2 q = vec2(
        fbm(p + gyroPush + 0.12 * uTime),
        fbm(p + vec2(1.7, 9.2) - 0.10 * uTime)
    );

    // 第二层域扭曲 r（OriginOS 核心流体折光感来源）
    vec2 r = vec2(
        fbm(p + 1.8 * q + vec2(8.3, 2.8) + 0.15 * uTime),
        fbm(p + 1.8 * q + vec2(1.2, 5.4) + 0.13 * uTime)
    );

    float f = fbm(p + 1.6 * r);

    // 对原壁纸采样坐标施加液态折射扭曲（幅度适中，既保留壁纸色彩又有流体涌动感）
    vec2 liquidOffset = (r - 0.5) * 0.08 + (q - 0.5) * 0.04 + vec2(uPan * 0.04, 0.0);
    vec2 uvFrom = clamp(uv * uFromUv.xy + uFromUv.zw + liquidOffset, 0.0, 1.0);
    vec2 uvTo   = clamp(uv * uToUv.xy   + uToUv.zw   + liquidOffset * 1.15, 0.0, 1.0);

    // 液态融合过渡带：在原本擦除坐标 e 上叠加流体噪声 (f - 0.5)，让交界线呈液体波浪状
    float e = ((uDir > 0.0) ? uv.x : (1.0 - uv.x)) + (f - 0.5) * uBand * 1.1;
    float edge = -uBand * 0.6 + uT * (1.0 + uBand * 1.2);
    float a = smoothstep(edge - uBand * 0.5, edge + uBand * 0.5, e);
    a = 1.0 - a;

    vec4 cFrom = texture2D(uFrom, uvFrom);
    vec4 cTo   = texture2D(uTo,   uvTo);
    vec4 baseColor = mix(cFrom, cTo, clamp(a, 0.0, 1.0));

    // OriginOS 标志性的流光层叠高光与色彩漫反射
    vec3 liquidTint = mix(
        vec3(0.12, 0.30, 0.78),
        vec3(0.58, 0.22, 0.85),
        clamp(f * f * 2.5, 0.0, 1.0)
    );
    liquidTint = mix(liquidTint, vec3(0.08, 0.82, 0.92), clamp(length(q) * 0.8, 0.0, 1.0));

    // 波峰柔光高光
    float sheen = smoothstep(0.48, 0.88, f) * 0.22;
    vec3 finalRgb = mix(baseColor.rgb, liquidTint, 0.22) + vec3(sheen);

    // AOD 压暗
    gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0) * uDim, baseColor.a);
}
"""
}