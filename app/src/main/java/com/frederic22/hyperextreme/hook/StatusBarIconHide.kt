package com.frederic22.hyperextreme.hook

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/**
 * 「多任务隐藏状态栏」的**图标直控**（v1.1.6 起，**v1.2.12 修好并全绿**）。
 *
 * ## 为什么要它
 *
 * disable 位**只对时钟有效**—— v1.3.7 做过决定性实验：把本类整个关闭、只留
 * `disable(0x920000)`，进多任务后 WiFi / 电池 / 充电**图标照样显示**（位挂着，图标在）。
 * 原因：系统图标（网速/WiFi/信号/电量）由 `MiuiStatusIconContainer` /
 * `MobileSignalAnimatorContainer` 在布局时**自行计算**可见性，**这段逻辑不读 disable 位**。
 * ⇒ **图标直控是必需的，删掉这个功能就不成立。**
 *
 * ## 为什么时钟能被 disable 位管住、图标不能（真机反编译 + dump 结论）
 *
 * - 时钟 `com.android.systemui.statusbar.views.MiuiClock` 自己管自己：
 *   ```
 *   setPolicyVisibility(int)      // 由 disable 位驱动
 *   setMultiTaskVisibility(int)   // 由多任务状态驱动
 *     └─ 值变化时 → updateClockVisibility()   // public final，读两个字段决定 GONE/INVISIBLE/VISIBLE
 *   ```
 *   所以任何让这两个字段变化的时机（shade 收起就会触发）都会**重新按 disable 位算一遍可见性**。
 *
 * ## ⚠⚠⚠ 本类曾经**几乎完全无效**：View 操作跑错了线程（v1.3.9 根治）
 *
 * 这是"shade 之后图标整片消失"类 bug 的**唯一真因**，此前三轮修补（补全树扫描 →
 * 补容器还原 → 延迟兜底）全部走偏。`hide()` / `restore()` 都由 worker 线程
 * `HyperExtreme-RecentsSB` 调用，而 `View#setVisibility` **只能在主线程执行**，
 * 抛出的 `CalledFromWrongThreadException` 又被 `runCatching` 吞掉 ⇒
 * **设位时的隐藏和退出时的还原全部静默失败**。唯一真正生效的隐藏是
 * `updateClockVisibility` 钩子内部（那个跑在主线程），于是 shade 收起时藏住的那批叶子
 * 永远还原不回来。现在所有 View 操作统一经 [runOnMain] 切回主线程。
 *
 * ## ⚠ 本类还曾**长期空转**（2026-10-17，已修）
 *
 * 旧版两个致命点，导致 `命中ICON容器=0` —— 图标直控**一次都没真正生效过**
 * （此前"进多任务图标会藏"其实是 disable 位的功劳，不是这段代码）：
 *
 * 1. **遍历起点错**：从 `clock.parent` 起步，而那一支**只覆盖状态栏左半边**
 *    （时钟 + 通知图标）。**右半边的系统图标根本不在这一支里**。
 *    ⇒ 改为 [findStatusBarRoot]：从时钟往上找**第一个状态栏根 View**
 *    （本 ROM = `MiuiPhoneStatusBarView`），整棵状态栏子树都在范围内。
 * 2. **排除名单误杀状态栏自身**：`EXCLUDE_CONTAINERS` 里有 `StatusBarView`，
 *    而状态栏根的真实类名是 **`MiuiPhoneStatusBarView`**（**包含** `StatusBarView` 子串）
 *    ⇒ 被当成"面板"整棵跳过。
 *    ⇒ 重写名单：只排除明确的下拉面板类，且状态栏根 / 时钟祖先链一律放行。
 *
 * ### 修好后的真机全树（2026-10-17 14:35 dump，`«ICON»` = 白名单命中）
 * ```
 * MiuiPhoneStatusBarView
 *   ├ FrameLayout
 *   │   ├ BatteryIndicator «ICON»                        （电池，顶部）
 *   │   └ LinearLayout …                                 ← 左半：MiuiClock + ComposeView + 通知图标
 *   │       └ AlphaOptimizedFrameLayout → NotificationIconContainer «ICON» → StatusBarIconView «ICON»
 *   └ LinearLayout                                        ← 右半（关键，之前完全没覆盖到）
 *       └ MiuiStatusBatteryContainer «ICON»
 *       └ MiuiStatusIconContainer «ICON»
 *           ├ NetworkSpeedView «ICON»        ← 网速
 *           ├ StatusBarIconView «ICON» ×N    ← WiFi / 信号 / 电量
 *           └ SingleBindableStatusBarComposeIconView
 * ```
 *
 * ## 时机：借 `MiuiClock#updateClockVisibility`（shade 收起必然触发它）
 * 在其执行后把图标子树强制置 `INVISIBLE`；设位时立刻做一次。
 * - 用 `INVISIBLE` 而非 `GONE`：保留布局占位，不引起状态栏重排；
 * - **记录原可见性并成对还原**，恢复时不会把系统本要隐藏的图标点亮；
 * - 遍历**只走状态栏根这一棵子树**，不越过它进入下拉面板（首版"面板卡住"教训）；
 * - 明确排除 Compose 宿主（`AndroidViewsHandler` 等），避免误伤 shade；
 * - 所有 View 引用用 [WeakReference]，不拽住宿主 View 树。
 */
internal object StatusBarIconHide {

    /** 是否处于「隐藏」态；由设位/清位切换。 */
    @Volatile
    private var hiding = false

    /** 时钟视图（作为定位状态栏根视图的锚点；由 updateClockVisibility 捕获）。 */
    private var clockRef: WeakReference<View>? = null

    /** 已强制隐藏的 View 及它们**原本**的可见性，用于精确还原。 */
    private val saved = ArrayList<Pair<View, Int>>()

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val diagnosed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val guardStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 只打一次"命中 0 个叶子"的告警（说明白名单 / 遍历起点又失效了）。 */
    private val emptyRunLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 取证：上一轮遍历实际访问的节点数 / 命中 ICON 容器的次数（用于定位"白名单为何对不上"）。 */
    @Volatile
    private var visitedNodes = 0

    @Volatile
    private var iconHits = 0

    private const val CLS_MIUI_CLOCK = "com.android.systemui.statusbar.views.MiuiClock"
    private const val CLS_ICON_CONTAINER = "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
    private const val CLS_STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView"

    /**
     * 需要强制隐藏的图标容器类名片段（大小写不敏感的包含匹配）。
     *
     * ⚠ 名单来自 **2026-10-17 真机 dump 的实际类名**（`StatusBarIconView` /
     * `NotificationIconContainer` 是 dump 里实测到的，之前漏了它们，所以一直是 `命中ICON容器=0`）。
     */
    private val ICON_CONTAINERS = arrayOf(
        // —— 右半边：系统图标（网速 / WiFi / 信号 / 电量）——
        "MiuiStatusIconContainer",
        "NetworkSpeedView",
        "MiuiStatusBatteryContainer",
        "BatteryIndicator",
        "MiuiBatteryMeterIconView",
        // —— 左半边：通知图标（dump 实测类名）——
        "NotificationIconContainer",
        "StatusBarIconView",
        // —— 信号动画容器 ——
        "MobileSignalAnimatorContainer",
    )

    /**
     * **显式排除**的面板/阴影区类名片段 —— 这些 View 绝不能碰：一旦把面板相关的 View 设成
     * INVISIBLE，会导致下拉面板收不起来 / 动画异常（2026-10-17 首版实测教训：范围过宽时确实面板卡住）。
     *
     * ⚠⚠ **绝不能用"包含匹配"排除状态栏自身**（2026-10-17 实测踩坑，`命中ICON容器=0` 之谜）：
     *   HyperOS 的状态栏整棵树的根是 **`MiuiPhoneStatusBarView`**，而它**包含 `StatusBarView` 子串**
     *   ⇒ 旧的 `EXCLUDE_CONTAINERS` 把它判成"面板"直接跳过，于是**一个图标叶子都碰不到**。
     *   正确口径：**只排除明确的下拉面板那几类，且状态栏根/时钟祖先链一律放行**。
     */
    private val EXCLUDE_CONTAINERS = arrayOf(
        "NotificationPanel",
        "NotificationStack",
        "NotificationPanelWindow",
        "StatusBarPanelView",
        "NotificationPanelViewController",
        "ShadeView",
        "QsPanel",
        "ControlCenter",
        "StatusBarWindowView",
    )


    /**
     * 还原后的**延迟二次兜底**扫描（毫秒）。
     *
     * ## 背景（2026-10-17 17:00–17:15 定位全过程）
     *
     * 「多任务内下拉一次控制中心 → 回桌面右上角图标整片消失」这个 bug 连修三轮无效
     * （补全树扫描 → 补容器还原 → 延迟兜底），根因其实是**线程错误**：
     * `hide()` / `restore()` 都由 worker 线程 `HyperExtreme-RecentsSB` 调用，
     * 而 `View#setVisibility` 只能在主线程执行 ⇒ 抛 `CalledFromWrongThreadException`
     * 被 `runCatching` 吞掉 ⇒ **设位时的隐藏和退出时的还原全部没生效**。
     * 唯一真正生效的隐藏是 `updateClockVisibility` 钩子内部（跑在主线程），
     * 于是"shade 收起时藏住的叶子"永远还原不回来。详见 [restore] 的完整说明。
     *
     * ## 本兜底的作用
     *
     * 线程问题修好后单点还原已经够用，这里作为**双保险**保留：
     * 系统在 shade 收起时会跑自己的"状态栏淡入"动画并可能把
     * `MiuiStatusBatteryContainer` 整体设 INVISIBLE（真机 dump 到过），
     * 即时还原恰好落在动画中途时会漏掉它；等 800ms 动画走完再扫一遍即可兜住。
     * 仅在 `!hiding` 时执行 —— 若期间又进入多任务（重新隐藏），绝不能扫。
     */
    private const val LATE_RESCAN_MS = 800L

    private val rescanner = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HyperExtreme-SBRescan").apply { isDaemon = true }
    }

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        if (installed.get()) return true
        val clazz = runCatching { Class.forName(CLS_MIUI_CLOCK, false, loader) }.getOrNull()
        if (clazz == null) {
            HookRuntime.log("多任务隐藏状态栏：未找到 $CLS_MIUI_CLOCK（图标直控跳过）")
            return false
        }
        val method = runCatching { clazz.getDeclaredMethod("updateClockVisibility") }.getOrNull()
        if (method == null) {
            HookRuntime.log("多任务隐藏状态栏：$CLS_MIUI_CLOCK 没有 updateClockVisibility()")
            return false
        }
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 顺带把时钟自己记下来：它是定位状态栏视图树最稳的锚点
                    (chain.getThisObject() as? View)?.let { clockRef = WeakReference(it) }
                    val r = chain.proceed()
                    // 时机机关：shade 收起必然走到这里，此刻把图标重新压回隐藏。
                    // 命中数为 0 说明白名单/起点又失效了（v1.2.11 之前的长期空转就是如此），
                    // 这条日志是唯一的"它到底有没有在干活"的判据，故保留。
                    if (hiding) {
                        val (targets, _) = applyHideWithCount()
                        if (targets == 0 && emptyRunLogged.compareAndSet(false, true)) {
                            HookRuntime.log(
                                "多任务隐藏状态栏：图标直控命中 0 个叶子（白名单或遍历起点失效，需重新取证）",
                            )
                        }
                    }
                    return r
                }
            })
            HookRuntime.log("多任务隐藏状态栏：已挂 $CLS_MIUI_CLOCK#updateClockVisibility（图标直控）")

// ❌ 已尝试并放弃：钩 `PhoneStatusBarView#onMeasure`（状态栏根视图，每次布局后重新隐藏）。
//   实测**无效**：shade 收起时状态栏布局根本不重跑，钩子压根不触发。
//   保留结论以免将来重走——**时机机关用 `MiuiClock#updateClockVisibility` 就够了**
//   （shade 收起必然触发它，见类注释）。
installed.set(true)
            // ⚠ 「刷新轮守护」（[refreshRound] / [startGuard]）**保留但不启用**：
            //   它靠伪造「假退出→假进入」强制重应用，而 shade 收起时桌面同样会发
            //   T3 edge=0 —— 两者交织会让状态栏**彻底不隐藏**（比不启用更糟）。
            //   v1.2.12 修好图标直控后已不需要它，故保持关闭。
            HookRuntime.log("多任务隐藏状态栏：图标直控已装（起点=状态栏根，白名单按真机 dump 类名）")
            true
        }.getOrElse {
            HookRuntime.log("多任务隐藏状态栏：挂图标直控失败：$it")
            false
        }
    }

/**
 * 「刷新轮」= 人为造一轮 **退出 → 进入**，让隐藏状态被完整地重新应用一遍。
     *
     * 起因（用户提议，2026-10-17）：shade 展开再收起后，disable 位仍是 `0x920000`（位对、画面错），
     * 而**系统图标的可见性已被 shade 侧重设**、不再由 disable 位决定。此前尝试的「单纯重放 disable」
     * 只改了位、并没有让图标直控一起重来，所以画面依旧错。
     *
     * 本轮把**两边都走一遍**：清位 + 图标恢复 → （间隔）→ 重新设位 + 重新隐藏，
     * 相当于伪造一次完整的「退出多任务 → 进入多任务」，让系统与我们自己的可见性控制都重新生效。
     *
     * @param gapMs 清位与重新设位之间的间隔，太短两次 binder 通知会乱序（实测无间隔会更糟）
     */
    fun refreshRound(gapMs: Long) {
        if (!hiding) return
        // 假退出
        restore()
        runCatching { HookRuntime.log("多任务隐藏状态栏：刷新轮 · 假退出") }
        RecentsStatusBar.setDisabledShared(0)
        runCatching { Thread.sleep(gapMs) }
        if (!hiding) return   // 期间可能已真退出
        // 假进入
        RecentsStatusBar.setDisabledShared(HIDE_BITS)
        hide()
    }

    /**
     * 周期性「刷新轮」守护：**只在已隐藏态**才动作。
     *
     * 不去检测 shade 收起时机（实测 `instantCollapse` / `collapse` / `flingToHeight` / 布局钩子
     * 全都不可靠：手势收起走的不是这些路径），改用"低频重放"覆盖 —— 每次刷新都是一次
     * 真实的状态位翻转 + 图标重应用，shade 收起后最多一个周期内被压回隐藏。
     */
    fun startGuard(intervalMs: Long) {
        if (guardStarted.getAndSet(true)) return
        Thread({
            while (true) {
                Thread.sleep(intervalMs)
                if (!hiding) continue
                runCatching { refreshRound(REFRESH_GAP_MS) }
                    .onFailure { HookRuntime.log("多任务隐藏状态栏：刷新轮异常：$it") }
            }
        }, "HyperExtreme-SBRefresh").apply {
            isDaemon = true
            start()
        }
        HookRuntime.log("多任务隐藏状态栏：已启动刷新轮守护（${intervalMs}ms 周期）")
    }

    /** 隐藏位的副本（避免与 RecentsStatusBar 的常量耦合）。 */
    private const val HIDE_BITS = 0x00800000 or 0x00100000 or 0x00020000
    private const val REFRESH_GAP_MS = 60L
    private const val GUARD_INTERVAL_MS = 1500L

    /** 沿类层次找方法（`onMeasure` 常由父类 `StatusBarView` 声明）。 */
    private fun findDeclaredUp(root: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = root
        val p: Array<out Class<*>> = params
        while (c != null && c != Any::class.java) {
            runCatching { c.getDeclaredMethod(name, *p) }.getOrNull()?.let { return it }
            c = c.superclass
        }
        return null
    }

    /**
     * 把状态栏根视图里的所有**叶子** View 置为 INVISIBLE，跳过时钟自身及其祖先链。
     *
     * 时钟交给 disable 位自行维护（它一旦隐藏就没人再动）；这里只管"其他一切"。
     */
    private fun hideStatusBarExceptClock(statusBar: View) {
        val clock = clockRef?.get()
        runCatching {
            // 时钟自身 + 其各级祖先 = 需要保留的路径
            val keep = HashSet<View>()
            if (clock != null) {
                var n: View? = clock
                while (n != null && n !== statusBar) {
                    keep.add(n)
                    n = n.parent as? View
                }
            }
            val targets = ArrayList<View>()
            val sbGroup = statusBar as? ViewGroup
            if (sbGroup != null) {
                for (i in 0 until sbGroup.childCount) {
                    walkForLeaves(sbGroup.getChildAt(i), keep, targets)
                }
            }
            if (targets.isEmpty()) return
            synchronized(saved) {
                for (t in targets) {
                    if (t.visibility != View.INVISIBLE) {
                        saved.add(t to t.visibility)
                        t.visibility = View.INVISIBLE
                    }
                }
            }
        }
    }

    private fun walkForLeaves(v: View, keep: Set<View>, out: MutableList<View>) {
        if (keep.contains(v) || isExcluded(v.javaClass.name)) return
        if (v is ViewGroup && v.childCount > 0) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c.visibility != View.GONE) walkForLeaves(c, keep, out)
            }
        } else {
            out.add(v)
        }
    }

    /** 把某个容器下的所有**叶子** View 置为 INVISIBLE 并记录原值（幂等）。 */
    private fun hideLeavesOf(container: View) {
        runCatching {
            val targets = ArrayList<View>()
            collectLeaves(container, targets)
            if (targets.isEmpty()) return
            synchronized(saved) {
                for (t in targets) {
                    if (t.visibility != View.INVISIBLE) {
                        saved.add(t to t.visibility)
                        t.visibility = View.INVISIBLE
                    }
                }
            }
        }
    }

    /**
     * 进入隐藏态（不清可见性，立即生效）。
     *
     * ★ v1.3.9：同样切主线程 —— 本方法也由 worker 线程调用，改可见性必须回主线程，
     *   否则抛 `CalledFromWrongThreadException`（历史症状：设位瞬间图标根本没藏，
     *   只有 shade 收起时主线程钩子那一次藏住了）。见 [restore] 的完整机制说明。
     */
    fun hide() {
        hiding = true
        runOnMain("hide") { applyHide() }
    }

    /**
     * 退出隐藏态并把可见性**逐个还原**到隐藏前的值。
     *
     * ★★ v1.3.9 **根本性修复**：整个方法体切到**主线程**执行。
     *
     * ## 为什么这是"根本性"的（2026-10-17 17:11 实锤）
     *
     * 本方法由 `RecentsStatusBar` 的 `HyperExtreme-RecentsSB` 线程调用，而
     * `View#setVisibility` **只能在创建该 View 树的线程（主线程）上调用**，否则抛
     * `CalledFromWrongThreadException`。原来 `runCatching` 把异常吞掉只剩一行日志，
     * 于是**每次退出还原都全盘失败、什么都没还原**。
     *
     * 配合 `hide()` 也在同一 worker 线程 ⇒ **设位瞬间的隐藏同样抛异常、从来没生效过**。
     * 唯一真正生效的隐藏路径是 `updateClockVisibility` 钩子内部（那个跑在主线程），
     * 于是形成极不对称的局面：
     * ```
     * 设位瞬间   worker 线程 → 抛异常 → 图标没藏   （disable 位只藏了时钟）
     * shade 收起 主线程     → 成功藏住            ← 唯一真正生效的隐藏
     * 退出还原   worker 线程 → 抛异常 → 没还原     ← shade 收起藏的那批永久卡住
     * ```
     * ⇒ **所有"shade 之后图标不显示"的症状都是这一条造成的**，
     * 与白名单、容器还原、时序全都无关（此前三次修补因此全部无效）。
     */
    fun restore() {
        hiding = false
        runOnMain { restoreOnMain() }
    }

    /** 主线程版 [restore]（由 [runOnMain] 调度）。 */
    private fun restoreOnMain() {
        runCatching {
            synchronized(saved) {
                for ((v, old) in saved) {
                    runCatching { v.visibility = old }
                }
                saved.clear()
            }
        }.onFailure { HookRuntime.log("多任务隐藏状态栏：图标可见性还原失败：$it") }
        // ★ 兜底全树还原（v1.2.13）：[saved] 只记了"隐藏那一刻"的 View，而 shade 收起后
        //   系统可能**重建**图标 View（新对象）—— 那些不在saved 里、仍是 INVISIBLE，
        //   表现为"回桌面后部分图标不显示"（2026-10-17 用户实测）。
        //   这里再把整棵状态栏子树扫一遍，凡命中白名单且当前 INVISIBLE 的一律恢复。
        restoreOrphanIcons()
        // ★ v1.3.8：延迟二次兜底（见 [LATE_RESCAN_MS] 的机制说明）。
        //   即时还原常发生在系统 shade 淡入动画的中途，系统随后才把容器设成 INVISIBLE；
        //   等动画走完再扫一遍，才能把"稍后才被设隐"的容器拉回来。
        //   `hiding` 复核：若期间又进入了多任务（重新隐藏），绝不能扫。
        runCatching {
            rescanner.schedule(
                {
                    if (hiding) return@schedule
                    runOnMain("延迟${LATE_RESCAN_MS}ms") {
                        restoreOrphanIcons()
                    }
                },
                LATE_RESCAN_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * 把一段 View 操作调度到**主线程**执行；若已在主线程则直接跑。
     *
     * @param tag 非空时，顺带把被调度的时刻/线程打进日志（取证用）。
     */
    private inline fun runOnMain(tag: String? = null, crossinline block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return
        }
        runCatching {
            mainHandler.post {
                runCatching { block() }
                    .onFailure { HookRuntime.log("多任务隐藏状态栏：主线程操作失败($tag)：$it") }
            }
        }.onFailure { HookRuntime.log("多任务隐藏状态栏：post 主线程失败($tag)：$it") }
    }

    /** 主线程 Handler —— SystemUI 的 View 树全在主线程，改可见性必须回到那里。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 兜底还原：遍历状态栏子树，把仍是 INVISIBLE 的**图标叶子**恢复成 VISIBLE。
     *
     * 只恢复"命中白名单 + 当前 INVISIBLE"的 —— 系统用 **GONE** 表示"本就不显示"
     * （真机 dump 里未激活的 `StatusBarIconView` 全是 `vGONE`），所以 INVISIBLE
     * 几乎只能是我们造成的，误点亮风险很低。
     */
    private fun restoreOrphanIcons() {
        val clock = clockRef?.get() ?: return
        val root = findStatusBarRoot(clock) ?: return
        runCatching {
            // ① 叶子（v1.2.13 起）
            var n = 0
            val targets = ArrayList<View>()
            collectLeavesOnly(root, targets)
            for (v in targets) {
                if (v.visibility == View.INVISIBLE) {
                    v.visibility = View.VISIBLE
                    n++
                }
            }
            // ② ★ 容器自身（v1.3.6 新增）—— 修"WiFi/信号/电池图标全没了"。
            //    真机 DBG（2026-10-17 16:52）抓到：
            //        MiuiStatusBatteryContainer(vINV) «ICON»«INV»
            //          MiuiStatusIconContainer(vVIS)   ← WiFi/信号在这里，VISIBLE
            //          MiuiBatteryMeterView(vVIS)      ← 电池，VISIBLE
            //    即**容器自己 INVISIBLE、里面全是 VISIBLE** ⇒ 父容器不渲染，里面图标
            //    一个都看不见。而 ① 只查叶子，叶子都 VISIBLE 就直接跳过 ⇒ 永远发现不了，
            //    表现就是"回桌面后右上角图标整片消失"（SystemUI 重启也无法自愈）。
            //    容器是系统自己在 shade 动画期间设成 INVISIBLE 的（我们从不碰容器）。
            //    还原时统一恢复成 VISIBLE；随后 disable 位变化会触发系统自己重算，
            //    该继续藏的（无 SIM、灵动岛占用等）系统会再设回去。
            var boxes = 0
            val containers = ArrayList<View>()
            collectIconContainers(root, containers)
            for (v in containers) {
                if (v.visibility == View.INVISIBLE && !isOnClockPath(v)) {
                    v.visibility = View.VISIBLE
                    boxes++
                }
            }
            if (n > 0 || boxes > 0) {
                HookRuntime.log(
                    "多任务隐藏状态栏：兜底还原 叶子=$n 容器=$boxes（saved 未覆盖的那批）",
                )
            }
        }.onFailure { HookRuntime.log("多任务隐藏状态栏：兜底还原异常：$it") }
    }

    /**
     * 收集所有命中白名单的**图标容器**自身（不再下钻）。
     *
     * 与 [collectLeavesOnly] 配对：那个收叶子、这个收容器壳。
     * 命中后即 `return`，避免把容器的子 View 也当容器重复收。
     */
    private fun collectIconContainers(view: View, out: MutableList<View>) {
        if (view !is ViewGroup) return
        if (isIconContainer(view.javaClass.name)) {
            out.add(view)
            return
        }
        for (i in 0 until view.childCount) {
            collectIconContainers(view.getChildAt(i), out)
        }
    }

    /** 只收集、不判断时钟路径（还原要连时钟旁边的图标一起扫）。 */
    private fun collectLeavesOnly(view: View, out: MutableList<View>) {
        if (view is ViewGroup) {
            val name = view.javaClass.name
            if (isIconContainer(name)) {
                collectLeaves(view, out)
                return
            }
            for (i in 0 until view.childCount) {
                collectLeavesOnly(view.getChildAt(i), out)
            }
        }
    }

    /**
     * 强制隐藏图标**叶子**视图；重复调用幂等（已 INVISIBLE 的不再重复记录）。
     *
     * ## 遍历起点为什么改成"时钟的祖先里第一个状态栏根"（2026-10-17 实测修正）
     *
     * 原实现从 `clock.parent` 起步。实测 dump 出来的树证明**那只覆盖状态栏左半边**：
     * ```
     * LinearLayout                      ← clock.parent，只能看到这一支
     *   AlphaOptimizedFrameLayout → OperatorNameView
     *   MiuiClock
     *   ComposeView                     ← 通知图标（Compose 画的）
     *   AlphaOptimizedFrameLayout → NotificationIconContainer → StatusBarIconView ×2
     * ```
     * 而**右半边的系统图标**（`MiuiStatusIconContainer` / `NetworkSpeedView` /
     * `MiuiStatusBatteryContainer`）**根本不在这一支里** —— 所以旧版永远 `命中ICON容器=0`，
     * 图标直控等于没生效（此前"进多任务图标会藏"是 disable 位的功劳，不是这段代码）。
     *
     * 正确起点 = 从时钟往上走，命中**第一个状态栏根 View**（本 ROM = `MiuiPhoneStatusBarView`）。
     * 只在这一棵子树里走，**绝不越过它进入 ComposeView/AndroidViewsHandler 之外的 shade 面板**，
     * 避免误伤下拉面板（首版"面板卡住"教训）。
     */
    private fun applyHide() {
        applyHideWithCount()
    }

    /**
     * @return `叶子命中数` 到 `本次新存入 saved 的数量`（取证用）
     */
    private fun applyHideWithCount(): Pair<Int, Int> {
        val clock = clockRef?.get() ?: return 0 to 0
        if (diagnosed.compareAndSet(false, true)) dumpStructure(clock)
        val start = findStatusBarRoot(clock) ?: return 0 to 0
        return runCatching {
            val targets = ArrayList<View>()
            visitedNodes = 0
            iconHits = 0
            collect(start, targets)
            if (targets.isEmpty()) return 0 to 0
            var added = 0
            synchronized(saved) {
                for (t in targets) {
                    if (t.visibility != View.INVISIBLE) {
                        saved.add(t to t.visibility)
                        t.visibility = View.INVISIBLE
                        added++
                    }
                }
            }
            targets.size to added
        }.getOrDefault(0 to 0)
    }

    /**
     * 从时钟往上找到**状态栏自身的根 View**（本 ROM = `MiuiPhoneStatusBarView`）。
     *
     * 命中条件：类名同时含 `StatusBar` 与 `View`，且**不含** shade 面板类名片段。
     * 找不到就退回首版行为（`clock.parent`），保证至少左半边仍受控。
     */
    private fun findStatusBarRoot(clock: View): ViewGroup? {
        var n: View? = clock
        var fallback: ViewGroup? = clock.parent as? ViewGroup
        var depth = 0
        while (n != null && depth < 10) {
            if (n is ViewGroup) {
                val name = n.javaClass.name
                val isBarRoot = name.contains("StatusBar") && name.contains("View") &&
                    !isExcluded(name) && !isComposeHost(name)
                if (isBarRoot) return n
                if (fallback == null) fallback = n
            }
            n = n.parent as? View
            depth++
        }
        return fallback
    }

    /** Compose 的宿主 View（`AndroidViewsHandler` 等）：往上走时**不能**把它当成状态栏根。 */
    private fun isComposeHost(className: String): Boolean =
        className.contains("ComposeView") || className.contains("AndroidComposeView") ||
            className.contains("AndroidViewsHandler") || className.contains("ViewFactoryHolder") ||
            className.contains("Recomposer") || className.contains("ViewTreeLifecycleOwner")

    /** 一次性结构取证：把状态栏里"图标到底挂在哪儿、类名叫什么"打清楚（只打一次）。 */
    private fun dumpStructure(clock: View) {
        runCatching {
            // ⚠ 必须打成**单行**：LSPosed 日志按行切分，多行消息在 `grep -A` 下会被拆散、极难对齐。
            //   （2026-10-17 实踩：改成单行后一次 grep 就能看到完整链条。）
            val chain = StringBuilder()
            var n: View? = clock
            var d = 0
            while (n != null && d < 12) {
                chain.append(d).append(')').append(n.javaClass.name)
                if (n is ViewGroup) chain.append("[G:").append(n.childCount).append(']')
                chain.append(" >> ")
                n = n.parent as? View
                d++
            }
            val root = findStatusBarRoot(clock)
            HookRuntime.log(
                "DBGV 祖先链: $chain | findStatusBarRoot=" +
                    (root?.javaClass?.name ?: "null"),
            )
            val start = root ?: (clock.parent as? ViewGroup) ?: clock
            val sb = StringBuilder()
            dumpTree(start, 0, sb, intArrayOf(0))
            HookRuntime.log("DBGV 遍历树(起点=${start.javaClass.name}): ${sb.toString().replace("\n", " | ")}")
        }
    }

    private fun dumpTree(v: View, depth: Int, sb: StringBuilder, counter: IntArray) {
        if (depth > 6 || counter[0] > 260) return
        counter[0]++
        val cls = v.javaClass.simpleName
        val marks = StringBuilder()
        if (isIconContainer(v.javaClass.name)) marks.append("«ICON»")
        if (isExcluded(v.javaClass.name)) marks.append("«EXCL»")
        sb.append("  ".repeat(depth)).append(cls)
            .append("(v").append(visName(v.visibility)).append(')')
            .append(if (marks.isNotEmpty()) " " else "").append(marks)
            .append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                dumpTree(v.getChildAt(i), depth + 1, sb, counter)
            }
        }
    }

    private fun visName(v: Int): String = when (v) {
        View.VISIBLE -> "VIS"
        View.INVISIBLE -> "INV"
        else -> "GONE"
    }

    /** 命中图标容器则收集其**所有叶子后代**；排除名单内的 View 一律不下钻。 */
    private fun collect(view: ViewGroup, out: MutableList<View>) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility == View.GONE) continue
            val name = child.javaClass.name
            visitedNodes++
            // 时钟自身及其祖先链必须放行 —— 时钟的可见性由 disable 位维护（它读 policy 字段），
            // 我们把它 INVISIBLE 会与系统逻辑打架（且恢复时可能出现闪烁）。
            if (isOnClockPath(child)) continue
            if (isExcluded(name)) continue
            if (isIconContainer(name)) {
                iconHits++
                collectLeaves(child, out)
            } else if (child is ViewGroup) {
                collect(child, out)
            }
        }
    }

    /** 该 View 是否在「时钟 → 状态栏根」的祖先链上。 */
    private fun isOnClockPath(v: View): Boolean {
        val clock = clockRef?.get() ?: return false
        var n: View? = v
        var depth = 0
        while (n != null && depth < 12) {
            if (n === clock) return true
            n = n.parent as? View
            depth++
        }
        return false
    }

    /**
     * 只收集叶子（无子 View 的）图标元素。
     *
     * **容器本身不动** —— 容器负责布局与动画（例如 `MobileSignalAnimatorContainer` 会逐个
     * 重设子 View 可见性），把它也隐藏反而可能破坏其逻辑；只让内容不可见即可。
     */
    private fun collectLeaves(view: View, out: MutableList<View>) {
        if (view is ViewGroup && view.childCount > 0) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility != View.GONE) collectLeaves(child, out)
            }
        } else {
            out.add(view)
        }
    }

    private fun isIconContainer(className: String): Boolean {
        for (key in ICON_CONTAINERS) {
            if (className.contains(key)) return true
        }
        return false
    }

    private fun isExcluded(className: String): Boolean {
        for (key in EXCLUDE_CONTAINERS) {
            if (className.contains(key)) return true
        }
        return false
    }
}