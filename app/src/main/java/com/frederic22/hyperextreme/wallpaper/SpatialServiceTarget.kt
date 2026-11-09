package com.frederic22.hyperextreme.wallpaper

import android.content.Context
import java.io.File

/**
 * 「系统壁纸服务」模式要渲染哪张图 —— **主进程写、壁纸进程读**。
 *
 * ## 为什么用文件而不是 SharedPreferences / ContentProvider
 *
 * 壁纸服务跑在 `:wallpaper` 独立进程（见 `AndroidManifest` 的 `android:process`）。
 * `SharedPreferences` 跨进程读有缓存（`MODE_MULTI_PROCESS` 已废弃且不可靠），
 * 为此专门架一个 Provider 又太重 —— 这里只有**一个 key + 一个时间戳**，一个 JSON 文件足够。
 *
 * 写入用「先写 tmp 再 rename」，保证读端永远看不到半截内容。
 */
internal object SpatialServiceTarget {

    private const val NAME = "service_target.json"

    private fun file(ctx: Context) = File(ctx.filesDir, NAME)
    private fun tmp(ctx: Context) = File(ctx.filesDir, "$NAME.tmp")

    /** 主进程：点「跳转到服务页设置」后记下要渲染的 key。 */
    fun write(ctx: Context, key: String) {
        runCatching {
            val json = org.json.JSONObject()
                .put("key", key)
                .put("at", System.currentTimeMillis())
            val f = tmp(ctx)
            f.writeText(json.toString())
            if (!f.renameTo(file(ctx))) {
                file(ctx).writeText(json.toString())
                f.delete()
            }
        }
    }

    /** 主进程：点「恢复到系统默认壁纸」时清掉，免得壁纸进程下次可见还去加载。 */
    fun clear(ctx: Context) {
        runCatching {
            file(ctx).delete()
            tmp(ctx).delete()
        }
    }

    /** 读当前要渲染的 key；没有 / 内容坏了返回 null。 */
    fun readKey(ctx: Context): String? = runCatching {
        val f = file(ctx)
        if (!f.isFile) return null
        val key = org.json.JSONObject(f.readText()).optString("key", "")
        key.takeIf { it.length == 16 }
    }.getOrNull()

    /** 文件最后一次被写的时间（用作「配置有没有变」的判据）。 */
    fun stamp(ctx: Context): Long = runCatching { file(ctx).lastModified() }.getOrDefault(0L)
}
