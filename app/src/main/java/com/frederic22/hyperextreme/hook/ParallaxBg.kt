package com.frederic22.hyperextreme.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「视差壁纸效果」在 `com.android.systemui` 里的整条链（2026-10-12 起）——**独立链**：
 * 不碰任何系统文件、不看保护期、与"替换模板文件"/"景深"/"强制深浅"三条链互不相关。
 *
 * ## 它是什么
 *
 * 在锁屏树里放一个自己的全屏 ImageView（背景层），只盖住系统壁纸：
 *
 * ```
 * 系统壁纸（独立 SurfaceControl）
 *   └ SystemUI 窗口
 *       ├ 窗口根 NotificationShadeWindowView
 *       │   └ [我们·整窗态] 背景 ImageView（index 0）
 *       └ keyguard_background_layer← 我们·树内态插在它的 index 0
 *           ├ [我们] 背景 ImageView（CENTER_CROP + 额外放大）
 *           └ 装饰 / 时钟 / 前景层（主体 deducted_image_view）/ 信息层 …
 * ```
 *
 * 这样"自定义背景"和"主体（现有注入链的 crop_subject）"在同一个 View 树里，
 * 相对位移 = 两个 View 各设 translation —— 层级关系见用户对齐结论。
 *
 * ## 状态权威在模块 App（provider）
 *
 * 开启状态（`MaskPrefs.KEY_PARALLAX_ACTIVE`）与图片 revision 都从 `MaskProvider.call("config")`
 * 读（3 秒缓存；广播路径**强制刷新**）。App 的指令是"先写状态、再发广播"，所以这里
 * 一律**以 config 为准**、广播只当触发信号 —— 两边永远不会互相打架。
 *
 * 读不到 config（模块被禁用 / 未激活 / provider 挂了）按「未开启」处理：
 * 移除背景层、复位主体位移 —— 卸载模块后最坏也只是"下次 SystemUI 重启前残留一层"，
 * 自愈一旦跑起来就把现场收干净。
 *
 * ## 生命周期
 *
 * * **装**：`HookEntry.installSystemUiHooks` 里挂 `View#onAttachedToWindow`（认 id 名，
 *   与 LockScreenButton 同招）；广播接收器借高频的入窗事件幂等安装（与 ScreenFlash 同招）。
 * * **自愈**：锁屏视图每次重建（SystemUI 重启 / 配置变化）都会重新走 `onAttachedToWindow`,
 *   命中 `keyguard_background_layer` / `deducted_image_view` 的 id 时做一次全量 `ensure` ——
 *   所以"背景层丢了""位移丢了"都会自己补回来。
 * * **收尾**：用户点「关闭替换」（config.active=false）→ 移除层 + 主体位移归零 + 传感器断开。
 *
 * ## 挂载点（v22 起双挂载点动态切换）
 *
 * ```
 * 灭屏   → 整窗 INVISIBLE（等下次亮屏）
 * 亮屏   → 整窗 index 0（背景先到、无交叉混合）→ 淡入完成后搬进锁屏树
 * 树内   → 编辑器缩放 / 解锁退场 / 上拉全部由系统继承带动（历史行为，无需自研跟随）
 * 灭屏稳定 → 搬回整窗
 * ```
 *
 * 依据（真机逐层采样）：亮灭屏淡入只有 `KeyguardPanelView` × `HyperOSKeyguardRootView`
 * 两层在动，窗口根恒 α=1。挂树内会继承两层淡入连乘 → 半透明期与下层系统壁纸交叉混合
 * （构图差＝用户说的"放大感"）；挂整窗则完全脱离锁屏树过渡——但那样解锁退场时图层"钉在原地"，
 * 内容清空变成凭空消失（"闪切"）。故两者按时机切换，详见 [insertIfNeeded] / [enterTreeNow]。
 *
 * ## 版本履历（2026-10-12 ~ 10-14，v1 → v22.6）
 *
 * 逐版存档见《04-功能设计存档.md》需求三；此处只留影响当前结构的关键转折：
 *
 * * **v1–v6 打基础**：静态位移验证图层关系 → 密码界面模糊图替换（本场景不触发，已移除）→
 *   连续模糊同步（`doBlurAndDim`）→ 传感器实时化 → 自动零位（修"正常握姿顶格导致上下无响应"）
 *   → 画质修复 + 调参（幅度 45/25、放大 1.15/1.10）。
 * * **v7–v11 内容抑制与副本**：玻璃包含实验（结论：玻璃输入 = 窗口后内容，本层默认不在其中）
 *   → 主体副本进玻璃（自绘合成，不改视图结构）→ 副本对齐修复（`transformMatrixToGlobal`
 *   需先 `reset()`，否则变换逐帧累积放大）→ 内容抑制（层不可见时 `onDraw` 完全不绘制，
 *   防解锁后下拉通知中心捕获）→ 副本补偿改为**用两视图公开属性手工重建自身变换**（链读在实机失效）。
 * * **v12–v17 全部参数运行时化**：位移幅度 / 放大倍率 / 反向开关 / 归位等待 / 满量程角，
 *   走 provider config 通道（3s TTL，最迟 ~4s 生效，无需重启）；陀螺混合（补"绕重力轴转动"
 *   这个重力测不到的自由度）+ 低通调小 + 陀螺解耦显示（响应 ~0.05s）。
 * * **v18–v20 抑制时序**：清空 = 桌面态立即 / 不可见需持续 600ms（吸收系统 `updateVisibility`
 *   的 7~17ms 瞬闪）；恢复 = 可见即恢复；**灭屏期间跳过清空**（亮屏零延迟）。
 * * **v21a 搬家 + 层闸门**：挂窗口根 index 0 脱离亮灭屏淡入连乘，可见性由"层闸门"自管
 *   （亮屏立即显示；灭屏 / 解锁退场按真值曲线自淡出）。
 * * **v22 双挂载点**（一次修两个 bug）：进编辑不跟随 + 解锁闪切 → 亮屏稳定入树、灭屏回整窗，
 *   两者都恢复"树内历史行为"，新代码只剩模式切换本身（编辑器复刻器与解锁曲线方案作废）。
 *   提前入树触发源 = 「自定义锁屏」按钮出现 / `setEditorState` 非 IDEL。
 * * **v22.1 解锁退场渐隐**：state 1/2→0（系统判定进桌面）时 0.2s 线性渐隐，清空推迟到渐隐完成。
 * * **v22.3 传感器合成「ωy + 反滚」**（三姿态取证后）：旧投影 ω·ĝ 随姿态按 cosθ 打折
 *   （1.01 / 0.57 / 0.006）且与 tilt 链相斗（"45° 不灵敏"＝先反向再翻正）。改取 **ωy**
 *   （三姿态满幅一致、竖放逐点不变）+ tilt 链**反滚**（θ=∫ωy 把重力滚回静止相位再与基准比较，
 *   纯滚动任意姿态恒零输出）→ 消除相斗与饱和。实测 tiltX 饱和率 0%。
 * * **v22.4 到顶归位**：单向快速转动把显示值顶到满幅后不再回落（旧投影版因打折不易顶满，
 *   故此前不显）。按用户提案"到顶即触发归位、任何归位都记录新基点"：持续满幅 [TOP_HOLD_MS]
 *   → 播归位动画（运动中不打断，`homingByTop`）+ **完成时按真实姿势重锚基准**（防死循环）。
 * * **v22.5 方向语义统一**：`SIGN_Y` 取反 → 上下左右均反方向；反向开关语义翻转
 *   （现在 = "使两者同向"，默认关 = 主体背景反方向）。
 * * **v22.6 参数调整**：到顶回正 1.2s → 0.5s；「最大幅度角度」上限 30° → 45°。
 * * **v1.0.3（本次）**：清理全部临时诊断设施（祖先链采集 D1 / 传感器采样 S / 副本诊断 /
 *   编辑器与解锁取证日志）—— 验证链路已闭环，不再保留。
 */
internal object ParallaxBg {

    const val ACTION = "com.frederic22.hyperextreme.action.PARALLAX_BG"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ON = "on"

    /** 「视差效果覆盖桌面」选项的独立通知（2026-10-21）：只改桌面层级，不动锁屏视差。 */
    const val EXTRA_COVER = "cover_on"

    /** 调试（v10 自测）：合成倾角注入的 extra——`am broadcast … --ef tilt_x 0.8 --ef tilt_y 0`；值 >1.5 = 释放。 */
    const val EXTRA_TILT_X = "tilt_x"
    const val EXTRA_TILT_Y = "tilt_y"

    /** 调试（v11）：主体副本开关的 extra——`am broadcast … --ei copy 0/1`（A/B 验证副本对齐用）。 */
    const val EXTRA_COPY = "copy"

    // ---------------------------------------------------------------- 参数
    //
    // 2026-10-13 起：位移幅度 / 放大倍率 / 反向开关全部**运行时可调**（App「参数调整」卡 →
    // prefs → provider config 快照 → 本进程 3s TTL 轮询取用，最迟 ~4s 生效）。
    // 下面的 `DEFAULT_*` 只在两种场景兜底：① config 还没读到过一次；② 老数据没有这些键。

    /**
     * 位移满幅的默认值（屏幕像素）：主体比背景动得多（同向差系数）。
     * 实际运行时从 config 取 `subjectAmp` / `bgAmp`（App 侧已按"放大余量 −5px 的 50%~100% / 35%~100%"
     * 约束好范围）；config 缺失时回落到这两个值（= 2026-10-12 第四轮实机调参 45 : 25）。
     */
    private const val DEFAULT_SUBJECT_AMP_PX = 45f
    private const val DEFAULT_BG_AMP_PX = 25f

    /**
     * 主体（抠像层）常驻放大的默认值。运行时从 config 取 `subjectScale`（1~1.25）。
     * （默认 pivot = 视图中心；两个都是全屏层，等效于绕屏幕中心放大。）关闭功能时复位 1.0。
     */
    private const val DEFAULT_SUBJECT_SCALE = 1.15f

    /** 反向开关默认值：关 = 同向差系数（反向语义 = 只翻背景，主体保持现状）。 */
    private const val DEFAULT_REVERSE = false

    /**
     * 满量程（m/s²）：**相对零位的差值**达到这个数时位移打满。4.9 ≈ 30°，实机可调。
     * 水平轴取 gravity.x、垂直轴取 gravity.z；**两轴分设**（垂直轴的自然活动范围与水平不同；
     * 之前共用 4.9 导致正常握姿 g.z 直接顶格 —— 2026-10-12 实测修订为"自动零位 + 分轴"）。
     */
    private const val FULL_SCALE_X = 4.9f
    private const val FULL_SCALE_Y = 4.9f

    /**
     * 响应低通时间常数（ms）：一阶指数平滑 `α = dt / (dt + τ)`。
     * 越小越跟手、越大越柔。2026-10-13 调小（130 → 70）：用户反馈"画面响应比手慢一拍"——
     * 70ms 时倾斜跟上约 0.07s（原 0.13s）；若噪声 / 手部微颤变明显再回调。实机可调。
     */
    private const val TILT_TIME_CONSTANT_MS = 70f

    // ---------------------------------------------------------------- 陀螺混合（2026-10-13 加）
    //
    // 背景（用户实测）：竖着拿、"绕长边（竖直轴）"转动时重力传感器几乎无反应——那个转动
    // ≈ **绕重力轴**，重力在设备坐标系的投影**天然不变**（任何重力/加速度方案都测不到，物理必然）。
    // 处理：[onGyro] 取陀螺角速度在**重力方向**上的投影（= 绕重力轴的角速度，其余轴的重力链本就
    // 有响应、不重复计入），低通后积分成**累积转角**，归一化后叠加进水平偏移（v16 起**解耦显示**：
    // 不借道重力链的第二级低通）——原地转向即产生水平视差；停下后照旧走"静止归位"回中。
    // 对原有重力链的一切行为零影响（非绕轴转动时投影≈0）。

    /**
     * 「最大幅度角度」的默认值（度）—— 转动的满量程角。
     *
     * 2026-10-13 起**运行时可调**（App「参数调整」→「最大幅度角度」，15~45°、默认 25°）：
     * 运行时值从 config 取（`gyroDeg`），本常量只在 config 缺失时兜底。
     * 语义 = 绕重力轴转多少度达到满幅（≈ 与重力链的 30° 满程同量级）。
     */
    private const val DEFAULT_GYRO_DEG = 25f

    /**
     * 陀螺响应低通（ms）——2026-10-13 调小（100 → 50）。v16（方案 2）起**只剩这一级**
     *（解耦显示，不再级联 [TILT_TIME_CONSTANT_MS]）——转动链跟上约 0.05s。实机可调。
     */
    private const val GYRO_TIME_CONSTANT_MS = 50f

    /**
     * 静止判定里的"没在转"阈值（rad/s）——窗口内 |绕轴角速度| 全程小于它才算"没转动"。
     * 0.05 rad/s ≈ 2.9°/s：比"手持静止"经 100ms 低通后的残值（< 1°/s 量级）宽裕，
     * 又远小于人转手机的角速度（10°/s 起）——不会把正常转动漏判成静止。
     */
    private const val GYRO_EPS_RAD = 0.05f

    /** 绕轴转动的方向符号：实机若发现"转动方向与画面移动方向反了"，翻这里（-1 ↔ 1）。 */
    private const val GYRO_SIGN = 1f

    /**
     * "静止"判定 + 归位动画（2026-10-12 调参重做；2026-10-13 窗口时长**运行时可调**）：
     * 最近 `homeWait`（App「参数调整」→「恢复原位等待时长」，0.2~1s，默认 [DEFAULT_HOME_WAIT_MS]）内
     * 传感器归一化读数的**变化范围**小于 [STILL_EPS] 即视为静止 → 启动一次归位动画：
     * 从**当前偏移**出发，用 [HOME_DURATION_MS] 回到 0（无论偏移多远），
     * 速度"先快后慢"（偏移按 `(1−p)²` 衰减）；动画途中一有运动立即取消、继续跟随。
     *
     * STILL_EPS 取值由我先定：0.05（归一化）≈ 0.25 m/s² ≈ 1.4°，折算到画面 ≤1.8px ——
     * 嫌灵敏 / 迟钝实机调这一个数。
     */
    private const val DEFAULT_HOME_WAIT_MS = 500L

    /** 同上，秒表（config 里的 `homeWait` 单位是秒；运行时值取它，缺省回落这个默认）。 */
    private const val DEFAULT_HOME_WAIT_S = DEFAULT_HOME_WAIT_MS / 1000f

    private const val STILL_EPS = 0.05f

    /** 归位动画时长（ms）：固定 1s。 */
    private const val HOME_DURATION_MS = 1_000L

    /**
     * v22.4「到顶归位」：合成值（= tiltX + yawTilt = 显示值）达到满幅 ±[TOP_TRIGGER_COMBINED]
     * 视为"到顶"；**持续 [TOP_HOLD_MS] 仍在顶** → 判定为"快速转动卡死"（实机：单向快速转动时
     * yaw 单向累积顶到 ±1 后一直不回，画面被钉在边缘十几秒不动）→ 启动一次归位（运动中不取消）。
     * 迟滞：低于 [TOP_RELEASE_COMBINED] 才算"离开顶部"，防在阈值附近抖动反复计时。
     */
    private const val TOP_TRIGGER_COMBINED = 1.0f

    private const val TOP_RELEASE_COMBINED = 0.9f

    /** v22.6（用户 2026-10-14 定）：1.2s → **0.5s** —— 到顶后更快回正，减少"顶住不动"的滞留感。 */
    private const val TOP_HOLD_MS = 500L

    /** 两个轴的方向符号：实机若发现"倾斜方向与位移方向反了"，翻这里（-1 ↔ 1）。
     *  v22.5（用户 2026-10-14 定）：X 轴保持 -1f（左右转 = 反方向），Y 轴取反 → +1f，
     *  使**上下左右两个轴都是"反方向移动"**（此前 Y=-1f 表现为上下同方向）。 */
    private const val SIGN_X = -1f
    private const val SIGN_Y = 1f

    /**
     * 背景额外放大的默认值 —— 「任意比例的图 → 全屏填充（CENTER_CROP）+ 额外放大留位移余量」。
     * 运行时从 config 取 `bgScale`（1~1.25）；默认 1.10 时水平每侧余量 600×0.10 = 60px
     * （屏幕 1200 宽），覆盖背景位移 ±25px 仍有余。App 侧滑块已按"余量 −5px"约束幅度上限，
     * 放大不足导致露边的情况不会出现。
     */
    private const val DEFAULT_BG_SCALE = 1.1f

    /**
     * ⚠️ **实验开关（v7，2026-10-12）**：玻璃包含实验。
     *
     * 目的：验证"高级材质 / 柔光玻璃"的模糊能否把"窗口内下方"的我们的背景层纳入输入。
     * 做法：hook `View#disableMiBackgroundContainBelow(boolean)` 与 `NotificationUtil#applyElementViewBlend`，
     * 把系统"禁止包含"的调用后置翻转为"允许包含"。实验结论出来后：无效则置 false（本段可整体删除）。
     */
    private const val EXPERIMENT_CONTAIN_BELOW = true

    /** 状态探针采样间隔（帧）——约 0.3s 一次；只用于"解锁 / 下拉时我们的层在哪里"的诊断。 */
    private const val PROBE_INTERVAL_FRAMES = 15

    /**
     * 配置轮询帧间隔（帧）——2026-10-13 加：传感器 ~50Hz，25 帧 ≈ 0.5s 检查一次 config TTL。
     * 「参数调整」的 5 个参数最迟 ~4s（TTL 3s + 检查间隔）落到锁屏画面。
     */
    private const val CONFIG_POLL_FRAMES = 25

    /**
     * **主体副本**（v8，2026-10-12 用户对齐结论）：
     * 玻璃（主时钟等）的模糊输入 = "它下方的内容"，而主时钟下方只有本层（背景层）——
     * 把"主体（前景层抠像）"按实时位置/缩放**画进本层**，玻璃的模糊里自然带上主体；
     * 主体原件照旧清晰显示在玻璃上层、与副本重合（被遮/被玻璃模糊）——观感自然。
     * **不改变视图结构**（仍是同一个 View，只是绘制内容多一份）。
     */
    private const val ENABLE_SUBJECT_COPY = true

    /** 主体副本的对齐微调（px）：实机若发现副本与原件有整体偏移，调这两个值。 */
    private const val SUBJECT_COPY_OFFSET_X = 0f
    private const val SUBJECT_COPY_OFFSET_Y = 0f

    /** 配置缓存时长（与 LockScreenTone 同款：锁屏一轮刷新可能连续命中，没必要次次跨进程）。 */
    private const val CONFIG_TTL_MS = 3_000L

    /** 视图 id 名（`aapt2` 从真机 APK 资源表核对过）。 */
    private const val ID_BG_LAYER = "keyguard_background_layer"
    private const val ID_DEDUCTED = "deducted_image_view"

    /**
     * v22：「自定义锁屏」按钮 id 名（长按锁屏弹出菜单里的那颗，LockScreenButton 同款认人法）——
     * 它"出现"= 用户进入编辑意图 → 视差层提前入树（在编辑器过渡动画前就位）。
     */
    private const val ID_EDITOR_BUTTON_MAIN = "custom_lockscreen_button"
    private const val ID_EDITOR_BUTTON_ALT = "keyguard_settings_button"

    private const val SYSTEMUI_PKG = "com.android.systemui"

    // 连续模糊同步 —— 挂 `KeyguardPanelViewController#doBlurAndDim`（系统唯一写入 keyguardBlurRatio 的地方，2026-10-12 逆向核实）
    private const val CLS_PANEL_CONTROLLER = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val FIELD_BLUR_RATIO = "keyguardBlurRatio"

    // ---------------------------------------------------------------- 安装

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 装整条链（五部分互相独立）：
     * ① `View#onAttachedToWindow` 认目标视图（显示 / 位移 / 自愈）；
     * ② 连续模糊同步 hook（[installBlurSyncHook]，上滑到密码 / 图案界面的逐帧模糊）；
     * ③ 玻璃包含实验（[installGlassContainExperiment]，v7 实验版；开关关掉时视为"无需安装"）；
     * ④ 可见性回调（[installVisibilityHook]，v9：解锁后清空内容 / 回锁屏恢复）；
     * ⑤ 状态机 hook（[installStateHook]，v10：内容仅锁屏在场时存在，桌面恒清空）。
     * 返回"至少挂上一条"（供 HookEntry 汇总，与其它链的纪律一致：全失败不留幂等位）。
     */
    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        if (installed.get()) return true
        desktopLoader = loader
        val attachOk = installAttachHook(module, loader)
        val syncOk = installBlurSyncHook(module, loader)
        val expOk = installGlassContainExperiment(module, loader)
        val visOk = installVisibilityHook(module, loader)
        val stateOk = installStateHook(module, loader)
        val awayOk = installKeyguardAwayHook(module, loader)
        // 诊断（临时·取证场景①）：编辑器状态监听——**只观察**；成败不并入 any（安装语义不变）。
        installEditorStateHook(module, loader)
        val any = attachOk || syncOk || expOk || visOk || stateOk || awayOk
        if (any) installed.set(true)
        return any
    }

    /**
     * 挂 `View#onAttachedToWindow` 认两个目标视图。
     *
     * 与 LockScreenButton 的入窗 hook 是**两个独立 hook**，互不干扰（它的 `ready()` 短路只影响它自己）。
     */
    private fun installAttachHook(module: XposedModule, loader: ClassLoader): Boolean {
        val clazz = runCatching { Class.forName("android.view.View", false, loader) }.getOrNull()
        if (clazz == null) {
            HookRuntime.log("视差壁纸效果：未找到 android.view.View（loader=${loader.javaClass.simpleName}）")
            return false
        }
        val method = runCatching { clazz.getDeclaredMethod("onAttachedToWindow") }.getOrNull()
        if (method == null) {
            HookRuntime.log("视差壁纸效果：未找到 View#onAttachedToWindow")
            return false
        }
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    // 高频回调：整段包住，出异常也只记日志，绝不炸宿主的入窗流程。
                    runCatching { onViewAttached(chain.getThisObject() as? View) }
                        .onFailure { HookRuntime.log("视差壁纸效果：入窗处理异常：$it") }
                    return result
                }
            })
            HookRuntime.log("视差壁纸效果：已挂 View#onAttachedToWindow（认 $ID_BG_LAYER / $ID_DEDUCTED）")
            true
        }.getOrElse {
            HookRuntime.log("视差壁纸效果：挂 View#onAttachedToWindow 失败：$it")
            false
        }
    }

    /** `View#onVisibilityAggregated(boolean)` —— 聚合可见性变化回调（v9 内容抑制用）。 */
    private const val M_VISIBILITY_AGGREGATED = "onVisibilityAggregated"

    /**
     * 可见性回调 hook（v9）—— 服务"内容抑制"（见 [reconcileContentVisibility]）。
     *
     * 解锁时锁屏树整体隐藏、回锁屏再显示：我们的层会随祖先收到 `onVisibilityAggregated` 回调 ——
     * 与可见性变化**同帧**，当场把绘制内容清空 / 恢复（比 0.3s 轮询快得多，解锁瞬间即生效）。
     * 只对"本层的回调"动作（一次恒等比较）；其余视图的可见性回调无成本通过。
     */
    private fun installVisibilityHook(module: XposedModule, loader: ClassLoader): Boolean = runCatching {
        val clazz = Class.forName("android.view.View", false, loader)
        val method = clazz.getDeclaredMethod(M_VISIBILITY_AGGREGATED, Boolean::class.javaPrimitiveType)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    val view = chain.getThisObject() as? View
                    if (view != null && view === backdropRef?.get()) {
                        reconcileContentVisibility("可见性回调")
                    }
                }.onFailure { HookRuntime.log("视差壁纸效果：可见性处理异常：$it") }
                return result
            }
        })
        HookRuntime.log("视差壁纸效果：已挂可见性回调（内容抑制）")
        true
    }.getOrElse {
        HookRuntime.log("视差壁纸效果：挂可见性回调失败：$it")
        false
    }

    /** 传感器采样周期：60Hz（与 60Hz 渲染刷新对齐）。 */
    private const val SENSOR_PERIOD_US = 16_667

    /** 状态机实现类（真机 `re-glass` 核实：`setState(int, boolean)`，int：0=SHADE / 1=KEYGUARD / 2=SHADE_LOCKED）。 */
    private const val CLS_STATE_IMPL = "com.android.systemui.statusbar.StatusBarStateControllerImpl"

    private const val STATE_SHADE = 0
    private const val STATE_KEYGUARD = 1
    private const val STATE_SHADE_LOCKED = 2

    /**
     * 状态机 hook（v10）—— 记录"真·桌面 / 锁屏"状态，供内容抑制使用（见 [reconcileContentVisibility]）。
     *
     * 为什么还要它（可见性之外的第二道闸）：解锁后下拉通知中心时，我们的层可能被系统**瞬显**
     * （可见性回调瞬时翻 true，"恢复绘制"会误触发）——只靠可见性判断有漏洞；而状态机在桌面稳定为
     * SHADE(=0)，下拉不改变它，是"内容该不该存在"的权威信号。
     * 内容仅在 KEYGUARD(1) / SHADE_LOCKED(2)（锁屏在场）时允许绘制；初值未知时退回可见性判断。
     */
    private fun installStateHook(module: XposedModule, loader: ClassLoader): Boolean = runCatching {
        val clazz = Class.forName(CLS_STATE_IMPL, false, loader)
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "setState" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: error("未找到 setState(int, boolean)")
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    // 扫第一个 Int 参数（防个别框架把接收者混进 args）。
                    val state = chain.getArgs().firstOrNull { it is Int } as? Int ?: return@runCatching
                    if (state == lastStatusBarState) return@runCatching
                    val prev = lastStatusBarState
                    lastStatusBarState = state
                    HookRuntime.log("视差壁纸效果：状态机 state=$state（${stateName(state)}）")
                    // v22.1：解锁退场（1/2→0，"系统判定进入桌面"）→ 走 0.2s 线性渐隐（不再硬切清空）。
                    val unlockExit = (prev == STATE_KEYGUARD || prev == STATE_SHADE_LOCKED) &&
                        state == STATE_SHADE
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        if (unlockExit) {
                            startUnlockFadeOut("状态机（$prev→0）")
                        } else {
                            reconcileContentVisibility("状态机")
                            reconcileLayerGate("状态机")
                        }
                    } else {
                        mainHandler.post {
                            if (unlockExit) {
                                runCatching { startUnlockFadeOut("状态机（$prev→0）") }
                            } else {
                                runCatching { reconcileContentVisibility("状态机") }
                                runCatching { reconcileLayerGate("状态机") }
                            }
                        }
                    }
                }.onFailure { HookRuntime.log("视差壁纸效果：状态机处理异常：$it") }
                return result
            }
        })
        HookRuntime.log("视差壁纸效果：已挂状态机（setState）")
        true
    }.getOrElse {
        HookRuntime.log("视差壁纸效果：挂状态机失败：$it")
        false
    }

    private fun stateName(state: Int): String = when (state) {
        STATE_SHADE -> "SHADE/桌面"
        STATE_KEYGUARD -> "KEYGUARD/锁屏"
        STATE_SHADE_LOCKED -> "SHADE_LOCKED/锁屏上拉"
        else -> "未知($state)"
    }

    /** 解锁退场类（`KeyguardViewMediatorInjector`，方法按名匹配——与 HyperMusicCover 同款取信号法）。 */
    private const val CLS_MEDIATOR_INJECTOR = "com.android.keyguard.injector.KeyguardViewMediatorInjector"

    /**
     * 解锁退场信号（v21a）：`KeyguardViewMediatorInjector#keyguardGoingAway` = 退场第一帧。
     * 用作"层闸门"的淡出起点（比状态机 state=0 更早；state=0 到达时的对账是幂等的）。
     */
    private fun installKeyguardAwayHook(module: XposedModule, loader: ClassLoader): Boolean = runCatching {
        val clazz = Class.forName(CLS_MEDIATOR_INJECTOR, false, loader)
        val method = clazz.declaredMethods.firstOrNull { it.name == "keyguardGoingAway" }
            ?: error("未找到 keyguardGoingAway")
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        reconcileLayerGate("解锁退场")
                    } else {
                        mainHandler.post { runCatching { reconcileLayerGate("解锁退场") } }
                    }
                }.onFailure { HookRuntime.log("视差壁纸效果：解锁退场处理异常：$it") }
                return result
            }
        })
        HookRuntime.log("视差壁纸效果：已挂解锁退场信号（keyguardGoingAway）")
        true
    }.getOrElse {
        HookRuntime.log("视差壁纸效果：挂解锁退场信号失败：$it")
        false
    }

    /** 编辑器服务类（SystemUI 侧）。编辑器状态 = 进入 / 退出锁屏编辑的权威信号（HMC 同款取信号法）。 */
    private const val CLS_EDITOR_HELPER = "com.android.keyguard.editor.KeyguardEditorHelper"

    /**
     * 编辑器状态监听（`setEditorState`）。
     *
     * **v22 功能**：状态非 IDEL（含 START）→ **提前入树** —— 在系统编辑器过渡动画开始前把层
     *   搬进 `keyguard_background_layer`，随后编辑器缩放 / 淡出 / 恢复全部由系统继承带动。
     */
    private fun installEditorStateHook(module: XposedModule, loader: ClassLoader): Boolean = runCatching {
        val clazz = Class.forName(CLS_EDITOR_HELPER, false, loader)
        val method = clazz.declaredMethods.firstOrNull { it.name == "setEditorState" }
            ?: error("未找到 setEditorState")
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    val state = chain.getArgs().firstOrNull()?.toString() ?: "?"
                    // v22：编辑器流程 → 提前入树（在系统过渡动画前就位，继承其缩放 / 淡出）。
                    if (state != "IDEL") {
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            enterTreeNow("编辑器状态($state)")
                        } else {
                            mainHandler.post { runCatching { enterTreeNow("编辑器状态($state)") } }
                        }
                    }
                }.onFailure { HookRuntime.log("视差壁纸效果：编辑器状态处理异常：$it") }
                return result
            }
        })
        HookRuntime.log("视差壁纸效果：已挂编辑器状态监听（setEditorState；v22 提前入树）")
        true
    }.getOrElse {
        HookRuntime.log("视差壁纸效果：挂编辑器状态监听失败：$it")
        false
    }

    /**
     * 连续模糊同步 hook —— 把系统的 `keyguardBlurRatio` 逐帧同步到我们的背景层（v3，2026-10-12）。
     *
     * ## 为什么需要它（真机实测 + 逆向核实）
     *
     * 上滑进入密码 / 图案界面的"连续模糊"（背景带主体、半径不断加大）由
     * `KeyguardPanelViewController#doBlurAndDim(ratio)` 逐帧驱动，它把 ratio 作用到：
     * ① 前景层（主体）`setMiSelfBlur`；② 系统壁纸 `ViewRootImpl#setWallpaperBlur`；
     * ③ 背景装饰（smartFrame）`RenderEffect`。**唯独不包含我们插入的背景层** ——
     * 结果是滑动时主体逐帧模糊、我们的层始终清晰（实测三个阶段的边缘能量几乎不变），
     * 到图案界面完成时与系统观感不一致。
     *
     * ## 修法
     *
     * 挂 `doBlurAndDim`（唯一写入 `keyguardBlurRatio` 的地方），proceed 后读字段，
     * 用与系统给"背景装饰"**完全同款**的参数（`MiuiKeyguardUtils#calculateDimBlurEffect`：
     * blur 100×ratio + 压暗 argb(77×ratio)）给本层设 `RenderEffect`。
     * 天然跟随系统：ratio=0 自动清除、归零 / 复位不用另写代码；只在值变化时动作。
     */
    private fun installBlurSyncHook(module: XposedModule, loader: ClassLoader): Boolean = runCatching {
        val clazz = Class.forName(CLS_PANEL_CONTROLLER, false, loader)
        val method = clazz.getDeclaredMethod(
            "doBlurAndDim",
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                // proceed 后字段已是本帧的最终值；只在变化时动作（幂等、轻量）。
                runCatching { onBlurRatioMaybeChanged(chain.getThisObject()) }
                    .onFailure { HookRuntime.log("视差壁纸效果：模糊同步处理异常：$it") }
                // 模糊探针（probe=3 记参数 / probe=4 把 100×ratio 设到壁纸 leash 上）
                runCatching {
                    val controller = chain.getThisObject()
                    val args = chain.getArgs()
                    val depth = depthEffectOf(controller)
                    val fg = foregroundLayerOf(controller)
                    SpatialRenderer.onKeyguardBlurArgs(args, depth, fg)
                    // **光栅壁纸同一个处境**（2026-11-04）：也挂在壁纸 leash 上、也是兄弟层，
                    // 景深关时系统同样不会糊它。转发到 RasterRenderer，内部自己判在不在跑、
                    // 景深开关、是否解锁退场；复用同一份反射缓存与日志。
                    RasterRenderer.onKeyguardBlurArgs(args, depth, fg)
                }.onFailure { HookRuntime.log("SPATIAL 探针：doBlurAndDim 处理异常：$it") }
                return result
            }
        })
        HookRuntime.log("视差壁纸效果：已挂连续模糊同步点（doBlurAndDim）")
        true
    }.getOrElse {
        HookRuntime.log("视差壁纸效果：挂连续模糊同步点失败：$it")
        false
    }

    // ---------------------------------------------------------------- 玻璃包含实验（v7 实验版，2026-10-12）
    //
    // 背景（调查结论，全文见《04-功能设计存档.md》"玻璃采集问题查明"）：
    // MIUI 的"高级材质 / 柔光玻璃" = **窗口级模糊**（SurfaceFlinger 对"窗口后面的合成内容"逐帧模糊）——
    // 我们的背景层是"窗口内"的 View，默认不在模糊输入里；且 SystemUI 对锁屏元素显式禁止
    // "背景模糊包含窗口内下方"（`disableMiBackgroundContainBelow(true)`，时钟 / 锁屏信息 / 密码容器均如此）。
    //
    // 实验：把"禁止"翻转成"允许"（后置覆盖，与 v3 连续模糊同步同款手法），验证玻璃的模糊
    // 能不能把"窗口内下方"的我们纳入输入。两个 hook 点：
    // ① `android.view.View#disableMiBackgroundContainBelow(boolean)` —— 系统所有"禁止"调用的最终落点
    //    （时钟 `MiuiBlurUtils`、锁屏信息 `LockScreenInfoLayout` 的直连反射、密码容器等都在这里收口）；
    // ② `NotificationUtil#applyElementViewBlend` —— "通知 / 播放控件的玻璃元素"设置混合色的通道
    //    （它们不经过 ①，这里"主动补一刀允许"）。
    //
    // 判定标准（用户真机观察）：锁屏上时钟 / 通知 / 播放控件的玻璃里能否看到我们的背景（或其模糊）。
    // 结论出来后：无效 → 把 [EXPERIMENT_CONTAIN_BELOW] 置 false；本段与 README 注释可整体删除。

    private const val CLS_NOTIFICATION_UTIL = "com.android.systemui.statusbar.notification.utils.NotificationUtil"
    private const val METHOD_APPLY_ELEMENT_BLEND = "applyElementViewBlend"
    private const val M_DISABLE_CONTAIN_BELOW = "disableMiBackgroundContainBelow"

    private fun installGlassContainExperiment(module: XposedModule, loader: ClassLoader): Boolean {
        if (!EXPERIMENT_CONTAIN_BELOW) return true
        val flipOk = installContainBelowFlip(module, loader)
        val blendOk = installBlendContainPatch(module, loader)
        return flipOk || blendOk
    }

    /** ① 翻转"禁止包含"：当次调用传 true（系统在禁止）→ 后置覆盖为 false（允许包含）。 */
    private fun installContainBelowFlip(module: XposedModule, loader: ClassLoader): Boolean = runCatching {
        val clazz = Class.forName("android.view.View", false, loader)
        val method = clazz.getDeclaredMethod(M_DISABLE_CONTAIN_BELOW, Boolean::class.javaPrimitiveType)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                runCatching {
                    if (chain.getArgs().firstOrNull() as? Boolean == true) {
                        val view = chain.getThisObject() as? View ?: return@runCatching
                        allowContainBelow(view)
                        val caller = callerHint()
                        logOnce("cb-flip-$caller", "玻璃包含实验：已翻转 禁止→允许（$caller）")
                    }
                }.onFailure { HookRuntime.log("玻璃包含实验：翻转异常：$it") }
                return result
            }
        })
        HookRuntime.log("视差壁纸效果：已挂玻璃包含实验 ①（$M_DISABLE_CONTAIN_BELOW 翻转）")
        true
    }.getOrElse {
        HookRuntime.log("视差壁纸效果：挂玻璃包含实验 ① 失败：$it")
        false
    }

    /** ② 通知 / 播放控件的玻璃元素：设置混合色时补一刀"允许包含下方"。 */
    private fun installBlendContainPatch(module: XposedModule, loader: ClassLoader): Boolean = runCatching {
        val clazz = Class.forName(CLS_NOTIFICATION_UTIL, false, loader)
        val methods = clazz.declaredMethods.filter { it.name == METHOD_APPLY_ELEMENT_BLEND }
        if (methods.isEmpty()) error("未找到 $METHOD_APPLY_ELEMENT_BLEND")
        var ok = 0
        for (method in methods) {
            val hooked = runCatching {
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            val target = chain.getArgs().filterIsInstance<View>().firstOrNull()
                            if (target != null) {
                                allowContainBelow(target)
                                logOnce(
                                    "cb-blend-${target.javaClass.simpleName}",
                                    "玻璃包含实验：已补允许包含（${target.javaClass.simpleName}）",
                                )
                            }
                        }.onFailure { HookRuntime.log("玻璃包含实验：补刀异常：$it") }
                        return result
                    }
                })
                true
            }.getOrElse {
                HookRuntime.log("玻璃包含实验：挂 ${method.parameterCount} 参重载失败：$it")
                false
            }
            if (hooked) ok++
        }
        HookRuntime.log("视差壁纸效果：已挂玻璃包含实验 ②（applyElementViewBlend × $ok/${methods.size}）")
        ok > 0
    }.getOrElse {
        HookRuntime.log("视差壁纸效果：挂玻璃包含实验 ② 失败：$it")
        false
    }

    /**
     * 把"背景模糊是否包含窗口内下方"设为**允许**（`disableMiBackgroundContainBelow(false)`）。
     * 反射调用、失败只记日志；注意它会再次进入 ① 的 hook（参数 false → 不翻转，无递归）。
     */
    private fun allowContainBelow(view: View) {
        runCatching {
            view.javaClass.getMethod(M_DISABLE_CONTAIN_BELOW, Boolean::class.javaPrimitiveType)
                .invoke(view, false)
        }.onFailure { HookRuntime.log("玻璃包含实验：允许包含调用失败：$it") }
    }

    /** 诊断：调用方"类.方法"（跳过 framework View 帧与我们自己的帧），用于日志归档调用来源。 */
    private fun callerHint(): String = runCatching {
        Throwable().stackTrace.firstOrNull { frame ->
            val n = frame.className
            n != "android.view.View" && !n.startsWith("com.frederic22.hyperextreme")
        }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
    }.getOrDefault("?")

    /** 系统当前的模糊比例（= 我们最后一帧同步到背景层上的值）。 */
    @Volatile
    private var lastBlurRatio = 0f

    /**
     * 读系统的「锁屏景深」开关（`KeyguardCommonSettingObserver#getDepthEffectEnable`）。
     *
     * 只给模糊探针打日志用 —— `doBlurAndDim` 里 `depth` 决定走
     * 「`setWallpaperBlur` 窗口级模糊」还是「`updateKeyguardWallpaperRatio` 让壁纸进程自己糊」。
     * 读不到就返回 null（不影响主流程）。
     */
    private fun depthEffectOf(controller: Any?): Boolean? {
        if (controller == null) return null
        return runCatching {
            val obs = controller.javaClass
                .getField("keyguardCommonSettingObserver").get(controller)
            obs.javaClass.getMethod("getDepthEffectEnable").invoke(obs) as? Boolean
        }.getOrNull()
    }

    /**
     * 系统的锁屏景深前景层（`KeyguardPanelViewController#keyguardForegroundLayer`）。
     *
     * 空间壁纸的「替系统补调模糊」要用它当参数：系统的
     * `applyDepthMiBlurEffect(ratio, view)` / `setWallpaperSyncBlur(ratio, view)` 都要求传这个 View。
     */
    private fun foregroundLayerOf(controller: Any?): android.view.View? {
        if (controller == null) return null
        return runCatching {
            controller.javaClass.getField("keyguardForegroundLayer").get(controller)
                as? android.view.View
        }.getOrNull()
    }

    private fun onBlurRatioMaybeChanged(controller: Any?) {
        if (controller == null) return
        val ratio = runCatching {
            controller.javaClass.getField(FIELD_BLUR_RATIO).get(controller) as? Float
        }.getOrNull() ?: return
        if (ratio == lastBlurRatio) return
        lastBlurRatio = ratio
        applyBlurToBackdrop(ratio)
    }

    /**
     * 给我们的背景层设"连续模糊"（**主线程**：`doBlurAndDim` 就在主线程上）。
     *
     * 参数与系统 `MiuiKeyguardUtils.calculateDimBlurEffect` 完全一致（同款链式 RenderEffect，
     * 顺序也照抄）：`createChainEffect(压暗色滤, 高斯模糊)`，半径 = 100×ratio、压暗 = argb(77×ratio)。
     * ratio <= 0 → 清除效果（恢复清晰）。
     */
    private fun applyBlurToBackdrop(ratio: Float) {
        val view = backdropRef?.get()?.takeIf { it.isAttachedToWindow } ?: return
        val effect = if (ratio <= 0f) {
            null
        } else {
            RenderEffect.createChainEffect(
                RenderEffect.createColorFilterEffect(
                    BlendModeColorFilter(
                        Color.argb((77f * ratio).toInt().coerceAtLeast(1), 0, 0, 0),
                        BlendMode.SRC_ATOP,
                    ),
                ),
                RenderEffect.createBlurEffect(ratio * 100f, ratio * 100f, Shader.TileMode.CLAMP),
            )
        }
        runCatching { view.setRenderEffect(effect) }
            .onFailure { logOnce("blur-sync-fail", "背景层模糊效果设置失败：$it") }
        logOnce("blur-sync", "背景层已接入连续模糊（ratio=$ratio 为实测首帧值）")
    }

    // ---------------------------------------------------------------- 入窗认人

    @Volatile
    private var idsResolved = false
    private var bgLayerId = 0
    private var subjectId = 0

    /** v22：编辑器入口按钮 id 集合（长按弹出菜单里「自定义锁屏」按钮出现 → 提前入树）。 */
    private var editorButtonIds: IntArray = IntArray(0)

    /** 最近见过的两个目标视图（弱引用；真正的兜底是 [findByWindows] 按 id 全窗口搜）。 */
    @Volatile
    private var bgLayerRef: WeakReference<ViewGroup>? = null

    @Volatile
    private var subjectRef: WeakReference<View>? = null

    /**
     * **视差是否真的动过系统主体**（translation / scale）—— 2026-11-05 真机 bug 的修复位。
     *
     * [applyParallax] 往 `deducted_image_view` 写位移/缩放时置 true；
     * [resetSubjectShift] **只在它为 true 时才写回** 0 / 1。
     *
     * 以前的写法是无条件复位，于是"视差根本没开"时也会把系统主体的 `scaleX/Y` 拉回 1.0；
     * 而系统在「AOD 与锁屏样式相同」时会把主体缩到 `f - 0.05`（全屏 AOD = 0.95）
     * ⇒ 被我们覆盖成 1.0，锁屏主体与背景稳态错位 5%（用户 2026-11-05 实测）。
     */
    @Volatile
    private var subjectShiftApplied = false

    @Volatile
    private var backdropRef: WeakReference<BackdropView>? = null

    /**
     * 我们的背景层 —— 自定义子类做类型判断（比 tag 干净；加进宿主视图树是安全的）。
     *
     * **v8 起 = 自绘合成**：`super.onDraw`（背景图，沿用 ImageView 的 CENTER_CROP / 缩放 / 滤波逻辑）
     * 之后，再按"主体原件（前景层 deducted_image_view）的实时位置 / 缩放"额外绘制一份**主体副本**
     * （v10 起：用两视图的公开属性手工重建自身变换后再换算，见 [buildViewTransformFor]，与原件逐帧重合）。
     * 这样"玻璃（主时钟等）的模糊输入（=它下方的内容，只有本层）"里就带上了主体。
     */
    private class BackdropView(context: Context) : ImageView(context) {

        private val copyPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val subjectM = Matrix()
        private val ourM = Matrix()
        private val invM = Matrix()
        private val combinedM = Matrix()
        private val copyDst = RectF()

        override fun onDraw(canvas: Canvas) {
            // v9 内容抑制：层不可见（解锁后）时**不绘制任何内容**——防被"玻璃包含实验"打开的
            // 捕获管线取走（用户实测：解锁后下拉通知中心，背景出现我们的图）。回锁屏自动恢复。
            if (contentSuppressed) return
            super.onDraw(canvas)
            if (!effectiveCopyEnabled()) return
            runCatching { drawSubjectCopy(canvas) }
                .onFailure { logOnce("copy-draw-fail", "主体副本：绘制异常：$it") }
        }

        /** 把"主体原件"按其当前的位置 / 缩放 / 裁剪规则，映射到本层的画布上重画一份。 */
        private fun drawSubjectCopy(canvas: Canvas) {
            val subject = subjectRef?.get() ?: return
            if (!subject.isShown || subject.width <= 0 || subject.height <= 0) return
            val image = subject as? ImageView ?: return
            val bmp = (image.drawable as? BitmapDrawable)?.bitmap
            if (bmp == null || bmp.isRecycled) {
                logOnce(
                    "copy-no-bitmap",
                    "主体副本：主体 drawable 不是位图（${image.drawable?.javaClass?.simpleName}），跳过",
                )
                return
            }
            // ---- 变换补偿（v10 重写）----
            // 旧版用 `transformMatrixToGlobal` 链读再求逆补偿；第三轮实测"副本跟着背景动、不跟主体"
            // 说明链读补偿在实机上失效（补偿差 ≈ 0，副本只随本层渲染变换走）——弃用链读。
            // 现改为**用两个视图的公开属性手工重建各自的"自身变换"**（渲染器真正应用的那条）：
            //   G = T(translation) ∘ T(pivot) ∘ S(scale) ∘ T(-pivot)
            // 补偿 = inv(G_本层) × G_主体 —— 主体怎么动（含我们给的 ±36px 与常驻放大），副本逐位跟随。
            // 先验：两视图祖先链几何一致（同窗口、同为全屏层）——由静止态对齐实拍兜底。
            buildViewTransformFor(ourM, this)
            buildViewTransformFor(subjectM, subject)
            if (!ourM.invert(invM)) return
            combinedM.setConcat(invM, subjectM)

            val w = subject.width.toFloat()
            val h = subject.height.toFloat()
            val bmpW = bmp.width.toFloat()
            val bmpH = bmp.height.toFloat()
            // 复刻主体 ImageView 的 scaleType（覆盖常见三种；其余按 CENTER_CROP 兜底）。
            when (image.scaleType) {
                ImageView.ScaleType.FIT_XY -> copyDst.set(0f, 0f, w, h)

                ImageView.ScaleType.FIT_CENTER, ImageView.ScaleType.CENTER_INSIDE -> {
                    val s = minOf(w / bmpW, h / bmpH)
                    val dw = bmpW * s
                    val dh = bmpH * s
                    val dx = (w - dw) / 2f
                    val dy = (h - dh) / 2f
                    copyDst.set(dx, dy, dx + dw, dy + dh)
                }

                else -> {
                    val s = maxOf(w / bmpW, h / bmpH)
                    val dw = bmpW * s
                    val dh = bmpH * s
                    val dx = (w - dw) / 2f
                    val dy = (h - dh) / 2f
                    copyDst.set(dx, dy, dx + dw, dy + dh)
                }
            }
            copyDst.offset(SUBJECT_COPY_OFFSET_X, SUBJECT_COPY_OFFSET_Y)
            // 透明度跟随原件（上滑过渡等场景原件被淡化时，副本同步淡化）。
            copyPaint.alpha = (subject.alpha.coerceIn(0f, 1f) * 255f).toInt()

            canvas.save()
            canvas.concat(combinedM)
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.drawBitmap(bmp, null, copyDst, copyPaint)
            canvas.restore()
            canvas.restore()
            logOnce(
                "copy-drawn",
                "主体副本：已绘制（bitmap=${bmp.width}x${bmp.height}，view=${subject.width}x${subject.height}，" +
                    "scaleType=${image.scaleType}）",
            )
        }
    }

    /**
     * 手工重建视图"自身变换"矩阵（与渲染器对该视图实际应用的那条一致）：
     *   G = T(translation) ∘ T(pivot) ∘ S(scale) ∘ T(-pivot)
     * pivot 约定：pivotX/Y 都为 0（未显式设置）时用视图中心（Android 默认语义）。
     *
     * 用于副本补偿：combined = inv(G_本层) × G_主体（由调用方组装）。
     */
    private fun buildViewTransformFor(m: Matrix, v: View) {
        val centered = v.pivotX == 0f && v.pivotY == 0f
        val px = if (centered) v.width / 2f else v.pivotX
        val py = if (centered) v.height / 2f else v.pivotY
        m.setTranslate(-px, -py)
        m.postScale(v.scaleX, v.scaleY)
        m.postTranslate(px, py)
        m.postTranslate(v.translationX, v.translationY)
    }

    private fun onViewAttached(view: View?) {
        val v = view ?: return
        val context = v.context ?: return
        // 借高频的入窗事件幂等装广播接收器 + 解析 id（都只跑一次）。
        installReceiver(context)
        if (!idsResolved) resolveIds(context)
        val id = v.id
        if (id == 0) return
        // v22：「自定义锁屏」按钮出现（长按弹菜单）—— 用户已进入编辑意图，提前把层搬进树内
        // （在编辑器过渡动画前就位；与"亮屏稳定"主触发互为兜底，幂等）。
        if (editorButtonIds.isNotEmpty() && editorButtonIds.contains(id)) {
            runCatching { enterTreeNow("编辑器按钮出现") }
                .onFailure { HookRuntime.log("视差壁纸效果：按钮入树异常：$it") }
            return
        }
        val reason = when (id) {
            bgLayerId -> {
                if (v is ViewGroup) bgLayerRef = WeakReference(v)
                "背景层入窗"
            }

            subjectId -> {
                subjectRef = WeakReference(v)
                "主体入窗"
            }

            else -> return
        }
        // 全量自愈（幂等）——两个目标哪个入窗都做一次。
        ensureAsync(context, reason)
    }

    private fun resolveIds(context: Context) {
        runCatching {
            bgLayerId = context.resources.getIdentifier(ID_BG_LAYER, "id", SYSTEMUI_PKG)
            subjectId = context.resources.getIdentifier(ID_DEDUCTED, "id", SYSTEMUI_PKG)
            editorButtonIds = arrayOf(ID_EDITOR_BUTTON_MAIN, ID_EDITOR_BUTTON_ALT)
                .mapNotNull { name ->
                    context.resources.getIdentifier(name, "id", SYSTEMUI_PKG).takeIf { it != 0 }
                }
                .toIntArray()
        }
        idsResolved = true
        HookRuntime.log("视差壁纸效果：id 解析 ⇒ 背景层=$bgLayerId，主体=$subjectId，编辑器按钮=${editorButtonIds.toList()}")
    }

    // ---------------------------------------------------------------- 广播接收（App → SystemUI）

    @Volatile
    private var receiverInstalled = false

    /**
     * 幂等安装广播接收器（借用高频的入窗事件拿 Context，与 `ScreenFlash` 同一招）。
     *
     * ⚠️ Android 14+ 动态注册必须带 `RECEIVER_EXPORTED` 标志（App 与 SystemUI 是两个应用），
     * 与 ScreenFlash 同款处理：先带标志、失败退回两参注册。
     */
    private fun installReceiver(context: Context) {
        if (receiverInstalled) return
        synchronized(this) {
            if (receiverInstalled) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    // v21a：亮灭屏广播驱动"层闸门"（与传感器边沿幂等互补）。
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            mainHandler.post {
                                runCatching { reconcileLayerGate("亮屏（广播）") }
                            }
                            return
                        }
                        Intent.ACTION_SCREEN_OFF -> {
                            mainHandler.post {
                                runCatching { reconcileLayerGate("灭屏（广播）") }
                            }
                            return
                        }
                    }
                    // 调试（v10）：合成倾角注入 / 释放——adb 远程驱动视差做截图验证用。
                    if (intent.hasExtra(EXTRA_TILT_X) || intent.hasExtra(EXTRA_TILT_Y)) {
                        val tx = intent.getFloatExtra(EXTRA_TILT_X, Float.NaN)
                        val ty = intent.getFloatExtra(EXTRA_TILT_Y, Float.NaN)
                        mainHandler.post { runCatching { setDebugTilt(tx, ty) } }
                        return
                    }
                    // 调试（v11）：副本开关——A/B 验证"副本与主体对齐"用。
                    if (intent.hasExtra(EXTRA_COPY)) {
                        val on = intent.getIntExtra(EXTRA_COPY, 1) != 0
                        mainHandler.post { runCatching { setDebugCopy(on) } }
                        return
                    }
                    // 标定（2026-10-21，临时调试）：直接给层下发 setPosition，用来反解父层矩阵。
                    //   am broadcast -a ...PARALLAX_BG --ef posx 100 --ef posy 0
                    //   am broadcast -a ...PARALLAX_BG --ez calib_end true
                    if (intent.hasExtra("posx") || intent.hasExtra("posy")) {
                        val px = intent.getFloatExtra("posx", 0f)
                        val py = intent.getFloatExtra("posy", 0f)
                        val sc = intent.getFloatExtra("scale", 1f)
                        val pvx = intent.getFloatExtra("pivotx", 0f)
                        val pvy = intent.getFloatExtra("pivoty", 0f)
                        mainHandler.post {
                            runCatching {
                                ParallaxSubjectLayer.setPositionForCalibration(px, py, sc, pvx, pvy)
                            }
                        }
                        return
                    }
                    if (intent.hasExtra("calib_end")) {
                        mainHandler.post { runCatching { ParallaxSubjectLayer.exitCalibration() } }
                        return
                    }
                    // 「视差效果覆盖桌面」选项变化（2026-10-21）：**独立于总开关**，
                    // 只决定要不要在桌面建层。收到即生效（不依赖 prefs 文件刷新）。
                    if (intent.hasExtra(EXTRA_COVER)) {
                        val cover = intent.getBooleanExtra(EXTRA_COVER, false)
                        mainHandler.post {
                            commandCover = cover
                            coverFlagAt = 0L
                            coverFlag = cover && commandOn == true
                            HookRuntime.log("PDSK 覆盖选项 → $cover（总开关=$commandOn）")
                            if (!coverFlag) {
                                runCatching { ParallaxSubjectLayer.destroy() }
                                desktopLoader?.let {
                                    runCatching { ParallaxDesktopLeash.reset(it, 0, 0) }
                                }
                            }
                        }
                        return
                    }
                    val token = intent.getLongExtra(EXTRA_TOKEN, 0L)
                    val on = intent.getBooleanExtra(EXTRA_ON, false)
                    val app = receiverContext.applicationContext ?: return
                    val worker = Thread { handleCommand(app, token, on) }
                    worker.isDaemon = true
                    worker.start()
                }
            }
            val app = context.applicationContext
            val filter = IntentFilter(ACTION)
            // 亮灭屏广播 = "层闸门"的第二信号源（与传感器边沿幂等互补：亮屏时传感器恢复更早，
            // 灭屏时广播更及时）。
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            val ok = runCatching {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                true
            }.getOrElse { error ->
                HookRuntime.log("视差壁纸效果：带标志注册失败（$error），退回两参注册")
                runCatching {
                    app.registerReceiver(receiver, filter)
                    true
                }.getOrDefault(false)
            }
            receiverInstalled = ok
            HookRuntime.log("视差壁纸效果：广播接收器注册${if (ok) "成功" else "失败"}")
            if (!ok) {
                runCatching {
                    app.contentResolver.call(
                        MaskProvider.configUri(),
                        MaskProvider.METHOD_NOTE,
                        "视差壁纸效果：广播接收器注册失败（见 LSPosed 日志）",
                        null,
                    )
                }
            }
        }
    }

    /**
     * 处理一次开 / 关指令：**强制刷新 config**（绕过 3s 缓存）→ `ensure` → 回执。
     *
     * 回执语义 = "指令已受理并已尝试执行"（不是"一定已在屏幕上可见"）——
     * 视图未就位时自愈会在它入窗后补上；回执后顺手把"层是否已就位"写进诊断。
     */
    private fun handleCommand(context: Context, token: Long, on: Boolean) {
        if (token <= 0L) return
        val config = parallaxConfig(context, force = true)
        HookRuntime.log("视差壁纸效果：收到指令 token=$token on=$on（config.active=${config?.active}）")
        // 视图操作必须回主线程（本函数在 daemon 线程上）；回执等视图操作真正做完再发。
        mainHandler.post {
            runCatching { applyState(context, config, "指令 on=$on") }
            // **指令就是权威状态**（2026-10-21）。prefs 文件通道有延迟 ——
            // 实测点「立即替换」后 hook 侧仍读到旧的 `bg_active=false`，导致每帧都在销毁、
            // 层永远建不起来。广播里的 `on` 是明确的，直接写进状态。
            commandOn = on
            if (!on) {
                runCatching { ParallaxSubjectLayer.destroy() }
                desktopLoader?.let { runCatching { ParallaxDesktopLeash.reset(it, 0, 0) } }
                coverFlag = false
                coverFlagAt = SystemClock.elapsedRealtime()
            }
            val placed = backdropRef?.get()?.isAttachedToWindow == true
            val worker = Thread {
                runCatching {
                    context.contentResolver.call(
                        MaskProvider.configUri(),
                        MaskProvider.METHOD_PARALLAX_ACK,
                        token.toString(),
                        null,
                    )
                }.onFailure { HookRuntime.log("视差壁纸效果：回执失败：$it") }
                runCatching {
                    context.contentResolver.call(
                        MaskProvider.configUri(),
                        MaskProvider.METHOD_NOTE,
                        "视差壁纸效果：${if (config?.active == true) "已开启" else "已关闭"}" +
                            "（背景层${if (placed) "已就位" else "未就位"}）",
                        null,
                    )
                }
            }
            worker.isDaemon = true
            worker.start()
        }
    }

    // ---------------------------------------------------------------- 配置

    /**
     * 运行时配置快照：开启状态 + 图片 revision + 「参数调整」5 参数（2026-10-13 扩展）。
     *
     * `data class` 的 equals 覆盖全部字段 —— 任何参数改动都会命中 `parsed != config` 分支
     * （内存缓存替换 + 日志），下一帧 [applyParallax] 起就用新值。
     */
    private data class ParallaxConfig(
        val active: Boolean,
        val revision: Long,
        val reverse: Boolean = DEFAULT_REVERSE,
        val subjectScale: Float = DEFAULT_SUBJECT_SCALE,
        val bgScale: Float = DEFAULT_BG_SCALE,
        val subjectAmp: Float = DEFAULT_SUBJECT_AMP_PX,
        val bgAmp: Float = DEFAULT_BG_AMP_PX,
        val homeWait: Float = DEFAULT_HOME_WAIT_S,
        val gyroDeg: Float = DEFAULT_GYRO_DEG,
    )

    /** config 还没读到过时的运行兜底：只有默认参数、`active=false`（防误显示）。 */
    private val defaultConfig = ParallaxConfig(active = false, revision = 0L)

    /** 当前生效的参数快照（纯内存、任意线程可读 —— config 缺失回落默认值）。 */
    private fun liveParams(): ParallaxConfig = config ?: defaultConfig

    /** 防御坏值：倍率夹到 [1, 1.5]（App 量程 1~1.25，放宽一点留余地）。 */
    private fun Float.saneScale(): Float = if (isNaN()) 1f else coerceIn(1f, 1.5f)

    /** 防御坏值：幅度夹到 [0, 400]px。 */
    private fun Float.saneAmp(): Float = if (isNaN()) 0f else coerceIn(0f, 400f)

    /** 防御坏值：归位等待时长夹到 [0.1, 5]s（App 量程 0.2~1s，放宽一点留余地）。 */
    private fun Float.saneHomeWait(): Float =
        if (isNaN()) DEFAULT_HOME_WAIT_S else coerceIn(0.1f, 5f)

    /** 防御坏值：满量程角夹到 [5, 120]°（App 量程 15~45°，放宽一点留余地）。 */
    private fun Float.saneGyroDeg(): Float =
        if (isNaN()) DEFAULT_GYRO_DEG else coerceIn(5f, 120f)

    @Volatile
    private var config: ParallaxConfig? = null

    @Volatile
    private var configAt = 0L

    @Volatile
    private var configFailedAt = 0L

    private val loggedOnce = ConcurrentHashMap<String, Boolean>()

    private fun logOnce(key: String, message: String) {
        if (loggedOnce.putIfAbsent(key, true) == null) {
            HookRuntime.log("视差壁纸效果：$message")
        }
    }

    /**
     * 读开启状态、图片 revision 与「参数调整」5 参数。主通道 = provider；兜底 = 远程偏好
     * （无 Context 也读得到，但拿不到 revision / 连续参数 —— 用默认值，仅极端场景可见的小代价）。
     * 读不到返回 null（调用方按「未开启」处理）。
     */
    private fun parallaxConfig(context: Context, force: Boolean = false): ParallaxConfig? {
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            config?.let { fresh -> if (now - configAt < CONFIG_TTL_MS) return fresh }
            if (now - configFailedAt < CONFIG_TTL_MS) return config
        }
        val bundle = runCatching {
            context.contentResolver.call(MaskProvider.configUri(), MaskProvider.METHOD_CONFIG, null, null)
        }.getOrNull()
        val parsed = bundle?.let {
            ParallaxConfig(
                active = it.getBoolean(MaskProvider.KEY_PARALLAX_ACTIVE, false),
                revision = it.getLong(MaskProvider.KEY_REVISION, 0L),
                reverse = it.getBoolean(MaskProvider.KEY_PARALLAX_REVERSE, DEFAULT_REVERSE),
                subjectScale = it.getFloat(MaskProvider.KEY_PARALLAX_SUBJECT_SCALE, DEFAULT_SUBJECT_SCALE)
                    .saneScale(),
                bgScale = it.getFloat(MaskProvider.KEY_PARALLAX_BG_SCALE, DEFAULT_BG_SCALE).saneScale(),
                subjectAmp = it.getFloat(MaskProvider.KEY_PARALLAX_SUBJECT_AMP, DEFAULT_SUBJECT_AMP_PX)
                    .saneAmp(),
                bgAmp = it.getFloat(MaskProvider.KEY_PARALLAX_BG_AMP, DEFAULT_BG_AMP_PX).saneAmp(),
                homeWait = it.getFloat(MaskProvider.KEY_PARALLAX_HOME_WAIT, DEFAULT_HOME_WAIT_S)
                    .saneHomeWait(),
                gyroDeg = it.getFloat(MaskProvider.KEY_PARALLAX_GYRO_DEG, DEFAULT_GYRO_DEG)
                    .saneGyroDeg(),
            )
        } ?: remoteConfig()
        if (parsed == null) {
            configFailedAt = now
            logOnce("config-miss", "配置读不到（provider 与远程通道都失败），按「未开启」处理")
            return null
        }
        configAt = now
        if (parsed != config) {
            config = parsed
            HookRuntime.log(
                "视差壁纸效果：配置更新 ⇒ active=${parsed.active}，revision=${parsed.revision}，" +
                    "反向=${parsed.reverse}，主体放大=${parsed.subjectScale}，背景放大=${parsed.bgScale}，" +
                    "主体幅度=${parsed.subjectAmp.toInt()}px，背景幅度=${parsed.bgAmp.toInt()}px，" +
                    "归位等待=${parsed.homeWait}s，最大幅度角=${parsed.gyroDeg.toInt()}°",
            )
        }
        return parsed
    }

    private fun remoteConfig(): ParallaxConfig? {
        val active = HookRuntime.remoteBoolean(MaskPrefs.KEY_PARALLAX_ACTIVE) ?: return null
        return ParallaxConfig(
            active = active,
            revision = 0L,
            reverse = HookRuntime.remoteBoolean(MaskPrefs.KEY_PARALLAX_REVERSE) ?: DEFAULT_REVERSE,
        )
    }

    // ---------------------------------------------------------------- 主逻辑（线程模型：读配置在后台、视图操作在主线程）

    /** 主线程 Handler —— 后台线程读完配置后回到它上面做视图操作。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastShownLogged: Boolean? = null

    /** 缓存未过期时的快速读取（纯内存、任意线程安全）。 */
    private fun freshConfig(): ParallaxConfig? {
        config?.let { if (SystemClock.elapsedRealtime() - configAt < CONFIG_TTL_MS) return it }
        return null
    }

    /**
     * 全量对齐一次（幂等，任意线程可调）：读配置（可能跨进程 → 丢后台线程）→
     * 回主线程做视图操作（[applyState]）。
     *
     * ⚠️ 之所以不当场直接读：入窗路径跑在 SystemUI 的**主线程**上，`contentResolver.call`
     * 在 provider 进程冷启动时可能阻塞几百毫秒 —— 绝不能在主线程做（本项目一贯纪律）。
     */
    private fun ensureAsync(context: Context, reason: String) {
        val app = context.applicationContext ?: return
        freshConfig()?.let { cached ->
            postApply(app, cached, reason)
            return
        }
        val worker = Thread {
            val config = parallaxConfig(app)
            postApply(app, config, reason)
        }
        worker.isDaemon = true
        worker.start()
    }

    /** 保证 [applyState] 在主线程执行（已在主线程就直接跑）。 */
    private fun postApply(context: Context, config: ParallaxConfig?, reason: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { applyState(context, config, reason) }
        } else {
            mainHandler.post { runCatching { applyState(context, config, reason) } }
        }
    }

    /** **主线程**上的实际应用：按配置决定「显示背景层 + 位移」或「移除背景层 + 复位位移」。 */
    private fun applyState(context: Context, config: ParallaxConfig?, reason: String) {
        val active = config?.active == true
        if (active) {
            // v21a：锚点 = 背景层槽（仍在树里、空着）或主体任一 → 向上找窗口根 → 搬家插入。
            // v22：宿主按当前模式取——树内（槽本身）或整窗（窗口根）；槽拿不到时降级整窗（标记随实际落位）。
            val anchor = resolveBgLayer(context) ?: subjectView(context)
            if (anchor == null) {
                logOnce("no-layer", "没找到搬家锚点（$ID_BG_LAYER / $ID_DEDUCTED）—— 等它下次入窗自愈")
                return
            }
            val treeSlot = if (treeAnchored) resolveBgLayer(context) else null
            val view = insertIfNeeded(anchor, treeSlot)
            // v21a：闸门首态（不播动画——照当前信号直接就位）；再对账内容（与闸门后的可见性一致）。
            reconcileLayerGate("状态应用", animate = false)
            // v22：挂载点对账补一脚（亮屏稳定态下自愈/首插后会自动调度入树）。
            reconcileAnchorMode("状态应用")
            // v9：按当前可见性对齐"内容抑制"（如"解锁状态下开启替换"——层隐藏，不绘制任何内容）。
            reconcileContentVisibility("状态应用")
            applyImageAsync(context, view, config.revision)
            syncParallax(context)
            ensureSensor(context)
            // 视图重建（SystemUI 重启 / 配置变化）后把系统当前的连续模糊比例补上，别丢状态。
            applyBlurToBackdrop(lastBlurRatio)
            if (lastShownLogged != true) {
                lastShownLogged = true
                HookRuntime.log("视差壁纸效果：显示（$reason）")
            }
        } else {
            removeBackdrop(context)
            resetSubjectShift(context)
            stopSensor()
            if (lastShownLogged != false) {
                lastShownLogged = false
                HookRuntime.log("视差壁纸效果：隐藏 / 未开启（$reason）")
            }
        }
    }

    /** 缓存优先、`WindowManagerGlobal.mViews` 全窗口按 id 兜底（与 LockScreenButton 同招）。 */
    private fun resolveBgLayer(context: Context): ViewGroup? {
        bgLayerRef?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        val found = findByWindows(context, bgLayerId) as? ViewGroup
        if (found != null) bgLayerRef = WeakReference(found)
        return found
    }

    private fun subjectView(context: Context): View? {
        subjectRef?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        val found = findByWindows(context, subjectId)
        if (found != null) subjectRef = WeakReference(found)
        return found
    }

    private fun findByWindows(context: Context, id: Int): View? {
        if (id == 0) return null
        val roots = runCatching {
            val clazz = Class.forName("android.view.WindowManagerGlobal")
            val instance = clazz.getMethod("getInstance").invoke(null)
            val field = clazz.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(instance) as? ArrayList<View>).orEmpty()
        }.getOrElse {
            logOnce("mviews-fail", "拿不到窗口列表（$it）——按 id 兜底搜索不可用")
            emptyList()
        }
        for (root in roots) {
            val found = runCatching { findByIdDeep(root, id) }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    /** 按 id 深度优先找 View（不用 `View.findViewById` —— 它的泛型签名会踩到 lint 的解析 bug）。 */
    private fun findByIdDeep(view: View, id: Int): View? {
        if (view.id == id) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findByIdDeep(view.getChildAt(index), id)?.let { return it }
            }
        }
        return null
    }

    /**
     * 插入 / 搬移（幂等）。
     *
     * v22：宿主二选一 —— [treeSlot] 非空 = 树内（`keyguard_background_layer` index0，继承系统过渡）；
     * [treeSlot] 为空 = 整窗（窗口根 index0，v21a 行为）。目标宿主与现状不符时同帧完成
     * remove + addView（搬运；调用方保证时机在"视觉无变化"的静止态）。成功后同步 [treeAnchored]。
     */
    private fun insertIfNeeded(anchor: View, treeSlot: ViewGroup? = null): View? {
        val host: ViewGroup = treeSlot ?: windowRootOf(anchor) ?: return null
        val targetTree = treeSlot != null
        val existing = backdropRef?.get()
        if (existing != null && existing.parent === host) {
            treeAnchored = targetTree
            return existing
        }
        if (existing != null) {
            // v22：跨宿主搬移（remove + addView 同帧完成；布局参数保持 MATCH_PARENT 通用）。
            val old = existing.parent as? ViewGroup
            runCatching { old?.removeView(existing) }
                val ok = runCatching { host.addView(existing, 0) }.isSuccess
            if (!ok) {
                HookRuntime.log("视差壁纸效果：搬移失败（${host.javaClass.simpleName}）——回滚到原宿主")
                if (old != null) runCatching { old.addView(existing, 0) }
                return null
            }
            backdropRef = WeakReference(existing)
            resolveFadeRefs(anchor)
            treeAnchored = targetTree
            HookRuntime.log(
                "视差壁纸效果：层已搬移（${if (targetTree) "入树·" else "出树·"}" +
                    "${host.javaClass.simpleName} index0，共 ${host.childCount} 个子）",
            )
            return existing
        }
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index)
            if (child is BackdropView) {
                backdropRef = WeakReference(child)
                treeAnchored = targetTree
                return child
            }
        }
        // 新层实例从"不抑制"起步；随后由 [reconcileContentVisibility] 按实际可见性校正。
        // （同时清掉旧层可能挂起的"延迟清空 / 恢复"任务——新状态由紧随其后的对账重新决定。）
        contentSuppressed = false
        cancelPendingClear()
        cancelPendingRestore()
        // 初始放大取当前参数（「参数调整」的倍率；config 缺失回落默认值）——
        // 之后每帧由 [applyParallax] 校正，参数变化自动跟上。
        val initialScale = liveParams().bgScale
        val view = runCatching {
            BackdropView(host.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleX = initialScale
                scaleY = initialScale
                // 位移由传感器实时驱动（[applyParallax]），初始为 0。
            }
        }.getOrNull() ?: return null
        runCatching { host.addView(view, 0) }.onFailure {
            HookRuntime.log("视差壁纸效果：插入失败：$it")
            return null
        }
        backdropRef = WeakReference(view)
        resolveFadeRefs(anchor)
        treeAnchored = targetTree
        HookRuntime.log(
            "视差壁纸效果：背景层已就位（${if (targetTree) "树内·" else "整窗·"}" +
                "${host.javaClass.simpleName} index0，共 ${host.childCount} 个子）",
        )
        // 一次性诊断（v21 验证期留）：整窗时子级清单确认 index 0 位置正确。
        if (!targetTree) HookRuntime.log("视差壁纸效果：窗口根子级清单=[${childrenListOf(host)}]")
        return view
    }

    private fun removeBackdrop(context: Context) {
        backdropRef?.get()?.let { view -> runCatching { (view.parent as? ViewGroup)?.removeView(view) } }
        // 兜底：缓存丢了但从树里还找得到我们的层（视图重建后引用可能变野）——
        // v22：层可能在两个宿主之一（窗口根 / keyguard_background_layer 槽），都扫一遍。
        runCatching {
            val slot = resolveBgLayer(context)
            val anchor = slot ?: subjectView(context)
            val hosts = listOfNotNull(slot, anchor?.let { windowRootOf(it) })
            for (host in hosts) {
                for (index in host.childCount - 1 downTo 0) {
                    if (host.getChildAt(index) is BackdropView) host.removeViewAt(index)
                }
            }
        }
        stopGateFade()
        gateShownLogged = null
        backdropRef = null
        treeAnchored = false // v22：模式复位（下次亮屏稳定重新入树）
        pendingEnterTree = false
        mainHandler.removeCallbacks(enterTreeTask)
        pendingExitTree = false
        mainHandler.removeCallbacks(exitTreeTask)
    }

    // ---------------------------------------------------------------- 背景图解码（revision 缓存）

    @Volatile
    private var imageBitmap: Bitmap? = null

    @Volatile
    private var imageRevision = -1L

    /** 解码互斥：入窗路径与指令路径可能同时触发（浪费一次解码事小，栈上打架事大）。 */
    private val decoding = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 显示背景图（**异步解码**）。
     *
     * ⚠️ 必须异步：入窗路径跑在 SystemUI 的**主线程**上（`View#onAttachedToWindow`），
     * 而背景图解码是几十毫秒级的 IO —— 在主线程做会让锁屏出现时卡一下。
     * 有缓存（且 revision 未变）时直接同步设图；否则后台解码、`view.post` 回主线程设置。
     *
     * 解码采样到屏幕长边 ×1.1（给 1.04 放大留一点锐度余量；采样只压到**不低于**它，见 [sampleSize]）；
     * `revision` 变了才重新解码（用户换图后 App 会 bumpRevision）。
     */
    private fun applyImageAsync(context: Context, view: View?, revision: Long) {
        if (view !is ImageView) return
        val cached = imageBitmap
        if (cached != null && revision == imageRevision) {
            if (view.drawable == null) {
                view.setImageBitmap(cached)
                enableBitmapFilter(view)
            }
            return
        }
        if (!decoding.compareAndSet(false, true)) return
        val app = context.applicationContext ?: run { decoding.set(false); return }
        val worker = Thread {
            try {
                val bitmap = decodeBackdrop(app, targetEdge(app))
                if (bitmap == null) {
                    logOnce("decode-fail", "背景图解码失败（provider 读不到流？）")
                    return@Thread
                }
                imageBitmap = bitmap
                imageRevision = revision
                runCatching {
                    view.post {
                        view.setImageBitmap(bitmap)
                        enableBitmapFilter(view)
                    }
                }
                HookRuntime.log("视差壁纸效果：背景图已加载（${bitmap.width}×${bitmap.height}，revision=$revision）")
            } finally {
                decoding.set(false)
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    /** 显示层显式开启位图滤波（放大绘制防硬边 / 锯齿；换 drawable 后要重设）。 */
    private fun enableBitmapFilter(view: ImageView) {
        runCatching { (view.drawable as? BitmapDrawable)?.setFilterBitmap(true) }
    }

    private fun decodeBackdrop(context: Context, targetEdge: Int): Bitmap? {
        return runCatching {
            val uri = Uri.parse("content://${MaskProvider.AUTHORITY}/${MaskProvider.PARALLAX_SEGMENT}")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val hasBounds = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0
            } ?: false
            if (!hasBounds) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            }
        }.getOrNull()
    }

    /** 解码目标：屏幕长边 ×1.1。 */
    private fun targetEdge(context: Context): Int {
        val metrics = context.resources.displayMetrics
        return maxOf(metrics.widthPixels, metrics.heightPixels) * 11 / 10
    }

    /**
     * 采样只压到"**不低于** [maxEdge]"为止（2026-10-12 修订）：
     * 旧算法是 2 的幂向下取 —— 1720×3370 的图会被压到 860×1685（低于屏幕 1200×2670），
     * 上屏再放大 1.5~1.7× → 发软 / 边缘锯齿。新算法允许 sample=1（保原尺寸），
     * 最多只压一级到仍 ≥ maxEdge（宁大勿小，内存代价可控）。
     */
    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    // ---------------------------------------------------------------- 位移（传感器实时驱动，v4）

    @Volatile
    private var sensorManager: SensorManager? = null

    @Volatile
    private var sensorListener: SensorEventListener? = null

    @Volatile
    private var powerManager: PowerManager? = null

    /** 低通后的输出倾角（-1..1；已扣除自动零位；X=左右、Y=前后）。 */
    @Volatile
    private var tiltX = 0f

    @Volatile
    private var tiltY = 0f

    /**
     * 零位基准（归一化、**不夹取**）。未初始化 = NaN：首个样本直接取为基准（开启瞬间零输出）；
     * 之后**只在"静止归位"启动那一刻对齐到当前姿势** —— 所以任何时候停住 0.5s，画面都会在 1s 内归零。
     */
    private var baseX = Float.NaN
    private var baseY = Float.NaN

    /** 最近 `homeWait` 窗口的读数 —— "静止"判定用（[at] = elapsedRealtime 毫秒）。 */
    private data class StillSample(val at: Long, val nx: Float, val ny: Float, val yawAbs: Float)

    private val stillWindow = java.util.ArrayDeque<StillSample>(60)

    /** 归位动画状态：进行中 + 起始时刻 + 起始偏移（从当前位置回 0）。 */
    private var homing = false
    private var homeStartAt = 0L
    private var homeFromX = 0f
    private var homeFromY = 0f

    /** 归位动画里"绕轴转角"的起始偏移（v16 解耦后由动画直接驱动，见 [onGravity]）。 */
    private var homeFromYaw = 0f

    /** 上次传感器事件时间（算低通 dt 用；0 = 会话开始）。 */
    private var lastSensorAt = 0L

    // ---- 陀螺混合（v14，2026-10-13；机制见参数区注释与 [onGyro]）----

    /** 最近一次重力**原始读数**（陀螺投影用；重力回调是唯一写入点）。 */
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f

    /** 是否已经拿到过重力读数（没拿到前陀螺无法做投影、不积分）。 */
    private var gravSeen = false

    /** 绕重力轴角速度（低通后，rad/s）与其绝对值（后者供静止判定跨回调读取）。 */
    private var yawRateSmoothed = 0f

    @Volatile
    private var yawRateAbs = 0f

    private var lastGyroAt = 0L

    /** 上一次重力帧的屏幕交互态（亮/灭）—— [onGravity] 里检出"灭→亮 / 亮→灭"边沿，驱动层闸门。 */
    private var lastInteractive = true

    /**
     * 绕重力轴的**累积转角**（归一化：满量程 = config 的「最大幅度角度」`gyroDeg`；
     * 缺失时回落 [DEFAULT_GYRO_DEG]）。
     *
     * 不夹到 ±1 —— 让"转多少显示多少"自然成立（超量程由 [applyParallax] 前的夹取处理，
     * 转回程也能线性恢复）；只有极端保险上限防无界累积。
     */
    private var yawTilt = 0f

    /**
     * v22.3「反滚」角（弧度）：静止重锚以来"绕长边"（device Y）的累积转角 = ∫ωy（与 [yawTilt]
     * 同源积分，但**不参与显示**；只在静止归位启动 / 传感器重置时清零，与基准同步重锚）。
     * [onGravity] 用它把重力滚回静止相位后再与基准比较 —— 纯滚动（任意姿态）时 tilt 输出恒 0，
     * 消除斜 45°/横放 下"陀螺链与 tilt 链相斗"（2026-10-14 三姿态取证，拍板"ωy+反滚"）。
     */
    private var unrollAcc = 0f

    /** v22.4：合成值持续到顶的起始时刻（elapsedRealtime 毫秒；0 = 当前不在顶部）。 */
    private var topSince = 0L

    /** v22.4：本次归位是否由"到顶"触发——true 时运动**不**取消动画（快速转动中也要播完）。 */
    private var homingByTop = false

    /**
     * 接入传感器（幂等）：`TYPE_GRAVITY` + `TYPE_GYROSCOPE`（v14 起）+ 游戏级采样（~50Hz）。
     *
     * 重力矢量是"加速度计 + 陀螺仪"的融合结果，天然比裸加速度计稳得多；
     * 这里再叠一层一阶低通（[onGravity]）负责"停止倾斜后柔和回正"的手感。
     * 陀螺单独注册（[onGyro]）只为补"绕重力轴转动"这一个重力测不到的自由度；注册失败不致命。
     */
    private fun ensureSensor(context: Context) {
        if (sensorListener != null) return
        synchronized(this) {
            if (sensorListener != null) return
            val app = context.applicationContext ?: return
            val mgr = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (mgr == null) {
                logOnce("no-sensor", "拿不到 SensorManager —— 视差保持静止")
                return
            }
            val gravity = mgr.getDefaultSensor(Sensor.TYPE_GRAVITY)
            if (gravity == null) {
                logOnce("no-gravity", "设备没有 TYPE_GRAVITY —— 视差保持静止")
                return
            }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_GRAVITY -> onGravity(event)
                        Sensor.TYPE_GYROSCOPE -> onGyro(event)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val ok = runCatching {
                // **60Hz 采样，与渲染刷新率对齐**（2026-10-21 用户指定）。
                // `SENSOR_DELAY_GAME` 是 20000µs = 50Hz，比 60Hz 刷新少 1/6 的帧有数据 ——
                // 那些帧只能沿用旧值，视觉上就是轻微的不均匀。
                // Android 会挑"不小于且最接近"的硬件档，16667µs 通常会落到 100Hz（比 50Hz 好）。
                mgr.registerListener(listener, gravity, SENSOR_PERIOD_US)
            }.getOrDefault(false)
            if (!ok) {
                logOnce("sensor-reg-fail", "传感器注册失败 —— 视差保持静止")
                return
            }
            // 陀螺（v14 混合用）：没有它只是"绕重力轴转动无响应"，其余链不受影响 —— 不当作失败。
            val gyro = mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val gyroOk = gyro != null && runCatching {
                mgr.registerListener(listener, gyro, SENSOR_PERIOD_US)
            }.getOrDefault(false)
            if (!gyroOk) {
                logOnce("no-gyro", "陀螺不可用（无传感器 / 注册失败）—— 绕重力轴转动无响应")
            }
            sensorManager = mgr
            sensorListener = listener
            powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            baseX = Float.NaN
            baseY = Float.NaN
            tiltX = 0f
            tiltY = 0f
            yawTilt = 0f
            unrollAcc = 0f
            yawRateSmoothed = 0f
            yawRateAbs = 0f
            lastGyroAt = 0L
            gravSeen = false
            topSince = 0L
            homingByTop = false
            resetStillTracking()
            HookRuntime.log(
                "视差壁纸效果：传感器已接入（TYPE_GRAVITY" +
                    (if (gyroOk) "＋TYPE_GYROSCOPE（绕长边）" else "") +
                    "，满量程 X/Y=${FULL_SCALE_X}/${FULL_SCALE_Y}m/s²，" +
                    "响应τ=${TILT_TIME_CONSTANT_MS.toInt()}ms，静止归位=${DEFAULT_HOME_WAIT_MS}ms窗（可调）/" +
                    "${HOME_DURATION_MS}ms动画）",
            )
        }
    }

    /** 断开传感器 + 归零平滑值（关闭功能时调用）。 */
    private fun stopSensor() {
        synchronized(this) {
            val mgr = sensorManager
            val listener = sensorListener
            if (mgr != null && listener != null) runCatching { mgr.unregisterListener(listener) }
            sensorManager = null
            sensorListener = null
        }
        tiltX = 0f
        tiltY = 0f
        baseX = Float.NaN
        baseY = Float.NaN
        lastSensorAt = 0L
        yawTilt = 0f
        unrollAcc = 0f
        yawRateSmoothed = 0f
        yawRateAbs = 0f
        lastGyroAt = 0L
        gravSeen = false
        topSince = 0L
        homingByTop = false
        resetStillTracking()
    }

    /** 清空"静止"判定窗口并取消归位动画（重开传感器 / 关闭时用）。 */
    private fun resetStillTracking() {
        stillWindow.clear()
        homing = false
    }

    // ---------------------------------------------------------------- 调试：合成倾角（v10 自测用）

    @Volatile
    private var debugTiltX = Float.NaN

    @Volatile
    private var debugTiltY = Float.NaN

    /**
     * 注入 / 释放"合成倾角"（调试自测用）：注入后 [onGravity] 短路、倾角保持注入值——
     * 用于 adb 远程驱动视差做截图验证（不靠手摇手机）。
     * 释放：发 tilt_x > 1.5（或任意越界值）；释放后传感器重新接管（低通平滑接续）。
     */
    private fun setDebugTilt(x: Float, y: Float) {
        if (x.isNaN() || y.isNaN() || x > 1.5f || x < -1.5f) {
            debugTiltX = Float.NaN
            debugTiltY = Float.NaN
            lastSensorAt = 0L
            HookRuntime.log("视差调试：合成倾角已释放（回到传感器）")
            return
        }
        debugTiltX = x.coerceIn(-1f, 1f)
        debugTiltY = y.coerceIn(-1f, 1f)
        tiltX = debugTiltX
        tiltY = debugTiltY
        HookRuntime.log("视差调试：合成倾角 = (${debugTiltX}, ${debugTiltY})")
        applyParallax()
    }

    // ---------------------------------------------------------------- 调试：副本开关（v11 自测用）

    /** 调试：主体副本开关（null = 跟随 [ENABLE_SUBJECT_COPY]）。 */
    @Volatile
    private var debugCopy: Boolean? = null

    /** 当前是否绘制主体副本（调试开关优先）。 */
    private fun effectiveCopyEnabled(): Boolean = debugCopy ?: ENABLE_SUBJECT_COPY

    private fun setDebugCopy(on: Boolean) {
        debugCopy = on
        HookRuntime.log("视差调试：主体副本 = ${if (on) "开（跟随主体）" else "关（用于 A/B 验证）"}")
        backdropRef?.get()?.let { runCatching { it.invalidate() } }
    }

    /**
     * 一次重力事件（无 Handler 注册 → 投递到主 Looper；仍兜底对一次线程）。
     *
     * 轴映射 / 跟随 / 归位（2026-10-12 调参版；2026-10-13 归位等待可调 + 陀螺混合）：
     * * 水平 = `gravity.x`、垂直 = `gravity.z`，各自归一化（除以分轴满量程，**不夹取**）；
     * * **跟随**：输出 =（归一化 − 基准）夹取 ±1 → 一阶低通（τ=130ms）；
     * * **陀螺混合**（v14；v16 解耦显示；v22.3 换源 ωy + tilt 反滚）：绕长边的累积转角
     *   （[yawTilt]，[onGyro] 维护）在 [applyParallax] 里与 tiltX 相加 —— 承担"绕长边旋转"
     *   整个动作；tilt 链经 [unrollAcc] 反滚后只保留"非滚动"部分（俯仰 / 左右歪），两链不重复、
     *   不相斗；归位动画（本函数）对它们同样"从当前值 1s 衰减回 0"；
     * * **归位**（用户定的规则）：最近 `homeWait`（默认 [DEFAULT_HOME_WAIT_MS]，"参数调整"可调 0.2~1s）内
     *   读数变化范围 < [STILL_EPS] **且** 绕轴角速度全程 < [GYRO_EPS_RAD] → 视为静止 →
     *   把基准对齐当前姿势、并从当前偏移开始播放 [HOME_DURATION_MS] 的归位动画
     *   （偏移按 `(1−p)²` 衰减，先快后慢；**无论偏移多远都 1s**）；动画途中一有运动立即取消、继续跟随。
     */
    private fun onGravity(event: SensorEvent) {
        // v21a：灭→亮 / 亮→灭边沿驱动"层闸门"——亮屏立即显示（背景先到）；灭屏启动自淡出。
        val interactive = powerManager?.isInteractive != false
        if (interactive && !lastInteractive) {
            runCatching { reconcileLayerGate("亮屏（传感器边沿）") }
        } else if (!interactive && lastInteractive) {
            runCatching { reconcileLayerGate("灭屏（传感器边沿）") }
        }
        lastInteractive = interactive
        // 调试：合成倾角激活时不接受传感器（保持注入值直到显式释放）。
        if (!debugTiltX.isNaN()) return
        // 灭屏后不再干活（省电；唤醒后低通自然跟上）。
        if (!interactive) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastSensorAt == 0L) 20f else (now - lastSensorAt).coerceIn(5L, 200L).toFloat()
        lastSensorAt = now
        val values = event.values
        // 保存原始重力（唯一写入点；[onGravity] 的反滚要用它）。
        gravX = if (values.isNotEmpty()) values[0] else 0f
        gravY = if (values.size > 1) values[1] else 0f
        gravZ = if (values.size > 2) values[2] else 0f
        gravSeen = true
        val nx = gravX / FULL_SCALE_X
        val ny = gravZ / FULL_SCALE_Y

        if (baseX.isNaN() || baseY.isNaN()) {
            baseX = nx
            baseY = ny
            tiltX = 0f
            tiltY = 0f
            return
        }
        // 维护静止判定窗口（时长运行时可调 ——「参数调整」→「恢复原位等待时长」，默认 500ms），
        // 求变化范围 → "静止"判定。
        stillWindow.addLast(StillSample(now, nx, ny, yawRateAbs))
        val windowMs = (liveParams().homeWait * 1000f).toLong()
        while (stillWindow.size > 2 && now - stillWindow.first.at > windowMs) {
            stillWindow.removeFirst()
        }
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxYaw = 0f
        for (sample in stillWindow) {
            if (sample.nx < minX) minX = sample.nx
            if (sample.nx > maxX) maxX = sample.nx
            if (sample.ny < minY) minY = sample.ny
            if (sample.ny > maxY) maxY = sample.ny
            if (sample.yawAbs > maxYaw) maxYaw = sample.yawAbs
        }
        // v14：静止还要求"没在绕轴转"（陀螺安静）——否则原地转向期间重力读数不变、会被误判静止。
        val still = (maxX - minX) < STILL_EPS && (maxY - minY) < STILL_EPS && maxYaw < GYRO_EPS_RAD

        if (homing) {
            // v22.4：到顶触发的归位（homingByTop）**不因运动取消**——用户口径"回正期间本就不该
            // 响应输入"；快速转动中也要把这段回零播完。静止触发的维持旧语义（一动就取消）。
            val cancelByMotion = !still && !homingByTop
            if (cancelByMotion) {
                // 归位途中又动了 → 取消动画，回到跟随（基准仍是归位启动时的姿势，不会跳变）。
                homing = false
                homingByTop = false
            } else {
                val p = ((now - homeStartAt).toFloat() / HOME_DURATION_MS).coerceIn(0f, 1f)
                if (p >= 1f) {
                    homing = false
                    homingByTop = false
                    tiltX = 0f
                    tiltY = 0f
                    // 绕轴转角一并清零（v14 起；v16 解耦后它由下面的动画行直接驱动，同样到位）。
                    yawTilt = 0f
                    // v22.4：反滚角清零，**并用完成时刻的真实姿势重锚基准**——到顶归位期间用户可能
                    // 一直在转，若只在启动时锚一次，下一帧算出的倾角会与实际姿势错位 → 再次顶到
                    // 边缘 → 反复触发归位的死循环。静止归位时用户没动，重锚等价于原锚，无副作用。
                    unrollAcc = 0f
                    baseX = nx
                    baseY = ny
                } else {
                    val m = (1f - p) * (1f - p) // 先快后慢
                    tiltX = homeFromX * m
                    tiltY = homeFromY * m
                    // v16（方案 2）：yaw 解耦显示后，归位动画要**直接驱动**它（不再借道 tiltX 的低通）。
                    yawTilt = homeFromYaw * m
                }
                postApply()
                return
            }
        } else if (still) {
            // 静止 → 启动归位：基准对齐当前姿势（跟随目标变为 0），显示值从当前位置用 1s 曲线回 0。
            baseX = nx
            baseY = ny
            // v22.3：反滚角与基准**同步重锚**（"静止以来"归零）—— 新基准帧下"纯滚动=零输出"依旧成立。
            unrollAcc = 0f
            homeFromX = tiltX
            homeFromY = tiltY
            homeFromYaw = yawTilt
            homeStartAt = now
            homing = true
            homingByTop = false
            topSince = 0L
            // p=0 帧保持现偏移，下一帧开始衰减。
            postApply()
            return
        }

        // v22.3「反滚」：把当前重力按 +θ（[unrollAcc]）滚回"静止相位"后再与基准比较 —— 绕长边
        // 滚动本体已由陀螺链（ωy 积分）承担，tilt 链只保留"非滚动"部分（俯仰 / 左右歪），
        // 消除斜 45°/横放 下两链相斗与饱和（θ=0 时——竖放 / 未滚动——与旧行为逐位一致）。
        val cosU = cos(unrollAcc)
        val sinU = sin(unrollAcc)
        val rawX = ((nx * cosU + ny * sinU) - baseX).coerceIn(-1f, 1f)
        val rawY = ((ny * cosU - nx * sinU) - baseY).coerceIn(-1f, 1f)
        // 响应一阶低通（指数平滑）；α 由 dt 推导，采样间隔抖动不改变手感。
        val alpha = dt / (dt + TILT_TIME_CONSTANT_MS)
        tiltX += (rawX - tiltX) * alpha
        tiltY += (rawY - tiltY) * alpha
        // v22.4「到顶」检测（显示值 = tiltX + yawTilt·GYRO_SIGN，与 [applyParallax] 同一公式）：
        // 合成值**持续满幅** [TOP_HOLD_MS] → 判定"快速转动把画面卡在边缘"（实机：合成值可
        // 连续十几秒钉在 +1 不动）→ 启动一次归位。到顶归位由动画独占驱动回零、运动不打断
        // （homingByTop），完成时按真实姿势重锚基准。
        val combNow = tiltX + yawTilt * GYRO_SIGN
        if (abs(combNow) >= TOP_TRIGGER_COMBINED) {
            if (topSince == 0L) {
                topSince = now
            } else if (now - topSince >= TOP_HOLD_MS) {
                // 持续到顶 → 启动"到顶归位"（基准对齐当前姿势，1s 曲线回 0，运动不打断）。
                baseX = nx
                baseY = ny
                unrollAcc = 0f
                homeFromX = tiltX
                homeFromY = tiltY
                homeFromYaw = yawTilt
                homeStartAt = now
                homing = true
                homingByTop = true
                topSince = 0L
                postApply()
                return
            }
        } else if (abs(combNow) < TOP_RELEASE_COMBINED) {
            // 迟滞：明确离开顶部才清零计时（否则在阈值附近抖动会反复重新计时）。
            topSince = 0L
        }
        postApply()
    }

    /**
     * 一次陀螺事件（v14 混合；**v22.3 换源**）：只取「绕长边」（device Y）的角速度 **ωy**。
     *
     * v14 原实现取"绕重力轴投影 ω·ĝ"——出发点：其余轴的转动重力链本就有响应（避免双重计入），
     * 唯独绕重力轴转时重力投影不变、测不到（"竖着拿原地转向没反应"那个动作）。2026-10-14 三姿态
     * 取证发现：投影随姿态按 **cosθ 打折**（竖放 1.01 / 斜 45° 0.57 / 横放 0.006），且斜 45°/横放
     * 下与 tilt 链**相斗**（合成值先反向再翻正 = "不灵敏"来源）。而"绕长边旋转"动作本体 ≡ ωy
     *（三姿态 ∫ωy 均 = 物理转角、方向一致）——改取 ωy 后：竖放行为逐点不变、斜 45° 补齐打折、
     * 横放方向归一（拍板路线"ωy + 反滚"）。
     *
     * 处理：ωy 低通 → 积分成累积转角 [yawTilt]（归一化，满量程 = 「最大幅度角度」，运行时取）；
     * 同源积分出 [unrollAcc]（弧度）供 [onGravity] 反滚。v16（方案 2）起**解耦显示**：贡献直接进
     * [applyParallax] 相加（不再借道 tiltX 的第二级低通）；归位动画由 [onGravity] 直接驱动 yawTilt
     * 衰减。这里更新后**立即 postApply** —— 转动响应只差陀螺自身一级平滑（~50ms），无级联延迟。
     */
    private fun onGyro(event: SensorEvent) {
        // 调试：合成倾角激活时同样停（保持注入值）。
        if (!debugTiltX.isNaN()) return
        if (powerManager?.isInteractive == false) return
        // 重力还没读到 —— 无法做投影，先跳过（首个重力事件后自然开始）。
        if (!gravSeen) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastGyroAt == 0L) 20f else (now - lastGyroAt).coerceIn(5L, 200L).toFloat()
        lastGyroAt = now
        val v = event.values
        // v22.4：到顶归位动画期间本函数不再推进 yawTilt（动画要把它按曲线拉回 0；若这里继续积分，
        // 快速转动会把动画刚拉下去的值又推上来，归位永远回不到 0）。仍刷新静止判定用的 yawRateAbs。
        if (homing && homingByTop) {
            yawRateAbs = 0f
            return
        }
        // v22.3：改取「绕长边」（device Y）角速度 —— 用户动作"绕长边旋转"在任意姿态下都表现为 ωy
        //（三姿态实测：竖放 ∫ωy≡物理转角、斜 45°、横放 均满幅一致）；旧实现取"绕重力轴投影"
        // （ω·ĝ）随姿态按 cosθ 打折（1.01 / 0.57 / 0.006）且与 tilt 链相斗（2026-10-14 三姿态取证）。
        val yawRate = if (v.size > 1) v[1] else 0f
        // 低通（陀螺原始读数比融合重力噪声大，先平滑再积分，防噪声积分漂移）。
        val alpha = dt / (dt + GYRO_TIME_CONSTANT_MS)
        yawRateSmoothed += (yawRate - yawRateSmoothed) * alpha
        yawRateAbs = abs(yawRateSmoothed)
        // 积分成累积转角（归一化）。**不夹 ±1**：超量程由应用前的夹取自然处理、回程线性恢复；
        // ±100 只是防无界累积的保险丝（实际用不到）。
        // 满量程角**运行时取**（「最大幅度角度」滑块，15~45°；config 缺失回落默认）——
        // 若在"有非零累积时切换刻度"，旧值按旧刻度、新增量按新刻度（实际切参数总在静止态、
        // 累积≈0，无感）。
        val fullScaleRad = (liveParams().gyroDeg * PI / 180.0).toFloat()
        yawTilt = (yawTilt + yawRateSmoothed * (dt / 1000f) / fullScaleRad).coerceIn(-100f, 100f)
        // v22.3：反滚角（弧度）同源积分 —— 与 [yawTilt] 是"同一物理量、两个用途"：一个做显示
        //（归一化、被归位动画衰减），一个做 tilt 帧反滚（自然弧度、只在静止重锚时清零）。
        unrollAcc = (unrollAcc + yawRateSmoothed * (dt / 1000f)).coerceIn(-100f, 100f)
        // v16：解耦后本函数直接驱动显示值 —— 立即应用（归位动画期间照常生效，只读当前值）。
        postApply()
    }

    /** 视图操作回主线程（传感器通常投递在主线程；兜底投递线程异常的情况）。 */
    private fun postApply() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyParallax()
        } else {
            mainHandler.post { runCatching { applyParallax() } }
        }
    }

    /**
     * 应用当前倾角：背景（我们的层）与主体（系统前景层的抠像）**同向、不同系数**。
     *
     * 主体是系统视图，我们改过就负责还原（[resetSubjectShift]）；背景随层移除即可。
     * 只碰缓存引用，不做窗口搜索（50Hz 路径要够便宜）。
     */
    private fun applyParallax() {
        // 「参数调整」5 参数（2026-10-13）：从 config 快照实时取（缺失回落默认值）。
        val params = liveParams()
        // v16（方案 2）：陀螺贡献**解耦显示** —— 绕轴转角（[yawTilt]，只受陀螺链一级低通）与重力倾角
        // 在"SIGN_X 之前"的同一坐标系相加后统一夹取；它不再借道 tiltX 的第二级低通
        //（原级联 ~120ms → 现在只剩陀螺自身 ~50ms）。
        val combined = (tiltX + yawTilt * GYRO_SIGN).coerceIn(-1f, 1f)
        val sx = combined * SIGN_X
        val sy = tiltY * SIGN_Y
        // 反向开关（v22.5 语义翻转，用户 2026-10-14）：开关现在 = "使主体与背景移动方向相同"。
        // 默认关 → 背景取反 → **主体与背景反方向**（此前的默认行为）；开关打开 → 同方向。
        val bx = if (params.reverse) sx else -sx
        val by = if (params.reverse) sy else -sy
        backdropRef?.get()?.takeIf { it.isAttachedToWindow }?.let { bg ->
            // 背景放大随参数实时校正（倍率滑块走这里生效；副本重绘由签名变化触发）。
            if (bg.scaleX != params.bgScale) bg.scaleX = params.bgScale
            if (bg.scaleY != params.bgScale) bg.scaleY = params.bgScale
            val tx = bx * params.bgAmp
            val ty = by * params.bgAmp
            if (bg.translationX != tx) bg.translationX = tx
            if (bg.translationY != ty) bg.translationY = ty
        }
        subjectRef?.get()?.takeIf { it.isAttachedToWindow }?.let { subject ->
            val tx = sx * params.subjectAmp
            val ty = sy * params.subjectAmp
            if (subject.translationX != tx) subject.translationX = tx
            if (subject.translationY != ty) subject.translationY = ty
            // 常驻放大（默认 pivot=视图中心；两者都是全屏层，等于绕屏幕中心放大）。
            // 与背景倍数可不同——副本补偿按各自矩阵算，比例差也逐位对齐。
            if (subject.scaleX != params.subjectScale) subject.scaleX = params.subjectScale
            if (subject.scaleY != params.subjectScale) subject.scaleY = params.subjectScale
            // 记下"我们动过它"——[resetSubjectShift] 只在这之后才允许写回，否则会覆盖
            // 系统自己在 AOD 下设置的 0.95 缩放（2026-11-05）。
            subjectShiftApplied = true
        }
        // 桌面主体层（v1.3.29）：锁屏那层主体是系统自己的景深 View，桌面没有；
        // 这里用 TYPE_WALLPAPER 窗口补一层，参数与锁屏完全同一套（sx/sy 不取反）。
        runCatching {
            backdropRef?.get()?.context?.applicationContext?.let { ctx ->
                ParallaxWallpaperWindow.ensure(ctx)
            }
            ParallaxWallpaperWindow.update(
                onDesk = lastStatusBarState == STATE_SHADE,
                tiltX = sx,
                tiltY = sy,
                subjectScale = params.subjectScale,
                subjectAmp = params.subjectAmp,
            )
        }
        // 【校准诊断 2026-10-21】锁屏那两层的**真实尺寸与变换**——桌面两层要对齐它们，
        // 只能以实测尺寸为准（锁屏主体是系统景深 View，不一定是屏宽高）。
        if (coverDbgTick2++ % 60 == 0) {
            val bgV = backdropRef?.get()
            val subV = subjectRef?.get()
            HookRuntime.log(
                "PDSK 锁屏层尺寸：bg=${bgV?.width}x${bgV?.height} scale=${bgV?.scaleX} " +
                    "pivot=${bgV?.pivotX},${bgV?.pivotY}；" +
                    "subj=${subV?.width}x${subV?.height} scale=${subV?.scaleX} " +
                    "pivot=${subV?.pivotX},${subV?.pivotY}",
            )
        }
        // 主体副本跟随（v8）：背景或主体"位置 / 缩放"变化时，重绘背景层（让副本逐帧跟上）。
        if (effectiveCopyEnabled()) {
            val bgView = backdropRef?.get()
            if (bgView != null) {
                val sig = subjectCopySignature(bgView)
                if (sig != subjectCopySig) {
                    subjectCopySig = sig
                    bgView.invalidate()
                }
            }
        }
        // 配置轮询（2026-10-13）：TTL 过期 → 后台重读 provider config ——
        // 「参数调整」5 参数最迟 ~4s 生效，无需重启、无需重开替换。
        if (++configTick >= CONFIG_POLL_FRAMES) {
            configTick = 0
            val lastTry = maxOf(configAt, configFailedAt)
            if (SystemClock.elapsedRealtime() - lastTry >= CONFIG_TTL_MS) {
                refreshConfigAsync()
            }
        }
        // 诊断探针（v7）：低频记录背景层处境（解锁 / 下拉通知中心时的问题定位用）。
        if (++probeTick >= PROBE_INTERVAL_FRAMES) {
            probeTick = 0
            probeLayerState()
        }
        // 「视差效果覆盖桌面」第二步（2026-10-21）：桌面状态下把同一套缩放/平移
        // 下发给桌面壁纸层（`WallpaperWindowToken` 的 leash），锁屏仍由我们自己的 ImageView 负责。
        applyDesktopLeash(params, sx, sy, bx, by)
    }

    // ---------------------------------------------------------------- 桌面壁纸接管（2026-10-21）

    private var desktopLoader: ClassLoader? = null
    private var coverFlag = false
    private var coverFlagAt = 0L

    /**
     * 最近一次「开始/关闭替换」指令的明确状态（`null` = 还没收到过指令）。
     *
     * **为什么要它**：prefs 文件通道有延迟/缓存 —— 实测点「立即替换」后
     * `remoteBoolean(KEY_PARALLAX_ACTIVE)` 仍返回旧的 false，桌面层永远建不起来
     * （表现为每帧都调 destroy、层怎么都出不来）。广播指令里的 `on` 是权威的，优先於 prefs。
     */
    private var commandOn: Boolean? = null

    /**
     * 最近一次「视差效果覆盖桌面」选项的明确状态（`null` = 还没收到过通知）。
     *
     * 与 [commandOn] 同一个理由：prefs 文件通道会读到旧值，实测把选项关掉后
     * hook 侧仍读到 true，导致「无论开不开都在桌面建层」。选项变化时 App 直接发广播，
     * 这里收到就记下来，优先于 prefs。
     */
    private var commandCover: Boolean? = null

    /** 关闭路径的壁纸层复位是否已做过（避免每帧 reset 跟系统抢控制权）。 */
    private var desktopResetDone = false

    /** 桌面接管的节流诊断计数（排查「桌面不动」用，正式版可删）。 */
    private var coverDbgTick = 0

    /** 上一次是否处于「镜像锁屏视图」状态（用于只在翻转时打日志）。 */
    private var lastMirrored = false
    private var coverDbgTick2 = 0

    /** 锁屏主体 View 的 pivotY（实测 1068≈屏高 40%）；桌面态读不到就沿用最后一次的值。 */
    private var subjPivotYCached = Float.NaN

    /** 桌面态的背景图（锁屏那份缓存可能没加载过，这里单独存一份）。 */
    private var desktopBgBitmap: Bitmap? = null
    private var desktopBgLoading = false

    /** 上一次打印过的 sx（变化 > 0.2 才再打，避免刷屏又能看到真实倾角）。 */
    private var lastSxLogged = 0f

    /**
     * 桌面层级该不该存在（500ms 缓存）。
     *
     * **语义（用户 2026-10-21 定）**：桌面层级 = **总开关 AND「视差效果覆盖桌面」选项**。
     * - 总开关 = 视差二级页的「立即替换 / 关闭替换」（`KEY_PARALLAX_ACTIVE`）。
     *   它关掉时，桌面层级**必须**跟着销毁（整套视差都撤了）。
     * - 「视差效果覆盖桌面」= 只决定**要不要在桌面建层**（`KEY_PARALLAX_COVER_DESKTOP`）。
     *   总开关开着、只把它关掉 ⇒ **桌面层级单独销毁**，锁屏视差继续正常工作。
     *
     * 读 prefs 而不是系统属性：root 写属性失败是静默的，会让开关完全没反应。
     */
    private fun desktopCoverEnabled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - coverFlagAt < 500L) return coverFlag
        coverFlagAt = now
        // **总开关**：指令优先，其次 `config.active`（provider 通道，3s 轮询）。
        // **不要用 prefs**：`remoteBoolean` 的框架通道会返回缓存旧值 ——
        // 新进程启动时读到旧 false，层就永远建不出来（实测）。
        val total = commandOn ?: config?.active
        // **读不到就保持上一次的状态**，绝不能当成「关」——
        // `config` 每 3s 轮询、provider 偶尔超时会返回 null，判成 false 会导致
        // 层「建 → 1 秒后删 → 再建」反复抖动（实测日志里成对出现）。
        if (total == null) return coverFlag
        if (!total) {
            coverFlag = false
            return false
        }
        val cover = commandCover ?: coverViaProvider()
        if (cover == null) return coverFlag
        coverFlag = cover
        return coverFlag
    }

    /** 通过 provider 读「视差效果覆盖桌面」（实时、零副作用）。 */
    private fun coverViaProvider(): Boolean? {
        val ctx = HookRuntime.appContext() ?: return null
        return runCatching {
            ctx.contentResolver
                .call(MaskProvider.configUri(), MaskProvider.METHOD_PARALLAX_COVER, null, null)
                ?.getBoolean(MaskProvider.KEY_PARALLAX_COVER_DESKTOP, false)
        }.getOrNull()
    }

    /** 只在桌面（SHADE）且开关打开时接管；锁屏/上拉期间完全不动。 */
    private fun applyDesktopLeash(
        params: ParallaxConfig,
        sx: Float,
        sy: Float,
        bx: Float,
        by: Float,
    ) {
        if (!desktopCoverEnabled()) {
            // 开关关掉：销毁两层，并把壁纸层复位。
            // **必须只做一次**：`reset()` 内部要反射拿 leash + 下发 setMatrix + apply，
            // 每帧跑等于以 50Hz 跟系统抢壁纸层的控制权 —— 多任务壁纸视图会被撕成抽搐。
            ParallaxSubjectLayer.destroy()
            if (!desktopResetDone) {
                desktopResetDone = true
                desktopLoader?.let {
                    runCatching { ParallaxDesktopLeash.reset(it, 0, 0) }
                }
            }
            return
        }
        // 重新启用：允许下次关闭时再复位一次。
        desktopResetDone = false
        if (lastStatusBarState != STATE_SHADE) {
            // **不在桌面（多任务/通知中心/锁屏）：不要 hide**。
            // hide 是 alpha 0↔1 来回切，状态抖动时每帧切一次 = 画面**闪烁抽搐**。
            // 而且我们的层是壁纸层的子层，壁纸被系统缩放时它会跟着缩放 —— 保持可见才自然。
            //
            // **但锁屏态要预建层**（2026-10-21）：指纹直接解锁时锁屏 View 瞬间消失，
            // 如果直到进桌面才建层，建层+首绘（1760×3230 一次全屏绘制约 20~30ms）
            // 会露出 1~2 帧系统壁纸。锁屏时壁纸 token 是 `flags:1` 隐藏的，
            // 我们的层挂在它下面天然不可见，**预建完全无副作用**，解锁时直接可用。
            if ((lastStatusBarState == STATE_KEYGUARD || lastStatusBarState == STATE_SHADE_LOCKED) &&
                desktopCoverEnabled()
            ) {
                val loader0 = desktopLoader
                if (loader0 != null) {
                    val dm0 = android.content.res.Resources.getSystem().displayMetrics
                    val bmp0 = imageBitmap ?: desktopBgBitmap
                    if (bmp0 != null &&
                        ParallaxDesktopLeash.ensure(
                            loader0,
                            dm0.widthPixels,
                            dm0.heightPixels,
                            bmp0,
                        )
                    ) {
                        if (!ParallaxSubjectLayer.hasBackground()) {
                            ParallaxSubjectLayer.setBackgroundBitmap(bmp0)
                        }
                        // **预建之后必须立刻把变换摆到锁屏当前值**（2026-10-21）。
                        // `attach` 出来的层变换是初始态（matrix identity + position 0），
                        // 内容会停在"偏右下 MARGIN"的位置。指纹直接解锁时 `#41` 一从隐藏变可见，
                        // 层就带着这个**错的位置**露出来，1~2 帧后才被 redraw 纠正 ——
                        // 用户看到的就是"没来得及反应的几帧"。
                        // 锁屏时层本身不可见（父层 `flags:1`），提前摆好零副作用。
                        val bgV = backdropRef?.get()
                        val sjV = subjectRef?.get()
                        val sjPvY0 = if (!subjPivotYCached.isNaN()) {
                            subjPivotYCached
                        } else {
                            dm0.heightPixels * 0.4f
                        }
                        var bTx = 0f
                        var bTy = 0f
                        var bScl = liveParams().bgScale
                        var bPx = dm0.widthPixels / 2f
                        var bPy = dm0.heightPixels / 2f
                        var sTx = 0f
                        var sTy = 0f
                        var sScl = liveParams().subjectScale
                        var sPx = dm0.widthPixels / 2f
                        var sPy = sjPvY0
                        if (bgV != null && bgV.isAttachedToWindow) {
                            bTx = bgV.translationX
                            bTy = bgV.translationY
                            bScl = bgV.scaleX
                            bPx = bgV.pivotX
                            bPy = bgV.pivotY
                            sjV?.let {
                                sTx = it.translationX
                                sTy = it.translationY
                                sScl = it.scaleX
                                sPx = it.pivotX
                                sPy = it.pivotY
                            }
                        }
                        ParallaxSubjectLayer.redraw(
                            ParallaxSubjectLayer.bgLayer,
                            ParallaxSubjectLayer.backgroundBitmap(),
                            bScl,
                            bTx,
                            bTy,
                            bPx,
                            bPy,
                            dm0.widthPixels,
                            dm0.heightPixels,
                        )
                        ParallaxSubjectLayer.redraw(
                            ParallaxSubjectLayer.subjectLayer,
                            ParallaxSubjectLayer.subjectBitmap(),
                            sScl,
                            sTx,
                            sTy,
                            sPx,
                            sPy,
                            dm0.widthPixels,
                            dm0.heightPixels,
                        )
                    }
                }
            }
            return
        }
        val loader = desktopLoader ?: return
        val dm = android.content.res.Resources.getSystem().displayMetrics
        // 桌面态锁屏层不在，`imageBitmap`（锁屏背景那份缓存）可能从没加载过。
        // 这时后台从 provider 读一次 `parallax_bg.png` 补上（IO，不能在主线程做）。
        if (imageBitmap == null && desktopBgBitmap == null && !desktopBgLoading) {
            desktopBgLoading = true
            val ctx = HookRuntime.appContext()
            Thread {
                val bmp = ctx?.let { runCatching { decodeBackdrop(it, targetEdge(it)) }.getOrNull() }
                if (bmp != null) {
                    desktopBgBitmap = bmp
                    HookRuntime.log("PDSK 桌面背景已加载（${bmp.width}x${bmp.height}）")
                }
                desktopBgLoading = false
            }.apply { isDaemon = true }.start()
        }
        val bgBmp = imageBitmap ?: desktopBgBitmap
        if (!ParallaxDesktopLeash.ensure(loader, dm.widthPixels, dm.heightPixels, bgBmp)) return
        if (bgBmp != null && !ParallaxSubjectLayer.hasBackground()) {
            ParallaxSubjectLayer.setBackgroundBitmap(bgBmp)
        }
        // 两层各自按锁屏那套 View 变换重画：dst 矩形 = centerCrop 视口按 scale（绕各自 pivot）
        // 放大后平移 —— 与锁屏 `ImageView` 的 scaleX/scaleY + translationX/Y 完全同义。
        // 主体 pivot 用锁屏实测值 (600,1068)≈屏高 40%，背景用屏幕中心 (600,1335)。
        val subjView = subjectRef?.get()
        if (subjView != null && subjView.pivotY > 0f) subjPivotYCached = subjView.pivotY
        val subjPivotY =
            if (!subjPivotYCached.isNaN()) subjPivotYCached else dm.heightPixels * 0.4f

        // ---- 无缝交接（2026-10-21）----
        // 解锁瞬间锁屏那两个 ImageView 还在（退出动画中），**直接镜像它们的
        // translationX/scaleX/pivot**，桌面层就从锁屏当前的位置继续，做到零跳变。
        // 锁屏视图不可见后（`isAttachedToWindow` 变 false）自动切回传感器值 ——
        // 边界由系统自己画，不需要计时器。
        val bgView = backdropRef?.get()
        val mirror = bgView != null && bgView.isAttachedToWindow &&
            bgView.visibility == android.view.View.VISIBLE
        var bgTx = bx * params.bgAmp
        var bgTy = by * params.bgAmp
        var bgScl = params.bgScale
        var bgPvX = dm.widthPixels / 2f
        var bgPvY = dm.heightPixels / 2f
        var sjTx = sx * params.subjectAmp
        var sjTy = sy * params.subjectAmp
        var sjScl = params.subjectScale
        var sjPvX = dm.widthPixels / 2f
        var sjPvY = subjPivotY
        if (mirror) {
            bgTx = bgView!!.translationX
            bgTy = bgView.translationY
            bgScl = bgView.scaleX
            bgPvX = bgView.pivotX
            bgPvY = bgView.pivotY
            subjView?.let {
                sjTx = it.translationX
                sjTy = it.translationY
                sjScl = it.scaleX
                sjPvX = it.pivotX
                sjPvY = it.pivotY
            }
        }
        ParallaxSubjectLayer.show()
        ParallaxSubjectLayer.redraw(
            ParallaxSubjectLayer.bgLayer,
            ParallaxSubjectLayer.backgroundBitmap(),
            bgScl,
            bgTx,
            bgTy,
            bgPvX,
            bgPvY,
            dm.widthPixels,
            dm.heightPixels,
        )
        ParallaxSubjectLayer.redraw(
            ParallaxSubjectLayer.subjectLayer,
            ParallaxSubjectLayer.subjectBitmap(),
            sjScl,
            sjTx,
            sjTy,
            sjPvX,
            sjPvY,
            dm.widthPixels,
            dm.heightPixels,
        )
        // 交接状态翻转时打一条（便于确认镜像是否真的接管了）。
        if (mirror != lastMirrored) {
            lastMirrored = mirror
            HookRuntime.log(
                if (mirror) {
                    "PDSK 无缝交接：镜像锁屏视图（bg=${bgTx},${bgTy} scale=$bgScl " +
                        "pivot=$bgPvX,$bgPvY）"
                } else {
                    "PDSK 无缝交接：锁屏视图已消失，切回传感器"
                },
            )
        }
        if (coverDbgTick++ % 40 == 0 || kotlin.math.abs(sx - lastSxLogged) > 0.2f) {
            lastSxLogged = sx
            HookRuntime.log(
                "PDSK 下发 sx=$sx sy=$sy bgTx=$bgTx bgTy=$bgTy " +
                    "subjTx=$sjTx 状态=$lastStatusBarState 镜像=$mirror",
            )
        }
    }

    /** 配置轮询的帧计数（[applyParallax] 里用；只在传感器帧的路径上递增）。 */
    private var configTick = 0

    /**
     * 后台重读一次 config（传感器帧触发的低频路径，~3s 一次）。
     *
     * 读到且**值有变化**（data class equals 覆盖全部字段）时回主线程立即应用一次 ——
     * 免得等到下一个传感器帧才把新倍率 / 新幅度落到画面上。
     */
    private fun refreshConfigAsync() {
        val app = backdropRef?.get()?.context?.applicationContext ?: return
        val before = config
        val worker = Thread {
            val fresh = runCatching { parallaxConfig(app, force = true) }.getOrNull()
            if (fresh != null && fresh != before) {
                mainHandler.post { runCatching { applyRuntimeParams() } }
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    /** 参数变化后的立即应用（主线程）：[applyParallax] 覆盖 scale 校正 + 位移重算 + 副本重绘。 */
    private fun applyRuntimeParams() {
        val params = liveParams()
        HookRuntime.log(
            "视差壁纸效果：参数已应用 ⇒ 反向=${params.reverse}，主体放大=${params.subjectScale}，" +
                "背景放大=${params.bgScale}，主体幅度=${params.subjectAmp.toInt()}px，" +
                "背景幅度=${params.bgAmp.toInt()}px，归位等待=${params.homeWait}s，" +
                "最大幅度角=${params.gyroDeg.toInt()}°",
        )
        applyParallax()
    }

    // ---------------------------------------------------------------- 主体副本的跟随触发（v8）

    /** 上一帧的"背景 × 主体位置"签名（变化才触发背景层重绘；静止时不刷帧）。 */
    private var subjectCopySig = ""

    private val subjectCopyLoc = IntArray(2)

    /** 轻量签名：主体全局位置 / 缩放 + 背景层自身位移与缩放——任何一项变化都意味着副本需要重绘。 */
    private fun subjectCopySignature(bg: BackdropView): String {
        val sb = StringBuilder(64)
        subjectRef?.get()?.let { subject ->
            runCatching { subject.getLocationInWindow(subjectCopyLoc) }
            sb.append(subjectCopyLoc[0]).append(',').append(subjectCopyLoc[1])
            sb.append(',').append((subject.scaleX * 1000f).toInt())
            sb.append(',').append((subject.scaleY * 1000f).toInt())
        }
        sb.append('|').append(bg.translationX.toInt()).append(',').append(bg.translationY.toInt())
        // 背景层缩放也进签名（2026-10-13）：倍率滑块改 bgScale 时（静止态位移不变），
        // 也要 invalidate 让 onDraw 用新的 G_本层 重算 combined——否则副本会停在旧补偿上。
        sb.append(',').append((bg.scaleX * 1000f).toInt())
        return sb.toString()
    }

    // ---------------------------------------------------------------- 内容抑制（v9 起；v10 加"状态机"第二道闸；v18 重做时序）

    /**
     * 内容抑制开关：我们的绘制内容不该在"会出问题"的场景被系统捕获管线取走
     * （典型：解锁后下拉通知中心，背景出现我们的图）。处理：**不该存在时完全不绘制**
     * （背景图 + 主体副本都不画），回到锁屏并能被看见时恢复。
     *
     * 2026-10-13 日志实证后重做（v18，修用户实测"灭屏→亮屏 / 上拉打开解锁界面 背景整片闪变"）：
     * * 实测 1：上拉 / bouncer 时系统 `KeyguardPanelViewController#updateVisibility` 会让本层经历
     *   **~7~17ms 的可见性瞬闪**；旧逻辑"瞬闪即即时清空、恢复等 700ms"→ 背景黑 0.7 秒再回来
     *   （用户描述"先没后回"，实测 55 秒内 39 次循环）；
     * * 实测 2：亮屏时清空后的恢复要等 700ms → 内容晚于过渡出现（"跳一下，再到我们的视图"）。
     *
     * 新时序（两端都对准"观众的时刻"）：
     * ① **清空**：状态机离开锁屏（桌面态）→ **立即清空**（权威判据，防捕获核心不变）；
     *    层不可见 → 需**持续 [CLEAR_DELAY_MS] 才清空**（吸收过渡瞬闪；期间重新可见即取消）；
     *    **屏幕灭着（v20 / 方案 B）→ 不清空**——灭屏期间无捕获场景，内容保留到亮屏
     *    （亮屏时一直在，零延迟、无恢复动作）；清空只针对"屏幕亮着但层不可见"；
     * ② **恢复**：条件（锁屏在场 + 层可见）满足 → **立即恢复**（v19——state 已知时同帧恢复，
     *    0ms；其防护职责已由"桌面态闸门 + 清空防抖"独立承担，定时确认只会引入可感知延迟）；
     *    state 未知（hook 失灵兜底）时保守走 [RESTORE_DEBOUNCE_UNKNOWN_STATE_MS] 确认窗。
     *
     * 事件源：`View#onVisibilityAggregated` hook（零延迟）+ `setState` hook + 传感器帧低频轮询（兜底）。
     */
    @Volatile
    private var contentSuppressed = false

    /** 最近一次 `setState` 收到的状态（0=SHADE/桌面、1=KEYGUARD/锁屏、2=SHADE_LOCKED/锁屏上拉；Int.MIN_VALUE=未知）。 */
    @Volatile
    private var lastStatusBarState = Int.MIN_VALUE

    /** "清空"的防抖（ms）：层持续不可见 ≥ 这个时长才清空（吸收过渡瞬闪；实测瞬闪 ~7~17ms）。 */
    private const val CLEAR_DELAY_MS = 600L

    /** state 未知（hook 失灵）时的恢复确认时长（ms）：保守值，防"解锁后瞬显 + 误恢复"。
     *  （state 已知时**不走确认窗**——可见即恢复，见 [schedulePendingRestore]。） */
    private const val RESTORE_DEBOUNCE_UNKNOWN_STATE_MS = 700L

    /** "延迟清空" / "恢复"任务是否已挂起（主线程操作）。 */
    private var clearPending = false
    private var restorePending = false

    /** 最近一次触发对账的 reason（供延迟任务的日志使用）。 */
    @Volatile
    private var lastReconcileReason = ""

    /** 内容只在"锁屏在场"时允许存在；状态未知（还没收到过 setState）时允许，退回可见性判断。 */
    private fun keyguardContentAllowed(): Boolean {
        val s = lastStatusBarState
        return s == Int.MIN_VALUE || s == STATE_KEYGUARD || s == STATE_SHADE_LOCKED
    }

    /**
     * 屏幕是否亮着（interactive）——v20"灭屏不清空"的判据（与传感器省电逻辑同一个信号）。
     * [powerManager] 缺省（还没接入过传感器组件）时视为"亮"：保守走会清空的旧行为。
     */
    private fun screenInteractive(): Boolean = powerManager?.isInteractive ?: true

    /** 延迟清空任务：到期时仍"不可见或不在锁屏"才真清空（期间恢复可见则自然落空）。 */
    private val pendingClearTask = Runnable {
        clearPending = false
        val v = backdropRef?.get() ?: return@Runnable
        if (!v.isAttachedToWindow) return@Runnable
        val shown = runCatching { v.isShown }.getOrDefault(false)
        if (shown && keyguardContentAllowed()) return@Runnable
        // v20（方案 B）：屏幕灭着时**不清空**——灭屏期间没有"捕获场景"（无人操作界面），
        // 内容保留到亮屏（亮屏时内容一直在，连恢复动作都没有）。清空只针对"屏幕亮着但
        // 层不可见"（解锁后 / hook 失灵兜底）。到期时再读——灭屏过渡中挂起的清空会在
        // 屏幕已灭后到期，自然被跳过。
        if (!screenInteractive()) {
            logOnce("clear-skip-screen-off", "清空已跳过（屏幕灭着，内容保留至亮屏；${lastReconcileReason}）")
            return@Runnable
        }
        suppressContentNow("持续不可见 ≥${CLEAR_DELAY_MS}ms 且屏幕亮着，${lastReconcileReason}")
    }

    /** 恢复任务（仅 state 未知路径用）：到期时条件（锁屏在场 + 层可见）仍满足才恢复。 */
    private val pendingRestoreTask = Runnable {
        restorePending = false
        restoreContentNow("${RESTORE_DEBOUNCE_UNKNOWN_STATE_MS}ms 确认")
    }

    /**
     * 执行"恢复绘制"（复查条件、幂等）——[schedulePendingRestore] 的立即路径与
     * [pendingRestoreTask] 的确认路径共用；[desc] 只进日志。
     */
    private fun restoreContentNow(desc: String) {
        val v = backdropRef?.get() ?: return
        if (!v.isAttachedToWindow) return
        val shown = runCatching { v.isShown }.getOrDefault(false)
        if (!shown || !keyguardContentAllowed()) return
        if (!contentSuppressed) return
        contentSuppressed = false
        runCatching { v.invalidate() }
        HookRuntime.log("视差壁纸效果：背景层内容已恢复绘制（锁屏可见，$desc；${lastReconcileReason}）")
    }

    /**
     * 内容抑制对账（幂等）——三个事件源（可见性回调 / 状态机 / 传感器帧轮询）共用：
     *
     * * 桌面态 → 立即清空（并取消其余挂起任务）；
     * * 不可见 → 挂起"延迟清空"（[CLEAR_DELAY_MS]；重复调用不重置计时）；
     * * 锁屏可见 → 取消"延迟清空"；如需恢复则**立即恢复**（state 未知时走 [RESTORE_DEBOUNCE_UNKNOWN_STATE_MS] 保守确认窗）。
     */
    private fun reconcileContentVisibility(reason: String) {
        val view = backdropRef?.get() ?: return
        if (!view.isAttachedToWindow) return
        lastReconcileReason = reason

        if (!keyguardContentAllowed()) {
            cancelPendingClear()
            cancelPendingRestore()
            // v22.1：解锁退场渐隐进行中——清空让位于渐隐（渐隐完成时统一清空）。
            if (unlockFading) return
            suppressContentNow("桌面态，$reason")
            return
        }
        val shown = runCatching { view.isShown }.getOrDefault(false)
        if (!shown) {
            cancelPendingRestore()
            schedulePendingClear()
            return
        }
        cancelPendingClear()
        schedulePendingRestore()
    }

    /** 立即清空（幂等；"内容不存在"是抑制的目标状态）。 */
    private fun suppressContentNow(reason: String) {
        if (contentSuppressed) return
        contentSuppressed = true
        runCatching { backdropRef?.get()?.invalidate() }
        HookRuntime.log("视差壁纸效果：背景层内容已清空（$reason；state=$lastStatusBarState）")
    }

    private fun schedulePendingClear() {
        if (clearPending || contentSuppressed) return
        clearPending = true
        mainHandler.postDelayed(pendingClearTask, CLEAR_DELAY_MS)
    }

    private fun cancelPendingClear() {
        if (!clearPending) return
        clearPending = false
        mainHandler.removeCallbacks(pendingClearTask)
    }

    private fun schedulePendingRestore() {
        if (!contentSuppressed) return
        if (lastStatusBarState != Int.MIN_VALUE) {
            // state 已知：**立即恢复**（v19）——若之前挂着"未知态"的确认任务，一并取消。
            cancelPendingRestore()
            restoreContentNow("立即")
            return
        }
        // state 未知（hook 失灵兜底）：保守确认窗（已挂起则不重置计时）。
        if (restorePending) return
        restorePending = true
        mainHandler.postDelayed(pendingRestoreTask, RESTORE_DEBOUNCE_UNKNOWN_STATE_MS)
    }

    private fun cancelPendingRestore() {
        if (!restorePending) return
        restorePending = false
        mainHandler.removeCallbacks(pendingRestoreTask)
    }

    // ---------------------------------------------------------------- v22.1：解锁退场渐隐（0.2s 线性）

    /**
     * v22.1「解锁退场渐隐」（2026-10-14 用户提案 + 拍板）：
     * `state 1/2→0`（**系统判定进入桌面**）那一刻起，本层 α 以 0.2s **线性**降到 0；
     * **清空（内容抑制）推迟到渐隐完成**——这不是放松防护：解锁动画本身 >500ms，
     * 200ms 后清空仍远早于任何"下拉"动作。渐隐中途回锁屏 / 灭屏 → 取消 + α 复位。
     * 覆盖全部解锁路径（bouncer / 上滑直接 / 指纹 / 人脸；整窗的极快解锁同一条逻辑）。
     */
    private const val UNLOCK_FADE_MS = 200L
    private const val UNLOCK_FADE_STEP_MS = 20L

    private var unlockFading = false
    private var unlockFadeTicks = 0
    private var unlockFadeFrom = 1f

    /** 渐隐逐帧驱动：线性 `α = from × (1 − p)`；每步复查"回锁屏 / 灭屏"→ 取消。 */
    private val unlockFadeTick = object : Runnable {
        override fun run() {
            if (!unlockFading) return
            val s = lastStatusBarState
            if (s == STATE_KEYGUARD || s == STATE_SHADE_LOCKED || !screenInteractive()) {
                cancelUnlockFade("回锁屏/灭屏（state=$s）")
                return
            }
            val v = backdropRef?.get()
            if (v == null || !v.isAttachedToWindow) {
                cancelUnlockFade("层不可用")
                return
            }
            unlockFadeTicks++
            val steps = (UNLOCK_FADE_MS / UNLOCK_FADE_STEP_MS).toInt().coerceAtLeast(1)
            val p = (unlockFadeTicks.toFloat() / steps).coerceIn(0f, 1f)
            val alpha = unlockFadeFrom * (1f - p)
            runCatching { if (v.alpha != alpha) v.alpha = alpha }
            if (p >= 1f) {
                unlockFading = false
                unlockFadeTicks = 0
                // 渐隐完成：**这时才**清空内容（防捕获最后防线）；整窗模式落 INVISIBLE；
                // 树内不动可见性（让位——系统收尾由继承完成）。
                suppressContentNow("解锁退场渐隐完成（0.2s 线性）")
                if (!treeAnchored) gateHideNow("解锁退场渐隐完成")
                HookRuntime.log(
                    "视差壁纸效果：解锁退场渐隐完成（α=0，内容已清空；${if (treeAnchored) "树内" else "整窗"}）",
                )
            } else {
                mainHandler.postDelayed(this, UNLOCK_FADE_STEP_MS)
            }
        }
    }

    /** 启动解锁退场渐隐（幂等；起手停掉任何 gate 自淡出，由渐隐统一收尾）。 */
    private fun startUnlockFadeOut(reason: String) {
        if (unlockFading) return
        val v = backdropRef?.get() ?: return
        if (!v.isAttachedToWindow || !v.isShown) return
        if (!screenInteractive()) return
        stopGateFade()
        unlockFading = true
        unlockFadeTicks = 0
        unlockFadeFrom = if (v.alpha > 0.01f) v.alpha.coerceAtMost(1f) else 1f
        HookRuntime.log("视差壁纸效果：解锁退场渐隐启动（0.2s 线性；$reason）")
        mainHandler.removeCallbacks(unlockFadeTick)
        mainHandler.post(unlockFadeTick)
    }

    /** 取消渐隐：停任务 + α 复位 1（内容未清——清空只在完成时做；完成后的复位由闸门显示路径管）。 */
    private fun cancelUnlockFade(reason: String) {
        if (!unlockFading) return
        unlockFading = false
        unlockFadeTicks = 0
        mainHandler.removeCallbacks(unlockFadeTick)
        runCatching { backdropRef?.get()?.let { if (it.alpha != 1f) it.alpha = 1f } }
        HookRuntime.log("视差壁纸效果：解锁退场渐隐已取消（$reason）——α 复位")
    }

    // ---------------------------------------------------------------- 搬家与层闸门（v21a，2026-10-14）

    /**
     * v21a「搬家」：背景层挂 **窗口根（NotificationShadeWindowView）index 0**，脱离
     * `KeyguardPanelView × HyperOSKeyguardRootView` 的亮灭屏淡入连乘（D1 实测：亮灭屏只有这两层在动，
     * 窗口根恒 α=1）。语义 = "壁纸替身" —— **不跟随**锁屏树的过渡动画（原生壁纸同样在动画树之外）。
     *
     * 「层闸门」自管可见性（搬家后系统不再替我们做这件事）：
     * * 亮屏（灭→亮边沿）→ **立即 VISIBLE + α=1**（背景先到，UI 从上面淡入）；
     * * 灭屏 / 解锁退场 → **自淡出**：每 [GATE_FADE_STEP_MS]ms 读一次两层真值
     *   （`alpha × transitionAlpha` 乘积）乘到本层 α；≈0 或超时 → INVISIBLE（内容保留，v20 不变）。
     * * 对账信号：状态机 + 灭亮屏边沿/广播 + `keyguardGoingAway` + 低频轮询（幂等）。
     */

    /** 淡出驱动步长与安全上限（真值曲线读不到时按 ~400ms 线性兜底）。 */
    private const val GATE_FADE_STEP_MS = 20L
    private const val GATE_FADE_MAX_TICKS = 60

    // ---------------------------------------------------------------- v22：双挂载点（整窗 / 树内）动态切换
    //
    // 结论（2026-10-14 取证 + 用户拍板）：整窗只在"亮屏淡入"阶段需要（背景先到、无交叉混合）；
    // 亮屏稳定之后回到锁屏树内（keyguard_background_layer index0），由系统继承带着走——
    // 编辑器缩放假/解锁退场/上拉全恢复"树内历史行为"（用户实测记忆"树内时解锁没问题"）。
    //
    // 状态机：灭屏后 → 整窗（INVISIBLE，等下次亮屏）；亮屏稳定（~800ms）→ 入树；
    // 按钮出现（长按弹出"自定义锁屏"菜单）/ 编辑器状态非 IDEL → 提前入树（幂等）；
    // 灭屏后（~500ms 稳定）→ 出树回整窗。树内期间"层闸门"让位（可见性/α 交还系统继承），
    // 只保留内容抑制对账；整窗期间闸门全功能（v21a 行为不变）。

    /** v22：挂载模式——false=整窗（窗口根 index0）、true=树内（keyguard_background_layer index0）。 */
    private var treeAnchored = false

    /** v22：入树时机——首检延迟 + 轮询等到"亮屏淡入完成"（treeFade≈1）再搬（防半透明期入树）。 */
    private const val ENTER_TREE_FIRST_DELAY_MS = 400L
    private const val ENTER_TREE_POLL_MS = 100L
    private const val ENTER_TREE_MAX_POLLS = 12

    /** v22：出树延迟——灭屏编排 / 继承淡出走完后回整窗。实测可调。 */
    private const val EXIT_TREE_DELAY_MS = 500L

    private var pendingEnterTree = false
    private var pendingExitTree = false
    private var enterTreePolls = 0

    /**
     * v22：入树任务（轮询版）——首检在 [ENTER_TREE_FIRST_DELAY_MS] 后；若此刻"亮屏淡入"
     * 还没走完（treeFade < 0.99），每 [ENTER_TREE_POLL_MS] 再检，最多 [ENTER_TREE_MAX_POLLS] 次；
     * 读不到真值（引用失效）按"就绪"处理（树未建好时继承本无效果）。
     */
    private val enterTreeTask: Runnable = object : Runnable {
        override fun run() {
            val fade = treeFade()
            if (fade != null && fade < 0.99f && enterTreePolls < ENTER_TREE_MAX_POLLS) {
                enterTreePolls++
                mainHandler.postDelayed(this, ENTER_TREE_POLL_MS)
                return
            }
            pendingEnterTree = false
            enterTreePolls = 0
            runCatching { enterTreeNow("亮屏稳定") }
                .onFailure { HookRuntime.log("视差壁纸效果：入树异常：$it") }
        }
    }
    private val exitTreeTask = Runnable {
        pendingExitTree = false
        runCatching { exitTreeNow("灭屏稳定") }
            .onFailure { HookRuntime.log("视差壁纸效果：出树异常：$it") }
    }

    /** 亮屏淡入的两层引用（自淡出的真值来源）：KeyguardPanelView / HyperOSKeyguardRootView。 */
    private var panelRef: WeakReference<View>? = null
    private var rootRef: WeakReference<View>? = null

    private var gateFading = false
    private var gateFadeTicks = 0

    /** 当前"显示"状态是否已记过日志（显示/淡出/隐藏三个转换各只记一次）。 */
    private var gateShownLogged: Boolean? = null

    /** 闸门：层是否应显示——屏幕亮着 && 锁屏在场（state 未知按"在场"保守处理）。 */
    private fun gateWantsVisible(): Boolean = screenInteractive() && keyguardContentAllowed()

    /** 两层淡入的真值乘积（任一引用失效返回 null → 走线性兜底）。 */
    private fun treeFade(): Float? {
        val panel = panelRef?.get() ?: return null
        val root = rootRef?.get() ?: return null
        if (!panel.isAttachedToWindow || !root.isAttachedToWindow) return null
        return panel.alpha * panel.transitionAlpha * root.alpha * root.transitionAlpha
    }

    /**
     * 闸门对账（幂等；主线程）：显示 / 淡出 / 立即隐藏三选一。
     * [animate]=false 用于"刚插入"的首态（不播动画，直接就位）。
     *
     * v22：同时兼任"挂载点调度中心"——显示方向 → 调度入树（亮屏稳定后）；隐藏方向 → 调度出树（灭屏后）。
     */
    private fun reconcileLayerGate(reason: String, animate: Boolean = true) {
        val v = backdropRef?.get() ?: return
        if (!v.isAttachedToWindow) return
        if (gateWantsVisible()) {
            gateShowNow(reason)
        } else {
            // v22：隐藏方向——可能即将灭屏 → 调度"灭屏后出树"（screenInteractive 检查在调度内）。
            scheduleExitTreeIfNeeded(reason)
            if (animate) gateStartFadeOut(reason) else gateHideNow("$reason（初始态）")
        }
    }

    /** 亮屏 / 回锁屏：立即恢复（背景先到）。v22：树内模式下让位给系统继承（只记日志 + 内容对账）。 */
    private fun gateShowNow(reason: String) {
        stopGateFade()
        // v22.1：回锁屏——若渐进隐则取消；若已渐隐完成（α=0），由下面的 α 复位拉回。
        cancelUnlockFade("闸门显示")
        // v22：亮屏稳定入树的主触发（树内 / 条件不满足时幂等跳过）。
        scheduleEnterTreeIfNeeded(reason)
        if (treeAnchored) {
            // 树内：可见性交还系统继承；α 仍是我们的值（v22.1：渐隐完成后要复位）。
            backdropRef?.get()?.let { v -> runCatching { if (v.alpha != 1f) v.alpha = 1f } }
            if (gateShownLogged != true) {
                gateShownLogged = true
                HookRuntime.log(
                    "视差壁纸效果：层闸门=显示（$reason；树内·继承托管；state=$lastStatusBarState）",
                )
                reconcileContentVisibility("闸门显示")
            }
            return
        }
        val v = backdropRef?.get() ?: return
        runCatching {
            if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
            if (v.alpha != 1f) v.alpha = 1f
        }
        if (gateShownLogged != true) {
            gateShownLogged = true
            HookRuntime.log("视差壁纸效果：层闸门=显示（$reason；state=$lastStatusBarState）")
            // 内容对账：锁屏在场 + 可见 → 立即恢复（幂等；与既有抑制逻辑共用）。
            reconcileContentVisibility("闸门显示")
        }
    }

    /** 隐藏方向：启动自淡出（读真值曲线；已隐藏或正在淡出则不动）。v22：树内模式让位（继承负责淡出）。 */
    private fun gateStartFadeOut(reason: String) {
        gateShownLogged = false
        if (treeAnchored) return // v22：树内——灭屏淡出由系统继承；出树由 [exitTreeTask] 负责
        if (unlockFading) return // v22.1：解锁渐隐进行中——由渐隐自己收尾
        val v = backdropRef?.get() ?: return
        if (!v.isAttachedToWindow || v.visibility != View.VISIBLE) return
        if (gateFading) return
        gateFading = true
        gateFadeTicks = 0
        HookRuntime.log("视差壁纸效果：层闸门=淡出（$reason；state=$lastStatusBarState）")
        mainHandler.removeCallbacks(gateFadeTick)
        mainHandler.post(gateFadeTick)
    }

    /** 立即隐藏（不淡出）——淡出完成 / 强制收尾 / 初始态用；α 复位供下次亮屏从 1 起。v22：树内让位。 */
    private fun gateHideNow(reason: String) {
        stopGateFade()
        gateShownLogged = false
        if (treeAnchored) return // v22：树内——不可见由继承完成；出树时会显式落 INVISIBLE
        if (unlockFading) return // v22.1：解锁渐隐进行中——由渐隐自己收尾
        val v = backdropRef?.get() ?: return
        runCatching {
            if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
            if (v.alpha != 1f) v.alpha = 1f
        }
        HookRuntime.log("视差壁纸效果：层闸门=隐藏（$reason）")
    }

    private fun stopGateFade() {
        gateFading = false
        mainHandler.removeCallbacks(gateFadeTick)
    }

    // ---------------------------------------------------------------- v22：入树 / 出树（挂载点切换）

    /**
     * v22 调度：亮屏稳定后入树（主触发）。
     *
     * 触发源 = 一切"显示方向"信号（[reconcileLayerGate] 显示分支 → [gateShowNow]）+ 低频探针兜底。
     * 条件：未在树内、无挂起任务、屏幕亮着、锁屏在场（state 已明确）、层已挂载。
     * 时机：[ENTER_TREE_FIRST_DELAY_MS] 首检 + 轮询等"亮屏淡入完成"（treeFade≈1）——搬运无观感。
     */
    private fun scheduleEnterTreeIfNeeded(reason: String) {
        if (treeAnchored || pendingEnterTree) return
        if (!gateWantsVisible()) return
        val s = lastStatusBarState
        if (s != STATE_KEYGUARD && s != STATE_SHADE_LOCKED) return // 保守：state 未明确不入树
        val v = backdropRef?.get() ?: return
        if (!v.isAttachedToWindow) return
        pendingEnterTree = true
        enterTreePolls = 0
        mainHandler.postDelayed(enterTreeTask, ENTER_TREE_FIRST_DELAY_MS)
    }

    /**
     * v22 调度：灭屏后出树（回整窗，为下次亮屏"背景先到"做准备）。
     *
     * 触发源 = 一切"隐藏方向"信号（[reconcileLayerGate] 隐藏分支）+ 低频探针兜底。
     * 只在"屏幕已灭"时生效（解锁等亮屏下的隐藏不调度）；延迟 [EXIT_TREE_DELAY_MS]——
     * 等灭屏编排 / 继承淡出走完，且期间若又亮屏（快速灭亮）则到期检查时放弃（保持树内）。
     */
    private fun scheduleExitTreeIfNeeded(reason: String) {
        if (!treeAnchored || pendingExitTree) return
        if (screenInteractive()) return
        pendingExitTree = true
        mainHandler.postDelayed(exitTreeTask, EXIT_TREE_DELAY_MS)
    }

    /** v22：立即入树（按钮出现 / 编辑器状态 / 稳定入树到期）——主线程，[insertIfNeeded] 内同帧完成搬运。 */
    private fun enterTreeNow(reason: String) {
        // 进入即"消费"本次挂起——即使下面条件不满足也放弃本次（下个信号会重新调度）。
        pendingEnterTree = false
        enterTreePolls = 0
        mainHandler.removeCallbacks(enterTreeTask)
        if (treeAnchored) return
        val v = backdropRef?.get() ?: return
        if (!v.isAttachedToWindow) return
        if (!gateWantsVisible()) return
        val s = lastStatusBarState
        if (s != STATE_KEYGUARD && s != STATE_SHADE_LOCKED) return
        val context = v.context ?: return
        val slot = resolveBgLayer(context) ?: return // 槽拿不到（树未就绪）→ 放弃本次，低频对账会再试
        insertIfNeeded(slot, slot)
        if (treeAnchored) {
            HookRuntime.log("视差壁纸效果：已入树（$reason）——keyguard_background_layer index0（继承系统过渡）")
        }
    }

    /** v22：出树（灭屏后）——搬回窗口根 + 落 INVISIBLE（屏幕灭着，无观感）。 */
    private fun exitTreeNow(reason: String) {
        // 进入即"消费"本次挂起——即使下面条件不满足也放弃本次（下次灭屏会重新调度）。
        pendingExitTree = false
        mainHandler.removeCallbacks(exitTreeTask)
        if (!treeAnchored) return
        if (screenInteractive()) return // 到期时已回亮（快速灭亮）→ 保持树内
        val v = backdropRef?.get() ?: return
        val context = v.context ?: return
        val anchor = resolveBgLayer(context) ?: subjectView(context) ?: return
        insertIfNeeded(anchor, null)
        if (!treeAnchored) {
            gateHideNow("灭屏出树落位")
            HookRuntime.log("视差壁纸效果：已出树（$reason）——窗口根 index0 + INVISIBLE（等下次亮屏）")
        } else {
            logOnce("exit-tree-fail", "视差壁纸效果：出树未完成（窗口根拿不到？）——下次亮屏兜底")
        }
    }

    /** v22：挂载点低频对账（探针帧兜底）——该树内而整窗 → 调度入树；灭屏而仍在树内 → 调度出树。 */
    private fun reconcileAnchorMode(reason: String) {
        if (treeAnchored) {
            if (!screenInteractive()) scheduleExitTreeIfNeeded(reason)
        } else {
            scheduleEnterTreeIfNeeded(reason)
        }
    }

    /** 自淡出逐帧驱动：读真值 → 写上屏 α；到 0 / 超时 → 隐藏；期间回亮/回锁屏 → 立即恢复。 */
    private val gateFadeTick = object : Runnable {
        override fun run() {
            if (!gateFading) return
            if (treeAnchored) { // v22：树内不做自淡出（双保险；正常不会走到）
                stopGateFade()
                return
            }
            val v = backdropRef?.get()
            if (v == null || !v.isAttachedToWindow) {
                stopGateFade()
                return
            }
            if (gateWantsVisible()) {
                gateFading = false
                gateShowNow("淡出中回锁屏/回亮")
                return
            }
            gateFadeTicks++
            val truth = treeFade()
            val alpha = truth
                ?: (1f - gateFadeTicks * GATE_FADE_STEP_MS / 400f).coerceIn(0f, 1f)
            runCatching { if (v.alpha != alpha) v.alpha = alpha }
            if (alpha <= 0.02f || gateFadeTicks >= GATE_FADE_MAX_TICKS) {
                gateHideNow("淡出完成（α=${String.format(java.util.Locale.US, "%.2f", alpha)}，真值=${truth != null}）")
            } else {
                mainHandler.postDelayed(this, GATE_FADE_STEP_MS)
            }
        }
    }

    /** 从任意锚点向上找窗口根（parent 非 View 的那层）——搬家目标宿主。 */
    private fun windowRootOf(v: View): ViewGroup? {
        var p: View = v
        var guard = 0
        while (guard++ < 30) {
            val up = p.parent as? View ?: break
            p = up
        }
        return p as? ViewGroup
    }

    /**
     * 解析两层的"淡入源"引用（自淡出的真值）：从锚点向上找窗口根，其直系一支 = 锁屏根
     * （HyperOSKeyguardRootView）；其中包着锚点的下一级 = KeyguardPanelView。
     * （v22：宿主有整窗 / 树内两处，统一从"窗口根视角"解析——入树 / 出树搬移后都要重调。）
     * 解析结果打一行日志（类名）——以后 SystemUI 改树时一眼看出。
     */
    private fun resolveFadeRefs(anchor: View) {
        runCatching {
            val host = windowRootOf(anchor) ?: return@runCatching
            var rootChild: View? = null
            var v: View = anchor
            var guard = 0
            while (guard++ < 30) {
                val up = v.parent as? View ?: break
                if (up === host) {
                    rootChild = v
                    break
                }
                v = up
            }
            val rc = rootChild ?: return@runCatching
            rootRef = WeakReference(rc)
            var p: View = anchor
            var g2 = 0
            while (g2++ < 30) {
                val up = p.parent as? View ?: break
                if (up === rc) {
                    panelRef = WeakReference(p)
                    break
                }
                p = up
            }
            HookRuntime.log(
                "视差壁纸效果：淡入源解析 ⇒ 根支=${rc.javaClass.simpleName}，面板层=" +
                    "${panelRef?.get()?.javaClass?.simpleName ?: "未找到"}",
            )
        }
    }

    /** 窗口根子级清单（一次性诊断：确认 index 0 位置正确）。 */
    private fun childrenListOf(host: ViewGroup): String {
        val sb = StringBuilder()
        for (i in 0 until host.childCount) {
            if (i > 0) sb.append(", ")
            val c = host.getChildAt(i)
            sb.append(i).append(':').append(c.javaClass.simpleName)
                .append("(0x").append(Integer.toHexString(c.id)).append(')')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 层状态探针（v7 诊断，定位完成后可整体删除）

    private var probeTick = 0
    private var probeShown = -1
    private var probeAlphaPct = -1
    private var probeWinY = Int.MIN_VALUE
    private var probeW = -1
    private var probeH = -1
    private var probeTransY = Float.NaN

    /**
     * 低频记录背景层的"真实处境"：可见性 / 祖先 alpha 乘积 / 窗口内坐标 / 尺寸 / 位移——
     * **签名变化时才记日志**。
     *
     * 用途（用户 2026-10-12 反馈）："解锁后下拉通知中心，背景出现在面板里"——
     * 靠这组记录判断我们的层当时是"在显示"、"被 alpha 藏了"、还是"被移出了屏幕"。
     * 纯诊断，不参与任何功能逻辑。
     */
    private fun probeLayerState() {
        val v = backdropRef?.get() ?: return
        if (!v.isAttachedToWindow) return
        // v9：顺便对账"内容抑制"（正常由可见性回调即时处理；这里是低频兜底）。
        reconcileContentVisibility("轮询")
        // v21a：顺便对账"层闸门"（正常由边沿/广播/状态机即时驱动；这里低频兜底）。
        reconcileLayerGate("轮询")
        // v22：顺便对账"挂载点"（该树内而整窗 / 灭屏而树内 → 调度切换；低频兜底）。
        reconcileAnchorMode("轮询")
        val shown = if (v.isShown) 1 else 0
        var alpha = 1f
        var p: View? = v
        var depth = 0
        while (p != null && depth < 12) {
            alpha *= p.alpha
            p = p.parent as? View
            depth++
        }
        val alphaPct = (alpha * 100f).toInt()
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        val winY = loc[1]
        if (shown == probeShown && alphaPct == probeAlphaPct && winY == probeWinY &&
            v.width == probeW && v.height == probeH && v.translationY == probeTransY
        ) {
            return
        }
        probeShown = shown
        probeAlphaPct = alphaPct
        probeWinY = winY
        probeW = v.width
        probeH = v.height
        probeTransY = v.translationY
        HookRuntime.log(
            "视差壁纸效果：层状态记录（shown=$shown，祖先alpha=$alphaPct%，winY=$winY，" +
                "尺寸=${v.width}x${v.height}，transY=${v.translationY}）",
        )
    }

    /** 视图（重）就位后：解析引用 + 把当前倾角补放到视图上（重建 / 换图不丢手感）。 */
    private fun syncParallax(context: Context) {
        subjectView(context)
        applyParallax()
    }

    private fun resetSubjectShift(context: Context) {
        // **只在视差真的动过主体时才复位**（2026-11-05 修复）。
        // 以前无条件把 scaleX/Y 写回 1.0 —— 视差没开也一样写，于是系统在
        // 「AOD 与锁屏样式相同」下给主体设的 0.95（`f - 0.05`）被我们覆盖成 1.0，
        // 锁屏主体与背景稳态错位 5%（真机：关掉 SystemUI 作用域就正常）。
        if (!subjectShiftApplied) return
        subjectShiftApplied = false
        subjectView(context)?.let { subject ->
            if (subject.translationX != 0f || subject.translationY != 0f ||
                subject.scaleX != 1f || subject.scaleY != 1f
            ) {
                subject.translationX = 0f
                subject.translationY = 0f
                subject.scaleX = 1f
                subject.scaleY = 1f
                HookRuntime.log("视差壁纸效果：主体位移 / 缩放已复位")
            }
        }
    }

}
