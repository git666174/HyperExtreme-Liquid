package com.frederic22.hyperextreme.wallpaper

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * 小米「动态壁纸服务」权限的检测与引导（2026-11-03）。
 *
 * ## 这是什么东西
 *
 * HyperOS 在「应用权限 → 其他权限 → 设置相关」里有一项**动态壁纸服务**，关掉之后
 * 系统会拒绝第三方把动态壁纸设上去。它没有 Manifest 权限名，背后是小米自定义的
 * **`MIUIOP(10045)`**：`LiveWallpaperChange` 在换壁纸前会
 * `checkOpNoThrow(10045, 调用方uid, 壁纸包名)`，不通过就自己 `finish()`。
 *
 * ## 为什么必须提前拦
 *
 * `finish()` 是**静默**的 —— `startActivity` 不抛异常，所以 [SpatialServiceMode.openPicker]
 * 里那三级 `runCatching` 永远返回 `"A"`，B（LiveWallpaperPreview）和 C（壁纸列表页）
 * **从来不会被执行**。用户看到的就是「点了一下，什么都没发生」。
 *
 * ## 实测（2026-11-03，小米 14 / HyperOS 4 / API 37）
 *
 * | 检查项 | 结果 |
 * |---|---|
 * | 权限项文案 | **动态壁纸服务**（应用权限 → 其他权限 → 设置相关） |
 * | 关闭时 `appops get` | `MIUIOP(10045): ignore` |
 * | 开启时 | `MIUIOP(10045): allow` |
 * | 普通 app 能否读取 | **能**。以应用自身 uid 直接调 `IAppOpsService.checkOperation(10045, uid, pkg)`，返回 `1`（IGNORED）；root 跑结果相同 ⇒ 不是特权接口 |
 * | 权限页能否直达 | **能**。`PermissionsEditorActivity` + `extra_pkgname`，拉起后标题就是本应用 |
 *
 * ## 两个保守设计
 *
 * 1. **先确认能跳到小米安全中心才判断**。别的 ROM 上 10045 可能是完全不同的含义，
 *    万一它也返回 IGNORED，我们就会把用户引到一个不存在的权限页。
 * 2. **只有明确 `MODE_IGNORED` 才算没权限**。`MODE_DEFAULT` / `MODE_ERRORED` /
 *    查询抛异常一律放行 —— 宁可漏拦，也不能把「查不到」当成「被拒绝」。
 */
object DynamicWallpaperPermission {

    private const val TAG = "SPATIAL_WP"

    /** 小米自定义 op：动态壁纸服务。`AppOpsManager` 没有对应常量，只能写数字。 */
    private const val OP_DYNAMIC_WALLPAPER = 10045

    private const val SECURITY_CENTER = "com.miui.securitycenter"
    private const val PERMISSION_EDITOR =
        "com.miui.permcenter.permissions.PermissionsEditorActivity"

    /** 权限页的 Intent；跳不过去时返回 null（非小米设备 / 被精简过）。 */
    private fun editorIntent(ctx: Context): Intent? {
        val intent = Intent()
            .setClassName(SECURITY_CENTER, PERMISSION_EDITOR)
            .putExtra("extra_pkgname", ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf {
            runCatching { ctx.packageManager.resolveActivity(it, 0) != null }.getOrDefault(false)
        }
    }

    /**
     * 读 `MIUIOP(10045)` 的当前 mode；读不到返回 null。
     *
     * ## 为什么不用 `AppOpsManager.checkOpNoThrow`
     *
     * 那个方法的 **int 版是 `@hide`** —— 公开 SDK（android-34 / 36 / 37 都核对过）里
     * 只有字符串版 `checkOpNoThrow(String op, int uid, String packageName)`，而字符串版
     * 要先把 op 名反查成 int；小米的自定义 op **没有名字**（`appops get` 就显示
     * `MIUIOP(10045)`，不是 `android:xxx`）⇒ 反查必然抛
     * `IllegalArgumentException("Unknown operation string")`。
     *
     * ## 所以直接走 binder
     *
     * `IAppOpsService.checkOperation(int, int, String)` 就是 `AppOpsManager` 内部调的那个接口。
     * 实测（2026-11-03，同一台机器上分别以 app uid 10335 和 root 运行）两次都返回了真实 mode：
     * 权限关闭时 `1`（IGNORED），同包的 10004 / 10033 是 `0`（ALLOWED）。
     * 说明**不是特权接口**，普通应用查自己的 op 完全允许（`verifyIncomingUid` 在 uid 相等时直接放行）。
     *
     * 全程 `runCatching`：隐藏 API 一旦变动，返回 null，调用方按「有权限」处理。
     */
    private fun readOpMode(uid: Int, pkg: String): Int? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "appops") as? IBinder ?: return null
        val svc = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return null
        svc.javaClass
            .getMethod(
                "checkOperation",
                Integer.TYPE,
                Integer.TYPE,
                String::class.java,
            )
            .invoke(svc, OP_DYNAMIC_WALLPAPER, uid, pkg) as? Int
    }.getOrNull()

    /**
     * 当前有没有「动态壁纸服务」权限。
     *
     * 非小米设备、查不到安全中心、查询失败 —— **一律返回 true**，让原来的流程照常走。
     * 只有**明确读到 IGNORED** 才算没权限（DEFAULT / ERRORED 都放行）。
     */
    fun allowed(ctx: Context): Boolean {
        if (editorIntent(ctx) == null) return true
        val mode = readOpMode(Process.myUid(), ctx.packageName) ?: return true
        return if (mode == AppOpsManager.MODE_IGNORED) {
            Log.i(TAG, "动态壁纸服务权限缺失（MIUIOP($OP_DYNAMIC_WALLPAPER)=$mode）")
            false
        } else {
            true
        }
    }

    /**
     * 跳到「本应用的权限页」（用户在那里把「动态壁纸服务」打开）。
     *
     * 直达那一项做不到 —— 实测点「动态壁纸服务」进去是 `WakePathManagerActivity`
     * （链式启动管理），不是它自己的开关页。所以停在「其他权限」列表，由用户自己点。
     * 跳不过去时兜底到系统应用详情页。
     */
    fun openSettings(ctx: Context) {
        val ok = editorIntent(ctx)?.let {
            runCatching { ctx.startActivity(it) }.isSuccess
        } ?: false
        if (!ok) {
            Log.w(TAG, "跳权限页失败，退回应用详情页")
            runCatching {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "应用详情页也打不开：${it.javaClass.simpleName}") }
        }
    }
}
