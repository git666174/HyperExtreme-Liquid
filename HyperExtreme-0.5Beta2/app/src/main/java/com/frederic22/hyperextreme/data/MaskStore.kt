package com.frederic22.hyperextreme.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 替换图在本模块私有目录里的存放、导入与预览。
 *
 * 统一重编码成 PNG(ARGB_8888)：JPEG 源图没有 alpha，直接落盘会让抠像层变成黑块。
 */
object MaskStore {

    /** 超过这个尺寸的照片会被等比缩小，避免 1080×2400 级别的 PNG 撑爆内存。 */
    private const val MAX_EDGE = 4096

    /** 「视差壁纸效果」背景图的文件名（与三个模板角色平级，但独立存放）。 */
    const val PARALLAX_FILE_NAME = "parallax_bg.png"

    fun file(context: Context, role: MaskRole): File = File(context.filesDir, role.fileName)

    fun exists(context: Context, role: MaskRole): Boolean =
        file(context, role).let { it.isFile && it.length() > 0L }

    fun clear(context: Context, role: MaskRole) {
        file(context, role).delete()
    }

    /**
     * 清掉**所有角色的候选**（已注入的快照不动）—— 软件每次重启时调一次。
     *
     * 为什么要在启动时清：候选只是"刚选好、还没点替换"的临时状态，跨一次进程重启还留着就是脏数据 ——
     * 用户下次进来会看到"已经选好了"（其实早忘了选的什么），更糟的是**它会成为下一次替换的替换源**
     * （用户以为替换的是当前壁纸的抠像，实际写进去的是上次残留那张）。
     *
     * 只删候选、不碰 `injected/`：注入快照代表"系统里现在就是这份"，与本次会话无关。
     *
     * @return 实际清掉的角色（用于日志 / 「最近操作」诊断）。
     */
    fun clearCandidates(context: Context): List<MaskRole> =
        MaskRole.selectable.filter { exists(context, it) }.onEach { clear(context, it) }

    // ---------------------------------------------------------------- 候选 vs 注入快照
    //
    // 一次替换的生命周期是「选图 → 替换并开始保护 → 收尾结束保护（候选清空）」。
    // 候选（用户选的那张）和"现在正保护着的那份"是两件事：候选清空之后 hook 还得继续守着系统文件，
    // 所以替换成功时把候选复制一份成**注入快照**，之后 hook 拿快照继续保护，界面上的候选则是空的。

    fun injectedFile(context: Context, role: MaskRole): File =
        File(File(context.filesDir, "injected"), role.fileName)

    fun hasInjected(context: Context, role: MaskRole): Boolean =
        injectedFile(context, role).let { it.isFile && it.length() > 0L }

    /** 候选或注入快照任一存在 —— provider / hook 关心的"这份图有没有"。 */
    fun available(context: Context, role: MaskRole): Boolean =
        exists(context, role) || hasInjected(context, role)

    /** 当前处于保护中的角色（有注入快照的那些）。 */
    fun injectedRoles(context: Context): List<MaskRole> =
        MaskRole.selectable.filter { hasInjected(context, it) }

    /** 把候选复制成注入快照。候选不存在时返回 false（没有可快照的内容）。 */
    fun snapshotToInjected(context: Context, role: MaskRole): Boolean = runCatching {
        if (!exists(context, role)) return@runCatching false
        val target = injectedFile(context, role)
        target.parentFile?.mkdirs()
        file(context, role).copyTo(target, overwrite = true)
        true
    }.getOrDefault(false)

    fun clearInjected(context: Context) {
        File(context.filesDir, "injected").listFiles()?.forEach { it.delete() }
    }

    fun clearInjected(context: Context, role: MaskRole) {
        injectedFile(context, role).delete()
    }

    /**
     * 相册返回的 URI 是临时授权，用户可能在确认框上停留很久才点确定。
     * 所以选中后立刻把字节复制到缓存目录，后续一律操作本地副本。
     */
    fun cachePicked(context: Context, uri: Uri): File? = runCatching {
        val target = File(context.cacheDir, "picked.png")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        target.takeIf { copied && it.length() > 0L }
    }.getOrNull()

    /** 只读图片头拿到尺寸，用于导入前的分辨率检查。 */
    fun readSize(source: File): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            null
        } else {
            options.outWidth to options.outHeight
        }
    }.getOrNull()

    /** 只读图片头，返回 "宽×高"，读不到返回 null。 */
    fun sizeText(context: Context, role: MaskRole): String? {
        val target = file(context, role)
        if (!target.isFile) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return "${options.outWidth}×${options.outHeight}"
    }

    fun loadPreview(context: Context, role: MaskRole, maxEdge: Int): Bitmap? =
        loadPreview(file(context, role), maxEdge)

    /** 从任意本地文件加载至多 [maxEdge] 长边的预览（背景图预览也走它）。 */
    fun loadPreview(target: File, maxEdge: Int): Bitmap? {
        if (!target.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(target.absolutePath, options)
        }.getOrNull()
    }

    /** 把缓存副本导入成该角色的替换图，返回 "宽×高"。 */
    fun importFromFile(context: Context, role: MaskRole, source: File): Result<String> =
        importTo(file(context, role), source)

    /** 把缓存副本重编码（PNG、[MAX_EDGE] 上限）写进目标文件，返回 "宽×高"。 */
    fun importTo(target: File, source: File): Result<String> = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("无法识别图片尺寸")

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: error("图片解码失败")

        try {
            write(bitmap, target)
            "${bitmap.width}×${bitmap.height}"
        } finally {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------- 视差壁纸效果的背景图（2026-10-12 加）
    //
    // 与三个"模板角色"不同：背景图不进替换链（**不写任何系统文件、无保护期**），
    // 候选与"生效中的那份"就是同一个文件。生命周期：
    //   - 开启中（`MaskPrefs.parallaxBgActive`）：跨重启保留 —— SystemUI 重启后要靠它重新显示；
    //   - 未开启：按普通候选语义，随下次 App 启动清理（见 `App.onCreate`）。

    fun parallaxFile(context: Context): File = File(context.filesDir, PARALLAX_FILE_NAME)

    fun parallaxExists(context: Context): Boolean =
        parallaxFile(context).let { it.isFile && it.length() > 0L }

    fun clearParallax(context: Context) {
        parallaxFile(context).delete()
    }

    /** 导入背景图（同一套重编码规则）。 */
    fun importParallax(context: Context, source: File): Result<String> =
        importTo(parallaxFile(context), source)

    fun loadParallaxPreview(context: Context, maxEdge: Int): Bitmap? =
        loadPreview(parallaxFile(context), maxEdge)

    fun write(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
        }
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }
}
