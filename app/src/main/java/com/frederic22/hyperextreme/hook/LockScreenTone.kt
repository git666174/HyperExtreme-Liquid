package com.frederic22.hyperextreme.hook

import android.content.ContentProvider
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 「强制锁屏元素深浅模式」—— **SystemUI 侧**实现（需求一，2026-10-11 开工）。
 *
 * 模块设置里 6 个元素各有一个档位（保持系统判定 / 浅色 / 深色，见 `MaskPrefs.forceTone*`；
 * 「快捷方式图标」另加两档「浅色（不透明）」「深色（不透明）」—— 见下"插件侧覆盖"）。
 * 本文件把这些档位接到**各自元素的消费端**上：系统怎么算出判定值我们完全不碰，
 * 只在每个元素真正要取色 / 变色那一刻，把它读到的那一档换成我们强制的档位。
 *
 * ## 为什么必须"消费端覆盖"，不能"改判定源"
 *
 * 判定值（`MiuiKeyguardWallPaperManager.m*IsDeep` 那五个字段 + 全局浅色位）**被多个组件共用**：
 * 例如时钟容器也读 `mStatusBarIsDeep`（信息行文字色）、底部提示初始化时也读 `mBottomIconIsDeep`。
 * 改源头会把没点名的元素一起改掉；**消费端覆盖才能做到每个元素独立**
 * （用户 2026-10-11 明确要求：除必要外，每个功能的逻辑独立）。
 *
 * ## 八个元素覆盖点 + 一个"插件加载"挂载点（全部在 SystemUI 进程）
 *
 * 注：原「通知（卡片 / 播放器）」覆盖点（`WallPaperRepository$isLightWallPaper$1$callback$1`）
 * 已于 2026-10-11 撤除 —— 该系统版本里玻璃材质下深浅两档的渲染实现逐行相同（切档无视觉变化），
 * 保留 hook 无意义。机制考证见 agent.md §2.10。
 *
 * | 元素 | 覆盖点 | 覆盖方式 |
 * | --- | --- | --- |
 * | 状态栏 | `MiuiKeyguardStatusBarView#updateIconsAndTextColors` | 进方法前改写字段 `mLightLockScreenWallpaper`（该字段只被它自己读） |
 * | 时间与锁屏内容 | `MiuiClockController#setClockPalette` 的 `z`（textDark） | 入参替换 |
 * | 时间与锁屏内容（信息行） | `MiuiClockController#setInfoTextColorDark` | 入参替换 |
 * | 快捷方式图标（更新） | `MiuiShortcutController#onSystemUIAction$1` 发给插件的 bundle | 只改 `onBottomIconDarkModeChanged` 这一条消息（初始化与更新走同一个漏斗） |
 * | 快捷方式图标（初始） | `WallpaperProvider#call("isWallpaperBottomIconDeep")` | 插件初始化查询的返回值替换 |
 * | 指纹图标 | `MiuiGxzwAnimView$MiuiGxzwAnimViewInternal#onWallpaperChange` | 入参替换 |
 * | 底部提示文字 | `KeyguardIndicationController#setDarkStyle` | 入参替换 |
 * | 锁屏签名 / 杂志文字 | `LockScreenInfoLayout#setClockPalette` 的 `z` | 入参替换 |
 * | 「快捷方式图标」插件加载点 | `MiuiShortcutController#setMiuiShortcutPlugin` | 插件加载时为「不透明」档动态挂插件侧 tint hook（见下） |
 *
 * ## 插件侧覆盖（「不透明」档专用，2026-10-11 加）
 *
 * 快捷方式图标的 tint 来自**插件类** `ViewUtils#getIconTintList(Context, boolean)`，系统值是
 * **80% 不透明**（`#CCFFFFFF` / `#CC000000`）。发送端两条覆盖点只能改"深浅"，改不了"不透明度"
 * （色值在插件内取资源）——「浅色（不透明）/ 深色（不透明）」两档在此函数上替换返回值为
 * `#FFFFFFFF` / `#FF000000`（alpha `0xCC` → `0xFF`）。一处覆盖同时管住两个消费点：
 * `ShortcutViewLayoutController.updateShortcutViewImageTintList`（锁屏左右图标）+
 * `BaseShortcutAnimView#setIconDarkMode`（按压 / 过渡动画层）。
 * 插件 dex 由 SystemUI 插件框架以独立 ClassLoader 动态加载 —— 挂载时机 = `setMiuiShortcutPlugin`
 * （`PluginListener#onPluginLoaded` 调用）；`shortcutFunnel` 内另有幂等兜底重试。
 *
 * ## 极性（全部实锤过，务必按这个来）
 *
 * 「IsDeep 判定」= **该区域是深色** ⇒ 元素取**浅色（白 / 亮）**版。所以：
 * * 用户档位「**浅色**」= 元素浅色版（白图标 / 亮字）⇔ 按 deep=true 的观感；
 * * 用户档位「**深色**」= 元素深色版（黑图标 / 暗字）⇔ 按 deep=false 的观感。
 *
 * 各覆盖点入参 ⇄ 档位的换算（都已与系统原逻辑对齐）：
 *
 * | 覆盖点 | 入参语义 | 浅色档 | 深色档 | 实锤出处 |
 * | --- | --- | --- | --- | --- |
 * | 状态栏字段 | `mLightLockScreenWallpaper = !deep` | false | true | `updateIconsAndTextColors`：true → `darkModeIconColorSingleTone`（`DEFAULT_INVERSE_ICON_TINT` = 0xFF000000 黑） |
 * | 时钟 / 杂志 `setClockPalette.z` | `textDark = !deep` | false | true | `mTextDark = z` |
 * | 时钟 `setInfoTextColorDark` | `!deep` | false | true | 调用点传 `!mStatusBarIsDeep` |
 * | 指纹 `onWallpaperChange` | `mLightLockWallpaperGxzw = !deep` | false | true | 调用点传 `!mFingerPrintIsDeep` |
 * | 底部提示 `setDarkStyle` | `mDarkStyle = !deep` | false | true | true → `miui_common_unlock_screen_common_dark_text_color` = #cc000000 黑 |
 * | 快捷方式 bundle / provider | `isIconDarkMode = deep` | true | false | 插件 `getIconTintList(true)` → `..._secondary_dark` = `miuix_color_white_level3`（#CCFFFFFF 80% 白） |
 * | 快捷方式「不透明」两档 | 发送端同上（浅→true / 深→false）+ 插件侧 tint 替换 | `#FFFFFFFF` | `#FF000000` | 系统色 `#CC` 的 alpha 提到 `0xFF`（= miuix level1） |
 *
 * ## 边界（设计上天然满足，已逐条核对）
 *
 * * **息屏显示（AOD）不受影响**：AOD 走 `DozeServiceHostInjector`，直接读壁纸管理器的字段，
 *   根本不经过 `MiuiClockController`（grep 核实过）——我们不动字段，AOD 就看不到任何变化。
 *   且编辑器扩展只装 `:keyguardeditor` 子进程，AOD 主进程不装（见 [installEditor]）。
 * * **锁屏编辑器的时钟预览已纳入**（2026-10-11 编辑器扩展）：[installEditor] 把
 *   「时间与锁屏内容」同款覆盖装进 `com.miui.aod:keyguardeditor` 进程（编辑一级/二级界面跟随强制档）；
 *   `:tinykeyguardeditor`（轻量版）暂不装。
 * * **每个元素只动自己那一处**：一个元素选择「保持系统判定」时，它的全部覆盖点直接放行，
 *   与"没开这个功能"完全一致。
 *
 * ## 配置读取（刻意不复用 HookRuntime.config）
 *
 * `HookRuntime.config` 会顺手跑 `syncFiles` / 换壁纸检测 / 上报 —— 那是"文件替换"那条链的副作用。
 * 本功能**自带**一条读取通道：provider（3 秒缓存）+ 远程偏好（无 Context 时兜底），
 * 与别的功能零共享。读不到两侧都失败时按「保持系统判定」处理（什么都不做，绝不瞎改）。
 */
internal object LockScreenTone {

    // ---------------------------------------------------------------- 常量

    /** 档位取值 —— 直接引用 `MaskPrefs` 的常量，单一出处。 */
    private const val FOLLOW = MaskPrefs.FORCE_TONE_FOLLOW
    private const val LIGHT = MaskPrefs.FORCE_TONE_LIGHT
    private const val DARK = MaskPrefs.FORCE_TONE_DARK

    /** 「快捷方式图标」专用档位（2026-10-11 加）：把图标 tint 的 80% 不透明度拉满为纯色。 */
    private const val LIGHT_OPAQUE = MaskPrefs.FORCE_TONE_LIGHT_OPAQUE
    private const val DARK_OPAQUE = MaskPrefs.FORCE_TONE_DARK_OPAQUE

    /** "不透明"档的两个 tint 色 —— 系统色（`#CCFFFFFF` / `#CC000000`）把 alpha 从 `0xCC` 提到 `0xFF`。 */
    private val OPAQUE_TINT_WHITE: ColorStateList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
    private val OPAQUE_TINT_BLACK: ColorStateList = ColorStateList.valueOf(0xFF000000.toInt())

    /** 配置缓存时长：锁屏一轮刷新会连续命中多个 hook 点，没必要每次都跨进程读。 */
    private const val CONFIG_TTL_MS = 3_000L

    /** 快捷方式那两条消息的键（SystemUI ⇄ 快捷方式插件之间的约定，字符串都在真机 dex 里核对过）。 */
    private const val ACTION_BOTTOM_ICON_DARK = "onBottomIconDarkModeChanged"
    private const val METHOD_BOTTOM_ICON_DEEP = "isWallpaperBottomIconDeep"
    private const val KEY_IS_ICON_DARK_MODE = "isIconDarkMode"
    private const val KEY_RESULT_BOOLEAN = "result_boolean"

    /** 状态栏那个"区域深浅"落点字段（只被 `MiuiKeyguardStatusBarView` 自己读，写它不牵连任何别的组件）。 */
    private const val FIELD_STATUS_BAR_LIGHT_WALLPAPER = "mLightLockScreenWallpaper"

    // 覆盖目标类名 —— 全部在真机 APK（系统界面_17.03.260226.r）的 dex 里核对过。
    private const val CLS_STATUS_BAR = "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView"
    private const val CLS_CLOCK = "com.miui.clock.MiuiClockController"
    private const val CLS_SHORTCUT = "com.android.keyguard.shortcut.MiuiShortcutController"
    private const val CLS_WALLPAPER_PROVIDER = "com.android.keyguard.WallpaperProvider"
    private const val CLS_GXZW = "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimView\$MiuiGxzwAnimViewInternal"
    private const val CLS_INDICATION = "com.android.systemui.statusbar.KeyguardIndicationController"
    private const val CLS_MAGAZINE_LAYOUT = "com.miui.lockscreeninfo.LockScreenInfoLayout"

    /**
     * 快捷方式图标的 tint 取色函数所在的类 —— **插件类**（随 `com.miui.aod` 的插件 dex
     * 由 SystemUI 插件框架动态加载进 SystemUI 进程，独立 ClassLoader）。
     * 由 [installPluginIconTintHook] 用插件自己的 loader 动态挂载（「不透明」档用）。
     */
    private const val CLS_PLUGIN_VIEW_UTILS = "com.miui.keyguard.shortcuts.utils.ViewUtils"

    // ---------------------------------------------------------------- 配置快照

    /** 一次 provider 快照解析出来的全部档位（`data class` 带 `equals`，变了才打日志）。 */
    private data class ToneConfig(
        val enabled: Boolean,
        val statusBar: Int,
        val clock: Int,
        val shortcut: Int,
        val fingerprint: Int,
        val bottomHint: Int,
        val signature: Int,
    )

    @Volatile
    private var config: ToneConfig? = null

    @Volatile
    private var configAt = 0L

    /** 上一次"读失败"的时刻。失败也缓存一小会，免得高频 hook 里反复跨进程。 */
    @Volatile
    private var configFailedAt = 0L

    /** 最近一次成功拿到的 Context（有些 hook 点自己拿不到，靠它兜底）。 */
    @Volatile
    private var lastContext: WeakReference<Context>? = null

    private val loggedOnce = ConcurrentHashMap<String, Boolean>()

    private fun logOnce(key: String, message: String) {
        if (loggedOnce.putIfAbsent(key, true) == null) {
            HookRuntime.log("强制锁屏元素深浅：$message")
        }
    }

    /**
     * 读档位配置。主通道 = provider（需要一个 Context，见 [resolveContext]）；
     * 兜底通道 = 远程偏好（框架通道，不需要 Context，`HookRuntime.remoteInt/Boolean`）。
     *
     * 读失败时**保留上一次的快照**（用户改开关通常只改动静默值，不要因为一次读失败就回退系统判定），
     * 并把"失败"也缓存 [CONFIG_TTL_MS]，避免高频 hook 打爆 binder。
     */
    private fun config(host: Any?): ToneConfig? {
        val now = SystemClock.elapsedRealtime()
        config?.let { fresh -> if (now - configAt < CONFIG_TTL_MS) return fresh }
        if (now - configFailedAt < CONFIG_TTL_MS) return config

        val bundle = resolveContext(host)?.let { ctx ->
            runCatching {
                ctx.contentResolver.call(MaskProvider.configUri(), MaskProvider.METHOD_CONFIG, null, null)
            }.getOrNull()
        }
        val parsed = bundle?.let { parse(it) } ?: remoteConfig()
        if (parsed == null) {
            configFailedAt = now
            logOnce("config-miss", "档位配置读不到（provider 与远程通道都失败），本轮按「保持系统判定」处理")
            return config
        }
        configAt = now
        if (parsed != config) {
            config = parsed
            HookRuntime.log("强制锁屏元素深浅：配置更新 → ${describe(parsed)}")
        }
        return parsed
    }

    private fun parse(bundle: Bundle) = ToneConfig(
        enabled = bundle.getBoolean(MaskPrefs.KEY_FORCE_TONE_MODE, false),
        statusBar = bundle.getInt(MaskPrefs.KEY_FORCE_TONE_STATUS_BAR, FOLLOW),
        clock = bundle.getInt(MaskPrefs.KEY_FORCE_TONE_CLOCK, FOLLOW),
        shortcut = bundle.getInt(MaskPrefs.KEY_FORCE_TONE_SHORTCUT, FOLLOW),
        fingerprint = bundle.getInt(MaskPrefs.KEY_FORCE_TONE_FINGERPRINT, FOLLOW),
        bottomHint = bundle.getInt(MaskPrefs.KEY_FORCE_TONE_BOTTOM_HINT, FOLLOW),
        signature = bundle.getInt(MaskPrefs.KEY_FORCE_TONE_SIGNATURE, FOLLOW),
    )

    private fun remoteConfig(): ToneConfig? {
        val enabled = HookRuntime.remoteBoolean(MaskPrefs.KEY_FORCE_TONE_MODE) ?: return null
        return ToneConfig(
            enabled = enabled,
            statusBar = HookRuntime.remoteInt(MaskPrefs.KEY_FORCE_TONE_STATUS_BAR) ?: FOLLOW,
            clock = HookRuntime.remoteInt(MaskPrefs.KEY_FORCE_TONE_CLOCK) ?: FOLLOW,
            shortcut = HookRuntime.remoteInt(MaskPrefs.KEY_FORCE_TONE_SHORTCUT) ?: FOLLOW,
            fingerprint = HookRuntime.remoteInt(MaskPrefs.KEY_FORCE_TONE_FINGERPRINT) ?: FOLLOW,
            bottomHint = HookRuntime.remoteInt(MaskPrefs.KEY_FORCE_TONE_BOTTOM_HINT) ?: FOLLOW,
            signature = HookRuntime.remoteInt(MaskPrefs.KEY_FORCE_TONE_SIGNATURE) ?: FOLLOW,
        )
    }

    private fun describe(cfg: ToneConfig): String =
        "模式=${if (cfg.enabled) "开" else "关"}；状态栏=${label(cfg.statusBar)}，" +
            "时间与锁屏内容=${label(cfg.clock)}，" +
            "快捷方式图标=${label(cfg.shortcut)}，指纹图标=${label(cfg.fingerprint)}，" +
            "底部提示文字=${label(cfg.bottomHint)}，锁屏签名/杂志文字=${label(cfg.signature)}"

    private fun label(mode: Int) = when (mode) {
        LIGHT -> "浅色"
        DARK -> "深色"
        LIGHT_OPAQUE -> "浅色（不透明）"
        DARK_OPAQUE -> "深色（不透明）"
        else -> "保持系统判定"
    }

    // ---------------------------------------------------------------- Context 解析

    /**
     * 给"读配置"用的 Context。优先级：
     * ① 宿主自带（View / ContentProvider / `Context` 本体 / 宿主类的公开 `mContext` 字段）；
     * ② 之前任一 hook 成功拿到的（[lastContext]）；
     * ③ `HookRuntime.appContext()`（反射 hidden API，不可靠，只作最后一档）。
     */
    private fun resolveContext(host: Any?): Context? {
        val direct = when (host) {
            is View -> host.context
            is ContentProvider -> host.context
            is Context -> host
            else -> null
        } ?: reflectedContext(host)
        if (direct != null) {
            lastContext = WeakReference(direct)
            return direct
        }
        return lastContext?.get() ?: HookRuntime.appContext()
    }

    /** 宿主类的公开 `mContext` 字段（`MiuiClockController` / `KeyguardIndicationController` 都是公开字段）。 */
    private fun reflectedContext(host: Any?): Context? {
        if (host == null) return null
        val field = fieldOf(host.javaClass, "mContext") ?: return null
        return runCatching { field.get(host) as? Context }.getOrNull()
    }

    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val fieldMisses = ConcurrentHashMap.newKeySet<String>()

    private fun fieldOf(clazz: Class<*>, name: String): Field? {
        val key = clazz.name + '#' + name
        fieldCache[key]?.let { return it }
        if (key in fieldMisses) return null
        val field = runCatching { clazz.getField(name) }.getOrNull()
        if (field == null) {
            fieldMisses.add(key)
            return null
        }
        fieldCache[key] = field
        return field
    }

    // ---------------------------------------------------------------- 覆盖逻辑

    /** 当前该元素的有效档位；「保持系统判定」/ 总开关关 / 配置读不到 → [FOLLOW]（放行原逻辑）。 */
    private fun effectiveMode(host: Any?, select: (ToneConfig) -> Int): Int {
        val cfg = config(host) ?: return FOLLOW
        if (!cfg.enabled) return FOLLOW
        return select(cfg)
    }

    /** 命中覆盖时"每个元素每种档位只记一次"的 INFO 日志 —— 用户看 LSPosed 日志就能确认生效路径。 */
    private fun logApply(label: String, mode: Int) {
        logOnce("apply:$label:$mode", "$label → 已强制「${label(mode)}」档（生效中）")
    }

    /**
     * 通用：**单个 bool 入参**的覆盖点。`valueOf` 把档位换算成该方法参数应有的取值。
     */
    private fun booleanArg(
        chain: XposedInterface.Chain,
        select: (ToneConfig) -> Int,
        label: String,
        argIndex: Int,
        valueOf: (Int) -> Boolean,
    ): Any? {
        val mode = effectiveMode(chain.getThisObject(), select)
        if (mode == FOLLOW) return chain.proceed()
        val args = chain.getArgs()
        if (argIndex >= args.size || args[argIndex] !is Boolean) return chain.proceed()
        val replaced = args.toMutableList()
        replaced[argIndex] = valueOf(mode)
        logApply(label, mode)
        return chain.proceed(replaced.toTypedArray())
    }

    /**
     * 状态栏：进 `updateIconsAndTextColors` 之前把 `mLightLockScreenWallpaper` 改成强制值，
     * 方法体里紧接着就会读它取色。**不还原** —— 系统会在下一次颜色回调里写回判定值，
     * 而我们每次进这个方法都会重新改一遍，等价于"每次都按强制档渲染"。
     */
    private fun statusBar(chain: XposedInterface.Chain): Any? {
        val mode = effectiveMode(chain.getThisObject(), { it.statusBar })
        val host = chain.getThisObject()
        if (mode != FOLLOW && host != null) {
            val field = fieldOf(host.javaClass, FIELD_STATUS_BAR_LIGHT_WALLPAPER)
            if (field == null) {
                logOnce("statusbar-miss", "状态栏：找不到 $FIELD_STATUS_BAR_LIGHT_WALLPAPER 字段，这一处不强制")
            } else if (runCatching { field.setBoolean(host, mode == DARK) }.isSuccess) {
                logApply("状态栏", mode)
            } else {
                logOnce("statusbar-fail", "状态栏：$FIELD_STATUS_BAR_LIGHT_WALLPAPER 写入失败，这一处不强制")
            }
        }
        return chain.proceed()
    }

    /**
     * 快捷方式（更新链 + 初始化链共用这一个漏斗）：`onSystemUIAction$1(Bundle, String)` 是
     * SystemUI 发给插件（`ShortcutPluginImpl`，跑在 SystemUI 进程里）的**唯一出口** ——
     * `setMiuiShortcutPlugin` 初始化那一条（读字段直接发 bundle）和
     * `miuiKeyguardUpdateMonitorCallback` 更新那一条都从这里走。
     */
    private fun shortcutFunnel(chain: XposedInterface.Chain): Any? {
        // 兜底：万一"插件加载"挂载点缺席（或插件在本 hook 安装前就已加载），
        // 每次消息发送时从控制器的 `miuiShortcutPlugin` 字段再试一次（幂等）。
        ensurePluginIconTintHook(chain.getThisObject())
        val mode = effectiveMode(chain.getThisObject(), { it.shortcut })
        if (mode != FOLLOW) {
            val args = chain.getArgs()
            val bundle = args.firstOrNull { it is Bundle } as? Bundle
            val action = args.firstOrNull { it is String } as? String
            if (action == ACTION_BOTTOM_ICON_DARK && bundle != null) {
                // 插件侧 `getIconTintList(true)` → `..._secondary_dark` = `#CCFFFFFF`（80% 白）
                // ⇒「浅色 / 浅色（不透明）」= true、「深色 / 深色（不透明）」= false。
                // （"不透明"的拉满动作在插件侧 tint hook [iconTintOverride] 里，此处只发深浅。）
                bundle.putBoolean(KEY_IS_ICON_DARK_MODE, mode == LIGHT || mode == LIGHT_OPAQUE)
                logApply("快捷方式图标", mode)
            }
        }
        return chain.proceed()
    }

    /**
     * 快捷方式（初始值）：插件创建时通过 `ViewUtils.getBottomIconDeep` 调 provider 查
     * `isWallpaperBottomIconDeep`，返回的 `result_boolean` 就是 `mBottomIconIsDeep`。
     * 这个查询只服务快捷方式（真机 dex 核对过），改返回值不影响别人。
     */
    private fun shortcutProvider(chain: XposedInterface.Chain): Any? {
        val mode = effectiveMode(chain.getThisObject(), { it.shortcut })
        val result = chain.proceed()
        if (mode != FOLLOW && result is Bundle) {
            val isTarget = chain.getArgs().any { it is String && it == METHOD_BOTTOM_ICON_DEEP }
            if (isTarget) {
                result.putBoolean(KEY_RESULT_BOOLEAN, mode == LIGHT || mode == LIGHT_OPAQUE)
                logApply("快捷方式图标（初始）", mode)
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 插件侧覆盖（「不透明」档）

    /** `install()` 时缓存的模块引用（插件类动态挂载发生在之后，闭包里拿不到原参数）。 */
    @Volatile
    private var moduleRef: XposedModule? = null

    /** 已挂过图标 tint hook 的插件 ClassLoader（WeakHashMap 防泄漏；插件重载产生新 loader 会自动重新挂）。 */
    private val hookedPluginLoaders: MutableSet<ClassLoader> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())

    /**
     * 挂"图标 tint 取色"hook —— **插件类** `ViewUtils#getIconTintList(Context, boolean)`。
     *
     * 为什么不复用上面两条"发送端"覆盖点：发送端只能改 boolean（深浅），而 tint 的
     * **色值本身**（80% 不透明）是插件内部取资源得到的，要"拉满不透明"必须替换它的返回值。
     * 一处覆盖同时管住两个消费点：`ShortcutViewLayoutController.updateShortcutViewImageTintList`
     * （锁屏左右图标）与 `BaseShortcutAnimView#setIconDarkMode`（按压 / 过渡动画层）。
     *
     * 插件 dex 由 SystemUI 插件框架动态加载（独立 ClassLoader），所以用
     * [pluginLoaded]（`setMiuiShortcutPlugin` 触发）拿插件实例的 loader 动态挂载；
     * [shortcutFunnel] 里另有幂等兜底重试。
     */
    private fun installPluginIconTintHook(loader: ClassLoader) {
        val module = moduleRef ?: return
        synchronized(hookedPluginLoaders) {
            if (loader in hookedPluginLoaders) return
        }
        val clazz = runCatching { Class.forName(CLS_PLUGIN_VIEW_UTILS, false, loader) }.getOrNull()
        if (clazz == null) {
            logOnce("plugin-viewutils-miss", "插件侧：此 loader 下未找到 $CLS_PLUGIN_VIEW_UTILS（「不透明」档暂不可用）")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "getIconTintList" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            logOnce("plugin-tint-miss", "插件侧：未找到 ViewUtils#getIconTintList(Context, boolean)")
            return
        }
        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = iconTintOverride(chain)
            })
            synchronized(hookedPluginLoaders) { hookedPluginLoaders.add(loader) }
            HookRuntime.log("强制锁屏元素深浅：已挂插件侧 $CLS_PLUGIN_VIEW_UTILS#getIconTintList（「不透明」档用）")
        }.onFailure {
            HookRuntime.log("强制锁屏元素深浅：挂插件侧 getIconTintList 失败：$it")
        }
    }

    /** 兜底：从 `MiuiShortcutController.miuiShortcutPlugin` 公开字段取插件 loader 重试（幂等）。 */
    private fun ensurePluginIconTintHook(controller: Any?) {
        if (controller == null) return
        val field = fieldOf(controller.javaClass, "miuiShortcutPlugin") ?: return
        val plugin = runCatching { field.get(controller) }.getOrNull() ?: return
        val loader = plugin.javaClass.classLoader ?: return
        installPluginIconTintHook(loader)
    }

    /**
     * `setMiuiShortcutPlugin(MiuiShortcutPlugin)` —— 插件加载 / 卸载的第一个触发点
     * （由 `PluginListener#onPluginLoaded` 调用）。插件加载时立即挂 tint hook，
     * 时机早于视图创建（`addShortcutViews` 在它内部、tint 首次应用在之后）。
     */
    private fun pluginLoaded(chain: XposedInterface.Chain): Any? {
        val plugin = chain.getArgs().firstOrNull()
        if (plugin != null) {
            val loader = plugin.javaClass.classLoader
            if (loader != null) installPluginIconTintHook(loader)
        }
        return chain.proceed()
    }

    /**
     * 插件侧 tint 覆盖：只在「不透明」两档生效 —— 替换返回的 `ColorStateList` 为全不透明色；
     * 其它档位原样返回（系统行为，`#CC` 的 80% 不动）。
     */
    private fun iconTintOverride(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        // 第一个入参就是 Context（`getIconTintList(Context, boolean)`），读配置不必另找宿主。
        val ctx = chain.getArgs().firstOrNull { it is Context } as? Context
        return when (effectiveMode(ctx, { it.shortcut })) {
            LIGHT_OPAQUE -> {
                logApply("快捷方式图标（不透明）", LIGHT_OPAQUE)
                OPAQUE_TINT_WHITE
            }
            DARK_OPAQUE -> {
                logApply("快捷方式图标（不透明）", DARK_OPAQUE)
                OPAQUE_TINT_BLACK
            }
            else -> result
        }
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 安装全部 hook（8 个元素覆盖点 + 1 个"插件加载"挂载点 = 共 9 个）。
     * 返回"至少挂上一个"（供 `HookEntry` 的安装结果汇总用）。
     *
     * 全部失败时**不留任何幂等位** —— `installSystemUiHooks` 的兜底线程会带着正确的
     * classLoader 再来一轮（与锁屏按钮那套同一条纪律，见 `HookEntry` 的注释）。
     */
    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        moduleRef = module
        val results = linkedMapOf<String, Boolean>()
        val bool1 = { method: Method ->
            method.parameterTypes.size == 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }

        // 1) 状态栏 —— 无参方法，改的是它即将读取的字段。
        results["状态栏"] = hookMethod(module, loader, CLS_STATUS_BAR, "updateIconsAndTextColors",
            { it.parameterTypes.isEmpty() }) { chain -> statusBar(chain) }

        // 2) 时间与锁屏内容 —— 时钟调色板（z = textDark）+ 信息行（= 日期那行字）。
        //    两者在系统里本来就是同一个"时钟级判定"，共用一个档位。
        results["时钟调色板"] = hookMethod(module, loader, CLS_CLOCK, "setClockPalette",
            { method -> method.parameterTypes.size == 3 && method.parameterTypes[1] == Boolean::class.javaPrimitiveType }) { chain ->
            booleanArg(chain, { it.clock }, "时间与锁屏内容", 1) { mode -> mode == DARK }
        }
        results["时钟信息行"] = hookMethod(module, loader, CLS_CLOCK, "setInfoTextColorDark", bool1) { chain ->
            booleanArg(chain, { it.clock }, "时间与锁屏内容（信息行）", 0) { mode -> mode == DARK }
        }

        // 3) 快捷方式图标 —— 更新链（漏斗 bundle）+ 初始化链（provider 返回值）
        //    + "插件加载"挂载点（「不透明」档的插件侧 tint hook 在插件加载时挂载）。
        results["快捷方式-更新"] = hookMethod(module, loader, CLS_SHORTCUT, "onSystemUIAction\$1",
            { it.parameterTypes.size == 2 }) { chain -> shortcutFunnel(chain) }
        results["快捷方式-初始"] = hookMethod(module, loader, CLS_WALLPAPER_PROVIDER, "call",
            { it.returnType == Bundle::class.java }) { chain -> shortcutProvider(chain) }
        results["快捷方式-插件加载"] = hookMethod(module, loader, CLS_SHORTCUT, "setMiuiShortcutPlugin",
            { it.parameterTypes.size == 1 }) { chain -> pluginLoaded(chain) }

        // 4) 指纹图标。
        results["指纹图标"] = hookMethod(module, loader, CLS_GXZW, "onWallpaperChange", bool1) { chain ->
            booleanArg(chain, { it.fingerprint }, "指纹图标", 0) { mode -> mode == DARK }
        }

        // 5) 底部提示文字。
        results["底部提示文字"] = hookMethod(module, loader, CLS_INDICATION, "setDarkStyle", bool1) { chain ->
            booleanArg(chain, { it.bottomHint }, "底部提示文字", 0) { mode -> mode == DARK }
        }

        // 6) 锁屏签名 / 杂志文字。
        results["锁屏签名/杂志文字"] = hookMethod(module, loader, CLS_MAGAZINE_LAYOUT, "setClockPalette",
            { method -> method.parameterTypes.size == 2 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType }) { chain ->
            booleanArg(chain, { it.signature }, "锁屏签名/杂志文字", 0) { mode -> mode == DARK }
        }

        val any = results.values.any { it }
        val summary = results.entries.joinToString("，") { (name, ok) -> "$name=${if (ok) "ok" else "失败"}" }
        HookRuntime.log("强制锁屏元素深浅：hook 安装结果（本轮${if (any) "已生效" else "全部失败，等下一个 loader"}）：$summary")
        return any
    }

    /**
     * 「时间与锁屏内容」的**编辑器扩展**（2026-10-11 加）：锁屏编辑进程（`com.miui.aod:keyguardeditor`）
     * 里的时钟预览也按强制档着色 —— 编辑器 APK 自带**同套时钟代码**（同名类 `com.miui.clock.MiuiClockController`
     * 的独立副本，编辑器自己的 ClassLoader），其深浅设置同样汇聚到下面三个方法。
     *
     * 与 SystemUI 侧的区别（真机 dex / 反编译双端核对过）：
     * * 编辑器版**多一个独立入口** `setTextColorDark(boolean)`（`LinkageStyleClockView` 等会单独调它、不走调色板），
     *   所以这里覆盖三个方法；SystemUI 版 controller 没有该方法、无需覆盖。
     * * `setClockPalette` / `setInfoTextColorDark` 两端同名同签名，档位换算直接复用（z = !deep）。
     *
     * **进程边界（重要）**：只由 `HookEntry` 在 `:keyguardeditor` 进程调用 ——
     * `com.miui.aod` 主进程（AOD 息屏）与 `:tinykeyguardeditor`（轻量版）都不装，
     * 保住"息屏不受影响"的既定边界。
     */
    fun installEditor(module: XposedModule, loader: ClassLoader): Boolean {
        moduleRef = module
        val results = linkedMapOf<String, Boolean>()
        val bool1 = { method: Method ->
            method.parameterTypes.size == 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }

        // 1) 时钟调色板（z = textDark）—— 编辑主界面 / 子页 / 各模板预览的深浅总咽喉。
        results["编辑器-时钟调色板"] = hookMethod(module, loader, CLS_CLOCK, "setClockPalette",
            { method -> method.parameterTypes.size == 3 && method.parameterTypes[1] == Boolean::class.javaPrimitiveType }) { chain ->
            booleanArg(chain, { it.clock }, "时间与锁屏内容（锁屏编辑）", 1) { mode -> mode == DARK }
        }

        // 2) 信息行（日期那行字）。
        results["编辑器-信息行"] = hookMethod(module, loader, CLS_CLOCK, "setInfoTextColorDark", bool1) { chain ->
            booleanArg(chain, { it.clock }, "时间与锁屏内容（锁屏编辑·信息行）", 0) { mode -> mode == DARK }
        }

        // 3) 文本深浅的独立入口（编辑器特有；`LinkageStyleClockView` 有硬编码 `false` 的调用）。
        results["编辑器-文本深浅"] = hookMethod(module, loader, CLS_CLOCK, "setTextColorDark", bool1) { chain ->
            booleanArg(chain, { it.clock }, "时间与锁屏内容（锁屏编辑·文本深浅）", 0) { mode -> mode == DARK }
        }

        val any = results.values.any { it }
        val summary = results.entries.joinToString("，") { (name, ok) -> "$name=${if (ok) "ok" else "失败"}" }
        HookRuntime.log("强制锁屏元素深浅：锁屏编辑 hook 安装结果（本轮${if (any) "已生效" else "全部失败"}）：$summary")
        return any
    }

    /**
     * 按类名 + 方法名挂 hook（与 `HookEntry.hookAll` 同款：逐级向上找 declaredMethods，
     * 同名重载全部挂上；只认传进来的宿主 loader）。
     */
    private fun hookMethod(
        module: XposedModule,
        loader: ClassLoader,
        className: String,
        methodName: String,
        filter: (Method) -> Boolean,
        block: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
        if (clazz == null) {
            HookRuntime.log("强制锁屏元素深浅：未找到 $className，跳过 $methodName")
            return false
        }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = current.declaredMethods.filter { it.name == methodName && filter(it) }
            if (declared.isNotEmpty()) {
                var any = false
                declared.forEach { method ->
                    runCatching {
                        module.hook(method).intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = block(chain)
                        })
                    }
                        .onSuccess {
                            HookRuntime.log("强制锁屏元素深浅：已挂 ${describe(method)}")
                            any = true
                        }
                        .onFailure { HookRuntime.log("强制锁屏元素深浅：挂 ${describe(method)} 失败：$it") }
                }
                return any
            }
            current = current.superclass
        }
        HookRuntime.log("强制锁屏元素深浅：$className 里没有 $methodName")
        return false
    }

    private fun describe(method: Method): String =
        "${method.declaringClass.simpleName}#${method.name}(" +
            method.parameterTypes.joinToString(",") { it.simpleName } + ")"
}
