package com.frederic22.hyperextreme.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 空间壁纸「关闭」的唯一收口（2026-10-29）。
 *
 * ## 为什么抽出来
 *
 * 关闭动作有两个入口：
 * 1. 空间壁纸二级页的「关闭替换」；
 * 2. 视差页的「功能冲突提醒」弹窗（要先把空间关掉才能开视差）。
 *
 * 两处必须**行为完全一致**：改 prefs → 推系统属性 → toast → 重启壁纸进程。
 * 以前这段写在 `SpatialWallpaperPage.toggleSpatial()` 的局部函数里，视差页够不到。
 *
 * ## 为什么要重启 miwallpaper（同视差）
 *
 * 我们的层是挂在 `ImageWallpaper` 的 leash 下的子层。关闭时 `remove` 掉 SC 之后，
 * 壁纸层（WallpaperWindowToken 及其子层）里仍可能留着历史残留的可见层 / 被改过的
 * matrix —— 只删 SurfaceControl 不够，得让壁纸服务重建壁纸层才彻底复位
 * （视差侧的同款结论见 `MaskUiState.disableParallax`）。
 *
 * 延迟 [RESTART_DELAY_MS] 再杀：先让 SystemUI 的轮询读到 `active=0` 把渲染器停掉，
 * 否则渲染器还攥着即将被销毁的 leash。
 */
object SpatialControl {

    /** 关闭后等 SystemUI 停渲染器的时间（轮询周期 ~200ms，留一倍余量）。 */
    const val RESTART_DELAY_MS = 400L

    /**
     * 关闭空间壁纸。**已经关着就什么都不做**（不重复 toast、不重复杀进程）。
     *
     * @param toastText 提示文案。手动关闭用默认的「空间壁纸已关闭」；
     *   换壁纸自动关闭传「检测到壁纸变化，空间壁纸已自动关闭」（2026-10-29）；
     *   传 **null 表示静默不弹** —— 「清除所有深度缓存」会紧接着弹「已清除 N 项深度缓存」，
     *   两条 LENGTH_LONG 叠在一起要排队播好几秒（2026-10-31 收口时加的）。
     *
     * 调用方负责同步自己的界面状态（页面里的 `activeKey`）。
     */
    fun disable(ctx: Context, toastText: String? = "空间壁纸已关闭") {
        val app = ctx.applicationContext ?: ctx
        val prefs = MaskPrefs(app)
        if (!prefs.spatialActive) return
        // ⚠️ **状态写入必须同步**（2026-10-29 实测）：
        // provider 的 `call` 跑在 binder 线程，早期版本把整个方法都 post 到主线程 ⇒
        // prefs 晚几百毫秒才变，aod 侧下一次 config 读到的还是 active=true ⇒ 重复触发关闭
        // （日志里连打三次「壁纸已变化」）。状态立刻写、只有 toast 需要切线程。
        prefs.setSpatialActive(null)
        // 壁纸基准随关闭一起清 —— 下次开启时重新记（与视差同款语义）。
        prefs.setSpatialWallpaperFp("")
        // SystemUI 侧读的是系统属性，prefs 通道在那里不可用。
        SpatialFlag.push(false, null, prefs.spatialScope, prefs.spatialLockDepth)
        restartWallpaperService()
        // Toast 需要 Looper：binder 线程直接弹会抛
        // "Can't create handler inside thread that has not called Looper.prepare()"。
        // toastText == null ⇒ 完全不弹（调用方自己会弹一条）。
        if (toastText != null) {
            if (Looper.myLooper() == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, toastText, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(app, toastText, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 后台重启 miwallpaper（强制重建壁纸层）。 */
    fun restartWallpaperService(delayMs: Long = RESTART_DELAY_MS) {
        Thread {
            runCatching {
                if (delayMs > 0L) Thread.sleep(delayMs)
                RootShell.run("killall com.miui.miwallpaper")
            }
        }.apply { isDaemon = true }.start()
    }
}
