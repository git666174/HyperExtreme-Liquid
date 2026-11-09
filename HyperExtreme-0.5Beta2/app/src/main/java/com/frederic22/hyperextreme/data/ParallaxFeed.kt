package com.frederic22.hyperextreme.data

import android.content.Context
import java.io.File

/**
 * 把视差用的两张图推送进 `com.miui.miwallpaper` 进程（2026-10-21）。
 *
 * ## 为什么需要推送
 *
 * 桌面壁纸由 miwallpaper 的 GL 管线绘制，视差要复刻锁屏效果就得在那一层画我们的图。
 * 但 miwallpaper 是**独立 app**（uid `u0_a184`），跨进程读不到模块私有目录：
 * 实测 `getRemotePreferences` / `openRemoteFile` / provider 在它里面全不可用（见 ParallaxDesktopGuard 注释），
 * 所以图片只能**落进它自己的 data 目录**，靠 root 复制 + chown。
 *
 * ## 落点
 *
 * ```
 * /data/data/com.miui.miwallpaper/files/he_parallax_bg.png       背景（用户导入的视差背景）
 * /data/data/com.miui.miwallpaper/files/he_parallax_subject.png  主体（theme_magic 的 crop_subject 抠像）
 * ```
 *
 * 主体缺失（用户没设主体）时删掉旧文件，让绘制侧按"单层"处理。
 * 图更新后由 miwallpaper 侧按文件 mtime 自动重载，不需要重启进程。
 */
internal object ParallaxFeed {

    /** miwallpaper 的私有 files 目录（root 可写）。 */
    const val DIR = "/data/data/com.miui.miwallpaper/files"
    const val BG_NAME = "he_parallax_bg.png"
    const val SUBJECT_NAME = "he_parallax_subject.png"

    /** 主体的源头：编辑器生成的透明抠像，与「已应用壁纸」逐像素对齐。 */
    private const val CROP_SUBJECT = "/data/system/theme_magic/users/0/wallpaper/crop_subject"

    fun bgPath(): String = "$DIR/$BG_NAME"

    fun subjectPath(): String = "$DIR/$SUBJECT_NAME"

    /**
     * 把当前背景（+ 主体）推进 miwallpaper。需 root；同步执行，调用方自己丢后台线程。
     *
     * **背景可以没有**：锁屏视差的背景本来就是系统壁纸（多数用户没导入自定义背景），
     * 那种情况下 GL 层只叠主体，背景交给桌面壁纸层自己的位移。
     *
     * @return true = 至少有一张图就位
     */
    fun push(context: Context): Boolean {
        val bg = MaskStore.parallaxFile(context)
        val hasBg = bg.isFile && bg.length() > 0L
        // chown 到 miwallpaper 自己的 uid/gid：目录 owner 就是它的 uid，直接借用。
        // restorecon 让 SELinux 上下文落到 app_data_file（cp 过去默认可能继承成别的类型）。
        val script = buildString {
            append("D=$DIR; ")
            append("mkdir -p \$D; ")
            if (hasBg) {
                append("cp '${bg.absolutePath}' \$D/$BG_NAME || exit 3; ")
            } else {
                append("rm -f \$D/$BG_NAME; ")
            }
            append("if [ -f $CROP_SUBJECT ]; then cp $CROP_SUBJECT \$D/$SUBJECT_NAME; ")
            append("else rm -f \$D/$SUBJECT_NAME; fi; ")
            append("OWN=\$(stat -c %u:%g /data/data/com.miui.miwallpaper); ")
            append("chown \$OWN \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null; ")
            append("chmod 600 \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null; ")
            append("restorecon \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null; ")
            append("ls -l \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null")
        }
        val result = RootShell.run(script)
        val listed = result.output.trim()
        if (!result.ok || listed.isEmpty()) {
            RunLog.append(
                context,
                "视差推图",
                listOf("失败（bg=$hasBg）：${listed.take(200)}"),
                false,
            )
            return false
        }
        RunLog.append(
            context,
            "视差推图",
            listOf("bg=$hasBg | " + listed.lines().joinToString(" | ") { it.trim() }),
            true,
        )
        return true
    }

    /** 关掉开关时清掉，避免 miwallpaper 侧还留着旧图（不清也不影响，只是占空间）。 */
    fun clear(): Boolean =
        RootShell.run("rm -f $DIR/$BG_NAME $DIR/$SUBJECT_NAME").ok
}
