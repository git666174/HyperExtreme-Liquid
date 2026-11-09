package com.frederic22.hyperextreme.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 光栅壁纸「关闭」的唯一收口（2026-11-05）。
 *
 * ## 为什么抽出来
 *
 * 关闭动作现在有三个入口：
 * 1. 光栅壁纸二级页的「取消应用」；
 * 2. 「功能冲突提醒」弹窗（要先关掉光栅才能开别的）；
 * 3. **换壁纸 → 自动关闭**（aod 侧 hook 检测到壁纸判据变化，经 provider 调进来）。
 *
 * 三处必须行为完全一致：清 `raster_active_id` → 清壁纸基准 → 推系统属性 → toast。
 *
 * ## 与 [SpatialControl] 的差异
 *
 * 空间壁纸的层挂在 `miwallpaper` 的 leash 下，关掉后必须重启壁纸进程重建壁纸层；
 * 光栅的层挂在 **SystemUI** 自己的 leash 上，`RasterFlag` 置 0 后渲染器轮询到就会
 * `RasterRenderer.stop()` 自己把层拆干净 —— 不需要杀任何进程（也不该杀：SystemUI
 * 重启的代价远大于空间那条链）。
 *
 * ## 服务模式
 *
 * 这里**只清状态与属性**，不碰系统壁纸。服务模式的手动关闭要额外走
 * `WallpaperManager.clear()`（见 `RasterWallpaperPage.clearActive`），那条路仍留在 UI 层；
 * 换壁纸自动关闭发生时系统已经把壁纸换掉了，再 clear 一次反而会清掉用户刚换的壁纸。
 */
object RasterControl {

    /**
     * 关闭光栅壁纸。**已经关着就什么都不做**（不重复 toast）。
     *
     * @param toastText 提示文案。手动关闭用默认的「光栅壁纸已关闭」；
     *   换壁纸自动关闭传「检测到壁纸变化，光栅壁纸已自动关闭」；
     *   传 **null 表示静默不弹**。
     *
     * 调用方负责同步自己的界面状态（页面里的 `activeId`）。
     */
    fun disable(ctx: Context, toastText: String? = "光栅壁纸已关闭") {
        val app = ctx.applicationContext ?: ctx
        val prefs = MaskPrefs(app)
        if (prefs.rasterActiveId.isEmpty()) return
        // ⚠️ 状态写入必须同步：provider 的 `call` 跑在 binder 线程，晚写几百毫秒的话
        // aod 侧下一次 config 读到的还是 active ⇒ 重复触发关闭（与空间同款坑）。
        prefs.setRasterActiveId(null)
        // 壁纸基准随关闭一起清 —— 下次开启时重新记。
        prefs.setRasterWallpaperFp("")
        // SystemUI 侧读的是系统属性，prefs 通道在那里不可用。
        RasterFlag.push(false, null, prefs.rasterScope)
        // Toast 需要 Looper：binder 线程直接弹会抛。
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
}
