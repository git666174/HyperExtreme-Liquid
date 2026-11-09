package com.frederic22.hyperextreme.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * 「视差效果覆盖桌面」第二步：把「背景 + 主体」合成图送进系统桌面壁纸（2026-10-21）。
 *
 * ## 为什么是「换壁纸」而不是「插一层」
 *
 * 桌面壁纸是 `com.miui.miwallpaper` 用 **OpenGL 管线**渲染的系统壁纸层（`WallpaperWindowToken`），
 * 桌面 Flutter 只是显示它。要在这一层显示我们的图，只有两条路：
 * 1. 改 miwallpaper 的 GL 管线（工作量大、跟版风险高）；
 * 2. **换它渲染的源**：把合成图设为系统壁纸 —— 也就是这里做的。
 *
 * 换源之后，视差继续由已经跑通的 `ParallaxDesktopLeash`（SurfaceControl.setMatrix）完成，
 * 桌面进程、miwallpaper 都不用改，图标永远在壁纸之上。
 *
 * ## 主体从哪来
 *
 * `crop_subject` 是编辑器生成、与「已应用壁纸」逐像素对齐的透明抠像
 *（路径见 [CROP_SUBJECT]），读它需要 root；先复制到模块私有目录再解码。
 *
 * ## 安全性
 *
 * 注入前把当前壁纸存一份 `desktop_wallpaper_backup.png`；关闭开关时用它还原并删除备份。
 * 备份已存在则不重复覆盖（避免把我们的合成图当成"原壁纸"备份进去）。
 */
object DesktopWallpaperInjector {

    private const val CROP_SUBJECT = "/data/system/theme_magic/users/0/wallpaper/crop_subject"
    private const val BACKUP_NAME = "desktop_wallpaper_backup.png"
    private const val SUBJECT_TMP = "subject_for_desktop.png"

    private var ctx: Context? = null

    private fun log(msg: String) {
        val c = ctx ?: return
        RunLog.append(c, "桌面壁纸注入", listOf(msg), true)
    }

    /** 异步执行；主线程调用安全。 */
    fun apply(context: Context, enabled: Boolean) {
        ctx = context.applicationContext
        Thread {
            runCatching {
                if (enabled) inject(context) else restore(context)
            }.onFailure { log("失败：${it.javaClass.simpleName}: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    private fun inject(context: Context) {
        val bgFile = MaskStore.parallaxFile(context)
        if (!bgFile.isFile) {
            log("没有背景图，跳过")
            return
        }
        val wm = WallpaperManager.getInstance(context)
        backupOnce(context, wm)

        val prefs = MaskPrefs(context)
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

        val bg = BitmapFactory.decodeFile(bgFile.absolutePath) ?: run {
            log("背景图解码失败")
            return
        }
        val subject = loadSubject(context)

        val composed = compose(bg, subject, screenW, screenH)
        wm.setBitmap(composed, null, true, WallpaperManager.FLAG_SYSTEM)
        log("已设为桌面壁纸（${screenW}×${screenH}，主体=${subject != null}，bgScale=${prefs.parallaxBgScale}）")
    }

    private fun restore(context: Context) {
        val backup = File(context.filesDir, BACKUP_NAME)
        if (!backup.isFile) {
            log("没有备份，跳过恢复")
            return
        }
        val bmp = BitmapFactory.decodeFile(backup.absolutePath) ?: run {
            log("备份解码失败")
            return
        }
        val wm = WallpaperManager.getInstance(context)
        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
        backup.delete()
        log("已恢复原桌面壁纸")
    }

    private fun backupOnce(context: Context, wm: WallpaperManager) {
        val backup = File(context.filesDir, BACKUP_NAME)
        if (backup.isFile && backup.length() > 0L) return
        runCatching {
            val drawable = wm.drawable ?: return
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            FileOutputStream(backup).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            log("已备份原壁纸（${w}×${h}）")
        }.onFailure { log("备份原壁纸失败：$it") }
    }

    /** `crop_subject` 在 system 分区，先 root 复制到私有目录再解码；没有就返回 null。 */
    private fun loadSubject(context: Context): Bitmap? {
        val tmp = File(context.filesDir, SUBJECT_TMP)
        if (!tmp.isFile || tmp.length() == 0L) {
            val result = RootShell.run("cp $CROP_SUBJECT ${tmp.absolutePath}")
            if (!result.ok) {
                log("复制 crop_subject 失败（可能没设主体）：${result.output.trim()}")
                return null
            }
        }
        return runCatching { BitmapFactory.decodeFile(tmp.absolutePath) }.getOrNull()
    }

    /** 背景 centerCrop 铺满；主体与壁纸同尺寸，按同样比例缩放后居中叠上去。 */
    private fun compose(bg: Bitmap, subject: Bitmap?, screenW: Int, screenH: Int): Bitmap {
        val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val scale = maxOf(screenW / bg.width.toFloat(), screenH / bg.height.toFloat())
        val bw = bg.width * scale
        val bh = bg.height * scale
        canvas.drawBitmap(
            bg,
            null,
            RectF((screenW - bw) / 2f, (screenH - bh) / 2f, (screenW + bw) / 2f, (screenH + bh) / 2f),
            paint,
        )

        if (subject != null) {
            val sw = subject.width * scale
            val sh = subject.height * scale
            canvas.drawBitmap(
                subject,
                null,
                RectF((screenW - sw) / 2f, (screenH - sh) / 2f, (screenW + sw) / 2f, (screenH + sh) / 2f),
                paint,
            )
        }
        return out
    }
}
