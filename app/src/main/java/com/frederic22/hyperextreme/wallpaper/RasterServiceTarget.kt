package com.frederic22.hyperextreme.wallpaper

import android.content.Context
import java.io.File

/**
 * 「系统壁纸服务」模式要渲染哪一组光栅图 —— **主进程写、壁纸进程读**（2026-11-04）。
 *
 * 与 [SpatialServiceTarget] **完全独立**（文件名不同）：
 * 两套壁纸各有各的「当前目标」，切换挂载模式时互不覆盖。
 * 共用一份的话，从光栅页跳到系统壁纸页再回空间壁纸页，会把对方的目标读成自己的。
 *
 * 为什么用文件：壁纸服务跑在 `:wallpaper` 独立进程，`SharedPreferences` 跨进程有缓存
 * （`MODE_MULTI_PROCESS` 已废弃且不可靠）。写入用「先写 tmp 再 rename」，读端永远看不到半截。
 */
internal object RasterServiceTarget {

    private const val NAME = "raster_service_target.json"

    private fun file(ctx: Context) = File(ctx.filesDir, NAME)
    private fun tmp(ctx: Context) = File(ctx.filesDir, "$NAME.tmp")

    /**
     * 主进程：点「跳转系统页」后记下要渲染的组 id 与**最大幅度角度**、**停顿时就近归位**。
     *
     * 角度一起写：壁纸进程读不到 `MaskPrefs`，而它与组 id 是同一时刻的配置快照，
     * 分两个文件就会出现「组换了、角度还是旧的」。
     *
     * @param panDeg 15..45（度）；越界按 25 兜底
     * @param settle 停顿时是否就近归位（默认开）
     */
    fun write(ctx: Context, key: String, panDeg: Int = 25, settle: Boolean = true) {
        runCatching {
            val json = org.json.JSONObject()
                .put("key", key)
                .put("panDeg", panDeg.coerceIn(15, 45))
                .put("settle", settle)
                .put("at", System.currentTimeMillis())
            val f = tmp(ctx)
            f.writeText(json.toString())
            if (!f.renameTo(file(ctx))) {
                file(ctx).writeText(json.toString())
                f.delete()
            }
        }
    }

    /** 读当前配置：组 id 与角度；没有 / 内容坏了返回 null / 默认 25。 */
    fun readPanDeg(ctx: Context): Int = runCatching {
        val f = file(ctx)
        if (!f.isFile) return 25
        org.json.JSONObject(f.readText()).optInt("panDeg", 25).coerceIn(15, 45)
    }.getOrDefault(25)

    /** 「停顿时就近归位」；没写 / 读不出来按**开**（= 加这个选项之前的行为）。 */
    fun readSettle(ctx: Context): Boolean = runCatching {
        val f = file(ctx)
        if (!f.isFile) return true
        org.json.JSONObject(f.readText()).optBoolean("settle", true)
    }.getOrDefault(true)

    /** 主进程：点「恢复默认」时清掉，免得壁纸进程下次可见还去加载。 */
    fun clear(ctx: Context) {
        runCatching {
            file(ctx).delete()
            tmp(ctx).delete()
        }
    }

    /** 读当前要渲染的组 id；没有 / 内容坏了返回 null。 */
    fun readKey(ctx: Context): String? = runCatching {
        val f = file(ctx)
        if (!f.isFile) return null
        val key = org.json.JSONObject(f.readText()).optString("key", "")
        key.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 文件最后一次被写的时间（用作「配置有没有变」的判据）。 */
    fun stamp(ctx: Context): Long = runCatching { file(ctx).lastModified() }.getOrDefault(0L)
}
