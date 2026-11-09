package com.frederic22.hyperextreme.hook

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import java.io.File

/**
 * 桌面主体层：**`TYPE_WALLPAPER` 窗口**（2026-10-21，视差覆盖桌面的最终落点）。
 *
 * ## 为什么不在 miwallpaper 里画
 *
 * 试过在 `ImageWallpaperRenderer.onDrawFrame()` 末尾追加 GL pass：能画，但**静态壁纸只出 1 帧**，
 * 传感器变化不刷新；从外部调 `renderer.refresh()` 驱动重绘会跑到没有 EGL context 的线程上
 * （`glError=0x501`，壁纸整块变黑）。miwallpaper 的引擎是自定义 `EngineService`，
 * 没有 `WallpaperService.Engine#invalidateSelf()` 这条线程安全的通道 —— 此路不通。
 *
 * ## 为什么是 TYPE_WALLPAPER
 *
 * 窗口层级（从下到上）：
 *
 * ```
 * 系统壁纸（WallpaperWindowToken）
 *   ← TYPE_WALLPAPER 窗口（本类）  ← 这里：盖住壁纸、又在图标之下
 * 桌面窗口（图标 / 文件夹 / dock）
 *   ← TYPE_APPLICATION_OVERLAY（会挡图标，不能用）
 * ```
 *
 * SystemUI 持有 `BIND_WALLPAPER` + `INTERNAL_SYSTEM_WINDOW`（实测均 granted），
 * 所以能直接加这种窗口；它是普通 View，由 vsync 驱动，天然持续刷新。
 *
 * ## 与其它两层的分工
 *
 * | 层 | 谁来画 | 怎么动 |
 * | --- | --- | --- |
 * | 背景（系统壁纸） | system_server / miwallpaper | [ParallaxDesktopLeash] 改壁纸 SurfaceControl 的 matrix |
 * | 主体（抠像） | 本类 | `translationX/Y + scaleX/Y`，与锁屏同一套参数 |
 * | 图标 | 桌面进程 | 不参与 |
 *
 * 图源 `/data/system/theme_magic/users/0/wallpaper/crop_subject` 是 `-rwxrwxrwx system`，
 * SystemUI 直接可读，不用走 provider。
 */
internal object ParallaxWallpaperWindow {

    private const val CROP_SUBJECT = "/data/system/theme_magic/users/0/wallpaper/crop_subject"

    /** 只在桌面显示：锁屏有自己的景深层，叠上去会重影。 */
    @Volatile
    private var visible = false

    private var imageView: ImageView? = null
    private var added = false
    private var failed = false
    private var pending = false
    private var lastScale = Float.NaN
    private var lastTx = Float.NaN
    private var lastTy = Float.NaN

    private val mainHandler: android.os.Handler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }

    private fun log(msg: String) = HookRuntime.log("PWW $msg")

    /**
     * 首次调用时建窗口；之后只做可见性切换。
     *
     * **`WindowManager#addView` 必须在有 Looper 的线程**（实测在裸 Thread 里调会抛
     * "that has not called Looper.prepare()"），所以这里一律派发到主线程执行。
     */
    fun ensure(context: Context): Boolean {
        if (added) return true
        if (failed) return false
        if (pending) return true
        pending = true
        val app = context.applicationContext ?: context
        mainHandler.post { doEnsure(app) }
        return true
    }

    private fun doEnsure(context: Context) {
        pending = false
        if (added || failed) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            failed = true
            log("拿不到 WindowManager")
            return
        }
        val file = File(CROP_SUBJECT)
        if (!file.isFile) {
            log("没有 $CROP_SUBJECT（用户没设主体）—— 只做背景视差")
            failed = true
            return
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(CROP_SUBJECT) }.getOrNull()
        if (bitmap == null) {
            failed = true
            log("主体图解码失败")
            return
        }
        val view = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_WALLPAPER,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }
        val ok = runCatching { wm.addView(view, params) }
            .onFailure { log("addView(TYPE_WALLPAPER) 失败：${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
        if (!ok) {
            failed = true
            return
        }
        imageView = view
        added = true
        log("TYPE_WALLPAPER 窗口已添加（${bitmap.width}x${bitmap.height}）")
    }

    /**
     * 由 [ParallaxBg.applyParallax] 每帧调用。
     *
     * @param onDesk 是否处于桌面（锁屏/通知中心展开时为 false）
     */
    fun update(
        onDesk: Boolean,
        tiltX: Float,
        tiltY: Float,
        subjectScale: Float,
        subjectAmp: Float,
    ) {
        val view = imageView ?: return
        if (!onDesk) {
            if (visible) {
                visible = false
                runCatching { view.visibility = View.GONE }
            }
            return
        }
        if (!visible) {
            visible = true
            runCatching { view.visibility = View.VISIBLE }
            log("主体层已显示（桌面态）")
        }
        val tx = tiltX * subjectAmp
        val ty = tiltY * subjectAmp
        if (view.scaleX != subjectScale) {
            view.scaleX = subjectScale
            view.scaleY = subjectScale
        }
        if (view.translationX != tx) view.translationX = tx
        if (view.translationY != ty) view.translationY = ty
        lastScale = subjectScale
        lastTx = tx
        lastTy = ty
    }

    fun hide() {
        visible = false
        runCatching { imageView?.visibility = View.GONE }
    }
}
