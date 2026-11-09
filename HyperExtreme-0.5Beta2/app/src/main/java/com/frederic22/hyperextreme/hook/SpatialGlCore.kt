package com.frederic22.hyperextreme.hook

/**
 * 空间壁纸的**渲染常量 / shader / 几何公式** —— 两条渲染链的唯一来源。
 *
 * 为什么单列一个文件（2026-10-30，v1.5.82）：
 *
 * * [SpatialRenderer] —— LSPosed 链，自建 SC 再 reparent 到系统壁纸 leash；
 * * `com.frederic22.hyperextreme.wallpaper.SpatialWallpaperService` —— 系统壁纸服务链（免 root）。
 *
 * 两条链的 EGL 与挂载方式完全不同，但**看到的东西必须一模一样**。所以
 * shader 文本、球面几何、姿态量程这些"数值敏感"的部分只在这里存一份 ——
 * 两边各写一遍必然漂移（《07》十六条的教训）。
 *
 * `SpatialRenderer` 里保留同名 `private const val` 别名指向这里，内部引用零改动；
 * 这样这次重构对已验证跑通的 LSPosed 链是**纯搬运**，不碰任何逻辑。
 */
internal object SpatialGlCore {

    // ─────────────────────────── 常量 ───────────────────────────

    /** 层数上限：与分层侧一致（2026-10-24 用户定案 10 层）。 */
    const val MAX_LAYERS = 10

    /**
     * 球面垂直半角（弧度）= **18°**。
     *
     * 边缘/中心的位移比 = `tan(18°)/18°rad ≈ 1.05` —— 极轻的球面感，几乎不压缩边缘。
     * 30° 时这个比值是 1.10，垂直方向的边缘压缩达 7.3%（水平只有 1.7%，因为水平半角小），
     * 用户实测"图片被拉长"（2026-10-24）⇒ 降到 18°，垂直压缩只剩 2.6%。
     */
    const val SPHERE_HALF_V_RAD = 0.3142f

    /**
     * 球面安全区：图像角范围 / 屏幕角范围 —— 决定 **uv 余量** `0.5(1 − 1/S)`。
     *
     * 需求 = `0.5 · PAN_RAD · k_max / spanX`（水平 span 最小，最先越界）。
     * `k` 的物理上限是 `K_MIN + (1−K_MIN)·WARP_GAIN = 0.15 + 0.85×0.55 = 0.6175`
     * （不是 1），所以 1.15 给 0.0652 余量 vs 0.0451 需求 ⇒ **1.45 倍，够用**。
     * 若以后调大 `WARP_GAIN` 或 `PAN_RAD`，必须重算这一条。
     */
    const val SPHERE_SAFE = 1.15f

    /**
     * **满量程角位移（弧度）** —— 2026-10-28 从「NDC 空间」改成「弧度口径」，两向各向同性。
     *
     * `0.02434 rad × 4108 px/rad = 100px`（水平 / 垂直都是 100px @2640 屏）。
     * 位移 = `nx · PAN_RAD / span` ⇒ 真实角位移 = `PAN_RAD` ⇒ 两向像素位移相等。
     */
    const val PAN_RAD = 0.02434f

    /**
     * **选择性融合的深度差阈值**（2026-10-26 用户定案）。
     *
     * 主体和背景之间是真实断崖 ⇒ 不融合；mask 多带的一圈背景深度相近 ⇒ 跟随背景。
     * 实测主体与背景的 k 差通常 0.2~0.4，取 0.08 ⇒ 只有 Δk<0.08 的近深度区域才融合。
     */
    const val DK_TH = 0.08f

    /** 最大角度满量程（弧度）= **10°**：手腕自然转动就能打到满幅。 */
    const val FULL_SCALE_RAD = 0.1745f

    /** 陀螺仪一阶低通时间常数（毫秒）—— 与视差链同一个值。 */
    const val GYRO_TAU_MS = 50f

    /** 判定"正在动"的角速度阈值（rad/s）。低于它累计 [STOP_HOLD_MS] 就归位。 */
    const val MOVE_EPS = 0.12f

    /** 连续静止多久触发归位（毫秒）。 */
    const val STOP_HOLD_MS = 60L

    /** 归位时长（毫秒，ease-out quartic）。 */
    const val HOME_DURATION_MS = 750f

    /** 重绘死区（弧度）：姿态变化小于它就不重绘（约 0.04px 的屏幕位移）。 */
    const val DRAW_EPS = 1e-4f

    // ─────────────────────────── 着色器 ───────────────────────────

    const val VERT = """
        attribute vec2 aPos;
        varying vec2 vNdc;
        void main() {
            vNdc = aPos;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    /**
     * 片元：屏幕 NDC → 球面角 → 减去位移 → 纹理 UV。
     *
     * **逆向（pull）写法**：每个输出像素都能采到颜色，不会出洞。
     * `depth.png` 里存的是**已经塑形好的 k 场**（锚点、归一、背景化补全、Lipschitz 限幅
     * 全在生成侧做完，见 `SpatialLayers`「八、位移场」），这里直接取。
     *
     * 两条场各管一层：`.r` = 背景场（主体区已被背景深度补全）、`.a` = 主体场（原始深度）。
     * 主体层是独立 alpha 层画在背景之上，轮廓由 alpha 决定、不由位移场决定。
     */
    const val FRAG = """
        precision highp float;
        varying vec2 vNdc;
        uniform sampler2D uTex;
        // 位移场：R = 背景场，G = 主体场（LUMINANCE_ALPHA 上传后分别是 .r / .a）
        uniform sampler2D uDepth;
        uniform vec2 uK;        // 屏幕 NDC → 角度：tan(半角) 与 aspect
        uniform vec2 uSpan;     // 图像覆盖的球面半角（弧度）
        uniform vec2 uShift;    // **满量程**位移，已按 span 归一化到 q0 空间（= PAN_RAD/span）
        uniform float uOpaque;
        uniform float uUseDepth;    // 1 = 逐像素取 k 场；0 = 刚性层
        uniform float uRigidK;      // 刚性层系数（无场时的兜底）
        uniform float uDepthCh;     // 0 = 背景场(.r)，1 = 主体场(.a)
        uniform float uDkTh;        // 选择性融合的深度差阈值（|Δk|）
        uniform float uDim;         // AOD 压暗系数（1 = 原亮度，0 = 全黑）
        // 显示区域（2026-11-04）：uv' = uv * uUvScale + uUvOffset
        // scale = 1/zoom，offset = c − 0.5/zoom（见 `SpatialCrop`）。
        // ⚠️ 深度场与颜色**必须用同一套变换**，否则位移场和图像错位、主体会飘。
        uniform vec2 uUvScale;
        uniform vec2 uUvOffset;
        void main() {
            vec2 q0 = vec2(atan(vNdc.x * uK.x) / uSpan.x, atan(vNdc.y * uK.y) / uSpan.y);
            // ⚠️ y 必须反转：GL 的 v=0 在纹理底部，而屏幕 NDC 的 y=+1 在顶部
            //（2026-10-23 实测：直接 0.5+q*0.5 会导致整幅壁纸上下颠倒）。
            vec2 uv0 = vec2(q0.x * 0.5 + 0.5, 0.5 - q0.y * 0.5);
            uv0 = uv0 * uUvScale + uUvOffset;

            // **逐像素位移**：位移不按「层」给定值，而是按每个像素自己的 k 算
            //（每层一个常数 ⇒ 深度只在层边界跳变 ⇒ 转动时层间割裂）。
            float k = uRigidK;
            if (uUseDepth > 0.5) {
                vec4 f = texture2D(uDepth, uv0);
                // .r = 背景场（主体区已被背景深度补全）、.a = 主体场（原始深度）
                //
                // **选择性融合**：主体与背景之间是真实断崖 ⇒ 不融合（保持硬遮挡）；
                // 但 mask 会多带一圈背景，那部分两层深度相近 ⇒ 应该跟随背景。
                // 判据是**深度差**（= |Δk|），不是几何距离 ⇒ 融合带宽度自动由内容决定。
                float kb = f.r;
                float kf = f.a;
                float g = 1.0 - clamp(abs(kb - kf) / max(uDkTh, 1e-3), 0.0, 1.0);
                k = (uDepthCh > 0.5) ? mix(kf, kb, g) : kb;
            }
            k = clamp(k, 0.0, 1.0);

            vec2 q = q0 - uShift * k;
            vec2 uv = vec2(q.x * 0.5 + 0.5, 0.5 - q.y * 0.5);
            uv = uv * uUvScale + uUvOffset;
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                gl_FragColor = vec4(0.0);
                return;
            }
            vec4 c = texture2D(uTex, uv);
            // 纹理是**预乘**的（RGB 已乘过 alpha），所以这里直接输出，混合用 GL_ONE。
            // 背景层强制不透明，保证任何位置都有实底。
            //
            // AOD 压暗在**绘制阶段**乘 uDim（系统壁纸服务链由系统自己压暗，传 1.0 即可）：
            // 合成阶段的 `Transaction#setColor` 在 HyperOS 上实测**仍会发白**（2 参 3 参都一样）。
            gl_FragColor = vec4(c.rgb * uDim, mix(c.a, 1.0, uOpaque));
        }
    """

    // ─────────────────────────── 几何 ───────────────────────────

    /** 屏幕 → 球面的 uniform 值。字段名与 GLSL 里的 `uK` / `uSpan` 一一对应。 */
    class Geometry(val kx: Float, val ky: Float, val spanX: Float, val spanY: Float) {

        /**
         * **屏幕当前看得见的 uv 半宽 / 半高**（zoom = 1 时；实际占用还要再除以 zoom）。
         *
         * 由 shader 那段反推：`q0 = atan(ndc·uK) / uSpan`，`uv = q0·0.5 + 0.5`
         * ⇒ `ñdc = ±1` 时 `q0 = ±atan(uK)/uSpan` ⇒ uv 半宽 = `atan(kx)/spanX · 0.5`。
         *
         * 用途：「显示区域」的平移边界（2026-11-04）。中心必须落在
         * `[half, 1−half]` 内才不会采到图外，`half` 就是它 —— 而不是 `0.5/zoom`
         * （那个在 zoom = 1 时等于 0.5，等于把平移自由度全锁死）。
         */
        val visibleHalfX: Float get() = kotlin.math.atan(kx) / spanX * 0.5f

        val visibleHalfY: Float get() = kotlin.math.atan(ky) / spanY * 0.5f

        /**
         * **每边要留给姿态位移的 uv 余量**（zoom = 1 时）。
         *
         * shader 里 `q = q0 − uShift·k`，`uShift = PAN_RAD / span` ⇒ uv 空间位移
         * = `0.5 · (PAN_RAD / span) · k`，再除以 zoom 才是最终占用的余量。
         *
         * `k` 的物理上限是 `K_MIN + (1−K_MIN)·WARP_GAIN = 0.6175`（见 [SPHERE_SAFE] 注释），
         * 不是 1 —— 用它算才能和 [SPHERE_SAFE] 的 0.0652 对上（1.45 倍余量）。
         */
        val shiftMarginX: Float get() = 0.5f * (PAN_RAD / spanX) * K_MAX
        val shiftMarginY: Float get() = 0.5f * (PAN_RAD / spanY) * K_MAX
    }

    /** `k` 场的物理上限（`K_MIN + (1−K_MIN)·WARP_GAIN`）；位移余量按它算。 */
    const val K_MAX = 0.6175f

    /**
     * 球面参数：屏幕垂直半角 = [SPHERE_HALF_V_RAD]，图像水平半角 = 垂直半角 × 图像宽高比。
     *
     * **cover + 安全区，两个方向同乘一个倍数**（2026-10-24 修「图片被拉长」）：
     * 旧写法在图像较窄时只把 spanX 撑大、spanY 跟着除，结果 spanX/spanY 偏离图像宽高比
     * ⇒ 纵向被多放大 7~8%。正确做法是先算"不放大时"的角范围（图像按自身宽高比贴球面），
     * 再按屏幕需求取一个统一的放大倍数 m，**两个 span 同乘 m**，比例永远等于 imgAspect。
     */
    fun geometry(screenW: Int, screenH: Int, layerW: Int, layerH: Int): Geometry {
        val aspect = screenW.toFloat() / screenH
        val imgAspect = layerW.toFloat() / layerH
        val tanV = kotlin.math.tan(SPHERE_HALF_V_RAD.toDouble()).toFloat()
        val screenHalfH = kotlin.math.atan(tanV * aspect.toDouble()).toFloat()
        val spanY0 = SPHERE_HALF_V_RAD              // 图像垂直半角（未放大）
        val spanX0 = SPHERE_HALF_V_RAD * imgAspect  // 图像水平半角（按图像宽高比）
        val needX = screenHalfH / spanX0            // 水平方向需要放多少倍才能盖住屏幕
        val needY = SPHERE_HALF_V_RAD / spanY0      // 垂直方向（= 1）
        // SAFE 给位移留余量：uv 余量 = 0.5(1 − 1/SAFE)，需 ≥ 0.5·PAN_RAD·k_max/spanX。
        val m = maxOf(needX, needY) * SPHERE_SAFE
        return Geometry(tanV * aspect, tanV, spanX0 * m, spanY0 * m)
    }
}
