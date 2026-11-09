package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.frederic22.hyperextreme.data.AppRestarter
import com.frederic22.hyperextreme.data.ActivationState
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskRole
import com.frederic22.hyperextreme.data.MaskStore
import com.frederic22.hyperextreme.data.ParallaxCoverFlag
import com.frederic22.hyperextreme.data.ParallaxFeed
import com.frederic22.hyperextreme.data.RestartTargets
import com.frederic22.hyperextreme.data.RootReplacer
import com.frederic22.hyperextreme.data.RootShell
import com.frederic22.hyperextreme.data.RunLog
import com.frederic22.hyperextreme.data.SelfTest
import com.frederic22.hyperextreme.data.SystemUiPackageName
import com.frederic22.hyperextreme.data.WallpaperSource
import com.frederic22.hyperextreme.data.WallpaperVersion
import com.frederic22.hyperextreme.hook.HookRuntime
import com.frederic22.hyperextreme.hook.ParallaxBg
import com.frederic22.hyperextreme.hook.ScreenFlash
import io.github.libxposed.service.XposedProvider
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 界面状态与动作的唯一出处。四个 Tab 共用它，方便以后继续加功能。
 */
@Stable
class MaskUiState(
    val context: Context,
    val prefs: MaskPrefs,
    private val scope: CoroutineScope,
) {

    var revision by mutableLongStateOf(prefs.revision)
        private set

    var previews by mutableStateOf<Map<MaskRole, Bitmap?>>(emptyMap())

    var sizes by mutableStateOf(roleState { MaskStore.sizeText(context, it) })

    var verbose by mutableStateOf(prefs.verboseLog)

    /** 设置页是否显示「自检」与「最近操作」（默认关，见 MaskPrefs.showDiagnostics）。 */
    var showDiagnostics by mutableStateOf(prefs.showDiagnostics)
        private set

    /** 解除编辑器的「主体遮挡时间过多」判定（默认关，见 `MaskPrefs.depthOcclusionRemoved`）。 */
    var depthOcclusionRemoved by mutableStateOf(prefs.depthOcclusionRemoved)

    /** 「替换后自动灭亮屏一次」（替换行为里的开关，默认关；便于快速查看效果）。 */
    var autoScreenFlash by mutableStateOf(prefs.autoScreenFlash)

    /** 设置页「锁屏修改 → 使自定义锁屏按钮显示在上层」（作用到 SystemUI 的开关之一）。 */
    var lockScreenButtonOnTop by mutableStateOf(prefs.lockScreenButtonOnTop)

    /**
     * 「打开多任务时隐藏状态栏」（系统桌面 → 多任务，默认关，2026-10-16 加）。
     *
     * 单一写者（本界面）；SystemUI 侧（hook/RecentsStatusBar）在「多任务出现」事件到达时现读 prefs。
     */
    var hideStatusBarInRecents by mutableStateOf(prefs.hideStatusBarInRecents)

    /**
     * 「视差壁纸效果」是否开启（背景层在锁屏上生效中）。
     *
     * 权威状态在 prefs（`MaskPrefs.parallaxBgActive`）——界面按钮显示「立即替换」还是
     * 「关闭替换」、SystemUI 侧插不插背景层，全都看它。
     */
    var parallaxActive by mutableStateOf(prefs.parallaxBgActive)
        private set

    /** 「视差壁纸效果」的背景图预览（`files/parallax_bg.png`；未选图时为 null）。 */
    var parallaxPreview by mutableStateOf<Bitmap?>(null)
        private set

    /**
     * 「视差壁纸效果 → 参数调整」5 参数（2026-10-13 加）。
     *
     * 权威存储仍在 prefs（SystemUI 侧经 provider config 快照 3s TTL 读取）；这里的镜像只服务 UI 显示。
     * **不放进 [refreshLocal] 重读清单**：它们是"用户拖动中"的高频状态，prefs 监听触发的重读
     * 若覆盖它们会把正在拖的滑块弹回去；prefs 的写入方也只有本值（拖动抬手 / 开关切换时）
     * —— 单一写者，无需回读对账。
     *
     * 拖动中 [applyParallaxSubjectScale] 等以 `persist = false` 只更新内存（防 `commit()` 卡拖动，
     * 与 NavBg 滑块同一教训），抬手时 [persistParallaxParams] 一次性落盘。
     */
    var parallaxReverse by mutableStateOf(prefs.parallaxReverse)
        private set

    /**
     * 「壁纸改变后自动关闭替换」（视差页开关，**默认开**，2026-10-15 加）。
     *
     * 与 [parallaxReverse] 同款：单一写者（本界面），不放进 [refreshLocal] 重读清单。
     */
    var parallaxAutoOff by mutableStateOf(prefs.parallaxAutoOff)
        private set

    /**
     * 「视差效果覆盖桌面」（视差页开关，**默认关**，2026-10-21 加）。
     *
     * 当前只用于「解锁动画抑制」；与 [parallaxAutoOff] 同款：单一写者，不放进 [refreshLocal] 重读清单。
     */
    var parallaxCoverDesktop by mutableStateOf(prefs.parallaxCoverDesktop)
        private set

    var parallaxSubjectScale by mutableStateOf(prefs.parallaxSubjectScale)
        private set
    var parallaxBgScale by mutableStateOf(prefs.parallaxBgScale)
        private set
    var parallaxSubjectAmp by mutableStateOf(prefs.parallaxSubjectAmp)
        private set
    var parallaxBgAmp by mutableStateOf(prefs.parallaxBgAmp)
        private set

    /** 「参数调整」第 6 项（2026-10-13 用户加）：恢复原位等待时长（秒）——静止判定窗口。 */
    var parallaxHomeWait by mutableStateOf(prefs.parallaxHomeWait)
        private set

    /** 「参数调整」第 7 项（2026-10-13 用户加）：最大幅度角度（度）——转动的满量程角。 */
    var parallaxGyroDeg by mutableStateOf(prefs.parallaxGyroDeg)
        private set

    /** 「强制锁屏元素深浅模式」主开关（默认关，见 `MaskPrefs.forceToneMode`）。 */
    var forceToneMode by mutableStateOf(prefs.forceToneMode)

    /** 7 个元素的强制档位：0=保持系统判定 / 1=浅色 / 2=深色（`MaskPrefs.FORCE_TONE_*`）。 */
    var forceToneStatusBar by mutableStateOf(prefs.forceToneStatusBar)
    var forceToneClock by mutableStateOf(prefs.forceToneClock)
    var forceToneShortcut by mutableStateOf(prefs.forceToneShortcut)
    var forceToneFingerprint by mutableStateOf(prefs.forceToneFingerprint)
    var forceToneBottomHint by mutableStateOf(prefs.forceToneBottomHint)
    var forceToneSignature by mutableStateOf(prefs.forceToneSignature)

    var themeMode by mutableStateOf(ThemeMode.of(prefs.themeMode))

    var selfTestLines by mutableStateOf<List<String>>(emptyList())

    var hookStatus by mutableStateOf(readHookStatus(prefs))

    /**
     * 记录下来的 root 可用性：null = 还没测过。
     *
     * 存在 prefs 里（重启保留）。主页的提示和「刷新 Root」按钮都只看这个值。
     */
    var rootAvailable by mutableStateOf(prefs.rootAvailable)
        private set

    /**
     * 当前已知的「壁纸原图尺寸」。
     *
     * 在功能页显示出来 —— 尺寸只在锁屏编辑器里刷过一次，光靠"刷新了"没法确认，
     * 显示出来才能一眼看出刷新有没有生效（选图时会主动刷一次）。
     */
    var wallpaperSize by mutableStateOf(prefs.wallpaperSize.toSizeOrNull())
        private set

    /** 选图时"正在准备文件"的等待态：等 hook 把这次探测的答案回写过来。 */
    var preparingWallpaper by mutableStateOf(false)
        private set

    /**
     * [ActivationState.frameworkConnected] 的镜像 —— 那个是普通静态变量，Compose 观察不到，
     * 所以在这里存一份 state；每帧刷新和回前台时同步。
     */
    var frameworkConnected by mutableStateOf(ActivationState.frameworkConnected)
        private set

    /**
     * 激活状态是否**已经确认过**（为 false 时界面还没资格下"未激活"的结论）。
     *
     * LSPosed 送服务 binder 是**跨进程异步**的：模块进程起来之后，框架还要绕一圈 IPC 才能把它
     * 送到我们的 `XposedProvider.call("SendBinder")`。所以首帧组合那一刻它常常还没到 ——
     * R8 让启动变快之后尤其明显（debug 版启动慢，binder 反而总能抢在首帧前面，所以那边看不到闪烁）。
     */
    var activationSettled by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)

    /** 正在等待相册返回的角色。 */
    var pendingRole by mutableStateOf<MaskRole?>(null)

    val configuredRoles: List<MaskRole>
        get() = MaskRole.selectable.filter { sizes[it] != null }

    /**
     * 是否已激活 —— **只看当前这个进程**：
     * * 框架把服务 binder 送过来了（[ActivationState.frameworkConnected]）；
     * * 或者框架把模块注入进了本进程（[HookRuntime.selfInjected]）。
     *
     * **不能**再用 `prefs.frameworkConnectedAt`（历史时间戳）或 `hookStatus.process`（注入记录）——
     * 它们是持久化的历史值，用户在 LSPosed 里取消勾选后还会一直为真，界面就永远显示"已激活"。
     * 现在"掉激活"在两种情况能立刻反映：回到前台时 binder 探活失败，或者应用重启后本进程没再收到 binder。
     */
    val activated: Boolean
        get() = frameworkConnected || HookRuntime.selfInjected

    /** 激活来源，给界面显示用。 */
    val activationSource: String?
        get() = when {
            frameworkConnected -> "框架已连接"
            HookRuntime.selfInjected -> "本应用（框架已注入）"
            else -> null
        }

    /**
     * 状态是否为「模块已激活」—— 也就是界面上唯一那个**完全可用**的状态。
     *
     * 和 [activated] 的区别是这里把 root 也算进来了：有框架但没 root 时模块照样用不了
     * （替换文件、探壁纸尺寸都要 su），所以关于页那张卡的文案也是"模块未激活（无 Root）"。
     *
     * [rootAvailable] 是 `Boolean?`（null = 还没测过）：只有**明确为 false** 才算没 root；
     * 还没测出结果时不在这里下结论（[MaskUiState] 的 init 会静默探一次）。
     */
    val fullyActivated: Boolean
        get() = activated && rootAvailable == true

    /**
     * 功能页所有按钮的统一闸门：状态不是「模块已激活」就提示并返回 false，调用方据此中止。
     */
    fun requireActivated(): Boolean {
        if (fullyActivated) return true
        toast(NotActivatedMessage)
        return false
    }

    /**
     * 已经注入到系统里的角色（= 注入快照）。
     *
     * 注意和 [configuredRoles] 的区别：候选项在注入收尾时已经清空，所以"注入过哪些"要单独看快照，
     * 不然界面上会出现"候选是空的、其实已经注入"的矛盾显示。快照也是界面显示「已注入」的依据。
     *
     * ⚠️ 必须做成 State，不能在 composable 里现读文件系统。之前就是后者，后果是：保护收尾清掉快照后，
     * 没有任何 State 变化 → FunctionTab / HomeTab 被跳过 → 界面还停在旧态。
     */
    var injectedRoles by mutableStateOf(MaskStore.injectedRoles(context))
        private set

    /** 界面上"当前生效的角色"：候选优先（还没注入），否则看已注入的快照。 */
    val effectiveRoles: List<MaskRole>
        get() = configuredRoles.ifEmpty { injectedRoles }

    suspend fun reloadPreviews() {
        previews = withContext(Dispatchers.IO) {
            // 遮罩页两个候选（主体 / 遮罩）预览也**不压分辨率**了（2026-10-13 用户要求）：
            // 按存储文件原分辨率解码（4096 = 导入封顶，等于不降采样）。
            MaskRole.selectable.associateWith { MaskStore.loadPreview(context, it, PreviewMaxEdge) }
        }
        parallaxPreview = withContext(Dispatchers.IO) {
            // 视差页预览不压缩（2026-10-12 用户要求）：同一套上限。
            MaskStore.loadParallaxPreview(context, PreviewMaxEdge)
        }
    }

    fun refreshLocal() {
        // 自愈会写回 prefs，而写回又会触发上面的监听 → 用这个标记断开重入。
        if (refreshing) return
        refreshing = true
        try {
            revision = prefs.revision
            sizes = roleState { MaskStore.sizeText(context, it) }
            hookStatus = readHookStatus(prefs)
            frameworkConnected = ActivationState.frameworkConnected
            themeMode = ThemeMode.of(prefs.themeMode)
            parallaxActive = prefs.parallaxBgActive
            syncProtectionState()
        } finally {
            refreshing = false
        }
    }

    /**
     * 回前台时对齐一次。
     *
     * hook / provider 在别的进程里可能已经写了新状态（保护收尾、视差自动关闭），那时只有 prefs 变了；
     * 界面不重新读一次就会一直停在旧状态 —— 这也是"行为没有同步"的一半原因。
     */
    fun onResume() {
        // 用户可能在后台把模块从 LSPosed 里取消了：框架服务 binder 一失效就立刻翻回未激活，
        // 不用等应用重启。
        if (ActivationState.frameworkConnected && !XposedProvider.isFrameworkAlive()) {
            ActivationState.markDisconnected()
        }
        refreshLocal()
        scope.launch { reloadPreviews() }
    }

    /**
     * 把保护状态与实际文件对齐，并顺手自愈。
     *
     * 自愈场景：指纹非空、但候选和注入快照都没了（hook 在编辑器里解除过，或文件被外部清掉）——
     * 说明保护其实早就结束了，把指纹一并清掉，别让界面继续显示"保护中"。
     */
    private fun syncProtectionState() {
        val roles = MaskStore.injectedRoles(context)
        val candidates = MaskRole.selectable.count { MaskStore.exists(context, it) }
        if (roles.isEmpty() && candidates == 0 && prefs.injectedWallpaperMd5.isNotEmpty()) {
            prefs.setInjectedWallpaper("")
            prefs.setProtecting(false)
        }
        injectedRoles = roles
    }

    private val main = Handler(Looper.getMainLooper())
    private var refreshing = false

    /**
     * 让界面跟着"别处写出来的状态"自动走。
     *
     * 两种"别处"：hook 进程（它写的都经 provider 落回本进程），以及本进程里的 ScreenDisarm
     * （灭亮屏兜底结束保护就是它干的）。之前只靠 ON_RESUME 刷新，而用户从锁屏回来时**不一定**有
     * 一次生命周期变化（Activity 可能一直没被 stop），界面就会停在"保护中"。
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.post { refreshLocal() }
    }

    init {
        prefs.registerListener(prefsListener)
    }

    /**
     * 导入图片时的对齐锚点：**壁纸原图**（`templates/current/wallpaper` 那一份）的像素尺寸。
     *
     * 不是屏幕尺寸，也**不是**裁切矩形那份（`wallpaper_cropped` / 已应用的 `lock_wallpaper.jpg`）的尺寸
     * —— 后者 = `screenWidth / scale`，壁纸一缩放/移动就变，拿它当锚点会一直误报（实测踩过）。
     *
     * 先用 root **现抓一次**：用户可能刚在锁屏编辑里换了壁纸，缓存与 hook 回写的那份就过期了
     * （另一条实测反馈："提示的还是上一张图的分辨率"）。抓不到就退回缓存/记录值；
     * 都拿不到返回 null，调用方据此跳过检查 —— 宁可不说，也别拿一个已知不对的锚点乱报。
     */
    /**
     * 选图第一步：发出一次「hook 探测壁纸原图」请求，并**重启宿主**（`com.miui.aod`）——
     * 先 `kill -15`，再用 provider 把它拉起来，保证 hook 一定在"刚启动"这个点跑到。
     *
     * 为什么走 hook：`templates/current/wallpaper` 是宿主的私有文件，只有它自己读得到 ——
     * 应用侧 su 在这台机器上看不到 `/data/user_de/0/com.miui.aod/...`（真机实测），
     * 而 hook 在宿主机进程里读同一份文件，没有这个限制。
     *
     * 请求号**直接写在 provider 调用的 `--arg` 里**（不依赖远程偏好是否实时）：provider 的 `call`
     * 被我们的 hook 拦到后，当场读文件并把这个号回写；进程没起会被这条调用拉起。
     * 结果由 App 在 [awaitWallpaperSize] 里等（重启是后台跑的，不挡"打开相册"）。
     */
    fun beginWallpaperProbe() {
        prefs.requestWallpaperProbe()
        // 后台唤醒宿主：不阻塞"打开相册"；选完图回来时 [awaitWallpaperSize] 才开始等结果。
        scope.launch(Dispatchers.IO) {
            val error = wakeAod(prefs.probeToken.toString())
            if (error.isNotEmpty()) prefs.setDiagNote("壁纸尺寸探测：唤醒宿主失败（$error）")
        }
    }

    /**
     * 选图第二步（从相册回来时）：等 hook 把**这次请求**的答案回写过来，最多 [timeoutMs]。
     *
     * **只认 hook**（用户要求：脚本不再负责取分辨率）：拿到就用；超时就 toast
     * 「分辨率对比失败，请手动检查后再开始替换」并返回 null（调用方据此**跳过尺寸检查**）。
     * **不退回旧缓存、也不回退脚本** —— 那正是"明明换了壁纸、弹窗还是旧分辨率"的来源。
     */
    suspend fun awaitWallpaperSize(timeoutMs: Long = 5_000L): Pair<Int, Int>? {
        val token = prefs.probeToken
        preparingWallpaper = true
        val hook = try {
            withContext(Dispatchers.IO) {
                runCatching { waitProbeAnswer(token, timeoutMs) }.getOrNull()
            }
        } finally {
            preparingWallpaper = false
        }
        if (hook != null) {
            wallpaperSize = hook
            prefs.setDiagNote("壁纸尺寸探测：${hook.first}×${hook.second}（hook）")
            return hook
        }
        toast("分辨率对比失败，请手动检查后再开始替换")
        prefs.setDiagNote("壁纸尺寸探测：hook 超时（未取到分辨率，已提示手动检查）")
        return null
    }

    /** 轮询等 hook 回执（每 100 ms 一次）。`probeAck >= token` 说明 hook 已经处理过这次请求。 */
    private fun waitProbeAnswer(token: Long, timeoutMs: Long): Pair<Int, Int>? {
        if (token <= 0L) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (prefs.probeAck >= token) {
                return if (prefs.wallpaperSizeToken >= token) prefs.wallpaperSize.toSizeOrNull() else null
            }
            Thread.sleep(100L)
        }
        return null
    }

    /** 所有提示都走系统 Toast（不再用应用内 Snackbar），详见 [AppToast]。 */
    private fun toast(message: String, long: Boolean = false) =
        AppToast.show(context, message, long)

    fun applyThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.setThemeMode(mode.id)
    }

    fun applyVerbose(value: Boolean) {
        verbose = value
        prefs.setVerboseLog(value)
    }

    /** 切换设置页「自检 / 最近操作」两块的显示（只管界面，不影响任何判定逻辑）。 */
    fun applyShowDiagnostics(value: Boolean) {
        showDiagnostics = value
        prefs.setShowDiagnostics(value)
    }

    /** 切换「解除景深遮挡限制」——让编辑器里的避让判定恒通过（视频景深不受影响）。 */
    fun applyDepthOcclusionRemoved(value: Boolean) {
        depthOcclusionRemoved = value
        prefs.setDepthOcclusionRemoved(value)
    }

    /**
     * 「立即替换」按钮的统一入口：先看有没有选图，没选就只说这个；选了就替换。
     *
     * （原来的「屏幕灭亮提醒」确认弹窗 2026-10-04 已按用户要求删除 —— 注入改成 hook 之后
     * 不再依赖灭亮屏，是否灭亮屏改由「替换行为 → 替换后自动灭亮屏一次」开关决定。）
     */
    fun requestReplace() {
        if (configuredRoles.isEmpty()) {
            toast("还没有选择任何图片")
            return
        }
        rootReplace()
    }

    /** 切换「替换后自动灭亮屏一次」。 */
    fun applyAutoScreenFlash(value: Boolean) {
        autoScreenFlash = value
        prefs.setAutoScreenFlash(value)
    }

    /**
     * 切换「使自定义锁屏按钮显示在上层」。
     *
     * 只写库就够：SystemUI 侧是在**下一次长按菜单出现时**才读这个开关的（有 3 秒缓存），
     * 当前已经浮着的那次菜单不受影响，下次长按自然按新值走。
     */
    fun applyLockScreenButtonOnTop(value: Boolean) {
        lockScreenButtonOnTop = value
        prefs.setLockScreenButtonOnTop(value)
    }

    /** 切换「打开多任务时隐藏状态栏」（系统桌面 → 多任务）。 */
    fun applyHideStatusBarInRecents(value: Boolean) {
        hideStatusBarInRecents = value
        prefs.setHideStatusBarInRecents(value)
    }

    /** 切换「强制锁屏元素深浅模式」主开关。 */
    fun applyForceToneMode(value: Boolean) {
        forceToneMode = value
        prefs.setForceToneMode(value)
    }

    /** 6 个元素的强制档位（0=保持系统判定 / 1=浅色 / 2=深色）。 */
    fun applyForceToneStatusBar(value: Int) {
        forceToneStatusBar = value
        prefs.setForceToneStatusBar(value)
    }

    fun applyForceToneClock(value: Int) {
        forceToneClock = value
        prefs.setForceToneClock(value)
    }

    fun applyForceToneShortcut(value: Int) {
        forceToneShortcut = value
        prefs.setForceToneShortcut(value)
    }

    fun applyForceToneFingerprint(value: Int) {
        forceToneFingerprint = value
        prefs.setForceToneFingerprint(value)
    }

    fun applyForceToneBottomHint(value: Int) {
        forceToneBottomHint = value
        prefs.setForceToneBottomHint(value)
    }

    fun applyForceToneSignature(value: Int) {
        forceToneSignature = value
        prefs.setForceToneSignature(value)
    }

    // ---------------------------------------------------------------- 视差壁纸效果（2026-10-12 加）
    //
    // 与"三个模板角色"不同：背景图**不写任何系统文件、不需要 root、无保护期** ——
    // 选图只入模块自己的 files/，生效 = 让 SystemUI 侧的 `ParallaxBg` 显示它。

    /**
     * 导入「视差壁纸效果」的背景图（相册缓存副本 → 模块文件）。
     *
     * 不查 root（背景链不写系统文件）、不做分辨率比对（任意比例都行 —— 显示侧
     * CENTER_CROP 全屏 + 额外放大，见 `ParallaxBg`）。
     */
    suspend fun importParallaxBg(source: File) {
        val result = withContext(Dispatchers.IO) { MaskStore.importParallax(context, source) }
        withContext(Dispatchers.IO) { source.delete() }
        result
            .onSuccess {
                prefs.bumpRevision()
                refreshLocal()
                toast("背景已就绪（点「立即替换」生效）", long = true)
            }
            .onFailure { error ->
                toast("导入失败：${error.message}")
            }
    }

    /** 「清空选择」：只清背景候选（生效中不允许 —— 按钮在界面上已禁用，这里再兜一层）。 */
    fun clearParallaxBg() {
        if (parallaxActive) {
            toast("请先关闭替换")
            return
        }
        MaskStore.clearParallax(context)
        prefs.bumpRevision()
        refreshLocal()
        toast("已清空")
    }

    /**
     * 「立即替换」：开启视差壁纸效果。
     *
     * 顺序：**先写状态（权威）、再发广播**（SystemUI 侧以 config 为准、广播只当触发）；
     * 等回执，超时则回滚成"未开启" —— 界面状态必须与真实情况一致
     * （超时通常意味着 SystemUI 没重启过 / hook 还没装上，提示重启后重试）。
     */
    fun enableParallax() {
        if (!MaskStore.parallaxExists(context)) {
            toast("还没有选择背景图片")
            return
        }
        scope.launch {
            busy = true
            // 记录「视差基准」：当前壁纸判据 —— 供「壁纸改变后自动关闭替换」比对；
            // 算不出就留空（hook 侧检测时会惰性补记）。开启即重置，避免沿用上一轮的陈旧基准。
            withContext(Dispatchers.IO) {
                val fp = runCatching { WallpaperVersion.compute(context) }.getOrNull()
                    ?.value?.takeIf { it.isNotEmpty() }
                prefs.setParallaxWallpaperFp(fp.orEmpty())
            }
            prefs.setParallaxBgActive(true)
            // 不动「视差效果覆盖桌面」—— 它是独立开关；桌面层级由 hook 端按
            // 「总开关 AND 覆盖选项」判据决定要不要建。
            // **但系统属性必须重写**：它代表「桌面层级最终生效」，miwallpaper 侧的
            // ParallaxDesktopGuard（抑制解锁动画）只读它，不重写就会停在旧值。
            ParallaxCoverFlag.push(prefs.parallaxCoverDesktop)
            val token = prefs.requestParallax()
            sendParallaxCommand(token, on = true)
            val acknowledged = withContext(Dispatchers.IO) { waitParallaxAck(token) }
            if (!acknowledged) {
                prefs.setParallaxBgActive(false)
                toast("系统界面未响应，请重启系统界面后重试", long = true)
            } else {
                toast("已生效")
            }
            busy = false
            refreshLocal()
        }
    }

    /**
     * 「关闭替换」：关闭视差壁纸效果（SystemUI 侧移除背景层 + 主体位移复位）。
     *
     * 超时**不回滚**（状态就按"已关闭"走 —— 权威状态为 false 时，最坏情况是背景层
     * 残留到 SystemUI 下次重启自愈时才消失，提示用户即可）。
     */
    fun disableParallax() {
        scope.launch {
            busy = true
            prefs.setParallaxBgActive(false)
            // 基准随关闭清掉（下次开启时重记）；与 hook 侧自动关闭的收尾保持一致。
            prefs.setParallaxWallpaperFp("")
            // **系统属性同步成 0**：总开关关 ⇒ 桌面层级最终不生效，
            // miwallpaper 侧的 ParallaxDesktopGuard 必须立刻停掉解锁动画抑制。
            ParallaxCoverFlag.push(false)
            // 注意：**不**动「视差效果覆盖桌面」那个选项 —— 它是独立开关，由用户自己管。
            // 总开关关掉时，hook 端按「总开关 AND 覆盖选项」的判据自动销毁桌面层级。
            val token = prefs.requestParallax()
            sendParallaxCommand(token, on = false)
            val acknowledged = withContext(Dispatchers.IO) { waitParallaxAck(token) }
            if (!acknowledged) {
                toast("已请求关闭，系统界面未响应（如仍显示请重启系统界面）", long = true)
            }
            // **兜底：桌面视差生效过就重载一次 miwallpaper**（用户 2026-10-21 定）。
            // 关闭后层虽被 remove，但壁纸层（WallpaperWindowToken 及其子层）里可能仍有
            // 历史残留的可见层 / 被改过的 matrix，只有 miwallpaper 重建壁纸层才彻底复位。
            // 放在 ack 之后执行，避免打断 SystemUI 侧的收尾。
            if (parallaxCoverDesktop) {
                withContext(Dispatchers.IO) {
                    runCatching { RootShell.run("killall com.miui.miwallpaper") }
                }
            }
            busy = false
            refreshLocal()
            // 关闭成功补一句（2026-10-29 用户要求）。超时/未响应上面已经单独提示过，
            // 这里只在真的收到 ack 时播报，避免"失败提示 + 成功提示"并存。
            if (acknowledged) toast("视差壁纸已关闭")
        }
    }

    private fun sendParallaxCommand(token: Long, on: Boolean) {
        runCatching {
            context.sendBroadcast(
                Intent(ParallaxBg.ACTION).apply {
                    setPackage(SystemUiPackageName)
                    putExtra(ParallaxBg.EXTRA_TOKEN, token)
                    putExtra(ParallaxBg.EXTRA_ON, on)
                },
            )
        }
    }

    private fun waitParallaxAck(token: Long): Boolean {
        val deadline = System.currentTimeMillis() + ParallaxAckTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (prefs.parallaxAck >= token) return true
            Thread.sleep(100L)
        }
        return false
    }

    // ---------------------------------------------------------------- 视差「参数调整」（2026-10-13 加）

    /**
     * 幅度上限（公式见 [MaskPrefs.parallaxAmpUpperBound]）：`半屏宽 ×（倍率−1） − 5px`。
     * 半屏宽取当前显示资源（本机 1200px 宽 → 600）。
     */
    private fun parallaxAmpUpper(scale: Float): Float =
        MaskPrefs.parallaxAmpUpperBound(
            scale,
            context.resources.displayMetrics.widthPixels / 2f,
        )

    /**
     * 「主体移动最大幅度」滑块的动态范围：下限 = 上限 × 50%（取整到 1px 步进）。
     * 倍率 = 1.0 时上限 0 → 范围退化为 0..0（UI 置禁用态，值锁 0）。
     */
    fun parallaxSubjectAmpRange(): ClosedFloatingPointRange<Float> {
        val upper = floor(parallaxAmpUpper(parallaxSubjectScale))
        return ceil(upper * MaskPrefs.PARALLAX_SUBJECT_AMP_MIN_RATIO)..upper
    }

    /** 「背景移动最大幅度」滑块的动态范围：下限 = 上限 × 35%（用户定 —— 25px 不落界外）。 */
    fun parallaxBgAmpRange(): ClosedFloatingPointRange<Float> {
        val upper = floor(parallaxAmpUpper(parallaxBgScale))
        return ceil(upper * MaskPrefs.PARALLAX_BG_AMP_MIN_RATIO)..upper
    }

    /** 「参数调整」→ 反向开关（单次操作，直接落盘）。 */
    fun applyParallaxReverse(value: Boolean) {
        parallaxReverse = value
        prefs.setParallaxReverse(value)
    }

    fun applyParallaxAutoOff(value: Boolean) {
        parallaxAutoOff = value
        prefs.setParallaxAutoOff(value)
    }

    /** 「视差效果覆盖桌面」（2026-10-21）。落盘 + 写系统属性 + 合成图设为桌面壁纸（关闭时还原）。 */
    fun applyParallaxCoverDesktop(value: Boolean) {
        parallaxCoverDesktop = value
        prefs.setParallaxCoverDesktop(value)
        // 传 **cover && active**：属性语义是「桌面层级最终是否生效」，
        // miwallpaper 侧的 ParallaxDesktopGuard 只认这个（它读不到总开关）。
        // 读 prefs 而不是 `parallaxActive`：内存属性要等 refreshLocal 才同步，
        // 刚切完总开关的瞬间可能是旧值。
        ParallaxCoverFlag.push(value && prefs.parallaxBgActive)
        // **通知 SystemUI**（2026-10-21）：prefs 文件通道有延迟，hook 侧会读到旧值 ——
        // 实测把选项关掉后它仍读到 true，表现就是「无论开不开都在桌面建层」。
        // 广播是权威通道，收到即生效。
        runCatching {
            context.sendBroadcast(
                Intent(ParallaxBg.ACTION).apply {
                    setPackage(SystemUiPackageName)
                    putExtra(ParallaxBg.EXTRA_COVER, value)
                },
            )
        }
        // 打开时把两张图推进 miwallpaper 的 data 目录（GL 侧按 mtime 自动重载，不用重启进程）。
        if (value) {
            Thread {
                runCatching { ParallaxFeed.push(context) }
            }.apply { isDaemon = true }.start()
        } else if (parallaxActive) {
            // **兜底：关闭本选项时重载 miwallpaper**（用户 2026-10-21 定）。
            // 桌面视差生效过 ⇒ 壁纸层里可能留着我们的子层/被改过的 matrix，
            // 只删 SurfaceControl 不够，得让壁纸服务重建壁纸层才彻底复位。
            Thread {
                runCatching { RootShell.run("killall com.miui.miwallpaper") }
            }.apply { isDaemon = true }.start()
        }
    }

    /**
     * 「参数调整」→ 主体放大倍率。
     *
     * 倍率变化会联动**夹取**幅度（上限=余量−5px、下限=上限×50%）——夹取只改内存，抬手一起落盘。
     */
    fun applyParallaxSubjectScale(value: Float, persist: Boolean = true) {
        parallaxSubjectScale = value
        parallaxSubjectAmp = parallaxSubjectAmp.coerceIn(parallaxSubjectAmpRange())
        if (persist) persistParallaxParams()
    }

    /** 「参数调整」→ 背景放大倍率（夹取逻辑同主体，下限比例 35%）。 */
    fun applyParallaxBgScale(value: Float, persist: Boolean = true) {
        parallaxBgScale = value
        parallaxBgAmp = parallaxBgAmp.coerceIn(parallaxBgAmpRange())
        if (persist) persistParallaxParams()
    }

    fun applyParallaxSubjectAmp(value: Float, persist: Boolean = true) {
        parallaxSubjectAmp = value
        if (persist) persistParallaxParams()
    }

    fun applyParallaxBgAmp(value: Float, persist: Boolean = true) {
        parallaxBgAmp = value
        if (persist) persistParallaxParams()
    }

    /**
     * 「参数调整」→ 恢复原位等待时长（秒）——**静止判定窗口**：停下来后等待多久开始归位动画
     *（对应 SystemUI 侧原先的编译期常量 `STILL_WINDOW_MS`，2026-10-13 起运行时可调）。
     */
    fun applyParallaxHomeWait(value: Float, persist: Boolean = true) {
        parallaxHomeWait = value
        if (persist) persistParallaxParams()
    }

    /**
     * 「参数调整」→ 最大幅度角度（度）——**转动的满量程角**：绕重力轴转多少度达到满幅
     *（对应 SystemUI 侧原先的编译期常量 `GYRO_FULL_SCALE_DEG`，2026-10-13 起运行时可调）。
     */
    fun applyParallaxGyroDeg(value: Float, persist: Boolean = true) {
        parallaxGyroDeg = value
        if (persist) persistParallaxParams()
    }

    /**
     * 抬手落盘：一次写齐 6 个连续参数（倍率 ×2 + 幅度 ×2 + 归位等待 + 满量程角，
     * 含倍率联动夹取后的值）。
     *
     * 拖动中不落盘（`applyXxx(persist = false)` 只改内存）——`MaskPrefs` 用同步 `commit()`，
     * 每帧写盘会把拖动卡成"一顿一顿"（NavBg 滑块的同一教训）；抬手补一次即可。
     */
    fun persistParallaxParams() {
        prefs.setParallaxSubjectScale(parallaxSubjectScale)
        prefs.setParallaxBgScale(parallaxBgScale)
        prefs.setParallaxSubjectAmp(parallaxSubjectAmp)
        prefs.setParallaxBgAmp(parallaxBgAmp)
        prefs.setParallaxHomeWait(parallaxHomeWait)
        prefs.setParallaxGyroDeg(parallaxGyroDeg)
    }

    /**
     * 点「选择图片」前的**景深检查** —— 读系统设置 `wallpaper_effect_type_2`（`2` = 景深开着）。
     *
     * 走 aod 的 hook：写请求号 → `content call` 唤醒宿主（`--arg depth=<号>`）→ 等回执。
     * 超时 / 读值失败（-1）都**放行不拦**（宁可漏检，不误伤）；只有明确读到"没开"才 toast 拦截。
     */
    suspend fun ensureDepthEnabled(): Boolean {
        val token = prefs.requestDepthProbe()
        withContext(Dispatchers.IO) { wakeAod("depth=$token") }
        val value = withContext(Dispatchers.IO) { waitDepthAnswer(token) }
        return when (value) {
            null -> {
                prefs.setDiagNote("景深检查：hook 超时（未拦截，继续）")
                true
            }

            -1 -> {
                prefs.setDiagNote("景深检查：读值失败（未拦截，继续）")
                true
            }

            2 -> true

            else -> {
                prefs.setDiagNote("景深检查：未开启（已拦截）")
                toast("请先在系统锁屏编辑开启景深效果")
                false
            }
        }
    }

    private fun waitDepthAnswer(token: Long): Int? {
        val deadline = System.currentTimeMillis() + DepthProbeTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (prefs.depthProbeAck >= token) return prefs.depthProbeValue
            Thread.sleep(100L)
        }
        return null
    }

    suspend fun importRole(role: MaskRole, source: File) {
        val result = withContext(Dispatchers.IO) { MaskStore.importFromFile(context, role, source) }
        withContext(Dispatchers.IO) { source.delete() }
        result
            .onSuccess { size ->
                prefs.bumpRevision()
                sizes = sizes.toMutableMap().apply { put(role, size) }
                refreshLocal()
                toast("${role.title} 已就绪 $size（点「立即替换」生效）", long = true)
            }
            .onFailure { error ->
                toast("导入失败：${error.message}")
            }
    }

    /**
     * 「清空选择」：只清掉这个角色的候选图（已经写进系统的快照不动）。
     */
    fun clearRole(role: MaskRole) {
        MaskStore.clear(context, role)
        prefs.bumpRevision()
        sizes = sizes.toMutableMap().apply { put(role, null) }
        refreshLocal()
        toast("已清空")
    }

    /**
     * 探一次 root 并记下来。
     *
     * 调用点只有两个：主页的「刷新 Root」按钮（[silent] = false，会转圈并弹提示），
     * 以及首次运行时的静默探测（[silent] = true，用户看不见）。
     */
    fun refreshRoot(silent: Boolean = false) {
        scope.launch {
            if (!silent) busy = true
            val available = withContext(Dispatchers.IO) { RootReplacer.isAvailable() }
            rootAvailable = available
            prefs.setRootAvailable(available)
            if (!silent) {
                busy = false
                toast(
                    if (available) "root 可用，可以直接替换" else NoRootMessage,
                    long = !available,
                )
            }
        }
    }

    /**
     * 需要 root 的操作（选图、替换）统一先过这一关：**实测一次**并写回记录，
     * 没有 root 就提示 [NoRootMessage] 并返回 false —— 调用方直接中止，别让操作"半生效"。
     */
    suspend fun ensureRoot(): Boolean {
        val hasRoot = withContext(Dispatchers.IO) { RootReplacer.isAvailable() }
        rootAvailable = hasRoot
        prefs.setRootAvailable(hasRoot)
        if (!hasRoot) toast(NoRootMessage, long = true)
        return hasRoot
    }

    init {
        // 首次运行（还没有任何记录）时静默探一次 root，别让主页一直停在"未知"。
        // 之后完全按记录走：只有「刷新 Root」按钮和「立即替换」失败会改写它。
        if (prefs.rootAvailable == null) refreshRoot(silent = true)
    }

    init {
        // 激活探测：LSPosed 的 binder 是跨进程送来的，首帧常常早于它到达（R8 加快启动后更明显），
        // 不主动等一等，界面就会先画一帧"未激活"再跳成"已激活"。
        // 这里每 [ActivationProbeIntervalMs] 探一次，一命中立刻定格；跑满也定格（那时确实没激活）。
        // 必须回写 state —— 静态变量 Compose 观察不到，改 state 才是唯一能触发重组的途径。
        scope.launch {
            for (attempt in 0 until ActivationProbeAttempts) {
                if (ActivationState.frameworkConnected || HookRuntime.selfInjected) break
                delay(ActivationProbeIntervalMs)
            }
            frameworkConnected = ActivationState.frameworkConnected
            activationSettled = true
        }
    }

    companion object {
        /**
         * 没有 root 时统一用这句（「刷新 Root」的结果提示、以及 [ensureRoot] 的实测兜底）。
         *
         * 功能页按钮**不再**用它：那边统一走 [NotActivatedMessage] —— 用户要求"只要状态不是
         * 「模块已激活」，点按钮就提示'模块未激活，请先激活'"，不管缺的是 LSPosed 还是 root。
         * 原来那句"未检测到Root 请给予模块Root后重试"已废弃。
         */
        const val NoRootMessage = "未检测到Root"

        /** 状态不是「模块已激活」时的统一提示（功能页所有按钮都走它）。 */
        const val NotActivatedMessage = "模块未激活，请先激活"

        /** 激活探测节奏：每 50ms 一次、最多 20 次（约 1 秒）。 */
        const val ActivationProbeIntervalMs = 50L
        const val ActivationProbeAttempts = 20

        /** 宿主模板 provider（清单里 `exported=true`）——「立即替换」与「壁纸探测」都靠唤醒它落到 hook 上。 */
        const val AOD_PROVIDER_URI = "content://miui.keyguard.editor.templatefileprovider"
        const val AOD_PROVIDER_METHOD = "callGetHistoryTemplates"

        /** 「视差壁纸效果」指令回执的等待时长（SystemUI 侧 = 读图 + 插层 + 回执，1.5s 足够）。 */
        const val ParallaxAckTimeoutMs = 1_500L

        /** 「景深检查」等待时长 —— 要覆盖"aod 进程被本条 call 冷启动"的路径，给 3s。 */
        const val DepthProbeTimeoutMs = 3_000L
    }

    /**
     * 重启选中的应用（顶栏刷新按钮 → 多选确认后调用）。
     *
     * 编辑器与 SystemUI 会把主体图 / 遮罩缓存在内存里，换完文件后可能仍在用旧的；
     * 结束目标进程是最干脆的清缓存方式，下次打开编辑器读的一定是磁盘上的新内容。
     *
     * 多选是 2026-09-29 加的：只重启 aod 时，SystemUI 里锁屏按钮那条链仍带着旧的模块代码，
     * 想验证那条链的改动就得单独重启系统界面。
     *
     * 结果按三种情况报（用户指定）：全部成功只报「全部重启完成」；部分成功先报
     * 「部分重启完成」，再单独一条列出每个失败项「xx重启失败，原因xx」；全失败则只报失败项。
     */
    fun restartTargets(packages: List<String>) {
        if (packages.isEmpty()) {
            toast("请先选择要重启的应用")
            return
        }
        scope.launch {
            busy = true
            val failures = withContext(Dispatchers.IO) {
                packages.mapNotNull { packageName ->
                    val result = AppRestarter.restart(context, packageName)
                    if (result.ok) {
                        null
                    } else {
                        val label = RestartTargets.firstOrNull { it.packageName == packageName }
                            ?.label ?: packageName
                        "${label}重启失败，原因${result.reason ?: "未知"}"
                    }
                }
            }
            busy = false
            refreshLocal()
            // 全成、部分成、全败 —— 三种情况三种报法（用户指定）：
            // 全不报"重启了几个"，只报"全部重启完成"；有失败就把失败项逐条列出来。
            when {
                failures.isEmpty() -> toast("全部重启完成")
                failures.size < packages.size -> {
                    toast("部分重启完成")
                    toast(failures.joinToString("\n"))
                }
                else -> toast(failures.joinToString("\n"))
            }
        }
    }

    fun rootReplace() {
        if (configuredRoles.isEmpty()) {
            toast("还没有选择任何图片")
            return
        }
        scope.launch {
            busy = true
            // ① 判据 + 注入快照 + 保护（和原来的 root 脚本路一致，只是"谁写"变了）。
            val version = withContext(Dispatchers.IO) {
                runCatching { WallpaperVersion.compute(context) }.getOrNull()
            }
            val fingerprint = version?.value?.takeIf { it.isNotEmpty() } ?: MaskPrefs.FINGERPRINT_WEAK
            withContext(Dispatchers.IO) {
                MaskRole.selectable.filter { MaskStore.snapshotToInjected(context, it) }
                prefs.setInjectedWallpaper(fingerprint)
                prefs.setProtecting(true)
                prefs.bumpRevision()
            }
            // ② 叫醒 aod 进程里的 hook 落盘（写 subject / subject_mask / crop_subject + 触发重解码）。
            val token = prefs.requestApply()
            val wakeError = withContext(Dispatchers.IO) { wakeAod("apply=$token") }
            val ok = withContext(Dispatchers.IO) { waitApplyAck(token, 8_000L) }
            // ③ 收尾：结束保护（原来靠"灭亮屏"当信号；现在 hook 已经写完并触发过重解码，直接收）。
            withContext(Dispatchers.IO) { releaseInjection() }
            // ④ 可选：灭亮屏一次（方案 C = SystemUI hook 优先，1.2s 没回执就退一条 su 命令）。
            val flashNote = if (ok && autoScreenFlash) {
                withContext(Dispatchers.IO) { flashScreen() }
            } else {
                ""
            }
            busy = false
            refreshLocal()
            toast(
                if (!ok) "替换失败：hook 注入超时（请看 run_log.txt）" else "替换已生效",
                long = true,
            )
            withContext(Dispatchers.IO) {
                RunLog.append(
                    context,
                    if (ok) "立即替换（hook）" else "立即替换（hook，超时）",
                    listOf(
                        "流程：App 写 protecting+指纹+快照 → provider 叫醒 aod → hook 落盘 + 触发重解码 → 回执",
                        "落盘内容：subject / subject_mask（只给主体时按 alpha 生成白剪影）/ crop_subject",
                        if (wakeError.isEmpty()) "唤醒宿主：直达/兜底至少一条成功" else "唤醒宿主报错：$wakeError",
                        if (flashNote.isEmpty()) "灭亮屏：未开启/未执行" else "灭亮屏：$flashNote",
                        "结果=${if (ok) "成功" else "超时（8s 没收到 apply_ack）"}",
                    ),
                    ok,
                )
            }
        }
    }

    /**
     * 叫醒 `com.miui.aod`：让它的 `TemplateFileProvider.call` 走到我们的 hook。
     *
     * 两条通道，**直连优先、su 兜底**：
     * 1. 直接 `contentResolver.call(…)`（清单里该 provider `exported=true`；真机上可能被权限/时序挡住）；
     * 2. 直连抛异常时用**一条** `su -c "content call …"` —— 只是这一条 IPC 唤醒命令，
     *    **不是**那个替换脚本（这条通道在真机上验证过）。
     *
     * 最多试 [attempts] 次、每次间隔 400ms：第一次可能在宿主进程刚起、hook 还没装上时落空。
     *
     * @return 空串 = 至少一条通道发成功了；非空 = 最后一次的错误描述（交给诊断行 / run_log）。
     */
    private fun wakeAod(arg: String, attempts: Int = 3): String {
        var lastError = ""
        repeat(attempts) { index ->
            val direct = runCatching {
                context.contentResolver.call(Uri.parse(AOD_PROVIDER_URI), AOD_PROVIDER_METHOD, arg, null)
            }
            if (direct.isSuccess) return ""
            lastError = direct.exceptionOrNull()
                ?.let { "${it.javaClass.simpleName}: ${it.message}" }
                .orEmpty()

            val su = runCatching {
                RootShell.run(
                    "content call --uri $AOD_PROVIDER_URI --method $AOD_PROVIDER_METHOD --arg $arg",
                    timeoutSeconds = 30L,
                )
            }.getOrNull()
            if (su != null && su.exitCode == 0) return ""
            lastError += if (su != null) {
                "；su 退出码=${su.exitCode} 输出=${su.output.trim().take(120)}"
            } else {
                "；su 也起不来"
            }
            if (index < attempts - 1) Thread.sleep(400L)
        }
        return lastError.ifEmpty { "未知" }
    }

    /** 等 hook 的注入回执（`apply_ack >= token`）。 */
    private fun waitApplyAck(token: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (prefs.applyAck >= token) return true
            Thread.sleep(100L)
        }
        return false
    }

    /**
     * 注入完成 → 结束保护。等价原来 `MaskProvider.METHOD_RELEASE` 做的事：
     * 只把 `protecting` 置 false 并清掉候选/注入快照（**不动系统里已经替换好的内容**）。
     */
    private fun releaseInjection() {
        if (!prefs.protecting) return
        prefs.setProtecting(false)
        MaskRole.entries.forEach { role ->
            MaskStore.clear(context, role)
            MaskStore.clearInjected(context, role)
        }
        prefs.setInjectedWallpaper("")
        prefs.recordInjection(
            processName = "本应用（hook 注入）",
            at = System.currentTimeMillis(),
            revision = prefs.revision,
            detail = "保护已结束：hook 直接落盘并触发重新解码，替换已生效；候选与注入快照已一并清除。",
        )
    }

    /**
     * 「替换后自动灭亮屏一次」—— 方案 C：**SystemUI hook 优先，su 一条命令兜底**。
     *
     * 1. 发一条显式广播给 `com.android.systemui`（显式到包名，避开隐式广播限制），
     *    SystemUI 侧 `ScreenFlash` 的接收器用 `PowerManager.goToSleep/wakeUp` 灭亮屏并回执；
     * 2. 最多等 1.2s；没等到回执就退到 `su -c "input keyevent 26; sleep 1; input keyevent 26"`
     *    （只有这一条按键命令，不是替换脚本）。
     *
     * @return 给 run_log 用的一行说明。
     */
    private fun flashScreen(): String {
        val token = prefs.requestFlash()
        runCatching {
            context.sendBroadcast(
                Intent(ScreenFlash.ACTION).apply {
                    setPackage(SystemUiPackageName)
                    putExtra(ScreenFlash.EXTRA_TOKEN, token)
                },
            )
        }
        val deadline = System.currentTimeMillis() + 1_200L
        while (System.currentTimeMillis() < deadline) {
            if (prefs.flashAck >= token) return "SystemUI hook 已完成"
            Thread.sleep(100L)
        }
        val su = runCatching {
            RootShell.run(
                "input keyevent 26; sleep 1; input keyevent 26",
                timeoutSeconds = 20L,
            )
        }.getOrNull()
        return if (su != null && su.exitCode == 0) {
            "SystemUI hook 没回执，已退回 su 一条命令"
        } else {
            "失败（hook 无回执，su 也失败）"
        }
    }

    fun runSelfTest() {
        scope.launch {
            busy = true
            selfTestLines = withContext(Dispatchers.IO) { SelfTest.run(context) }
            busy = false
        }
    }
}

/**
 * 候选预览的解码上限：**4096 = 导入封顶，等于不降采样**。
 *
 * 2026-10-12 视差页先改（用户要求"用原分辨率"）；2026-10-13 遮罩页两个候选（主体 / 遮罩）
 * 也统一为不压缩（用户要求，旧 720 的降采样预览取消）。三个预览位图各自按原分辨率解码
 * （最大 4096 边 → 单张 ≈ 16MB 级），换来预览框里的真实清晰度。
 */
const val PreviewMaxEdge = 4096

internal fun <T> roleState(block: (MaskRole) -> T): Map<MaskRole, T> =
    MaskRole.selectable.associateWith(block)

internal fun readHookStatus(prefs: MaskPrefs): HookStatus = HookStatus(
    process = prefs.injectedProcess,
    at = prefs.injectedAt,
    revision = prefs.injectedRevision,
    detail = prefs.injectedDetail,
)

internal fun formatTime(at: Long): String =
    if (at <= 0L) "未知时间" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/** 判据是 sha1，界面上只显示头尾，免得一行放不下。 */
internal fun String.abbreviate(max: Int = 18): String =
    if (length <= max) this else take(max / 2) + "…" + takeLast(max / 2)

/** 解析 `宽x高`（[MaskPrefs.wallpaperSize] 的格式）；不是这个格式就返回 null。 */
internal fun String.toSizeOrNull(): Pair<Int, Int>? {
    val parts = split('x', 'X')
    if (parts.size != 2) return null
    val width = parts[0].trim().toIntOrNull() ?: return null
    val height = parts[1].trim().toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return width to height
}

/** hook / root 回写的替换状态。 */
data class HookStatus(
    val process: String?,
    val at: Long,
    val revision: Long,
    val detail: String?,
)
