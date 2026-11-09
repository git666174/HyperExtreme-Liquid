package com.frederic22.hyperextreme.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.frederic22.hyperextreme.data.MaskProvider
import java.lang.ref.WeakReference

/**
 * 让锁屏上的「自定义锁屏」按钮显示在**通知 / 播放类控件之上**。
 *
 * ## 问题长什么样
 *
 * 长按锁屏会浮出一个「自定义锁屏」按钮。它是 `keyguard_settings_popup_menu` 布局里的
 * `keyguard_settings_button`；锁屏的通知 / 媒体控件在 `shared_notification_container` 里
 * （`NotificationStackScrollLayoutSection` 把 `notification_stack_scroller` 搬进去的）。
 * 通知一旦"下沉"（HyperChanger 那个「去除通知下沉位置限制」就是放开了系统的下沉位置限制），
 * 就会压到按钮所在的区域把按钮盖掉。这里要做的就是反过来：**菜单出现期间把按钮抬到通知之上**。
 *
 * ## 真机结构（这是本模块唯一必须"搬到别的父容器"的功能，理由就在这张图里）
 *
 * `super_notification_shade.xml` → `NotificationShadeWindowView` 的子级顺序：
 *
 * ```
 * [7] HyperOSKeyguardRootView      id=keyguard_root_view             ← 「自定义锁屏」按钮的父级
 *         └─ … KeyguardPanelView → keyguard_foreground_layer
 *                                    → deducted_image_view（景深主体，全屏不透明）
 * [8] SharedNotificationContainer  id=shared_notification_container  ← 通知 / 播放控件
 * ```
 *
 * 按钮由 `DefaultSettingsPopupMenuSection#addViews(constraintLayout)` 加进 **`keyguard_root_view`**
 * （blueprint 宿主，`KeyguardViewConfigurator` 传的就是 `keyguard_root_view`），而**景深主体、
 * 壁纸层、时钟也都在同一棵树里**；通知容器则是 `keyguard_root_view` 的**兄弟**。
 *
 * ⚠️ **2026-10-01 真机踩过**：先前用 `translationZ` 把"按钮支"（= `keyguard_root_view`）抬到通知之上。
 * View 的绘制顺序**只能在同一个父容器内排**，所以想让按钮压过通知容器，`keyguard_root_view`
 * 就必须整个压过它 —— 景深主体跟着上去，长按菜单一出现，**通知和播放控件被那层全屏不透明的
 * 主体图盖没**（用户报的 bug）。**这不是调参能解决的，Z 值再小也一样。**
 *
 * ## 所以改成"搬按钮"，而不是"抬容器"
 *
 * 在 `NotificationShadeWindowView` 里、**通知容器的正上方**插一个全屏 `FrameLayout` 宿主，
 * 菜单出现时把按钮**搬进宿主**：
 *
 * | 事项 | 做法 |
 * | --- | --- |
 * | 宿主位置 | `indexOf(notif) + 1` —— 压在通知容器之上，又在后面的层（密码层 bouncer、指纹涟漪、状态栏…）之下，不越界 |
 * | 位置不跑偏 | 把**到窗口根为止每一层的 `left/top` 累加**得到"窗口空间里的布局位置"，再减去宿主在窗口空间的原点（容器的 padding），当 margin 用（见 [layoutOffsetInWindow]）。**不能只用 `button.left`** —— 那只是相对它自己父级的（2026-10-01 真机因此把 margin 算成 `(0,0)`） |
 * | 缩放不跑偏 | `KeyguardRootViewBinder` 会对 `keyguard_root_view` 做 `setScaleX/setScaleY`（长按有个缩小的过渡）。宿主按帧照抄**锁屏根视图**的 transform，否则按钮不跟着缩放、看着"飘" |
 * | 复原 | 菜单收起时把按钮**原样放回**（同一个 index、同一个 `LayoutParams` 实例），并移除宿主。放回时若原位已有系统重建的同 id 按钮，就丢掉我们这份、不制造重复 |
 *
 * 为什么用框架自带的 `FrameLayout` 而不是 `ConstraintLayout`：宿主用不上约束（位置靠 margin 算），
 * 而 `androidx.constraintlayout` 并不在依赖里，没必要为这个功能引一个包。
 *
 * 为什么宿主每次开菜单都新建、收起就移除：不用常驻一个空容器在系统视图树里，
 * 也避免它跟着窗口生命周期残留（见 [restoreHome]）。
 *
 * ## 认哪个 View 才是"按钮"（2026-10-01 分清楚了）
 *
 * **要搬的是"容器"，不是里面的文字。** 真机上那个胶囊是
 * `custom_lockscreen_layout.xml` 的根（`EffectivelyVisibleFlowLayout`，id = `custom_lockscreen_button`），
 * 里面只有一个 `TextView`（id = `custom_lockscreen_text`，文字「自定义锁屏」）；胶囊本身由
 * `keyguard_bottom_area.xml` 里的 `<include android:id="@+id/custom_lockscreen_button">` 挂进
 * **锁屏底部区域** `KeyguardBottomAreaView`（`KeyguardBottomAreaInjector#setView` 里按这个 id 取它，
 * 用 Folme 改 alpha/scale 显隐 —— 所以它**不是**靠 visibility 显隐的，见下）。
 *
 * 只认文字（v4 的做法）会把 `custom_lockscreen_text` 从胶囊里抠出来搬走 ⇒ 胶囊留在原地、
 * 文字被扔到宿主左上角。所以 [BUTTON_ID_NAMES] 把两个容器的 id 名都列上，**id 名优先**，
 * 文本只作兜底（且兜底时要往上取一层容器）。
 *
 * ## 怎么拿到按钮 / 什么时候搬
 *
 * 三条来源任一命中就把按钮记下来（谁先到算谁）：
 *
 * | 来源 | 说明 |
 * | --- | --- |
 * | `View#onAttachedToWindow` | 按 id 名认人（`custom_lockscreen_button` / `keyguard_settings_button`），文本兜底 |
 * | [attachKeyguardRoot] | `DefaultSettingsPopupMenuSection#addViews` 的参数就是**锁屏根视图**，顺手在里面搜一次 |
 * | `KeyguardSettingsViewBinder#bind` 的 View 参数 | 只有某些版本会带 View，兼容保留 |
 *
 * **真正搬动发生在"按钮变为可见"这一刻**（`onVisibilityChanged`）：
 *
 * * AOSP 那套（`keyguard_settings_button`）在布局里是 `setVisibility(8)`，刚 attach 时还没被
 *   `applyConstraints` 写过约束，提前搬走会让它在宿主里落到 (0,0)。
 * * MIUI 那个胶囊由 `KeyguardBottomAreaInjector` 用 **Folme 改 alpha/scale** 显隐，
 *   但**显隐两端仍会 `setVisibility`**（隐藏动画结束时 `hideCustomLockScreenButtonRunnable` 里
 *   `setVisibility(4)`；`isCustomLockscreenButtonShowing()` 判的就是 `visibility == 0`），
 *   所以 `onVisibilityChanged` 这条触发对两种实现都成立。
 * * 也正因为如此，[applyLift] 在按钮不可见时**只记日志不动手**。
 *
 * ## 复原（三重，缺一不可）
 *
 * | 信号 | 说明 |
 * | --- | --- |
 * | `View#onVisibilityChanged` 里按钮变**不可见** | **最可靠** —— 菜单收起时按钮必然隐藏 |
 * | `View#onDetachedFromWindow` | 有些实现是 `removeView` 而不是改 visibility，只会走这里 |
 * | `KeyguardTouchHandlingInteractor#hideMenu` | 真机上可能一次都不触发，只作兜底，另配 8 秒定时还原 |
 *
 * 都是**延迟 `EXIT_ANIMATION_MS` 再复原**：等菜单淡出动画走完再搬回去，观感上不闪。
 *
 * ## 诊断：为什么 Context 要从 View 上取
 *
 * 这一步跑在 SystemUI 进程里，模块 app 看不到过程，所以关键结论要通过 provider 写进
 * `diagNote`（设置页「最近操作」）。而**写它需要 Context** —— 千万**别用**
 * `HookRuntime.appContext()`：那个走的是反射 hidden API（`ActivityThread#currentApplication`），
 * 在 SystemUI 进程里拿不到就整条链静默死掉，真机表现就是"作用域勾了、重启多轮、
 * 连一条诊断都写不出来"（2026-09-28 踩过）。
 * hook 的参数里就有 View，`view.context` 一定拿得到，所以诊断在 [attach] / [attachKeyguardRoot] 里写。
 */
internal object LockScreenButton {

    /**
     * **按钮容器的 id 名** —— 按名字认人才是主路，两条都要，顺序就是优先级：
     *
     * | id 名 | 是谁 | 出处 |
     * | --- | --- | --- |
     * | `custom_lockscreen_button` | **MIUI / HyperOS 真正的那一个**：`custom_lockscreen_layout.xml` 的根（`EffectivelyVisibleFlowLayout`），由 `keyguard_bottom_area.xml` 里的一个 `<include android:id="@+id/custom_lockscreen_button">` 挂进**锁屏底部区域** | `KeyguardBottomAreaInjector#setView` 里的 `findViewById(R.id.custom_lockscreen_button)` |
     * | `keyguard_settings_button` | AOSP 那套 `keyguard_settings_popup_menu` 的根（`LaunchableLinearLayout`） | `DefaultSettingsPopupMenuSection#addViews` |
     *
     * ⚠️ **2026-10-01 真机踩过**：只认 `keyguard_settings_button` + 文本时，认到的是**胶囊里面那个
     * TextView**（`custom_lockscreen_text`）—— 于是我们把它从胶囊里"抠"出来搬走，胶囊留在原地、
     * 文字跑到左上角（margin 全是 0）。**必须认容器，不能认文字。**
     */
    private val BUTTON_ID_NAMES = arrayOf("custom_lockscreen_button", "keyguard_settings_button")

    /** 锁屏根视图的 id 名（`keyguard_root_view`）—— 按钮就挂在它里面。 */
    private const val ROOT_ID_NAME = "keyguard_root_view"

    /**
     * 通知容器的 id 名（`shared_notification_container`）。
     *
     * 宿主就插在它**后面一位**（绘制顺序上就是压在它上面）。资源名在真机 `resources.arsc` 里核实过。
     */
    private const val NOTIFICATION_CONTAINER_ID = "shared_notification_container"

    /** 兜底复原时间。系统自己 5 秒自动收起菜单（`scheduleAutomaticMenuHiding` 的 5000ms），这里留余量。 */
    private const val FALLBACK_RESTORE_MS = 8_000L

    /** 菜单收起的淡出动画走完再复原，否则淡出中的按钮会被通知重新盖住（观感上闪一下）。 */
    private const val EXIT_ANIMATION_MS = 420L

    /** 开关值的缓存时长 —— 长按是低频操作，但同一轮里 provider 只该被问一次。 */
    private const val SWITCH_CACHE_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())

    private var button = WeakReference<View>(null)

    /** 锁屏根视图（`keyguard_root_view`），拿不到按钮时靠它重新搜。 */
    private var keyguardRoot = WeakReference<View>(null)

    /** 写诊断用的 Context —— **从 hook 到的 View 上取**，见类文档最后一段。 */
    private var hostContext = WeakReference<Context>(null)

    /** 四处 hook 的安装结果，由 `HookEntry` 写进来，随诊断一起回写。 */
    private var installSummary = "尚未安装"

    // ------------------------------------------------------------------ 搬迁用的状态

    /** 全屏宿主：插在通知容器之上，按钮临时住在里面。 */
    private var host = WeakReference<ViewGroup>(null)

    /** 按钮的"家"（`keyguard_root_view`）与它原来的 index / `LayoutParams` —— 复原时原样放回。 */
    private var homeParent = WeakReference<ViewGroup>(null)
    private var homeParams: ViewGroup.LayoutParams? = null
    private var homeIndex = -1

    /** 按钮此刻是不是住在宿主里。 */
    private var relocated = false

    /**
     * 正在由**我们自己**搬动按钮（`removeView` / `addView`）。
     *
     * 那两下会同步触发按钮的 `onDetachedFromWindow` / `onAttachedToWindow`，而两条都被我们 hook 着 ——
     * 不挡住的话：detach 会当成"菜单收起"去排复原，attach 又会当成"按钮出现"去再搬一次，来回打架。
     */
    private var moving = false

    /** 根视图的 transform 监听（跟着它缩放/平移/透明），只在按钮住进宿主期间挂着。 */
    private var syncObserver: ViewTreeObserver? = null
    private var syncListener: ViewTreeObserver.OnPreDrawListener? = null

    private val restoreTask = Runnable { restoreNow() }

    private var cachedSwitch = false
    private var cachedSwitchAt = 0L

    /**
     * 「详细过程写入LSPosed日志」。
     *
     * 自己缓存一份而不复用 `HookRuntime.vlog`：那个用的是 aod 侧的配置快照，而本进程（SystemUI）
     * 从不调用 `HookRuntime.config()`，那个字段永远是 null —— 直接用它这里的日志根本不会输出。
     * 反正开关值和 verbose 在同一个 config bundle 里，一次读回两份。
     */
    private var cachedVerbose = false

    /** 上一次回写过的诊断文本（内容没变就不重复写，免得覆盖掉别的诊断）。 */
    private var lastReport = ""

    /** `HookEntry` 装完 hook 后把结果记进来。 */
    fun recordInstall(summary: String) {
        installSummary = summary
        HookRuntime.log("锁屏按钮置顶：安装结果已记录（$summary）")
    }

    /**
     * 已经拿到按钮了吗 —— 给 `View#onAttachedToWindow` 那条**高频** hook 当快速路径用。
     *
     * SystemUI 里每个 View 进窗口都会走一次那个 hook，所以除了"还没拿到按钮"的短暂窗口期，
     * 都应当在这里立刻返回（一次弱引用读取 + 一次 isAttachedToWindow，代价可忽略）。
     */
    fun ready(): Boolean = button.get()?.isAttachedToWindow == true

    /** 按钮引用拿到了吗 —— 给 [onVisibilityChanged] 那条高频 hook 当"值不值得取参数"的前置判断。 */
    fun hasButton(): Boolean = button.get() != null

    /**
     * **按 id 名认人** —— 不依赖任何"一次性回调"。这是现在的主路。
     *
     * 为什么不能用 `addViews` / `bind`：它们都是**窗口构建时只调用一次**的方法，
     * 真机上那一刻 hook 还没装上（LSPosed 的 `onPackageReady` 晚于窗口 inflate）。
     * 按 id 名认人则与"谁创建、什么时候创建"无关，只要它进了窗口就能认出来。
     */
    fun attachIfTarget(view: View?) {
        val target = view ?: return
        val customize = customizeButtonOrNull(target)
        if (customize != null) {
            attach(customize, "入窗")
            return
        }
        if (idNameOf(target) == ROOT_ID_NAME) attachKeyguardRoot(target)
    }

    /**
     * 这个 View 是不是「自定义锁屏」按钮的一部分 —— 是就返回**真正要搬的那个 View**，否则 null。
     *
     * 两把钥匙（第二把照抄 HyperChanger 的 `findCustomizationButton`）：
     *
     * 1. **id 名**（主路）：见 [BUTTON_ID_NAMES]。`custom_lockscreen_button`（MIUI）/
     *    `keyguard_settings_button`（AOSP）**都是"外层容器"**，这才是要搬的东西。
     * 2. **文本兜底**（布局改名 / 其它 ROM）：`text` / `contentDescription` 含「自定义锁屏」或 "custom lock"。
     *    ⚠️ **文本命中时拿到的是里面那个 TextView，要往上取一层容器** ——
     *    2026-10-01 就是漏了这一步：把 `custom_lockscreen_text` 从胶囊里抠出来搬走，
     *    结果胶囊留在原地、文字被扔到宿主左上角（margin 全 0）。
     */
    private fun customizeButtonOrNull(view: View): View? {
        if (BUTTON_ID_NAMES.any { it == idNameOf(view) }) return view
        if (view !is TextView) return null
        val text = view.text?.toString().orEmpty()
        val description = view.contentDescription?.toString().orEmpty()
        val haystack = "$text $description".lowercase()
        val matched = haystack.contains("自定义锁屏") ||
            haystack.contains("custom lock") ||
            (haystack.contains("custom") && haystack.contains("lock"))
        if (!matched) return null
        // 往上取一层：容器才是"按钮"。宿主是我们自己插的，别把它当成按钮。
        val parent = view.parent as? ViewGroup
        return if (parent != null && parent !== host.get()) parent else view
    }

    /** 深度优先找那个按钮 —— 返回的是**要搬的容器**（见 [customizeButtonOrNull]）。 */
    private fun searchCustomizeButton(view: View): View? {
        customizeButtonOrNull(view)?.let { return it }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                searchCustomizeButton(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /**
     * 拿到按钮本体。
     *
     * 为什么不等长按回调（2026-09-29 真机日志实测）：`onLongPress` 的 hook 明明 `已挂`，
     * 但真机上**一次都没被回调**（Compose lambda 里那个调用点被 ART 内联、没被 deopt）。
     * 而**按钮出现本身**就等价于"菜单可见"（它的可见性由 `isMenuVisible` 驱动），
     * 所以直接把它当触发信号，不依赖任何回调时机。
     *
     * @param source 谁送来的，只用于日志与诊断定位。
     */
    fun attach(view: View?, source: String) {
        if (view == null) return
        if (moving) return
        hostContext = WeakReference(view.context)
        button = WeakReference(view)
        // 顺手记下根视图：它是后面兜底搜索的起点（`rootView` 本身就是 View，不用再转）。
        val root = view.rootView
        if (root !== view) keyguardRoot = WeakReference(root)
        HookRuntime.log(
            "锁屏按钮置顶：已拿到「自定义锁屏」按钮（${view.javaClass.simpleName}，" +
                "来源=$source，visibility=${view.visibility}，attached=${view.isAttachedToWindow}）",
        )
        report("已拿到按钮（$source）")
        // 延迟一帧：等这一轮 addView / 布局走完。
        main.post { applyLift("按钮出现（$source）") }
    }

    /** `DefaultSettingsPopupMenuSection#addViews` 的参数就是锁屏根视图，进来按 id 名搜一次按钮。 */
    fun attachKeyguardRoot(root: View?) {
        if (root == null) return
        hostContext = WeakReference(root.context)
        keyguardRoot = WeakReference(root)
        val found = searchCustomizeButton(root)
        if (found != null) {
            HookRuntime.log("锁屏按钮置顶：锁屏根视图（${root.javaClass.simpleName}）里搜到了按钮")
            attach(found, "锁屏根视图内搜索")
            return
        }
        // ⚠️ 这里搜不到是**正常**的：根视图刚 attach 时 blueprint 那一趟还没跑
        // （`KeyguardBlueprintViewBinder` 也是 `repeatWhenAttached`，晚于 attach），
        // 按钮还没被 addViews 加进来。真正的入口是 `View#onAttachedToWindow` 按 id 名认人。
        HookRuntime.log("锁屏按钮置顶：已拿到锁屏根视图（${root.javaClass.simpleName}），此刻按钮还没建出来")
        report("已拿到锁屏根视图")
    }

    /** 长按菜单出现。 */
    fun onMenuShown() {
        // 这条是"长按 hook 真的被回调了"的铁证。若它始终不出现、而按钮却出现了，
        // 就说明这个 hook 没被触发（见 [attach] 的注释），功能靠 [attach] 那条路兜住。
        HookRuntime.log("锁屏按钮置顶：onLongPress hook 命中（菜单出现）")
        report("已收到长按")
        main.removeCallbacks(restoreTask)
        main.post {
            ensureButtonFound()
            applyLift("长按菜单出现")
        }
        main.postDelayed(restoreTask, FALLBACK_RESTORE_MS)
    }

    /** 菜单收起（点按钮 / 滑走 / 5 秒超时都汇到这里）。 */
    fun onMenuHidden() {
        main.removeCallbacks(restoreTask)
        main.postDelayed(restoreTask, EXIT_ANIMATION_MS)
    }

    /**
     * `View#onVisibilityChanged` —— 按钮显隐两个方向都要处理。
     *
     * * **变可见**：**这才是真正搬动的时机** —— 菜单弹出来了，blueprint 早跑完、`LayoutParams`
     *   上带着约束，`layout()` 也走过了。
     * * **变不可见**：**复原**。这是最可靠的一条复原信号 —— 真机上 `hideMenu` 那个 hook
     *   和 `onLongPress` 一样可能一次都不触发。
     */
    fun onVisibilityChanged(changed: View?, visibility: Int) {
        if (moving) return
        val target = button.get() ?: return
        if (changed !== target) return
        if (visibility == View.VISIBLE) {
            main.post { applyLift("按钮变为可见") }
        } else {
            main.postDelayed(restoreTask, EXIT_ANIMATION_MS)
        }
    }

    /**
     * 按钮被移出窗口 → 复原。第三重保险（有些实现是 `removeView` 而不是 `setVisibility`，
     * 那样只会走这里、不走上一条）。
     */
    fun onButtonDetached(view: View?) {
        if (moving) return
        val target = button.get() ?: return
        if (view !== target) return
        main.postDelayed(restoreTask, EXIT_ANIMATION_MS)
    }

    // ------------------------------------------------------------------ 实现

    /**
     * 执行抬高。**任何触发都汇到这里**（按钮出现 / 长按 / 按钮变可见）。
     *
     * @param reason 触发原因，只用于日志与诊断定位 —— 出问题时一眼能看出是哪条路跑到的。
     */
    private fun applyLift(reason: String) {
        // 入口留痕：这条日志能把"到底有没有被调用"和"卡在它内部哪一步"分开。
        HookRuntime.log(
            "锁屏按钮置顶：开始抬高（原因=$reason，按钮=${button.get()?.javaClass?.simpleName ?: "无"}，" +
                "锁屏根视图=${keyguardRoot.get()?.javaClass?.simpleName ?: "无"}）",
        )
        val start = resolveButton()
        if (start == null) {
            val hint = if (keyguardRoot.get() == null) {
                "（按钮与锁屏根视图都没拿到）"
            } else {
                "（锁屏根视图里有，但按 id 名 / 文本没搜到）"
            }
            HookRuntime.log("锁屏按钮置顶：没找到「自定义锁屏」按钮 $hint")
            note("锁屏按钮置顶：未找到按钮$hint")
            return
        }
        if (!switchEnabled(start.context)) {
            // 开关默认是关的 —— 这里必须**写到诊断里**，不然用户只会看到"没反应"，
            // 完全想不到是自己没打开开关。
            HookRuntime.log("锁屏按钮置顶：功能开关未开启，不处理")
            note("锁屏按钮置顶：已收到长按，但功能开关没打开（设置 → 系统界面 → 锁屏修改）")
            return
        }
        // 只有"按钮真的显示出来"（= 菜单已弹出）才搬 —— 见类文档「什么时候搬」：
        //   * 按钮在布局里是 `setVisibility(8)`，刚 attach 时还没被 `applyConstraints`
        //     写过约束，那时搬走会让它在宿主里落到 (0,0)；
        //   * 而菜单弹出时那一趟早就跑完，参数是齐的，`layout()` 也走过了（拿得到 left/top/宽高）。
        if (start.visibility != View.VISIBLE) {
            HookRuntime.log("锁屏按钮置顶：按钮还没显示（菜单未弹出），这一步不搬")
            return
        }
        if (!start.isLaidOut) {
            // 刚由 GONE 转 VISIBLE：这一刻还没走过 layout，量不到位置。差一帧再搬，肉眼看不出来。
            HookRuntime.log("锁屏按钮置顶：按钮尚未布局，下一帧再搬")
            main.post { runCatching { relocateAboveNotifications(start, reason) }.onFailure { onLiftFailed(it) } }
            return
        }
        runCatching { relocateAboveNotifications(start, reason) }.onFailure { onLiftFailed(it) }
    }

    /** 抬高过程整体兜异常：这一步以前是裸的，万一某个 View 的父级状态异常，会直接崩到 SystemUI 的主线程。 */
    private fun onLiftFailed(error: Throwable) {
        HookRuntime.log("锁屏按钮置顶：抬高过程出错 $error")
        note("锁屏按钮置顶：抬高过程出错（$error）")
    }

    /**
     * **把按钮搬进"压在通知容器之上"的宿主** —— 这是本功能的核心动作。
     *
     * 为什么不是抬 `keyguard_root_view` 的 Z：见类文档那张结构图 —— 按钮和**景深主体 / 壁纸层 /
     * 时钟**都在 `keyguard_root_view` 里，而通知容器只是它的兄弟。绘制顺序只能在同一个父容器里排，
     * 所以"让按钮压过通知"必然把整屏锁屏内容一起抬上去，通知就被景深主体那张全屏不透明的图盖没了
     * （2026-10-01 用户报的 bug）。**唯一的出路是把按钮换个父容器。**
     *
     * 宿主插在 `NotificationShadeWindowView` 里、**通知容器的后一位**：
     * 绘制顺序上压在通知之上，同时又在后面的层（密码层 bouncer、指纹涟漪…）之下。
     */
    private fun relocateAboveNotifications(target: View, reason: String) {
        if (relocated && host.get()?.parent != null) return

        val home = target.parent as? ViewGroup
        if (home == null) {
            HookRuntime.log("锁屏按钮置顶：按钮没有父容器，放弃")
            return
        }
        val window = target.rootView
        val notif = searchByIdName(window, NOTIFICATION_CONTAINER_ID)
        if (notif == null) {
            HookRuntime.log("锁屏按钮置顶：窗口里没有通知容器，放弃（不知道该压在哪一层之上）")
            note("锁屏按钮置顶：没找到通知容器，未处理")
            return
        }
        val container = notif.parent as? ViewGroup
        if (container == null) {
            HookRuntime.log("锁屏按钮置顶：通知容器没有父容器，放弃")
            return
        }

        // 位置：**首选"照抄它自己的 `LayoutParams`（gravity + 四条 margin）"**。
        //
        // 实测真机：胶囊的家是 `KeyguardBottomAreaView`，而它由 `KeyguardBottomAreaSection` 以
        // "四边贴住父级"（`connect(id, TOP/BOTTOM/START/END, 0, …)`）加到 `keyguard_root_view` 上
        // ⇒ **整块底部区域是全屏的**，与我们的宿主同一个原点、同样没有 padding。
        // 所以同一套 gravity + margin 在宿主里算出来就是同一个位置。
        //
        // 这一条还有个**关键好处**：系统自己会周期性用
        // `KeyguardBottomAreaInjector#updateCustomLockscreenButtonLayoutParams`
        // 改写这个 `LayoutParams`（`setMarginStart/End`、`bottomMargin`）。
        // 我们照抄的是同一套语义，它改的时候宿主里也照样算对；
        // 反过来，要是我们自己算绝对 margin（`leftMargin = 窗口空间坐标`），会被它当场覆盖、按钮跳走。
        //
        // **兜底：按"窗口空间里的布局位置"换算** —— AOSP 那个弹层用的是
        // `ConstraintLayout.LayoutParams`（约束相对父级），搬进 FrameLayout 后约束失效，只能算绝对位置。
        // 累加的是布局值、**不含任何 transform**（见 [layoutOffsetInWindow]），transform 交给宿主照抄。
        val original = target.layoutParams
        val hostParams = FrameLayout.LayoutParams(original.width, original.height)
        val copied = original is FrameLayout.LayoutParams
        if (original is FrameLayout.LayoutParams) {
            hostParams.gravity = original.gravity
            hostParams.leftMargin = original.leftMargin
            hostParams.topMargin = original.topMargin
            hostParams.rightMargin = original.rightMargin
            hostParams.bottomMargin = original.bottomMargin
        } else {
            val targetPos = layoutOffsetInWindow(target)
            hostParams.leftMargin = targetPos[0] - container.paddingLeft
            hostParams.topMargin = targetPos[1] - container.paddingTop
        }

        // 记下原位，复原时原样放回（连 `LayoutParams` 实例一起留着）。
        homeParent = WeakReference(home)
        homeParams = original
        homeIndex = home.indexOfChild(target)
        HookRuntime.log(
            "锁屏按钮置顶：按钮的家=${home.javaClass.simpleName}(id=${idNameOf(home)}) " +
                "index=$homeIndex/${home.childCount}；尺寸=${target.width}x${target.height}；" +
                (if (copied) {
                    "定位=照抄 LayoutParams（gravity=${hostParams.gravity}，" +
                        "margin=${hostParams.leftMargin},${hostParams.topMargin}," +
                        "${hostParams.rightMargin},${hostParams.bottomMargin}）"
                } else {
                    "定位=窗口空间坐标（margin=${hostParams.leftMargin},${hostParams.topMargin}）"
                }),
        )

        val newHost = FrameLayout(target.context).apply {
            clipChildren = false
            clipToPadding = false
            // 纯容器：别让它进无障碍焦点序列。
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        moving = true
        try {
            home.removeView(target)
            container.addView(
                newHost,
                (container.indexOfChild(notif) + 1).coerceAtMost(container.childCount),
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            newHost.addView(target, hostParams)
        } finally {
            moving = false
        }
        host = WeakReference(newHost)
        relocated = true

        // 锁屏根视图被 `KeyguardRootViewBinder` 缩放（长按有个缩小的过渡）时，宿主跟上它的 transform，
        // 否则按钮不跟着缩放、看着"飘"在原位（见 [startTransformSync]）。
        //
        // ⚠️ transform 的来源是**锁屏根视图**（`keyguard_root_view`），不是按钮的父级 ——
        // 按钮在 `KeyguardBottomAreaView` 这类小容器里，那个容器自己并不被缩放。
        startTransformSync((searchByIdName(window, ROOT_ID_NAME) as? ViewGroup) ?: home)

        dumpStructure(target, newHost, notif)
        HookRuntime.log("锁屏按钮置顶：已把按钮搬到通知之上（原因=$reason）")
        note("锁屏按钮置顶：已搬到通知之上（$reason）")
    }

    /** 把"按钮原来在哪、搬到哪、动了几层"摊成日志 —— 一次问清楚，省得再来回猜。 */
    private fun dumpStructure(button: View, host: ViewGroup, notif: View) {
        val rootView = button.rootView
        val container = host.parent as? ViewGroup
        val params = button.layoutParams as? FrameLayout.LayoutParams
        HookRuntime.log(
            "锁屏按钮置顶：按钮=${button.javaClass.simpleName}(id=${idNameOf(button)}) " +
                "窗口根=${rootView.javaClass.simpleName} " +
                "通知容器=${notif.javaClass.simpleName}(id=${idNameOf(notif)}) " +
                "尺寸=${button.width}x${button.height}",
        )
        HookRuntime.log(
            "锁屏按钮置顶：宿主=${host.javaClass.simpleName} 在 ${container?.javaClass?.simpleName} 里 " +
                "index=${container?.indexOfChild(host) ?: -1}/${container?.childCount ?: -1}；" +
                "按钮 gravity=${params?.gravity ?: -1} " +
                "margin=(${params?.leftMargin},${params?.topMargin},${params?.rightMargin},${params?.bottomMargin})（宿主内）",
        )
        // 逐层摊开（最多 16 层），每层给出类名 / id 名 / z / 兄弟数；宿主那一层用 ← 标出。
        var node: View? = button
        var depth = 0
        while (node != null && depth < 16) {
            val parent = node.parent as? ViewGroup
            HookRuntime.log(
                "  层[$depth] ${node.javaClass.simpleName} id=${idNameOf(node)} " +
                    "z=${node.z} 兄弟=${parent?.childCount ?: -1}${if (node === host) "  ← 宿主" else ""}",
            )
            node = parent
            depth++
        }
        if (node != null) HookRuntime.log("  …（更深层已省略）")
    }

    // ------------------------------------------------------------------ 复原

    private fun restoreNow() {
        main.removeCallbacks(restoreTask)
        restoreHome()
    }

    /**
     * 把按钮**原样放回原位**（同一个 index、同一个 `LayoutParams` 实例），并移除宿主。
     *
     * 复原必须可靠：不复原的话按钮会一直住在宿主的固定位置上，不跟着锁屏根视图缩放/位移
     * （长按之外的场景也会错位）。
     *
     * 一个边界：如果在按钮"出门"期间系统重建过 blueprint（`removeViews` + `addViews`），
     * 原位会有**另一个**同 id 的按钮。这时我们这份就是多余的 —— 丢掉它、别制造两个「自定义锁屏」。
     */
    private fun restoreHome() {
        if (!relocated) return
        relocated = false
        stopTransformSync()

        val view = button.get()
        val home = homeParent.get()
        val existing = if (view != null && home != null && view.id != View.NO_ID) {
            home.findViewById<View>(view.id)
        } else {
            null
        }

        moving = true
        try {
            if (view != null && home != null && (existing == null || existing === view)) {
                // 宿主期间系统可能改过 gravity / margin（`updateCustomLockscreenButtonLayoutParams`
                // 会周期性改写它）—— 搬回去之前把这些值抄回**原参数**，别把旧值带回家。
                val hosted = view.layoutParams
                val original = homeParams
                if (hosted is FrameLayout.LayoutParams && original is FrameLayout.LayoutParams) {
                    original.gravity = hosted.gravity
                    original.leftMargin = hosted.leftMargin
                    original.topMargin = hosted.topMargin
                    original.rightMargin = hosted.rightMargin
                    original.bottomMargin = hosted.bottomMargin
                }
                (view.parent as? ViewGroup)?.removeView(view)
                home.addView(view, homeIndex.coerceIn(0, home.childCount), homeParams)
            } else if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                button = WeakReference(null)
                HookRuntime.log("锁屏按钮置顶：原位已有系统重建的同 id 按钮，丢弃我们这份副本")
            }
        } finally {
            moving = false
        }

        host.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        host = WeakReference(null)
        homeParent = WeakReference(null)
        homeParams = null
        homeIndex = -1
        vlog("锁屏按钮置顶：已复原（按钮搬回原位）")
    }

    // ------------------------------------------------------------------ transform 跟随

    /**
     * 按帧把**锁屏根视图**的 transform 照抄到宿主上。
     *
     * 为什么需要：`KeyguardRootViewBinder` 会对 `keyguard_root_view` 做
     * `setScaleX/setScaleY`（以及 alpha）—— 长按进编辑预览时整个锁屏会缩一下。按钮本来住在
     * 那棵树里，是"跟着缩"的；搬进宿主之后就脱离那个 transform 了，不补上就会看着不动、位置偏。
     *
     * 两者同为全屏、同为 `NotificationShadeWindowView` 的子级，pivot 默认都是自己的中心，
     * 所以照抄 scale / translation / alpha / pivot 就能得到完全一样的视觉效果。
     */
    private fun startTransformSync(home: ViewGroup) {
        stopTransformSync()
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = host.get() ?: return true
                current.scaleX = home.scaleX
                current.scaleY = home.scaleY
                current.pivotX = home.pivotX
                current.pivotY = home.pivotY
                current.translationX = home.translationX
                current.translationY = home.translationY
                current.alpha = home.alpha
                return true
            }
        }
        val observer = home.viewTreeObserver
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(listener)
        syncObserver = observer
        syncListener = listener
    }

    private fun stopTransformSync() {
        val listener = syncListener ?: return
        runCatching { syncObserver?.removeOnPreDrawListener(listener) }
        syncObserver = null
        syncListener = null
    }

    // ------------------------------------------------------------------ 拿视图

    /**
     * 拿按钮：先用 hook 记下的引用，失效了就从锁屏根视图里按 id 名重新搜。
     *
     * 为什么要兜底：`bind` 那次拿到的 View 是弱引用，锁屏视图重建后就没了；
     * 而 `keyguard_root_view` 的生命周期与窗口一致，可以长期持有。
     */
    private fun resolveButton(): View? {
        button.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        val root = keyguardRoot.get() ?: return null
        // 1) 先在这棵锁屏根视图里找（按钮确实是它的直接子级）。
        searchCustomizeButton(root)?.let {
            button = WeakReference(it)
            return it
        }
        // 2) 放大到**整个窗口**再搜一遍 —— `rootView` 就是窗口根，这条不依赖任何 @hide API。
        searchCustomizeButton(root.rootView)?.let {
            HookRuntime.log("锁屏按钮置顶：按钮不在锁屏根视图里，在窗口的其它分支找到了（${it.javaClass.simpleName}）")
            button = WeakReference(it)
            return it
        }
        return null
    }

    /**
     * 长按那一刻的最后一道兜底：前面所有入口都没赶上时，直接去**窗口管理器**里翻。
     *
     * 用户在长按，说明窗口一定已经建好了 —— 所以哪怕 hook 装得比窗口还晚
     * （`View#onAttachedToWindow` 也错过了），这里照样能把按钮捞回来。
     * `WindowManagerGlobal` 是 @hide 类、`mViews` 是 @hide 字段，反射可能被 hidden API 挡掉，
     * 所以整段 `runCatching`，失败就当没有。
     */
    private fun ensureButtonFound() {
        if (resolveButton() != null) return
        HookRuntime.log("锁屏按钮置顶：视图入窗没赶上，改用窗口管理器兜底搜寻")
        val roots = runCatching {
            val clazz = Class.forName("android.view.WindowManagerGlobal")
            val instance = clazz.getMethod("getInstance").invoke(null)
            val field = clazz.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(instance) as? ArrayList<View>).orEmpty()
        }.getOrElse {
            HookRuntime.log("锁屏按钮置顶：拿不到窗口列表（$it）")
            return
        }
        HookRuntime.log("锁屏按钮置顶：窗口管理器里有 ${roots.size} 个根视图")
        var lockscreenTexts = 0
        roots.forEach { root ->
            if (keyguardRoot.get() == null) {
                searchByIdName(root, ROOT_ID_NAME)?.let { keyguardRoot = WeakReference(it) }
            }
            lockscreenTexts += countLockscreenTexts(root)
            searchCustomizeButton(root)?.let {
                button = WeakReference(it)
                HookRuntime.log("锁屏按钮置顶：兜底搜寻找到了按钮（${it.javaClass.simpleName}）")
                note("锁屏按钮置顶：已找到「自定义锁屏」按钮（${it.javaClass.simpleName}）")
                return
            }
        }
        // 还没找到：把现场情况写出来，下次一眼知道是"按钮还没建"还是"压根不在这棵树上"。
        HookRuntime.log("锁屏按钮置顶：兜底也没找到按钮（窗口 ${roots.size} 个，含'锁屏'字样的文本节点 $lockscreenTexts 个）")
        note("锁屏按钮置顶：长按了但没找到按钮（窗口 ${roots.size} 个，含'锁屏'字样的文本 $lockscreenTexts 个）")
    }

    /** 纯诊断用：数一数这棵树里有多少个含「锁屏」字样的文本节点。 */
    private fun countLockscreenTexts(view: View): Int {
        var count = if (view is TextView && view.text?.toString()?.contains("锁屏") == true) 1 else 0
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                count += countLockscreenTexts(view.getChildAt(index))
            }
        }
        return count
    }

    private fun searchByIdName(view: View, name: String): View? {
        if (idNameOf(view) == name) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                searchByIdName(view.getChildAt(index), name)?.let { return it }
            }
        }
        return null
    }

    /**
     * 一个 View 的**布局位置**（不含任何 transform），相对**窗口根**的左上角。
     *
     * 累加每一层的 `left/top` 就够了：`View#getLeft()` 本来就是相对自己父级左上角的，
     * 而每一级的坐标系原点都重合在父级的左上角，所以一路加到窗口根就是窗口空间坐标。
     *
     * **为什么不用 `getLocationOnScreen()`**：那个会把祖先链上的 scale/translation 也算进去
     * （是"视觉位置"）。而我们的宿主会**照抄**锁屏根视图的 transform，所以这里必须拿"没有 transform 的
     * 布局位置"，否则缩放会被叠加两次。
     */
    private fun layoutOffsetInWindow(view: View): IntArray {
        var x = 0
        var y = 0
        var node: View? = view
        while (node != null) {
            x += node.left
            y += node.top
            node = node.parent as? View
        }
        return intArrayOf(x, y)
    }

    private fun idNameOf(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    /**
     * 开关值来自模块 app（跨进程）—— SystemUI 读不到我们的 SharedPreferences，只能走 provider。
     * **读不到按关处理**：开关默认是关的，读不到就什么都不做，行为与"没装模块"一致。
     */
    private fun switchEnabled(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedSwitchAt < SWITCH_CACHE_MS) return cachedSwitch
        cachedSwitchAt = now
        val bundle = runCatching {
            context.contentResolver
                .call(MaskProvider.configUri(), MaskProvider.METHOD_CONFIG, null, null)
        }.getOrNull()
        cachedVerbose = bundle?.getBoolean(MaskProvider.KEY_VERBOSE, false) ?: false
        cachedSwitch = bundle?.getBoolean(MaskProvider.KEY_LOCKSCREEN_BUTTON_TOP, false) ?: false
        return cachedSwitch
    }

    /** 把关键结论回写到模块 app 的 `diagNote`（设置页「最近操作」能看到）。 */
    private fun report(stage: String) {
        val text = "锁屏按钮置顶：$stage；hook 安装：$installSummary"
        if (text == lastReport) return
        lastReport = text
        note(text)
    }

    private fun note(text: String) {
        val context = hostContext.get() ?: HookRuntime.appContext()
        if (context == null) {
            HookRuntime.log("锁屏按钮置顶：没有 Context，诊断写不出去（$text）")
            return
        }
        runCatching {
            context.contentResolver.call(MaskProvider.configUri(), MaskProvider.METHOD_NOTE, text, null)
        }.onFailure { HookRuntime.log("锁屏按钮置顶：诊断回写失败（$it）") }
    }

    private fun vlog(message: String) {
        if (cachedVerbose) HookRuntime.log(message)
    }
}
