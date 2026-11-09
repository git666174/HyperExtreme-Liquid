package com.frederic22.hyperextreme.data

/**
 * **桌面层级最终状态**的跨进程传递（2026-10-21 建立，2026-10-22 改语义）。
 *
 * ## 这个属性到底代表什么
 *
 * `debug.hyperextreme.parallax_cover = 1` 的含义已经从「视差效果覆盖桌面 = 开」
 * 改成「**桌面层级最终生效** = `cover_desktop && bg_active`」。
 *
 * **为什么**：miwallpaper 进程里的 `ParallaxDesktopGuard`（抑制解锁动画：缩放 / 模糊 /
 * zoom out / `isSameImageWallpaper` 标志）只能通过这个属性读开关。改语义前它只看
 * 覆盖选项、不看总开关 —— 实测「总开关关、覆盖选项开」时，解锁过渡仍被完整抑制
 * （`PARAX 开关读取 -> true` + `d(): isSameImageWallpaper 已强制 true`）。
 *
 * ## 为什么不用 prefs / provider
 *
 * 实测（v1.3.15 诊断）：miwallpaper 进程里
 * - `getRemotePreferences` 返回 null；
 * - `openRemoteFile("shared_prefs/mask_swap.xml")` 读不到（原文 -1 字符）；
 * - 模块 provider 在该进程里也拿不到 Context。
 *
 * 系统属性是唯一「所有进程都能读、且不需要 Context / 框架通道」的载体：
 * 写入需要 root（模块本来就有），读取不需要。
 *
 * 属性用 `debug.` 前缀（不持久化）。App 每次进程启动在 [com.frederic22.hyperextreme.App]
 * 里重新同步一次，所以重启手机也不会丢。
 *
 * **写入时机**（两处缺一不可，否则会出现"总开关关了但属性还是 1"）：
 * 1. 覆盖选项变化 → `applyParallaxCoverDesktop`；
 * 2. **总开关变化** → `enableParallax` / `disableParallax`。
 *
 * 与 hook 侧 `ParallaxDesktopGuard.PROP_FLAG` 必须保持一致。
 */
object ParallaxCoverFlag {

    const val PROP = "debug.hyperextreme.parallax_cover"

    /**
     * 异步写属性；root 不可用时静默失败（开关退回默认关，不会误抑制）。
     *
     * 2026-10-21：**这已不是主通道**。SystemUI 侧改为直接读模块 prefs
     * （`HookRuntime.remoteBoolean`），不需要 root、实时生效。
     * 系统属性只给 miwallpaper 进程用（那个进程读不到 prefs），
     * 所以这里的失败不影响 SystemUI 侧功能 —— 但仍然打日志，
     * 免得下次再出现「点了开关没反应」却查不到原因。
     *
     * **注意传值**：调用方必须传 `cover && active` 的最终结果，不能只传覆盖选项。
     */
    fun push(value: Boolean) {
        Thread {
            val r = runCatching { RootShell.run("setprop $PROP ${if (value) 1 else 0}") }
                .getOrNull()
            if (r == null || !r.ok) {
                android.util.Log.w(
                    "HyperExtreme",
                    "ParallaxCoverFlag: setprop $PROP 失败（${r?.output?.take(160) ?: "异常"}）" +
                        "—— 主通道是模块 prefs，不影响功能",
                )
            }
        }.start()
    }
}
