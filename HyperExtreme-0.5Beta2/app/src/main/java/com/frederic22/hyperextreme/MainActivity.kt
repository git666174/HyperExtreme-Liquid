package com.frederic22.hyperextreme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.ui.MaskSwapApp
import com.frederic22.hyperextreme.ui.ThemeMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先按"磁盘里记着的主题"设一次系统栏，再进 Compose —— 避免第一帧图标颜色是错的。
        applyAppSystemBars(resolveAppDark(this, MaskPrefs(this).themeMode))
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        setContent {
            MaskSwapApp(onDarkModeChange = { applyAppSystemBars(it) })
        }
        holdFirstFrameForLaunchAnimation()
    }

    /**
     * 启动遮罩的「最短展示时长」（2026-10-29，方案 B）。
     *
     * ## 为什么需要它
     *
     * 遮罩本身是 `SplashActivity` 的纯色窗口 + 本 Activity 的 starting window，两者都
     * **活到本 Activity 画出第一帧为止**。真机实测（`ActivityTaskManager: Displayed`）：
     * 我们的首帧只要 **+238ms**，而 HyperOS 的图标展开动画约 400~500ms ⇒ 动画播到一半
     * 内容就顶上来，观感「一闪而过」。HyperCeiler 之所以没这个问题，纯粹是它的首页
     * 首帧本来就要 436ms（ViewPager + 三个 Fragment），不是它做了什么等待。
     *
     * 所以这里**主动压住首帧**：到 [LAUNCH_HOLD_MS] 之前 `onPreDraw` 一直返回 false，
     * 系统就会继续显示 starting window（纯色遮罩），到点放行后再走系统自己的
     * splash→app 转场 —— 与 HyperCeiler 走的是同一条系统路径，只是把时长补齐。
     *
     * ## 两个容易踩的点
     *
     * 1. `onPreDraw` 返回 false **只丢掉当前这一帧**，ViewTreeObserver 不会自己再回调；
     *    必须 `postInvalidateOnAnimation()` 主动排下一帧，否则会永久卡住不显示。
     * 2. 时间基准是 [App.processStartAt]（进程创建），不是本 Activity 的 onCreate ——
     *    要把 SplashActivity 那 ~100ms 也算进「从点击到界面」的总时长里。
     */
    private fun holdFirstFrameForLaunchAnimation() {
        val deadline = App.processStartAt + LAUNCH_HOLD_MS
        // 已经够久（热启动 / 慢设备 / 配置变更重建）⇒ 直接放行，不要再压。
        if (deadline <= SystemClock.uptimeMillis()) return
        val decor = window.decorView
        val observer = decor.viewTreeObserver
        observer.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (SystemClock.uptimeMillis() >= deadline) {
                        runCatching { if (observer.isAlive) observer.removeOnPreDrawListener(this) }
                        return true
                    }
                    decor.postInvalidateOnAnimation()
                    return false
                }
            },
        )
    }

    private companion object {
        /** 从进程创建算起的遮罩最短时长。HyperCeiler 实测 436ms，取 400ms 略短一点。 */
        const val LAUNCH_HOLD_MS = 400L
    }
}

/**
 * 系统栏沉浸 + 图标明暗跟随【本应用】的主题。
 *
 * SystemBarStyle.auto 的第三个参数是"这个界面是不是深色"的判定函数，SDK 默认实现读的是
 * 系统配置（Resources.configuration.uiMode）：我们支持"应用强制浅色/深色"，一旦应用主题
 * 和系统主题相反，状态栏图标就会和背景同色，直接看不见。
 *
 * 所以这里把判定换成外部喂进来的值，UI 层每次主题变化都会再调一次（enableEdgeToEdge 可以
 * 重复调用，只是重新下发样式）。
 *
 * 2026-09-29 从 [MainActivity] 的私有方法提成**顶层函数**：二级页现在也是一个真的 Activity
 * （`ui.MaskDetailActivity`，为了拿到系统默认的那条转场），它要设一模一样的一套 —— 两处共用这一份。
 */
internal fun ComponentActivity.applyAppSystemBars(dark: Boolean) {
    // 两个 scrim 都用全透明：SystemBarStyle.auto 的默认值是半透明 scrim，
    // 那层会盖在状态栏/导航条后面，看起来就不沉浸。
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
    )
    // 关掉导航条的「对比度强制」：系统默认会在导航条后面再糊一层 scrim 保证图标可见，
    // 即使 bar 本身透明也能看出色差。enableEdgeToEdge 之后必须重新关一次。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

/**
 * 主动申请屏幕的最高刷新率。两个 Activity 都在 `onCreate` 里调一次。
 *
 * 背景（2026-10-22 实测定位）：HyperOS 上出现过「模块里所有动画都是 60Hz 节奏、
 * 而桌面和系统应用都是 120Hz」的现象。`dumpsys display` 显示 `mActiveModeId` 是 2（60Hz），
 * 而 `DisplayModeDirectorImpl` 的日志里写着：
 *
 * ```
 * updateRefreshRateResultIfNeeded after: baseModeId=2 ... appRequest=render: (0.0 60.0)
 * ```
 *
 * **`appRequest` 就是我们这个窗口投的票** —— 不声明时 ViewRootImpl 默认投 60，
 * MIUI 的 `SmartDisplayPolicy` 照着这个票把整块屏锁到 60Hz（secure 里
 * `miui_refresh_rate=60`，而用户设的 `user_refresh_rate=120`）。
 * 所以不是"掉帧"，是**帧率上限被自己降到 60**：长距离弹簧动画在 60Hz 下就显得拖。
 *
 * 两处都设，覆盖不同读取路径：
 * 1. `preferredDisplayModeId` / `preferredRefreshRate` —— 老式窗口属性，MIUI 系实际读这个；
 * 2. `View#setRequestedFrameRate`（Android 15+）—— 公开的逐 View 帧率投票，直接进 DisplayModeDirector。
 *    （`Window#setFrameRate` 是 @hide，不能用。）
 *
 * 取 `supportedModes` 里刷新率最高的那个，不写死 120（不同机型高刷档位不同）。
 */
@Suppress("DEPRECATION")
internal fun ComponentActivity.requestHighRefreshRate() {
    val best = display?.supportedModes?.maxByOrNull { it.refreshRate } ?: return
    window.attributes = window.attributes.apply {
        preferredDisplayModeId = best.modeId
        preferredRefreshRate = best.refreshRate
    }
    if (Build.VERSION.SDK_INT >= 36) {
        // Android 16 起新增的「帧率省电平衡」：默认开启时，系统会在省电策略下把
        // 普通应用的帧率压到 60Hz（HyperOS 的 `SmartDisplayPolicy` 就是靠它生效的）。
        // 关掉之后，帧率由 App 自己的投票决定。
        window.setFrameRatePowerSavingsBalanced(false)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // 必须等 decorView attach 完再投票：`onCreate` 时它还没挂到窗口上，
        // 直接调 `setRequestedFrameRate` 会被丢掉（实测 `DisplayModeDirector` 里
        // `appRequest` 仍然是 60，MIUI 的 SmartDisplayPolicy 就在 20 秒后把
        // `miui_refresh_rate` 写回 60）。
        val dv = window.decorView
        dv.post { dv.setRequestedFrameRate(best.refreshRate) }
    }
}

/** 把「跟随系统/浅色/深色」折算成一个布尔值。两个 Activity 共用。 */
internal fun resolveAppDark(context: Context, themeModeId: String): Boolean =
    when (ThemeMode.of(themeModeId)) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
