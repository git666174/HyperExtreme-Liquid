package com.frederic22.hyperextreme.data

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 「当前壁纸是哪一张」的判据 —— 用来判断用户有没有换壁纸：
 * 替换链的保护兜底（解除保护）与视差的「壁纸改变后自动关闭替换」都靠它。
 *
 * ## 为什么换掉了旧判据（壁纸文件 MD5）
 *
 * 旧实现是：注入时挑一个壁纸文件记下 MD5，之后哈希**同一个文件**比对。实测失效，因为它假定了
 * "挑中的那个文件会跟着换壁纸变"——真机上很可能不成立（编辑器换图时改写的是模板目录里另外几个
 * 文件，或者用户是在系统设置里换的壁纸，模板目录根本还没动）。
 *
 * ## 现在的判据
 *
 * 编辑器每次应用模板都会走 `TemplateApiImpl.writeTemplateInfo`，把整份配置写进
 * `Settings.Secure.constant_template_editor_info`。这份 JSON 里 `lockscreenInfo.wallpaperInfo`
 * 就记着"这张壁纸是谁"：
 *
 * ```
 * { "lockscreenInfo": { "wallpaperInfo": { "source": "...", "imagePath": "...",
 *                                          "sha1": "...", "cropSubject": "..." } } }
 * ```
 *
 * * **主判据 `tpl:`**：上面前几个字段拼起来算摘要。换一张图 → 字段必变；只是重新点一次应用
 *   （哪怕重新抠了主体、挪了位置）→ `subject` / `cropSubject` / `cropSource` 这些**不参与**，
 *   所以判据不变。这也是为什么这里不把 `cropSource` 算进去：拖一下位置它就可能变，但壁纸没换。
 * * **兜底 `files:`**：读不到配置（或字段全空）时，退回"把所有能找到的壁纸文件一起算摘要"。
 *   只有 hook 进程（com.miui.aod）读得到那些文件，模块 app 读不到，所以这条在主进程里通常算不出来。
 * * **都算不出来**：返回空值，调用方写 [MaskPrefs.FINGERPRINT_WEAK]。
 *
 * 判据里带 `tpl:` / `files:` 前缀，就是为了让"判据种类变了"和"壁纸变了"能分开 ——
 * 前者只更新指纹，不解除保护。
 */
object WallpaperVersion {

    /** 换成一张图时**一定**会变的字段。 */
    private val IDENTITY_KEYS = listOf(
        "source",
        "imagePath",
        "originResourcePath",
        "sha1",
        "videoName",
    )

    /**
     * 配置里记着"这张壁纸的原图文件在哪"的字段，按可信度排序。
     *
     * * `source`：编辑器把它拷成 `<filesDir>/templates/current/wallpaper`（原图空间那一份），最可信；
     * * `originResourcePath`：原图在相册/主题里的绝对路径（`copyOriginWallpaper` 逐字节拷成 `wallpaper_copied`）；
     * * `imagePath`：兜底，某些入口会把原图路径放在它这里。
     *
     * ⚠️ 这些是**原图**，和 `crop` 系列（`cropSource` / `cropSubject`）不是一个空间：后者已被裁到屏幕比例。
     */
    private val ORIGINAL_PATH_KEYS = listOf("source", "originResourcePath", "imagePath")

    const val TEMPLATE_KEY = "constant_template_editor_info"

    const val PREFIX_TEMPLATE = "tpl:"
    const val PREFIX_FILES = "files:"

    const val DETAIL_TEMPLATE = "编辑器模板配置"
    const val DETAIL_FILES = "壁纸文件"
    const val DETAIL_NONE = "（算不出来）"

    /** 小于这个的多半是缩略图 / 配置文件，不是壁纸。与 [WallpaperSource] 保持一致。 */
    private const val MIN_BYTES = 50_000L

    private const val MAX_FILES = 8

    data class Version(
        /** 存进 prefs 的指纹值；空串 = 这次算不出来。 */
        val value: String,
        /** 判据来源，写进日志与界面。 */
        val detail: String,
        /** 原始身份串（`tpl:` 判据时），日志里直接可见，便于对账。 */
        val identity: String,
    ) {
        val usable: Boolean get() = value.isNotEmpty()
    }

    /** 判据种类（前缀）。种类不同不能直接比大小 —— 那只是"换了尺子"，不是"壁纸变了"。 */
    fun kindOf(fingerprint: String): String = when {
        fingerprint.startsWith(PREFIX_TEMPLATE) -> PREFIX_TEMPLATE
        fingerprint.startsWith(PREFIX_FILES) -> PREFIX_FILES
        else -> ""
    }

    /** 读 `Settings.Secure.constant_template_editor_info`；读不到返回 null。 */
    fun readTemplateInfo(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, TEMPLATE_KEY)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 从模板配置里抽出"这张壁纸是谁"。字段全空返回空串。
     *
     * 字段名与 SystemUI / 编辑器的 `WallpaperInfo` 一致，JSON 结构见类注释。
     */
    fun identityOf(templateInfo: String?): String {
        if (templateInfo.isNullOrBlank()) return ""
        val root = runCatching { JSONObject(templateInfo) }.getOrNull() ?: return ""
        val lock = root.optJSONObject("lockscreenInfo") ?: root
        val wallpaper = lock.optJSONObject("wallpaperInfo") ?: lock
        return IDENTITY_KEYS.mapNotNull { key ->
            wallpaper.optString(key, "").takeIf { it.isNotBlank() }?.let { "$key=$it" }
        }.joinToString("|")
    }

    /**
     * 模板配置里"壁纸原图"的候选绝对路径（原图空间）。
     *
     * 为什么需要它：`<filesDir>/templates/current/wallpaper` 才是编辑器用的那份原图空间壁纸，
     * 对齐锚点必须以它为准。但它**可能被清掉**（`tryClearLocalInvalidWallpaper`、用户换过壁纸、
     * 或者这台机器压根没跑过编辑器），这时配置里记的 `source` / `originResourcePath` 是同一张图的
     * 原始文件，可以顶上来 —— 而且是原图空间，不是被裁到屏幕比例的那份。
     *
     * 只收绝对路径；`content://` 之类留给上层的 contentResolver 处理，这里拿不到就跳过。
     */
    fun originalPathsOf(templateInfo: String?): List<String> {
        if (templateInfo.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(templateInfo) }.getOrNull() ?: return emptyList()
        val lock = root.optJSONObject("lockscreenInfo") ?: root
        val wallpaper = lock.optJSONObject("wallpaperInfo") ?: lock
        return ORIGINAL_PATH_KEYS
            .mapNotNull { key -> wallpaper.optString(key, "").takeIf { it.startsWith("/") } }
            .distinct()
    }

    /**
     * 壁纸在源图里的裁切矩形（编辑器 `WallpaperPositionInfo` 的四个边）。
     *
     * 为什么需要它：`crop_subject` 不是"subject 的副本"，而是**把 subject 按这个矩形裁一刀**之后的
     * 显示空间版本 ——
     *
     * ```java
     * // TemplateApiImpl.applyEditPageTemplate（生成 crop_subject 那一步）
     * Bitmap bitmapCropBitmap = (positionInfo == null || !isDepth(magicType)) ? null
     *                           : cropBitmap(bitmap, positionInfo, false);
     * wallpaperInfo.setCropSubject(...);
     * ```
     *
     * 而且 SystemUI 侧是**无矩阵**绘制的：`BaseTinyTemplateView` 把 `cropSource`（裁过的壁纸）和
     * `cropSubject`（裁过的主体）都塞进全屏 `ImageView` + `CENTER_CROP`，没有额外变换。
     * 所以模块要是把未裁的 subject 写进 crop_subject，壁纸一旦被缩放/移动，抠像层就对不齐
     * —— 用户实测的现象就是这个。
     */
    fun positionOf(templateInfo: String?): WallpaperPosition? {
        if (templateInfo.isNullOrBlank()) return null
        val root = runCatching { JSONObject(templateInfo) }.getOrNull() ?: return null
        val lock = root.optJSONObject("lockscreenInfo") ?: root
        val wallpaper = lock.optJSONObject("wallpaperInfo") ?: lock
        val position = wallpaper.optJSONObject("positionInfo") ?: return null
        val left = position.optDouble("left", Double.NaN)
        val top = position.optDouble("top", Double.NaN)
        val right = position.optDouble("right", Double.NaN)
        val bottom = position.optDouble("bottom", Double.NaN)
        if (left.isNaN() || top.isNaN() || right.isNaN() || bottom.isNaN()) return null
        return WallpaperPosition(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /** 读 Settings 算指纹（模块 app 与 hook 进程都能用）。 */
    fun compute(context: Context): Version = computeFrom(context, readTemplateInfo(context))

    /**
     * 用给定的模板配置串算指纹。
     *
     * hook 侧在 `writeTemplateInfo` 里直接拿参数算，省掉一次 Settings 往返，也避免写入尚未可见的竞态。
     */
    fun computeFrom(context: Context, templateInfo: String?): Version {
        val identity = identityOf(templateInfo)
        if (identity.isNotEmpty()) {
            return Version(PREFIX_TEMPLATE + sha1(identity), DETAIL_TEMPLATE, identity)
        }
        val files = filesDigest(context)
        if (files != null) {
            return Version(PREFIX_FILES + files.digest, DETAIL_FILES, files.detail)
        }
        return Version("", DETAIL_NONE, "")
    }

    /** 指纹值（不含前缀的人类可读形式），日志用。 */
    fun short(fingerprint: String): String =
        if (fingerprint.length <= 12) fingerprint else fingerprint.take(12) + "…"

    // ---------------------------------------------------------------- 兜底判据

    private data class FilesDigest(val digest: String, val detail: String)

    /**
     * 把所有"可能是当前壁纸"的文件一起算摘要。
     *
     * 顺序固定（按路径排序）所以结果稳定；只要其中任何一份内容变了（换图会重新生成它们），摘要就变。
     */
    private fun filesDigest(context: Context): FilesDigest? {
        val candidates = buildList {
            WallpaperSource.sourceCandidates(context).forEach { add(it.file) }
            WallpaperSource.displayCandidates(context).forEach { add(it.file) }
        }
        val parts = mutableListOf<String>()
        candidates
            .filter { runCatching { it.isFile && it.length() >= MIN_BYTES }.getOrDefault(false) }
            .sortedBy { it.absolutePath }
            .take(MAX_FILES)
            .forEach { file ->
                parts += "${file.name}:${file.length()}:${md5Of(file) ?: return@forEach}"
            }
        if (parts.isEmpty()) return null
        return FilesDigest(sha1(parts.joinToString("\n")), "${parts.size} 个壁纸文件")
    }

    private fun md5Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun sha1(text: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).toHex()
    }.getOrDefault(text.hashCode().toString())

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * 壁纸在源图里的裁切矩形（对应编辑器的 `WallpaperPositionInfo`）。
 *
 * 坐标系是**源图像素**：`(left, top)` 是裁切区的左上角，`(right, bottom)` 是右下角。
 * 编辑器把源图按这个矩形裁一刀得到"显示空间"的那份（`cropSource` / `crop_subject` 都在这个空间里）。
 *
 * ⚠️ 类里还有个 `matrixArr`（缩放矩阵），但系统实际用的是这四个边：生成 crop_subject 走的是
 * `BitmapUtil.cropBitmap(bitmap, left, right, top, bottom)`，纯矩形裁切。
 */
data class WallpaperPosition(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val valid: Boolean get() = right > left && bottom > top

    override fun toString(): String =
        "(${left.toInt()},${top.toInt()})-(${right.toInt()},${bottom.toInt()})"
}
