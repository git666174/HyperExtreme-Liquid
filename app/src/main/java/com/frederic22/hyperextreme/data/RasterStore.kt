package com.frederic22.hyperextreme.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 一个「光栅组」= 一组按顺序排列的图。**第一张就是封面**（用户 2026-11-03 定）。
 *
 * `items` 里存的是**文件名**（不是绝对路径），顺序 = 排序结果的顺序。
 * 用文件名而不是完整路径，是为了整个 `files/raster` 目录可以被整体挪走 / 备份而不失效。
 */
internal data class RasterGroup(
    val id: String,
    val dir: File,
    val items: List<String>,
    val createdAt: Long,
) {
    /** 封面 = 排序里的第一张；空组返回 null。 */
    val coverFile: File?
        get() = items.firstOrNull()?.let { File(dir, it) }

    fun fileOf(name: String): File = File(dir, name)

    /**
     * 这张图算过的**主体抠像**（RGBA PNG，alpha 就是轮廓）。没算过时文件不存在。
     *
     * ⚠️ 存的是**抠好的主体**，不是灰度遮罩（2026-11-04 改）：渲染链最后要拿它直接当主体层
     * 贴上去，存灰度等于每次渲染都要再合成一遍。
     */
    fun subjectFileOf(name: String): File = File(dir, "$name.subject.png")
}

/**
 * 光栅壁纸的磁盘存储（2026-11-03）。
 *
 * ## 目录即真相
 *
 * 不把组列表写进 `MaskPrefs`：`files/raster/` 下**一个子目录 = 一个组**，
 * 目录里的 `meta.json` 说明顺序。页面每次进前台重扫一遍（几毫秒），
 * 不会出现「prefs 说有、磁盘上没有」的对不上的状态。
 *
 * ```
 * files/raster/<groupId>/
 *   meta.json      {"version":1,"id":"…","createdAt":…,"items":["img_…jpg",…]}
 *   img_….jpg      原图副本（**未压缩**，导入时按字节原样拷进来）
 *   img_….subject.png   可选，算过的**主体抠像**（RGBA，alpha = 轮廓）
 * ```
 *
 * ## 为什么一定要复制
 *
 * 系统相册选图（`PickVisualMedia`）给的是**临时授权**的 `content://` ——
 * 进程重启后授权失效，直接存 URI 重进页面就是一堆打不开的图。
 * 所以导入时立刻把字节读出来、写进私有目录，之后只认自己的文件。
 *
 * **原图字节原样保存，不解码、不缩放、不重编码**（2026-11-04 改）：景深主体要把它送给
 * AICR 抠图，任何一次 JPEG 重编码都会让主体边缘变糊。显示侧一律走 `inSampleSize` 采样，
 * 存多大都不影响内存和帧率。
 *
 * ## 为什么只压长边 1600
 *
 * 卡片封面显示宽度不到 600px（物理像素），浮页行更小；真正渲染壁纸时
 * 屏幕短边也只有 1200。1600 已经覆盖所有显示场景，再大只是白占内存。
 */
internal object RasterStore {

    private const val TAG = "HX-RASTER"

    /** 私有根目录名。 */
    private const val ROOT = "raster"

    /** 组元数据文件名。 */
    private const val META = "meta.json"

    /** 元数据格式版本（以后改结构时用来判新旧）。 */
    private const val META_VERSION = 1

    /** 卡片封面解码时的**短边**目标（卡片宽 45% × 屏宽 ≈ 540px，短边给 540 足够）。 */
    const val CARD_SHORT_SIDE = 540

    /** 浮页排序行的小缩略图**短边**（显示只有 32×56dp）。 */
    const val ROW_SHORT_SIDE = 160

    /**
     * 图片文件名前缀；**内容指纹**编在名字里，格式 `img_<时间戳>_<16位hex>.<ext>`。
     *
     * 2026-11-04 改：指纹从「双路 dHash（32 hex）」换成「SHA-256 前 16 位（16 hex）」。
     * 判重语义随之从"看起来像"变成"**字节完全相同**"—— 见 [import] 的说明。
     */
    private const val PREFIX = "img_"

    /** 指纹长度（hex 字符数）= SHA-256 的前 8 字节（64 bit）。与空间壁纸的 `sha16` 同长。 */
    private const val HASH_HEX = 16

    /** 探测「遮罩 / 主体」时解码的短边（只做分类，不落盘）。 */
    private const val PROBE_SHORT_SIDE = 512

    /** 导入遮罩时的**工作短边**：≥ 屏幕短边（1200）够 1:1 渲染，又不会把原图整幅解进内存。 */
    private const val WORK_SHORT_SIDE = 1600

    fun root(ctx: Context): File = File(ctx.filesDir, ROOT)

    /**
     * 扫描全部组。
     *
     * @param order 用户排定的顺序（`MaskPrefs.rasterGroupOrder`）。空 = 老行为（按创建时间倒序）。
     *   给了顺序时：order 里的按它排在前，**不在里面的按创建时间倒序追加到末尾**
     *   —— 应用 / 新建置顶都靠这条持久化（2026-11-05：以前是加载时按 activeId 临时排序，
     *   关掉应用顺序会跳回原位）。
     *
     * 空组（`items` 为空）一律忽略 —— 用户可能点开浮页又直接退出，
     * 那种目录不该在卡片流里留一张空卡。真正清理由 [deleteGroup] 负责。
     */
    fun list(ctx: Context, order: List<String> = emptyList()): List<RasterGroup> = runCatching {
        val found = root(ctx).listFiles()
            ?.mapNotNull { if (it.isDirectory) load(it) else null }
            ?.filter { it.items.isNotEmpty() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        if (order.isEmpty()) return@runCatching found
        val byId = found.associateBy { it.id }
        order.mapNotNull { byId[it] } + found.filterNot { it.id in order }
    }.onFailure {
        Log.w(TAG, "扫描失败：${it.message}")
    }.getOrElse { emptyList() }

    /** 读一个组目录；`meta.json` 缺失或损坏返回 null（当作没有这个组）。 */
    fun load(dir: File): RasterGroup? = runCatching {
        val meta = File(dir, META)
        if (!meta.isFile) return null
        val o = JSONObject(meta.readText())
        val arr = o.optJSONArray("items") ?: return null
        // 文件被外部删掉时**跳过**而不是留一个打不开的条目。
        val names = (0 until arr.length())
            .map { arr.optString(it) }
            .filter { it.isNotEmpty() && File(dir, it).isFile }
        RasterGroup(
            id = o.optString("id").ifEmpty { dir.name },
            dir = dir,
            items = names,
            createdAt = o.optLong("createdAt", dir.name.toLongOrNull() ?: 0L),
        )
    }.onFailure {
        Log.w(TAG, "读 ${dir.name} 失败：${it.message}")
    }.getOrNull()

    /**
     * 新建一个组目录。**这时候还不写 meta** —— 一张图都没导入就退出的组，
     * 不该在磁盘上留下痕迹（[list] 也会把它过滤掉，这里是双保险）。
     */
    fun create(ctx: Context): RasterGroup {
        val root = root(ctx)
        root.mkdirs()
        var stamp = System.currentTimeMillis()
        while (File(root, stamp.toString()).exists()) stamp++
        val dir = File(root, stamp.toString())
        dir.mkdirs()
        return RasterGroup(id = stamp.toString(), dir = dir, items = emptyList(), createdAt = stamp)
    }

    /** 把 [items] 的当前顺序写回 `meta.json`（排序、删除都走它）。 */
    fun writeMeta(group: RasterGroup) {
        runCatching {
            group.dir.mkdirs()
            val arr = group.items.map { it }
            val o = JSONObject()
                .put("version", META_VERSION)
                .put("id", group.id)
                .put("createdAt", group.createdAt)
                .put("items", org.json.JSONArray(arr))
            File(group.dir, META).writeText(o.toString())
        }.onFailure {
            Log.w(TAG, "写 meta 失败：${it.message}")
        }
    }

    /** 一次导入的结果：成功的文件名 + 因重复被跳过的张数。 */
    internal data class ImportResult(val added: List<String>, val skipped: Int)

    /**
     * 导入若干张图。
     *
     * 同步阻塞，调用方放 IO 线程。任何一张失败只跳过那一张，不影响其他。
     *
     * ## 去重（2026-11-04 定案：**SHA-256 前 16 位，字节完全相同才算重复**）
     *
     * 历史：先单路 dHash（横向）⇒ 用户选 4 张不同的图被误判 2 张；改成双路 dHash
     * （横 + 纵，32 hex）⇒ 又出现「同一张图的不同调色版被判成一张」。
     *
     * 用户 2026-11-04 明确：**只有真真正正的重复（同一个文件）才算一张**，
     * 「同一张图的不同调色 / 不同时段」理应作为多张图共存。结构哈希（dHash）只看相对明暗、
     * 对整体调色免疫，所以它**永远做不对**这件事 ⇒ 整套退役。
     *
     * 现在用**内容哈希**：SHA-256 流式读一遍取前 16 位 hex（与空间壁纸
     * `SpatialDepth.cacheKey` 同一口径）。同一个文件选两次 ⇒ 相同；调色版 ⇒ 不同。
     *
     * **已知代价（用户 2026-11-04 明确接受）**：微信转发 / 相册导出后重新保存的同一张图，
     * 字节变了 ⇒ **不再判重**。取舍：槽位只有 2~4 个，「错拦」让用户想放的两张只能留一张
     * （更疼）；「漏拦」只是多一张，手动删一下。
     *
     * 指纹直接**编进文件名**，不额外维护映射表 —— 文件被删掉，指纹自然消失，
     * 不会出现「记录说见过、图却已经被删了」的脏数据。
     * ⚠️ 旧格式（32 hex dHash）长度不同 ⇒ [hashOf] 返回 null ⇒ **不参与判重**；
     * 旧组直接清掉重建（用户 2026-11-04：缓存他会清）。
     *
     * ## `keep`
     *
     * 浮页是**事务式**的：移除一张只是从内存列表里拿掉，文件要到点 ✓ 才真删。不传 `keep` 的话，
     * 被移除那张的指纹还在磁盘上 ⇒ 再导入同一张会被判成重复（用户报的 bug）。
     * 调用方把**当前列表里还留着的文件名**传进来，参与判重的就只有真正还在的那些。
     */
    fun import(
        ctx: Context,
        group: RasterGroup,
        uris: List<Uri>,
        keep: Collection<String>? = null,
    ): ImportResult {
        group.dir.mkdirs()
        val keepSet = keep?.toSet()
        // 参与判重的指纹一律从**文件名**读（新格式才认得出，旧 32 hex 自然被排除）。
        val taken = group.dir.listFiles()
            ?.filter {
                it.isFile && it.name.startsWith(PREFIX) && (keepSet == null || it.name in keepSet)
            }
            ?.mapNotNull { hashOf(it.name) }
            ?.toMutableList()
            ?: mutableListOf()
        val added = ArrayList<String>(uris.size)
        var skipped = 0
        for (uri in uris) {
            // 内容哈希：流式读整份字节，不解码（比 dHash 那条路还快，且判据是确定的）。
            val fp = sha16(ctx, uri)
            if (fp == null) {
                skipped++
                continue
            }
            // **精确相等**，没有容差。同一个文件 ⇒ 相同；调色 / 重编码 ⇒ 不同。
            if (fp in taken) {
                skipped++
                continue
            }
            val ext = extensionOf(ctx, uri)
            val name = "$PREFIX${System.currentTimeMillis()}_$fp.$ext"
            // ⚠️ **原图字节原样落盘**（2026-11-04 用户要求）：不解码、不缩放、不重编码。
            //    景深主体要拿它去喂 AICR 抠图，任何一次 JPEG 重编码都会让主体边缘变糊、
            //    细发丝直接丢掉。显示侧本来就走 `inSampleSize` 采样，存多大都不影响性能。
            val ok = copyTo(ctx, uri, File(group.dir, name))
            if (ok) {
                added += name
                taken += fp
            } else {
                skipped++
            }
        }
        return ImportResult(added, skipped)
    }

    /** 把 uri 的**原始字节**复制进组目录（不解码、不重编码）。 */
    private fun copyTo(ctx: Context, uri: Uri, out: File): Boolean = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } != null
    }.onFailure { Log.w(TAG, "复制失败 $uri：${it.message}") }.getOrDefault(false)

    /** 从 mime 推扩展名（HEIC / PNG 原样保留）；拿不到就当 jpg。 */
    private fun extensionOf(ctx: Context, uri: Uri): String {
        val sub = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
            ?.substringAfterLast('/')
            ?.lowercase()
        return when (sub) {
            "jpeg" -> "jpg"
            "jpg", "png", "webp", "heic", "heif", "avif", "bmp", "gif" -> sub
            else -> "jpg"
        }
    }

    /** 删除一张图（连它的遮罩一起）。 */
    fun deleteItem(group: RasterGroup, name: String) {
        runCatching { File(group.dir, name).delete() }
        runCatching { group.subjectFileOf(name).delete() }
        // 旧格式的灰度遮罩（v1.7.8 之前）顺手清掉，留着也没用。
        runCatching { File(group.dir, "$name.mask.png").delete() }
    }

    /** 删除整个组（目录连同里面的图）。 */
    fun deleteGroup(group: RasterGroup) {
        runCatching { group.dir.deleteRecursively() }
            .onFailure { Log.w(TAG, "删组失败：${it.message}") }
    }

    /** 删除**所有**组（整个 `files/raster/` 连根拔掉，含遮罩缓存），返回删掉的组数。 */
    fun deleteAll(ctx: Context): Int {
        val n = runCatching { root(ctx).listFiles()?.count { it.isDirectory } ?: 0 }.getOrDefault(0)
        runCatching { root(ctx).deleteRecursively() }
            .onFailure { Log.w(TAG, "清空失败：${it.message}") }
        return n
    }

    /**
     * 存一张**主体抠像**（RGBA PNG，覆盖写）。
     *
     * 存 PNG 不存 JPEG：alpha 通道是轮廓本身，JPEG 会把它糊掉。
     */
    /**
     * 用户手动导入的「遮罩 / 主体」是什么类型（2026-11-04）。
     *
     * 渲染侧只认 `<name>.subject.png`（RGBA 主体），所以遮罩要先合成 —— 见 [applySubject]。
     */
    enum class SubjectKind {
        /** RGBA 抠像主体：有透明区域、也有颜色 ⇒ 直接缩放存。 */
        SUBJECT,

        /** 灰度遮罩（无颜色）：合成成主体。可能带 alpha（见 [maskOf]）。 */
        MASK,

        /** 既不是灰度、也没有透明区域 ⇒ 无法判定，拒绝。 */
        INVALID,
    }

    /** 导入前的探测结果：源尺寸 / 原图尺寸 / 判定类型。 */
    data class SubjectProbe(
        val srcW: Int,
        val srcH: Int,
        val dstW: Int,
        val dstH: Int,
        val kind: SubjectKind,
    ) {
        /** 尺寸是否与原图一致（不一致要弹「尺寸与壁纸不一致」）。 */
        val sizeMatch: Boolean get() = srcW == dstW && srcH == dstH
    }

    /**
     * 探测用户选的那张图是**遮罩**还是**主体**（只读，不落盘；IO 线程调用）。
     *
     * 判定规则（2026-11-04 用户定，两条一起用）：
     * * **有透明区域**（`alpha < 250` 的像素 > 0.1%）⇒ 已经是抠好的主体；
     * * **有颜色**（`max(|r−g|,|g−b|,|r−b|) > 8` 的像素 > 1%）⇒ 不是灰度。
     *
     * | 有 alpha | 有颜色 | 结论 |
     * |---|---|---|
     * | ✅ | ✅ | [SubjectKind.SUBJECT]（直接存） |
     * | ✅ | ❌ | [SubjectKind.MASK]（灰度存成了 RGBA，用灰度/alpha 当遮罩） |
     * | ❌ | ❌ | [SubjectKind.MASK]（灰度遮罩） |
     * | ❌ | ✅ | [SubjectKind.INVALID] ⇒ 提示「无法检测出该图片为遮罩或主体」 |
     *
     * 采样短边 [PROBE_SHORT_SIDE]（不读全图）；任一读取失败返回 null。
     */
    fun probeSubject(ctx: Context, group: RasterGroup, name: String, uri: Uri): SubjectProbe? =
        runCatching {
            val orig = File(group.dir, name)
            if (!orig.isFile) return null
            val dstW = boundsOf(orig)?.first ?: return null
            val dstH = boundsOf(orig)?.second ?: return null
            val srcBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, srcBounds)
            }
            val srcW = srcBounds.outWidth
            val srcH = srcBounds.outHeight
            if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) return null

            val sample = decodeSampled(ctx, uri, PROBE_SHORT_SIDE) ?: return null
            val kind = classify(sample)
            sample.recycle()
            SubjectProbe(srcW, srcH, dstW, dstH, kind)
        }.onFailure { Log.w(TAG, "探测遮罩失败：${it.message}") }.getOrNull()

    /**
     * 把用户选的图**存成主体**（`<name>.subject.png`，IO 线程调用）。
     *
     * * [SubjectKind.SUBJECT]：缩放到与原图同比例的工作尺寸后直接存；
     * * [SubjectKind.MASK]：先定遮罩来源（灰度有层次就用灰度，否则用 alpha），
     *   再走 [MaskComposer.compose] 合成 —— 与**锁屏遮罩替换是同一套**（黑→透明、白→保留）。
     *
     * 工作尺寸 = 短边 [WORK_SHORT_SIDE]（≥ 屏幕短边，够 1:1 渲染；不把 2802×4830 整幅解进内存）。
     * 宽高比与原图一致 ⇒ 渲染侧按同一 cover 参数裁到屏幕，不会错位。
     */
    fun applySubject(
        ctx: Context,
        group: RasterGroup,
        name: String,
        uri: Uri,
        kind: SubjectKind,
    ): Boolean = runCatching {
        if (kind == SubjectKind.INVALID) return false
        val orig = File(group.dir, name)
        if (!orig.isFile) return false
        val wallpaper = decodeSampled(orig, WORK_SHORT_SIDE) ?: return false
        val chosen = decodeSampled(ctx, uri, WORK_SHORT_SIDE) ?: run {
            wallpaper.recycle()
            return false
        }
        val out = when (kind) {
            SubjectKind.SUBJECT -> {
                val scaled = Bitmap.createScaledBitmap(
                    chosen, wallpaper.width, wallpaper.height, true,
                )
                if (scaled !== chosen) chosen.recycle()
                scaled
            }
            else -> {
                val mask = maskOf(chosen)
                val composed = MaskComposer.compose(wallpaper, mask)
                if (mask !== chosen) mask.recycle()
                chosen.recycle()
                composed
            }
        }
        wallpaper.recycle()
        val ok = saveSubject(group, name, out)
        out.recycle()
        ok
    }.onFailure { Log.w(TAG, "导入遮罩失败：${it.message}") }.getOrDefault(false)

    /** 读文件尺寸（只读 bounds）。 */
    private fun boundsOf(f: File): Pair<Int, Int>? = runCatching {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) null else b.outWidth to b.outHeight
    }.getOrNull()

    /** 采样分类（见 [probeSubject] 的规则表）。 */
    private fun classify(bmp: Bitmap): SubjectKind {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return SubjectKind.INVALID
        val stepX = (w / 256).coerceAtLeast(1)
        val stepY = (h / 256).coerceAtLeast(1)
        var total = 0
        var alphaCount = 0
        var colorCount = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val a = (c ushr 24) and 0xFF
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (a < 250) alphaCount++
                if (abs(r - g) > 8 || abs(g - b) > 8 || abs(r - b) > 8) colorCount++
                total++
                x += stepX
            }
            y += stepY
        }
        if (total == 0) return SubjectKind.INVALID
        val hasAlpha = alphaCount > total * 0.001f
        val hasColor = colorCount > total * 0.01f
        return when {
            hasAlpha && hasColor -> SubjectKind.SUBJECT
            hasAlpha -> SubjectKind.MASK
            hasColor -> SubjectKind.INVALID
            else -> SubjectKind.MASK
        }
    }

    /**
     * 从一张「灰度/带 alpha」的图里取出遮罩。
     *
     * 优先用**灰度**（有层次时）；灰度几乎是纯白/纯黑、但 alpha 有形状时改用 alpha ——
     * 后者是「主体导出成纯白 RGB + alpha」的常见格式，用灰度会得到全白 ⇒ 主体全覆盖。
     */
    private fun maskOf(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        var minG = 255
        var maxG = 0
        val stepX = (w / 128).coerceAtLeast(1)
        val stepY = (h / 128).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val g = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                x += stepX
            }
            y += stepY
        }
        if (maxG - minG > 8) return bmp

        // 灰度没层次 ⇒ 用 alpha 当遮罩：白 = 不透明（保留），黑 = 透明。
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = (px[i] ushr 24) and 0xFF
            px[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 从 uri 解码，短边超过 [maxShort] 时按 2 的幂粗降（探测与遮罩导入共用）。 */
    private fun decodeSampled(ctx: Context, uri: Uri, maxShort: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (ctx.contentResolver.openInputStream(uri) ?: return null).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        var sample = 1
        while (min(sw, sh) / (sample * 2) >= maxShort) sample *= 2
        (ctx.contentResolver.openInputStream(uri) ?: return null).use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
    }.getOrNull()

    fun saveSubject(group: RasterGroup, name: String, subject: Bitmap): Boolean = runCatching {
        group.subjectFileOf(name).outputStream()
            .use { subject.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrElse {
        Log.w(TAG, "存主体失败：${it.message}")
        false
    }

    /** 读一张主体抠像；没有返回 null。 */
    fun loadSubject(group: RasterGroup, name: String): Bitmap? = runCatching {
        val f = group.subjectFileOf(name)
        if (!f.isFile) null else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    /** 删一张主体抠像（连旧格式的灰度遮罩一起）。返回是否真的删掉了东西。 */
    fun deleteSubject(group: RasterGroup, name: String): Boolean {
        val a = runCatching { group.subjectFileOf(name).delete() }.getOrDefault(false)
        val b = runCatching { File(group.dir, "$name.mask.png").delete() }.getOrDefault(false)
        return a || b
    }

    /** 卡片封面（按短边 [CARD_SHORT_SIDE] 采样）。 */
    /**
     * 卡片封面。
     *
     * @param src 归一化源矩形（0..1）。非空时**按它裁一刀**再返回（空间壁纸的「显示区域」，
     *   2026-11-05）；null = 整幅。裁失败就退回整幅，绝不返回 null（否则卡片空白）。
     */
    fun decodeCard(file: File, src: RectF? = null): Bitmap? {
        val full = decodeSampled(file, CARD_SHORT_SIDE) ?: return null
        if (src == null) return full
        return runCatching {
            val w = full.width
            val h = full.height
            val l = (src.left * w).roundToInt().coerceIn(0, w - 1)
            val t = (src.top * h).roundToInt().coerceIn(0, h - 1)
            val r = (src.right * w).roundToInt().coerceIn(l + 1, w)
            val b = (src.bottom * h).roundToInt().coerceIn(t + 1, h)
            val outW = r - l
            val outH = b - t
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(
                full,
                android.graphics.Rect(l, t, r, b),
                android.graphics.Rect(0, 0, outW, outH),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
            full.recycle()
            out
        }.getOrElse {
            Log.w(TAG, "裁封面失败：${it.message}")
            full
        }
    }

    /**
     * 兜底用：`AicrVision.segmentFile()` 直送原图失败时，解一份够大的位图再走"PNG 中转"。
     * 短边采到 1200（服务自己会缩到长边 1600），内存约 12MB，不会 OOM。
     */
    fun decodeForMask(file: File): Bitmap? = decodeSampled(file, 1200)

    /** 浮页行的小缩略图（按短边 [ROW_SHORT_SIDE] 采样）。 */
    fun decodeRow(file: File): Bitmap? = decodeSampled(file, ROW_SHORT_SIDE)

    /**
     * 解码并按短边 [maxShort] 做 `inSampleSize` 采样。
     *
     * 只采样、不做精确缩放：`inSampleSize` 只认 2 的幂，解出来的尺寸会在目标附近浮动，
     * 而显示侧是 `ContentScale.Crop`，多几十像素比多一次重采样更划算。
     */
    private fun decodeSampled(file: File, maxShort: Int): Bitmap? = runCatching {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        var sample = 1
        while (min(sw, sh) / (sample * 2) >= maxShort) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.onFailure {
        Log.w(TAG, "解码失败 ${file.name}：${it.message}")
    }.getOrNull()

    // ------------------------------------------------------------------ 内部

    /**
     * **内容指纹**：SHA-256 流式读整份字节，取前 [HASH_HEX]（16）位 hex（64 bit）。
     *
     * 与空间壁纸 `SpatialDepth.cacheKey` 同一口径（那边取前 16 位做缓存目录名）。
     * 判据是**字节级完全相同**：同一张图换一次调色 / 重新导出，字节就变了 ⇒ 指纹不同
     * ⇒ 允许作为两张图共存（用户 2026-11-04 明确要求）。
     *
     * 读不到 / 打开失败返回 null（调用方按"跳过这一张"处理）。
     */
    private fun sha16(ctx: Context, uri: Uri): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        } ?: return null
        val hex = StringBuilder(64)
        for (b in md.digest()) hex.append("%02x".format(b))
        hex.substring(0, HASH_HEX)
    }.onFailure {
        Log.w(TAG, "算指纹失败：${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 从文件名里取指纹。
     *
     * 新格式（16 hex）才认；**旧 32 hex dHash 返回 null ⇒ 不参与判重**
     * （用户 2026-11-04 明确旧缓存他会自己清掉重建）。
     */
    private fun hashOf(name: String): String? {
        val h = name.removePrefix(PREFIX).substringAfter('_', "").substringBefore('.')
        return h.takeIf { it.length == HASH_HEX && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

}
