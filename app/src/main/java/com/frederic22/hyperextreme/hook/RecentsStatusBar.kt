package com.frederic22.hyperextreme.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Parcel
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「打开多任务和文件夹时隐藏状态栏」（v1.3.0 起，需求口径变更）—— **SystemUI 侧**实现。
 *
 * ## 需求口径（2026-10-17 用户拍板，v1.3.0）
 *
 * **多任务页** 和 **文件夹** 打开时，隐藏状态栏"内容"（时钟 / 系统图标 / 通知图标），
 * **保留下拉**；**回到桌面** 或 **打开应用** 时恢复正常。
 *
 * 相比旧口径（只覆盖多任务）的最大好处：**不再需要区分「文件夹」与「多任务」**，
 * 于是此前为区分它们而堆的全部判据（`toRecents` 确认窗口、桥信号够新、顶层任务校验、
 * 桌面可见性）**全部删除** —— 桌面侧的 `edge=1` 本来就会在两种场景都触发，正好一刀切。
 *
 * ## 触发：桌面 → SystemUI 的桥信号（v1.1.0 起，v1.3.0 改三分支）
 *
 * `LauncherProxyService` 内嵌的 `ISystemUiProxy` Stub（运行时类名
 * `com.android.systemui.LauncherProxyService$1`）的 `onTransact` 上挂 hook —— **唯一挂点**。
 *
 * | code | 来源 | 说明 |
 * | --- | --- | --- |
 * | **7** | **桌面侧 native 桥**（`libhome_bridge.so`） | payload 末字节传三分支，见 [handleBridgeValue] |
 * | 62 / 63 | ROM 自带的 int 版 onOverviewShown/Hidden | 本 ROM 实测桌面从不发（保留兼容） |
 *
 * ## 新逻辑（v1.3.0，大幅简化）
 *
 * | 信号 | 含义 | 动作 |
 * | --- | --- | --- |
 * | `edge=1` | 桌面发生路由变化 —— 本 ROM 实测只会是**进多任务**或**打开文件夹** | **立即隐藏**（无需任何确认） |
 * | `edge=0` | 退出多任务 / 文件夹，或打开应用 | **延迟 400ms 还原** |
 * | `toRecents` 广播 | 桌面补发的状态广播（shade 收起时也会来） | 取消待还原 + 隐藏 |
 *
 * 为什么 `edge=0` 要延迟：**下拉 / 收起 shade 时桌面同样会误发 `edge=0`**（与真实退出
 * 的信号完全一样），但紧接着会补发 `edge=1` + `toRecents`。延迟 400ms 让这些后续信号
 * 有机会取消还原，从而避免 shade 操作导致状态栏闪一下。
 *
 * ## 动作：SystemUI 进程内的状态栏 disable 通道（自持 token，天然隔离）
 *
 * 用系统自己的 `StatusBarManager#disable(int)`（本进程 `getSystemService("statusbar")`）：
 *
 * | 时机 | 调用 | 含义 |
 * | --- | --- | --- |
 * | 进入（开关开） | `disable(0x920000)` | 隐藏 clock + system-icons + notification-icons |
 * | 退出（设过才动） | `disable(0)` | 清掉**本调用方**的这条 disable 记录 |
 *
 * - 位集 = AOSP 标准 disable 位；2026-10-16 已用 `cmd statusbar send-disable-flag` 真机验证：
 *   内容全隐、**下拉不受影响**、`none` 后完全还原（dumpsys `mDisabled1` 0x920000 ↔ 0x0）。
 * - **不带** `DISABLE_EXPAND`（0x00010000）—— 那是"禁止下拉"，正是要保留的东西。
 * - 图标只靠 disable 位在 shade 收起后会复活，故另有 [StatusBarIconHide] 做 View 直控补充。
 *
 * ## 边界与已接受的口径
 *
 * - 开关只在**事件到达时**现读（频率 = 用户进出多任务/文件夹的频率），无轮询、无常驻线程；
 * - 错过 hidden（如桌面进程异常死亡）时，最坏情形 = 内容藏到下次进出 / SystemUI 重启；
 * - `edge=1` 是"任何路由变化"，桌面其他路由变化（如进编辑模式）也会触发隐藏 —— 已接受。
 *
 * ## 日志
 *
 * 桥信号到达 / 进出处理 / 设位结果各有日志。过滤用 ASCII 特征串：
 * `code=7` / `value=` / `0x920000` / `what=0` / `onTransact`。
 */
internal object RecentsStatusBar {

    // ---------------------------------------------------------------- 挂点

    /** 桌面调进来用的 `ISystemUiProxy` Stub —— 运行时类名。 */
    private const val CLS_PROXY_STUB = "com.android.systemui.LauncherProxyService\$1"

    /** `onTransact` 的 transaction code（同版本 dex 核实）。 */
    private const val CODE_OVERVIEW_SHOWN = 62
    private const val CODE_OVERVIEW_HIDDEN = 63

    /** 桥信号：launcher 侧 `BpSystemUiProxy::onOverviewShown(bool)` 发出的 code 7（空壳通道，v1.1.0）。 */
    private const val CODE_OVERVIEW_BOOL = 7

    // ---------------------------------------------------------------- 精确判据：MIUI 全屏状态广播

    /**
     * ★ 精确判据（v1.1.4 起）：MIUI 系统广播 `com.miui.fullscreen_state_change`。
     *
     * 由桌面 Dart 侧 `sendStateBroadcast` 主动发出，**只反映真实的全屏状态切换**（进/出多任务），
     * 而 T3 那条链是 `LoggingNavigatorObserver._notifyRouteToRust`（**任何路由变化**）——所以
     * 「点开文件夹」也会触发 T3、误隐藏状态栏（2026-10-17 用户实测报告）。
     *
     * 真机矩阵：187 进 / 手势进 → `toRecents`；HOME 退 → `toHome`；
     * **进文件夹 → 无广播（完全干净）**；返回键退 / 多任务点卡片 → 无 `toHome`（故仍需 T3 兜底）。
     */
    private const val ACTION_FULLSCREEN_STATE_CHANGE = "com.miui.fullscreen_state_change"
    private const val EXTRA_STATE = "state" // 注意：日志里打印的是变量名 stateName，extra key 其实是 "state"
    private const val STATE_TO_RECENTS = "toRecents"
    private const val STATE_TO_HOME = "toHome"

    /** 接收方在 WMShell 共享库里，随 SystemUI 加载（纯 Java hook，不碰 Dart/Rust）。 */
    private const val CLS_FREEFORM_RECEIVER =
        "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeSettingsObserver\$FreeFormBroadcastReceiver"

    /** shade 收起后的重放挂点（见 [installPanelReHooks]）。 */
    private const val CLS_PANEL_CONTROLLER = "com.android.systemui.shade.NotificationPanelViewController"

    /**
     * `edge=0`（退出）后延迟这么久才真正还原。
     *
     * 为什么必须延迟（2026-10-17 实测）：**下拉 / 收起 shade 时桌面同样会误发 `edge=0`**
     * —— 与真实退出多任务的信号**完全一样**，无法从信号本身区分。但 shade 操作后桌面会
     * 紧接着补发 `edge=1` + `toRecents`。延迟 400ms 让这些后续信号有机会取消还原，
     * 从而避免"在多任务里拉一下通知面板、状态栏闪一下又藏回去"。
     */
    private const val RESTORE_DELAY_MS = 400L

    // ---------------------------------------------------------------- 位集

    /**
     * 要隐藏的位 = 时钟 + 系统图标（信号 / 电量等）+ 通知图标，合起来就是状态栏"全部内容"。
     *
     * 刻意**不含** `DISABLE_EXPAND`（0x00010000）—— 下拉（展开通知面板）必须保留。
     */
    private const val HIDE_BITS = 0x00800000 or 0x00100000 or 0x00020000 // = 0x920000

    // ---------------------------------------------------------------- 状态

    private val hooked = AtomicBoolean(false)

    /**
     * 我们的动作串行队列 —— 保证 hide / restore 的处理顺序与信号到达顺序一致。
     *
     * ⚠⚠ **必须是真正的 [java.util.concurrent.ScheduledThreadPoolExecutor]，不能用
     * `Executors.newSingleThreadScheduledExecutor()`**（v1.3.2 实测踩坑）：
     * 后者返回的是 `DelegatedScheduledExecutorService` **包装类**，
     * `as? ScheduledThreadPoolExecutor` 永远转型失败 ⇒ [cancelPendingRestore] 里的
     * `remove()` 从未被调用 ⇒ **延迟还原永远取消不掉**。
     * 表现：快速切换文件夹时，`edge=0` 排的还原照常执行，状态栏在两个文件夹之间闪一下
     * （一度被误判成"400ms 延迟太长"，其实取消机制压根没工作）。
     */
    private val executor: java.util.concurrent.ScheduledThreadPoolExecutor =
        java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "HyperExtreme-RecentsSB").apply { isDaemon = true }
        }

    /** 是否"位是我们设的、还没还"（只在 [executor] 线程读写）。 */
    @Volatile
    private var applied = false

    /**
     * 待执行的「还原」任务（v1.3.0 延迟还原用）。
     *
     * 非 null 表示"有一个还原在排队等着"，此时任何 `edge=1` / `toRecents` 都应**取消**它。
     */
    @Volatile
    private var pendingRestore: Runnable? = null

    /**
     * 还原任务的"代际"（v1.3.2）：每次排还原就 +1，取消/重新隐藏也 +1。
     *
     * 作用：`ScheduledThreadPoolExecutor#remove` 只对**还没开跑**的任务有效；
     * 若任务已经开跑才取消，`remove` 返回 false 但任务仍在跑 —— 此时它读到旧代际，
     * 直接跳过还原，避免把"已经重新隐藏"的状态错误还原掉。
     */
    private val restoreGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 面板收起/广播接收器是否已挂（各自独立容错，缺一个不影响主链）。 */
    private val broadcastHooked = AtomicBoolean(false)
    private val panelHooked = AtomicBoolean(false)

    /** 解锁/亮屏兜底还原的广播接收器是否已注册（幂等）。 */
    private val unlockGuardRegistered = AtomicBoolean(false)

    /** 探针：已经打过日志的 transaction code（每个码一条）。 */
    private val seenCodes = ConcurrentHashMap<Int, Boolean>()

    /**
 * `StatusBarManager` 实例与反射出来的 `disable(int)`。
 *
 * ⚠ **必须强引用、且全进程只用这一个实例**（2026-10-17 踩坑）：`StatusBarManager` 的
 * `mToken` 是**实例级**的（构造时 `new Binder()`），system_server 按 token 分组记录 disable 位。
 * 之前用 `WeakReference` 缓存，一旦实例被 GC、再次 `getSystemService` 拿到**新实例**，
 * `disable(0)` 清的就是**另一条记录**，而设位那条（0x920000）纹丝不动 ——
 * 表现为「明明已退出、dumpsys 却一直是 0x920000」，且与 GC 时机相关、极难复现。
 */
    private var manager: Any? = null
    private var disableMethod: Method? = null

    // ---------------------------------------------------------------- 安装

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        if (hooked.get()) return true
        val clazz = runCatching { Class.forName(CLS_PROXY_STUB, false, loader) }.getOrNull()
        if (clazz == null) {
            HookRuntime.log("多任务隐藏状态栏：未找到 $CLS_PROXY_STUB（SystemUI 版本变了？）")
            return false
        }
        val method = runCatching {
            clazz.getDeclaredMethod(
                "onTransact",
                Int::class.javaPrimitiveType,
                Parcel::class.java,
                Parcel::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (method == null) {
            HookRuntime.log("多任务隐藏状态栏：$CLS_PROXY_STUB 里没有 onTransact(int,Parcel,Parcel,int)")
            return false
        }
        // ⚠ 这里**不能**写 `return`（v1.3.3 修正）：写成 `return runCatching{...}.also{}` 的话，
        //    函数在这一行就返回了，后面注册解锁兜底还原的语句全是死代码
        //    （v1.3.2 实测：注册行一条都没打出来，白改一轮）。
        val installOk = runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val code = chain.getArg(0) as? Int
                    // 桥信号（launcher 主动调用，v1.1.0）：data = [12B 前导][len dword][UTF-16 token]
                    // [NUL+对齐][bool …]（v1.1.3 真机字节取证定稿）。要点：
                    //  · token 带 12 字节前导，Java readString() 对不上 → 不能用"读 token 再续读"定位；
                    //  · **bool 是最后一个字段**：其字节在 dataSize-4 处（int32 或 int8+补齐都一样）；
                    //  · 用 readByte()（native=读 int32 取低字节）语义取该字节。
                    if (code == CODE_OVERVIEW_BOOL) {
                        val data = chain.getArg(1) as? Parcel
                        val raw = if (data != null) {
                            runCatching {
                                val pos = data.dataPosition()
                                val size = data.dataSize()
                                require(size >= 8) { "size=$size too small" }
                                // 桥信号是 payload 最后一个字段，字节在 dataSize-4（int32/int8+补齐都一致）
                                data.setDataPosition(size - 4)
                                val b = data.readByte().toInt()
                                data.setDataPosition(pos)
                                HookRuntime.log("多任务隐藏状态栏：桥信号 code=7 value=$b")
                                b
                            }.getOrNull()
                        } else null
                        if (raw != null) {
                            runCatching { executor.execute { handleBridgeValue(raw) } }
                        } else {
                            HookRuntime.log("多任务隐藏状态栏：桥信号 code=7 但取值解析失败")
                        }
                    }
                    val result = chain.proceed()
                    if (code != null) {
                        // 探针：每个 code 首见打一条（已知码附中文说明）。
                        if (seenCodes.putIfAbsent(code, true) == null) {
                            val hint = when (code) {
                                CODE_OVERVIEW_BOOL -> "（桥 bool）"
                                CODE_OVERVIEW_SHOWN -> "（onOverviewShown）"
                                CODE_OVERVIEW_HIDDEN -> "（onOverviewHidden）"
                                else -> ""
                            }
                            HookRuntime.log("多任务隐藏状态栏：transact 首见 code=$code$hint")
                        }
                        if (code == CODE_OVERVIEW_SHOWN || code == CODE_OVERVIEW_HIDDEN) {
                            // 62/63 是 ROM 自带的 int 版（本 ROM 实测桌面从不发，保留兼容）。
                            val shown = code == CODE_OVERVIEW_SHOWN
                            runCatching {
                                executor.execute { if (shown) applyHide() else scheduleRestore() }
                            }
                        }
                    }
                    return result
                }
            })
            HookRuntime.log(
                "多任务隐藏状态栏：已挂 $CLS_PROXY_STUB#onTransact" +
                    "（$CODE_OVERVIEW_SHOWN=shown / $CODE_OVERVIEW_HIDDEN=hidden）",
            )
            hooked.set(true)
            true
        }.getOrElse {
            HookRuntime.log("多任务隐藏状态栏：挂 onTransact 失败：$it")
            false
        }.also { ok ->
            // 增强挂点：广播（精确判据）与图标直控。各自独立容错，缺哪个都不影响主链。
            if (ok) {
                runCatching { installBroadcastHook(module, loader) }
                runCatching { installPanelReHooks(module, loader) }
                runCatching { StatusBarIconHide.install(module, loader) }
            }
        }
        // 解锁/亮屏兜底还原（v1.3.2）：必须在挂点装好后注册，否则锁屏期间桌面抖出的
        // `edge=0 → edge=1` 会让我们在**锁屏上**就设位，解锁后没有新 edge 来还原
        // ⇒ 状态栏空白（2026-10-17 用户实测）。
        // ⚠⚠ 必须是**普通语句**、写在 `return ...also{}` 表达式结束之后 ——
        //   第一版接在 return 表达式后面，成了死代码，注册行一条都没打出来。
        registerUnlockRestoreGuard()
        @Suppress("UNUSED_EXPRESSION")
        installOk
        return hooked.get()
    }

    /**
     * 解锁 / 亮屏时强制还原到安全态（v1.3.2）。
     *
     * ## 为什么需要（2026-10-17 真机复现）
     * 锁屏 → 唤醒 → 解锁的过程中，桌面会抖出 `edge=0` → `edge=1`（实测间隔仅 45ms）。
     * 于是我们的逻辑在**锁屏还盖着屏幕的时候**就重新 `disable(0x920000)` 设了位；
     * 等用户真正解锁进桌面/应用时，路由没再变、没有新 edge ⇒ **位一直挂着**，
     * 表现为"解锁后状态栏图标不全或完全不显示"。
     *
     * ## 做法
     * 监听 `ACTION_USER_PRESENT`（解锁完成）与 `ACTION_SCREEN_ON`（亮屏）：
     * 只要我们还 `applied`，就立刻还原（清位 + 图标恢复）。
     * 即使用户解锁后落在多任务/文件夹里，也只是**短暂**显示一下状态栏，
     * 紧随的下一次 `edge=1` 会重新隐藏 —— 相比"卡在空白"这是明显更好的失败模式。
     */
    private fun registerUnlockRestoreGuard() {
        val ctx = HookRuntime.appContext()
        if (ctx == null) {
            HookRuntime.log("多任务隐藏状态栏：拿不到 Context，跳过解锁兜底还原注册")
            return
        }
        if (unlockGuardRegistered.get()) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                        if (!applied) return
                        HookRuntime.log("多任务隐藏状态栏：${intent.action} → 强制还原（解锁/亮屏兜底）")
                        cancelPendingRestore()
                        runCatching { StatusBarIconHide.restore() }
                        if (setDisabled(HookRuntime.appContext(), 0)) applied = false
                    }
                }
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ctx.registerReceiver(receiver, filter)
            unlockGuardRegistered.set(true)
            HookRuntime.log("多任务隐藏状态栏：已注册解锁/亮屏兜底还原（USER_PRESENT + SCREEN_ON）")
        }.onFailure { HookRuntime.log("多任务隐藏状态栏：注册解锁兜底还原失败：$it") }
    }

    // ---------------------------------------------------------------- 精确判据：全屏状态广播

    /**
     * hook WMShell 的 `FreeFormBroadcastReceiver#onReceive` —— 读 `state` extra：
     * `toRecents` → 设位（**这才是"真进了多任务"**）；`toHome` → 清位。
     *
     * 挂点只读、不改任何东西，原逻辑照常执行。
     */
    private fun installBroadcastHook(module: XposedModule, loader: ClassLoader) {
        if (broadcastHooked.get()) return
        val clazz = runCatching { Class.forName(CLS_FREEFORM_RECEIVER, false, loader) }.getOrNull()
        if (clazz == null) {
            HookRuntime.log("多任务隐藏状态栏：未找到 $CLS_FREEFORM_RECEIVER（WMShell 版本变了？）")
            return
        }
        val method = runCatching {
            clazz.getDeclaredMethod(
                "onReceive",
                Context::class.java,
                Intent::class.java,
            )
        }.getOrNull()
        if (method == null) {
            HookRuntime.log("多任务隐藏状态栏：$CLS_FREEFORM_RECEIVER 没有 onReceive(Context,Intent)")
            return
        }
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val intent = chain.getArg(1) as? Intent
                if (intent != null && ACTION_FULLSCREEN_STATE_CHANGE == intent.action) {
                    val state = runCatching { intent.getStringExtra(EXTRA_STATE) }.getOrNull()
                    if (state != null) onFullscreenState(state)
                }
                return chain.proceed()
            }
        })
        HookRuntime.log("多任务隐藏状态栏：已挂 $CLS_FREEFORM_RECEIVER#onReceive")
        broadcastHooked.set(true)
    }

    /** [executor] 线程：处理全屏状态广播。 */
    private fun onFullscreenState(state: String) {
        when (state) {
            STATE_TO_RECENTS -> {
                // 桌面补发的状态广播。shade 收起时它也会来 —— 正好用来取消" shade 造成的误退出"。
                val enabled = enabled(HookRuntime.appContext())
                HookRuntime.log("多任务隐藏状态栏：广播 toRecents（开关=$enabled）")
                if (!enabled) return
                applyHide()
            }

            STATE_TO_HOME -> {
                // 本广播在 shade 操作时会**误发**，不能据此还原（否则 shade 一拉状态栏就闪）。
                // 还原只认桌面的 `edge=0` 桥信号（见 [handleBridgeValue]）。
                HookRuntime.log("多任务隐藏状态栏：广播 toHome（不处理）")
            }

            else -> HookRuntime.log("多任务隐藏状态栏：广播 state=$state（不处理）")
        }
    }

    // ---------------------------------------------------------------- 下拉收起后重放位

    /**
     * hook shade 的收起动作，在收起完成后重放 disable 位。
     *
     * 为什么需要（2026-10-17 真机实测）：下拉再收起后，SystemUI 会重置 system icons 的可见性
     * 却**不重放 disable 记录** ⇒ `mDisabled1` 仍是 `0x920000`，但右侧网速/WiFi/信号/电量重新出现
     * （左侧时钟仍隐藏，因为时钟那条 disable 路径没被重置）。
     * 验证：退出再重进多任务会重新调用 `disable(0x920000)`，图标立刻重新消失 ⇒ 重放即可修复。
     *
     * 收起必经 `flingToHeight(0)`（手势甩动）或 `instantCollapse()` / `collapse()`（程序化收起），
     * 三个都挂上互相兜底；只在「我们自己设过位」时才重放，零额外开销。
     */
    /**
 * 「下拉收起后重放位」的挂点预留（v1.1.4：重放已被证伪，**当前不启用**）。
 *
 * 保留原因：`flingToHeight` / `collapse` 在本版签名不同或不在本类（`collapse` 属父类
 * `PanelController`），可靠挂到的只有 `instantCollapse`。若将来改用「直接控制 View 可见性」，
 * 这里正是收起的触发点；届时把 [replayAfterShadeCollapse] 换成 View 直控即可。
 */
    private fun installPanelReHooks(module: XposedModule, loader: ClassLoader) {
        if (panelHooked.get()) return
        panelHooked.set(true)
        runCatching {
            val clazz = Class.forName(CLS_PANEL_CONTROLLER, false, loader)
            var installed = 0
            listOf("instantCollapse", "collapse").forEach { name ->
                val m = findMethodUp(clazz, name)
                if (m == null) {
                    HookRuntime.log("多任务隐藏状态栏：$CLS_PANEL_CONTROLLER 无 $name（跳过）")
                    return@forEach
                }
                runCatching {
                    module.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val r = chain.proceed()
                            replayAfterShadeCollapse(name)
                            return r
                        }
                    })
                    installed++
                }.onFailure { HookRuntime.log("多任务隐藏状态栏：挂 $name 失败：$it") }
            }
            if (installed > 0) {
                HookRuntime.log("多任务隐藏状态栏：已挂收起钩子（$installed 个 @ ${clazz.name}，重放暂未启用）")
            }
        }
    }

    /**
     * 沿**类层次 + 接口**逐级查找方法（`getDeclaredMethod` 只看本类声明，
     * 而 `collapse` / `instantCollapse` 声明在父类 `PanelController` 上）。
     */
    private fun findMethodUp(root: Class<*>, name: String): Method? {
        var c: Class<*>? = root
        while (c != null && c != Any::class.java) {
            runCatching { c.getDeclaredMethod(name) }.getOrNull()?.let { return it }
            for (i in c.interfaces) {
                runCatching { i.getDeclaredMethod(name) }.getOrNull()?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    /**
     * 「下拉收起后图标复活」的修复尝试 —— **已证伪，暂不启用**（v1.1.4 真机三次迭代结论）。
     *
     * 现象：进多任务时图标正确隐去；但下拉再收起后，右上角图标重新出现，而 `dumpsys` 的
     * `mDisabled1` **仍是正确的 `0x920000`**（极具迷惑性：位是对的，画面是错的；左侧时钟
     * 因走另一条 disable 路径而仍隐着，于是呈现「时钟隐去 + 图标显示」的半吊子状态）。
     *
     * 三种重放方式全部无效（dumpsys 均显示 `0x920000`，画面均不正确）：
     * 1. 单纯重设 `disable(0x920000)` → 值未变 → SystemUI 不重新应用可见性；
     * 2. 先清后设（无间隔）→ 两次异步 binder 顺序可能被打乱 → **反而连时钟都显示出来**；
     * 3. 先清后设 + 120ms 间隔 → 仍旧全部显示。
     *
     * ⇒ 推断：shade 展开/收起后，图标可见性被 SystemUI 的**本地状态**接管，不再响应
     * DisplayPolicy 的 disable 回调。要修只能改为**直接控制状态栏 View 的可见性**
     * （绕开 disable 机制）。方案与代价待定，故此处保留代码骨架但不启用。
     */
    private fun replayAfterShadeCollapse(why: String) {
        // 暂不启用：重放已被证伪（详见上方注释）。若将来改用 View 直控，此处重新启用。
    }

    // ---------------------------------------------------------------- 事件处理（executor 线程）

    /**
     * 桥信号三分支派发（v1.3.0 口径）。
     *
     * | value | 来源 | 含义 | 动作 |
     * | --- | --- | --- | --- |
     * | 0 | `edge=0` 或 `UVIS visible=0` | 退出多任务/文件夹，或打开了应用 | **延迟还原**（见 [RESTORE_DELAY_MS]） |
     * | 1 | `edge=1` | 桌面路由变化 —— 本 ROM 实测只会是**进多任务**或**打开文件夹** | **立即隐藏** |
     * | 2 | `UVIS visible=1` | 桌面 UI 重新可见 | 不动作（还原交给 `edge=0`） |
     *
     * v1.3.0 起因：需求从"只隐藏多任务"扩成"多任务 + 文件夹都隐藏"，于是
     * `edge=1` 可以直接当"隐藏"信号用（两种情况都要隐藏），此前为区分两者而加的
     * 全部确认逻辑（确认窗口 / 桥信号够新 / 顶层任务校验 / 桌面可见性）一并删除。
     */
    private fun handleBridgeValue(raw: Int) {
        when (raw) {
            0 -> scheduleRestore()
            1 -> applyHide()
            2 -> Unit   // 桌面 UI 可见：还原由 edge=0 负责，这里不额外动作
            else -> HookRuntime.log("多任务隐藏状态栏：桥信号 value=$raw（未知值，忽略）")
        }
    }

    /** 立即隐藏（`edge=1` / `toRecents` 都会走到这）。取消任何排着队的还原。 */
    private fun applyHide() {
        cancelPendingRestore()
        if (!enabled(HookRuntime.appContext())) return
        if (setDisabled(HookRuntime.appContext(), HIDE_BITS)) {
            applied = true
            // ★ 懒注册解锁兜底还原（v1.3.3）：装 hook 的时机 `appContext()` 还是 null
            //   （见 HookEntry 里"Context 要等第一次真拿到视图才有"的注释），而这里
            //   setDisabled 刚成功 ⇒ Context 一定可用。且只此一次（内部有幂等位）。
            registerUnlockRestoreGuard()
            // ★ 两条路径（桥信号 / 广播）都必须调 hide() —— 漏掉任一都会让图标直控的
            //   `hiding` 一直是 false（2026-10-17 实测踩坑）。
            runCatching { StatusBarIconHide.hide() }
            HookRuntime.log("多任务隐藏状态栏：已隐藏（what=0x${HIDE_BITS.toString(16)}）")
        }
    }

    /**
     * 排一个**延迟**的还原（[RESTORE_DELAY_MS] 后执行）。
     *
     * 延迟的理由：下拉/收起 shade 时桌面会误发 `edge=0`，但随后会补 `edge=1` + `toRecents`；
     * 给这些后续信号一个取消的机会，避免 shade 一拉状态栏就闪。
     */
    private fun scheduleRestore() {
        cancelPendingRestore()
        if (!applied) {
            HookRuntime.log("多任务隐藏状态栏：请求还原（未设过位，跳过）")
            return
        }
        val gen = restoreGen.incrementAndGet()
        val task = Runnable {
            // 代际过期 ⇒ 期间又有 edge=1 / toRecents 取消了这次还原（或已重新隐藏）⇒ 不还原。
            if (gen != restoreGen.get()) {
                HookRuntime.log("多任务隐藏状态栏：还原任务已过期（gen=$gen），跳过")
                return@Runnable
            }
            pendingRestore = null
            runCatching { StatusBarIconHide.restore() }
            if (applied) {
                val ok = setDisabled(HookRuntime.appContext(), 0)
                if (ok) applied = false
                HookRuntime.log("多任务隐藏状态栏：已还原（what=0，ok=$ok）")
            }
        }
        pendingRestore = task
        val scheduled: Result<*> = runCatching {
            executor.schedule(task, RESTORE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
        scheduled.onFailure {
            // schedule 不可用就直接同步执行（保底）
            HookRuntime.log("多任务隐藏状态栏：延迟还原排不上，立即还原（$it）")
            task.run()
        }
    }

    private fun cancelPendingRestore() {
        val t = pendingRestore ?: return
        pendingRestore = null
        restoreGen.incrementAndGet()   // 立即使排着队的任务过期
        // executor 现在是真正的 ScheduledThreadPoolExecutor，remove() 能真正取消排队中的任务。
        // 返回 false 表示任务已经开跑 —— 但代际已过期，它开跑后也会自己跳过。
        val removed = runCatching { executor.remove(t) }.getOrDefault(false)
        if (!removed) {
            HookRuntime.log("多任务隐藏状态栏：取消还原（任务已开跑，靠代际兜底跳过）")
        }
    }

    // ---------------------------------------------------------------- 开关读取

    /**
     * 读「打开多任务时隐藏状态栏」。**事件到达时现读、无缓存**（频率很低）。
     *
     * 主通道 = provider `config` 快照（权威、实时；用户就是在 App 里点的这个开关，那一刻
     * App 进程必然活着；就算之后被杀，provider 也会按需拉起）。读不到再退远程通道
     * （`HookRuntime.remoteBoolean`：框架通道 + prefs 文件原文，不需要 App 进程）。
     */
    private fun enabled(context: Context?): Boolean {
        val ctx = context ?: HookRuntime.appContext()
        if (ctx != null) {
            val bundle = runCatching {
                ctx.contentResolver.call(MaskProvider.configUri(), MaskProvider.METHOD_CONFIG, null, null)
            }.getOrNull()
            if (bundle != null) {
                return bundle.getBoolean(MaskProvider.KEY_HIDE_STATUSBAR_IN_RECENTS, false)
            }
        }
        return HookRuntime.remoteBoolean(MaskPrefs.KEY_HIDE_STATUSBAR_IN_RECENTS) ?: false
    }

    /**
     * 供 [StatusBarIconHide] 的「刷新轮」复用 —— **必须走这里**，才能保证设位/清位用的是
     * 同一个 `StatusBarManager` 实例（token 稳定是 disable 位能成对清退的前提，见 [manager] 注释）。
     */
    internal fun setDisabledShared(what: Int): Boolean = setDisabled(HookRuntime.appContext(), what)

    // ---------------------------------------------------------------- 设位

    /**
     * 调 `StatusBarManager#disable(int)`（@hide，反射；编译期 SDK stub 里没有 —— 与
     * `ScreenFlash` 调 `PowerManager#goToSleep` 同一类做法，且这就是 `KeyguardViewMediator`
     * 在同进程里用的系统原生通道）。
     *
     * 传 0 = 清掉本调用方的记录（只清我们自己的这条，不碰别人）。
     */
    private fun setDisabled(context: Context?, what: Int): Boolean {
        val ctx = context ?: HookRuntime.appContext()
        if (ctx == null) {
            HookRuntime.log("多任务隐藏状态栏：拿不到 Context，放弃设位")
            return false
        }
        return runCatching {
            // 强引用缓存同一个实例 —— token 稳定是 disable 位能成对清退的前提（见字段注释）
            val m = manager ?: ctx.getSystemService("statusbar").also { manager = it }
                ?: error("getSystemService(\"statusbar\") 返回 null")
            val method = disableMethod
                ?: m.javaClass.getMethod("disable", Int::class.javaPrimitiveType).also {
                    disableMethod = it
                }
            method.invoke(m, what)
            true
        }.getOrElse {
            HookRuntime.log("多任务隐藏状态栏：调用 StatusBarManager#disable($what) 失败：$it")
            false
        }
    }
}
