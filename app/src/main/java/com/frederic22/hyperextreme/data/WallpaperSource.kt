package com.frederic22.hyperextreme.data

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 「当前壁纸」的定位。关键是把**两个坐标空间**分清楚，否则锚点和裁切都会错。
 *
 * | 空间 | 是哪一份 | 尺寸 | 谁在用 |
 * | --- | --- | --- | --- |
 * | **原图空间** | `templates/current/wallpaper`（编辑器拿它当裁剪画布的那张原图） | 原图像素 | `subject` / `subject_mask` / `WallpaperPositionInfo` 的四个边 |
 * | **显示空间** | 按裁切矩形裁过、贴到屏幕上的那份 | = 裁切矩形 | `crop_subject` / `cropSource` |
 *
 * 证据（反编译 `com.miui.aod`）：
 *
 * * `GestureManager.getPosition(bitmap)` 先要求 `bitmap.width == srcDrawAble.getIntrinsicWidth()`
 *   （即传进来的就是原图），然后 `left = -mappedRect.left / scaleY`、`right - left = screenWidth / scaleY`
 *   —— 返回的是**源图像素**里的可见窗口，也就是原图坐标；
 * * `TemplateApplier` 把 `lockOriginWallpaper` 交给 `applyEditPageTemplate`，后者用
 *   `cropBitmap(subject, positionInfo, false)` 生成 `crop_subject`；
 * * `<filesDir>/templates/current/wallpaper` ← `wallpaperInfo.source` 的拷贝（原图）；
 *   `wallpaper_cropped` ← `copyCroppedWallpaper(裁过的 bitmap)`（显示空间）；`wallpaper_scaled.jpg` 同理。
 *
 * 所以锚点**只能是原图空间那一份**。拿显示空间的那份去比（或去和遮罩做「壁纸 ∩ 遮罩」合成），
 * 遮罩和它就不在同一个坐标系里，`crop_subject` 跟着一起错 —— 用户实测踩到的就是这个：
 * 被当成锚点的是显示空间那份，而它的尺寸会随壁纸缩放/移动变化（`screenWidth / scale`）。
 */
object WallpaperSource {

    /**
     * 原图空间的文件名候选，按优先级排。
     *
     * `wallpaper` 就是编辑器当前在用的原图（用户确认过：原图 = `current/wallpaper`），
     * 必须排第一；`wallpaper_copied` 是 `originResourcePath` 的逐字节拷贝，同一张原图，
     * 只在 `wallpaper` 被清掉时顶上。
     */
    val sourceTemplateNames = listOf("wallpaper", "wallpaper_copied")

    /**
     * 探测脚本里**唯一**允许当锚点的文件名 —— 用户要求："只读 `templates/current/wallpaper`"。
     *
     * 脚本那条路不再碰 `wallpaper_copied` / 配置原图 / 显示空间候选（真机上它们会读到
     * 屏幕尺度的预览图或别的文件）；进程内的 [sourceCandidates] 仍保留 `wallpaper_copied` 兜底，
     * 那是 hook 在宿主进程里合成主体时用的旧路径，与探测脚本无关。
     */
    val anchorTemplateNames = listOf("wallpaper")

    /** 已应用的锁屏壁纸路径 —— 只作为 `crop_subject` 的对齐基准，绝不参与锚点挑选。 */
    fun appliedLockPath(userId: Int): String =
        displayPaths(userId).first { it.endsWith("lock_wallpaper.jpg") }

    /**
     * 显示空间的文件名 —— **不能**当对齐锚点，也不要拿去和遮罩合成。
     *
     * `wallpaper_cropped` / `wallpaper_scaled.jpg` 的尺寸 = 原图里那块可见窗口
     * （`screenWidth / scale`），壁纸一缩放/移动它就变，所以拿它当锚点必然错。
     */
    val displayTemplateNames = listOf("wallpaper_cropped", "wallpaper_scaled.jpg", "wallpaper_home")

    /**
     * 显示空间的绝对路径候选（已应用的壁纸）。
     *
     * ⚠️ 真实路径带 `image/` 段（`MiuiWallpaperPathUtils`：`theme_magic/users/<uid>/wallpaper/image/lock_wallpaper.jpg`），
     * 旧代码漏了这一段，等于这几个候选一直是无效的。
     */
    fun displayPaths(userId: Int): List<String> = listOf(
        "/data/system/theme_magic/users/$userId/wallpaper/image/lock_wallpaper.jpg",
        "/data/system/theme_magic/users/$userId/wallpaper/image/lock_wallpaper.png",
        "/data/system/theme_magic/users/$userId/wallpaper/image/home_wallpaper.jpg",
        "/data/system/users/$userId/wallpaper_lock",
        "/data/system/users/$userId/wallpaper_lock_orig",
        "/data/system/users/$userId/wallpaper",
    )

    /** 指纹的补充来源（切壁纸一定会被改写）。 */
    fun fingerprintExtras(userId: Int): List<String> = listOf(
        "/data/system/theme_magic/users/$userId/wallpaper/data/lock.xml",
        "/data/system/theme_magic/users/$userId/wallpaper/data/home.xml",
    )

    /** 小于这个的多半是缩略图 / 配置文件，不是壁纸。 */
    private const val MIN_BYTES = 50_000L

    fun currentUserId(): Int = Process.myUid() / 100_000

    /** 一个候选：文件 + 日志用的标签 + 它属于哪个空间。 */
    data class Candidate(val file: File, val tag: String, val sourceSpace: Boolean) {
        val path: String get() = file.absolutePath
    }

    private fun usable(file: File): Boolean = runCatching {
        file.isFile && file.length() >= MIN_BYTES
    }.getOrDefault(false)

    /** 模板配置里记着的原图路径（`source` / `originResourcePath` / `imagePath`）。 */
    fun configPaths(context: Context): List<String> =
        WallpaperVersion.originalPathsOf(WallpaperVersion.readTemplateInfo(context))

    /** 模板目录。hook 进程（com.miui.aod）读得到自己那份；模块 app 进程这里通常是空的。 */
    private fun templateRoots(context: Context): List<File> =
        listOf(File(context.filesDir, "templates/current"))

    /**
     * 原图空间的候选，按优先级：
     * `templates/current/wallpaper` → `wallpaper_copied` → 配置里的原图路径。
     *
     * 配置路径放最后是因为它可能是**原始文件**（分辨率可能和编辑器实际用的那份不同，编辑器可能缩过），
     * 只在模板目录里没有原图时才用它。
     */
    fun sourceCandidates(
        context: Context,
        configPaths: List<String> = configPaths(context),
    ): List<Candidate> = buildList {
        templateRoots(context).forEach { dir ->
            sourceTemplateNames.forEach { name -> add(Candidate(File(dir, name), "模板/$name", true)) }
        }
        configPaths.forEach { path -> add(Candidate(File(path), "配置原图", true)) }
    }

    /** 显示空间的候选：模板目录里的 crop/scaled，以及已应用的那几份。 */
    fun displayCandidates(context: Context, userId: Int = currentUserId()): List<Candidate> = buildList {
        templateRoots(context).forEach { dir ->
            displayTemplateNames.forEach { name -> add(Candidate(File(dir, name), "模板/$name", false)) }
        }
        displayPaths(userId).forEach { add(Candidate(File(it), "已应用", false)) }
    }

    /** 原图空间那份（**只有它**能当对齐锚点）；找不到返回 null。 */
    fun resolveSourceSpace(context: Context, configPaths: List<String> = configPaths(context)): File? =
        sourceCandidates(context, configPaths).firstOrNull { usable(it.file) }?.file

    /**
     * 所有读得到的候选（原图空间优先），带空间标记。
     *
     * 只给"必须有张图才能干活"的场景用（合成主体）。调用方**必须**看 [Candidate.sourceSpace]：
     * 显示空间那份和遮罩不在同一坐标系，拿它合成会错位。
     */
    fun resolveAny(context: Context, configPaths: List<String> = configPaths(context)): Candidate? =
        (sourceCandidates(context, configPaths) + displayCandidates(context)).firstOrNull { usable(it.file) }

    /**
     * 已应用的锁屏壁纸文件 —— 一级「我的搭配」当前卡片的贴图基准。
     *
     * 卡片把这张壁纸和 `crop_subject` 当一对叠着画，位置由按**壁纸贴图**算出的 GL 矩阵给出，
     * 所以 `crop_subject` 的像素尺寸要跟它一致（见 [MaskComposer.fitTo]）。
     */
    fun appliedLockWallpaper(context: Context, userId: Int = currentUserId()): File? =
        displayPaths(userId)
            .filter { it.endsWith("lock_wallpaper.jpg") }
            .map(::File)
            .firstOrNull { usable(it) }

    /**
     * 「原图」的像素尺寸 —— 导入替换图时的对齐锚点。
     *
     * 只取本进程能直接读到的原图空间候选。**拿不到就返回 null**，调用方据此跳过检查：
     * 宁可不说，也不能退回显示空间乱报（那正是之前"提示的分辨率随壁纸缩放变化"的原因）。
     */
    fun currentSize(context: Context): Pair<Int, Int>? {
        resolveSourceSpace(context)?.let { file -> MaskStore.readSize(file)?.let { return it } }
        return null
    }

    /**
     * 候选能否装下裁切矩形。
     *
     * 判据来自 `GestureManager.getPosition()`：矩形是**原图坐标**里的可见窗口，所以原图必然满足
     * `right ≤ W` 且 `bottom ≤ H`；显示空间那份是窗口本身（或更小），必然装不下 —— 这条正好能把
     * 两者分开。容 2px 取整误差。
     */
    fun fits(size: Pair<Int, Int>, position: WallpaperPosition, tolerance: Int = 2): Boolean =
        size.first + tolerance >= position.right.toDouble().let { Math.ceil(it).toInt() } &&
            size.second + tolerance >= position.bottom.toDouble().let { Math.ceil(it).toInt() }

    /**
     * 在 [sizes]（与候选一一对应）里挑原图，返回下标；空列表返回 -1。
     *
     * 1. 没有裁切信息 → 取第一个（列表本身就是优先级）；
     * 2. 有裁切信息 → 取第一个**装得下**矩形的；一个都装不下也取第一个，让调用方记一条日志
     *    （那种情况说明列表里的候选都不像"编辑器用的那份原图"）。
     *
     * 注意**不要**在这里按"像素最多"挑：配置里的原图路径可能是个几千万像素的相册原图，
     * 那会把真正的编辑器原图挤掉。
     */
    fun chooseSource(sizes: List<Pair<Int, Int>>, position: WallpaperPosition?): Int {
        if (sizes.isEmpty()) return -1
        if (position == null || !position.valid) return 0
        sizes.indexOfFirst { fits(it, position) }.let { if (it >= 0) return it }
        return 0
    }
}
