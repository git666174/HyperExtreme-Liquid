package com.frederic22.hyperextreme.wallpaper

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.Log

/**
 * 「系统壁纸服务」模式的开关与状态查询（2026-10-30，v1.5.82）。
 *
 * ## 只走系统提供的路径
 *
 * 用户 2026-10-30 明确定的两条约束，本类严格照办：
 * * **设置**：`ACTION_CHANGE_LIVE_WALLPAPER` —— 拉起系统的动态壁纸预览页，由用户选
 *   "桌面 / 锁屏 / 同时"。我们无权也不能替用户选（`setWallpaperComponent` 是 @SystemApi）。
 * * **关闭**：`WallpaperManager.clear(FLAG_SYSTEM or FLAG_LOCK)` —— 公开 API、`SET_WALLPAPER`
 *   普通权限。结果是**恢复到系统默认壁纸**（不是用户原来那张 —— 第三方读不到原图，
 *   `getWallpaperFile` 需要 signature 级的 `READ_WALLPAPER_INTERNAL`）。
 *
 * 不碰隐藏 API、不碰 root。
 */
object SpatialServiceMode {

    private const val TAG = "SPATIAL_WP"

    /**
     * 当前系统壁纸是不是我们的 service。
     *
     * **桌面和锁屏任一命中就算"运行中"**：用户在系统页可以选"仅桌面 / 仅锁屏 / 同时"，
     * 选"仅锁屏"时 `FLAG_SYSTEM` 还指着原来的壁纸，只查那一路会漏判 ⇒ UI 会以为没生效、
     * 又去点一次。带 `which` 的重载是 API 34，33 上退回无参（等价 FLAG_SYSTEM）。
     *
     * ⚠️ **判据必须比到 component 类名，不能只比包名**（2026-11-04）：现在有两个
     * `WallpaperService`（[SpatialWallpaperService] 与 [RasterWallpaperService]），
     * 包名相同 ⇒ 只比包名会让两套功能互相误判「我的服务正在跑」。
     *
     * @param service 要检查的服务类，默认空间壁纸那条
     */
    fun isRunning(
        ctx: Context,
        service: Class<out WallpaperService> = SpatialWallpaperService::class.java,
    ): Boolean = runCatching {
        val wm = WallpaperManager.getInstance(ctx)
        val pkg = ctx.packageName
        fun ours(info: android.app.WallpaperInfo?): Boolean {
            val c = info?.component ?: return false
            return c.packageName == pkg && c.className == service.name
        }
        if (Build.VERSION.SDK_INT >= 34) {
            ours(wm.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)) ||
                ours(wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK))
        } else {
            ours(wm.getWallpaperInfo())
        }
    }.getOrDefault(false)

    /**
     * 打开系统动态壁纸页 —— 目标**直达预览页**。
     *
     * ## HyperOS 上有两条路，各有各的门槛（2026-10-30 全部实测过）
     *
     * | 方案 | 目标 Activity | 能否被第三方启动 | 需要的参数 |
     * |---|---|---|---|
     * | **A（WE 用的）** | `LiveWallpaperChange` | ✅ **能**（它有 `intent-filter` ⇒ 对外可见） | `EXTRA_LIVE_WALLPAPER_COMPONENT`（ComponentName） |
     * | **B（系统列表页用的）** | `LiveWallpaperPreview` | ❌ **不能**（无 intent-filter ⇒ 第三方启动抛 `ActivityNotFoundException`） | `android.live_wallpaper.info`（WallpaperInfo） |
     *
     * ⇒ A 才是第三方唯一可用的直达路。B 只对 livepicker 自己有效（它的列表页点进去时用）。
     *
     * ## A 还有一道 AppOps 关
     *
     * `LiveWallpaperChange.withoutChangePermission()` 会查
     * `AppOpsManager.checkOpNoThrow(MIUIOP(10045), 调用方uid, 壁纸包名)`，
     * 不通过就 `finish()`（日志 `No permission to change wall paper`）。
     *
     * ✅ **新装 app 的 `MIUIOP(10045)` 默认就是 allow**（`appops get` 显示
     * `Default mode: allow`）⇒ **不需要任何授权**，免 root 也能直达（2026-10-30 实测）。
     *
     * ⚠️⚠️ **千万别用 `appops set <pkg> 10045 ...` 去"整理"它**：MIUI 的自定义 op 一旦有了
     * 记录（哪怕 set 成 `default`）就会变成**拒绝**状态 ⇒ 连 A 路都被拦，表现是
     * `ActivityNotFoundException` / 一闪而过。要清干净只能用 `appops reset <pkg>`
     * （会把该 uid 的全部 op 记录清回系统默认）。这个坑我踩过一次。
     *
     * ## WE 没有后门
     *
     * 反编译 `io.wallpaperengine.weclient`：`Util$Companion.callApplyWallpaper` 用的就是
     * **A 路**（`ACTION_CHANGE_LIVE_WALLPAPER` + `EXTRA_LIVE_WALLPAPER_COMPONENT`），
     * 代码里没有任何针对小米的特殊处理；它的 dex 里也**没有** `setWallpaperComponent`
     * （那个 API 要 signature 权限，第三方拿不到）⇒ 它只能靠系统页设置。
     * 差别只在 AppOps：WE 的 `MIUIOP(10045)` 是**显式 allow**，我们靠**系统默认 allow**。
     */
    fun openPicker(
        ctx: Context,
        service: Class<out WallpaperService> = SpatialWallpaperService::class.java,
    ) {
        val app = ctx.applicationContext ?: ctx
        // 一、A：WE 那条路（component + LiveWallpaperChange）—— 唯一第三方可直达的
        val a = runCatching {
            ctx.startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .setClassName(
                        "com.android.wallpaper.livepicker",
                        "com.android.wallpaper.livepicker.LiveWallpaperChange",
                    )
                    .putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(app, service),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "A"
        }.onFailure {
            Log.w(TAG, "A 路失败：${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        // 二、B：WallpaperInfo + LiveWallpaperPreview —— HyperOS 上第三方启动不了，
        //     保留是为了别的 ROM 上它可能是通的（系统列表页自己就是这么用的）。
        var b: String? = null
        if (a == null) {
            b = runCatching {
                val pm = app.packageManager
                val ri = pm.queryIntentServices(
                    Intent(WallpaperService.SERVICE_INTERFACE),
                    PackageManager.GET_META_DATA,
                ).firstOrNull {
                    // ⚠️ 必须连类名一起比：本包有两个 WallpaperService，只比包名会拿到另一个。
                    it.serviceInfo.packageName == app.packageName &&
                        it.serviceInfo.name == service.name
                } ?: error("查不到自己的壁纸 service（${service.name}）")
                ctx.startActivity(
                    Intent()
                        .setClassName(
                            "com.android.wallpaper.livepicker",
                            "com.android.wallpaper.livepicker.LiveWallpaperPreview",
                        )
                        .putExtra("android.live_wallpaper.info", WallpaperInfo(app, ri))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "B"
            }.onFailure {
                Log.w(TAG, "B 路失败：${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
        }
        // 三、兜底：动态壁纸列表页（实测永远可用，只是要多点一下）
        var c: String? = null
        if (a == null && b == null) {
            c = runCatching {
                ctx.startActivity(
                    Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "C"
            }.onFailure {
                Log.w(TAG, "C 路失败：${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
        }
        Log.i(TAG, "openPicker: A=$a B=$b C=$c")
    }

    /**
     * 准备并跳转：先把「要渲染哪张图」落盘，再拉起系统页。
     *
     * 壁纸进程是独立进程，读不到 UI 侧的内存状态，只能靠 [SpatialServiceTarget] 这个文件。
     * **先写文件再跳转** —— 用户从系统页返回时壁纸进程可能已经开始渲染了。
     */
    /**
     * 准备并跳转：先把「要渲染哪张图」落盘，再拉起系统页。
     *
     * 壁纸进程是独立进程，读不到 UI 侧的内存状态，只能靠 target 文件。
     * **先写文件再跳转** —— 用户从系统页返回时壁纸进程可能已经开始渲染了。
     *
     * @param service 目标壁纸服务（空间 / 光栅）
     * @param writeTarget 目标文件的写入方式（两套壁纸**各一份**，互不覆盖）
     */
    fun enable(
        ctx: Context,
        key: String,
        service: Class<out WallpaperService> = SpatialWallpaperService::class.java,
        writeTarget: (Context, String) -> Unit = { c, k -> SpatialServiceTarget.write(c, k) },
    ) {
        writeTarget(ctx, key)
        openPicker(ctx, service)
    }

    /**
     * 恢复到系统默认壁纸（桌面 + 锁屏一起清）。
     *
     * ⚠️ 必须清 **both**：只清 `FLAG_SYSTEM` 的话，之前设成 both 的壁纸会"退化成只剩锁屏"，
     * 我们的 service 还赖在锁屏上（官方文档原话："it will become lock screen only"）。
     *
     * @param clearTarget 目标文件的清理方式（与 [enable] 的写入方成对）
     */
    fun disable(
        ctx: Context,
        clearTarget: (Context) -> Unit = { SpatialServiceTarget.clear(it) },
    ) {
        runCatching {
            WallpaperManager.getInstance(ctx)
                .clear(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        }.onFailure { Log.w(TAG, "clear 壁纸失败：${it.javaClass.simpleName}: ${it.message}") }
        clearTarget(ctx)
    }
}
