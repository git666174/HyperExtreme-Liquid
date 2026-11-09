package com.frederic22.hyperextreme

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 启动遮罩（2026-10-29，照 HyperCeiler 的 `SplashActivity` 抄）。
 *
 * ## 为什么必须有一个「空 Activity」
 *
 * Android 12+ 的系统 SplashScreen 在**应用绘制首帧时立刻关闭**，它不等 launcher 的
 * 应用打开动画（HyperOS 那套图标展开/放大，约 400~500ms）结束。结果就是：
 * 动画还没播完，主界面已经顶上来了 —— 观感上「直接就是界面」，没有过渡。
 *
 * 这里放一个**不调用 `setContentView`** 的 Activity：它的窗口内容就是主题的
 * `windowBackground`（= `@color/launch_bg`，浅色白 / 深色黑，见 `values-night`）。
 * 窗口一旦创建就会**一直存在**，直到下一个 Activity 的窗口准备好接管为止，
 * 所以在系统动画期间、以及 MainActivity 的 starting window 期间，屏幕上是连续的纯色遮罩，
 * 直到主界面真正画出第一帧才过渡过去。
 *
 * ## 与 HyperCeiler 完全同构
 *
 * 只做三件事：`super.onCreate` → `startActivity(MainActivity)` → `finish()`。
 * * 不设布局（窗口 = windowBackground）
 * * 不设转场动画（交给系统默认那套，和二级页的策略一致，见 `MaskDetailActivity` 的注释）
 * * 不做延迟 / 不做条件等待（窗口的存活期本身就是「遮罩时长」）
 *
 * ⚠️ 不要给它加 `noHistory`：那会在 `onStop` 时立刻把窗口摘掉，遮罩就断了。
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
