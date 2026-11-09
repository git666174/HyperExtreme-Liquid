package com.frederic22.hyperextreme.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.frederic22.hyperextreme.hook.HookRuntime
import java.io.File

/**
 * 验证 hook 侧依赖的同一套通道：`ContentResolver.call` 读配置 + `openInputStream` 读图。
 *
 * 这两个通道通了，模块在 com.miui.aod 进程里就一定能拿到图；通道不通，装了 LSPosed 也不会生效。
 */
object SelfTest {

    fun run(context: Context): List<String> {
        val lines = mutableListOf<String>()

        val snapshot = runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_CONFIG,
                null,
                null,
            )
        }.getOrNull()

        if (snapshot == null) {
            lines += "配置通道：失败 —— provider 没能响应 call()"
        } else {
            val configured = MaskRole.entries.count {
                snapshot.getBoolean(MaskProvider.KEY_PRESENT_PREFIX + it.id, false)
            }
            // `present_*` 现在认的是**注入快照**（见 MaskProvider），所以这里数的是"保护中的角色"，
            // 用户刚选、还没点「立即替换」的候选不算。
            lines += "配置通道：正常（revision=${snapshot.getLong(MaskProvider.KEY_REVISION, 0L)}，" +
                "保护中 $configured 个角色）"
        }

        val protecting = MaskPrefs(context).protecting
        MaskRole.selectable.forEach { role ->
            // 参照物要跟 provider 的取图优先级一致：**保护期里它给的是注入快照**（见 MaskProvider.openFile），
            // 保护期外才是候选。不这么挑的话，保护期内一比对就会误报"与本地一致=否"。
            val expected = if (protecting && MaskStore.hasInjected(context, role)) {
                MaskStore.injectedFile(context, role)
            } else {
                MaskStore.file(context, role)
            }
            if (!expected.isFile) {
                lines += "${role.id}：未设置图片"
                return@forEach
            }
            val local = runCatching { BitmapFactory.decodeFile(expected.absolutePath) }.getOrNull()
            val remote = runCatching {
                context.contentResolver.openInputStream(MaskProvider.imageUri(role))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()

            when {
                remote == null -> lines += "${role.id}：provider 读取失败"
                local == null -> lines += "${role.id}：本地解码失败"
                else -> {
                    val sameSize = local.width == remote.width && local.height == remote.height
                    lines += "${role.id}：provider 返回 ${remote.width}×${remote.height}，" +
                        "带 alpha=${remote.hasAlpha()}，与本地一致=${if (sameSize) "是" else "否"}"
                }
            }
            local?.recycle()
            remote?.recycle()
        }

        lines += verifyCompose()
        lines += verifyCandidateAndSnapshot(context)
        lines += verifyWallpaperVersion(context)
        lines += verifyWallpaperSpace()
        lines += verifyDisplayAlignment()
        lines += verifyCropToDisplay()
        return lines
    }

    /**
     * 验证「把主体按壁纸的裁切矩形裁成显示空间」这一步（[MaskComposer.cropToDisplay] + 配置解析）。
     *
     * 这步做错的后果很具体：壁纸在编辑器里被缩放/移动之后，抠像层跟不上（实测过），
     * 而且只有真机锁屏上看得出来 —— 所以把规则固定成自检项。
     */
    private fun verifyCropToDisplay(): String = runCatching {
        val json = "{\"lockscreenInfo\":{\"wallpaperInfo\":{\"positionInfo\":" +
            "{\"left\":10,\"top\":20,\"right\":110,\"bottom\":220}}}}"
        val parsed = WallpaperVersion.positionOf(json)
        val parseOk = parsed != null && parsed.left == 10f && parsed.top == 20f &&
            parsed.right == 110f && parsed.bottom == 220f

        val source = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val cropped = MaskComposer.cropToDisplay(
            source,
            WallpaperPosition(left = 20f, top = 50f, right = 100f, bottom = 150f),
        )
        val cropOk = cropped != null && cropped.width == 80 && cropped.height == 100 &&
            Color.red(cropped.getPixel(0, 0)) == 255

        // 越界要夹住而不是抛（编辑器那套就是钳制）。
        val clamped = MaskComposer.cropToDisplay(
            source,
            WallpaperPosition(left = 100f, top = 150f, right = 999f, bottom = 999f),
        )
        val clampOk = clamped != null && clamped.width == 20 && clamped.height == 50

        // 无效矩形（right <= left）返回 null，调用方据此退回"原样写入"。
        val invalid = MaskComposer.cropToDisplay(
            source,
            WallpaperPosition(left = 10f, top = 10f, right = 5f, bottom = 50f),
        )
        val invalidOk = invalid == null
        val missingOk = WallpaperVersion.positionOf("{\"lockscreenInfo\":{}}") == null

        cropped?.recycle()
        clamped?.recycle()
        source.recycle()

        if (parseOk && cropOk && clampOk && invalidOk && missingOk) {
            "壁纸裁切：正常（解析 10,20→110,220；20,50→100,150 裁出 80×100；越界夹住；无效/缺失返回 null）"
        } else {
            "壁纸裁切：异常（解析=$parseOk 裁切=$cropOk 夹住=$clampOk 无效=$invalidOk 缺失=$missingOk）"
        }
    }.getOrElse { error -> "壁纸裁切：失败 ${error.message}" }

    /**
     * 验证换壁纸判据（[WallpaperVersion]）：
     *
     * * 换一张图 → 判据必须变（否则永远不自动解除保护）；
     * * 同一张图重新应用（换了裁切/重新抠主体）→ 判据必须**不变**（否则保护会被误解除）。
     *
     * 顺手把"当前这台机器上实际算出来的是什么判据"打出来 —— 真机上排查"为什么没自动解除"时，
     * 第一眼要看的就是这一行。
     */
    private fun verifyWallpaperVersion(context: Context): String {
        fun json(source: String, sha1: String, cropSource: String): String =
            "{\"lockscreenInfo\":{\"wallpaperInfo\":{" +
                "\"source\":\"$source\"," +
                "\"sha1\":\"$sha1\"," +
                "\"cropSource\":\"$cropSource\"," +
                "\"cropSubject\":\"/data/system/theme_magic/users/0/wallpaper/crop_subject\"}}}"

        val sameWallpaper = json("/sdcard/DCIM/a.jpg", "aaaa", "/data/…/a_crop_1.jpg")
        val sameWallpaperRecropped = json("/sdcard/DCIM/a.jpg", "aaaa", "/data/…/a_crop_2.jpg")
        val otherWallpaper = json("/sdcard/DCIM/b.jpg", "bbbb", "/data/…/a_crop_1.jpg")

        val first = WallpaperVersion.computeFrom(context, sameWallpaper)
        val recropped = WallpaperVersion.computeFrom(context, sameWallpaperRecropped)
        val other = WallpaperVersion.computeFrom(context, otherWallpaper)
        val live = WallpaperVersion.compute(context)

        val stable = first.usable && first.value == recropped.value
        val detectsChange = first.value != other.value
        val ignoresCrop =
            WallpaperVersion.identityOf(sameWallpaper) == WallpaperVersion.identityOf(sameWallpaperRecropped)

        val liveText = if (live.usable) {
            "${live.detail} / ${WallpaperVersion.short(live.value)}"
        } else {
            "当前这台机器算不出来（会写 weak，不自动解除）"
        }
        // 顺手把"导入图片时用的对齐锚点"也打出来 —— 真机上怀疑"提示的还是上一张图"或者
        // "锚点是不是被抓成了裁切那份"时，看这一行就够（两个尺寸并排，一眼能对比）。
        val anchor = WallpaperSource.currentSize(context) ?: prefsSize(context)
        val displaySize = MaskPrefs(context).displayWallpaperSize
        val anchorText = (if (anchor != null) {
            "锚点(原图)=${anchor.first}×${anchor.second}"
        } else {
            "锚点=抓不到（跳过检查）"
        }) + if (displaySize.isNotEmpty()) "；显示空间那份=$displaySize" else ""
        return if (stable && detectsChange && ignoresCrop) {
            "换壁纸判据：正常（换图必变、只重新裁切不变；本文档判据=$liveText；$anchorText）"
        } else {
            "换壁纸判据：异常（同图稳定=$stable 换图检测=$detectsChange " +
                "忽略裁切=$ignoresCrop；$anchorText）"
        }
    }

    /**
     * 验证「锚点只取原图空间那一份」这条规则 —— 这正是真机上踩到的坑。
     *
     * 场景：模板目录里同时躺着原图（`wallpaper`，200×400）和**显示空间**那份
     * （`wallpaper_cropped`，100×200，尺寸 = 屏幕比例，壁纸一缩放就变），裁切矩形
     * （`positionInfo`）落在原图坐标里。要求：
     *
     * 1. 只有原图那份"装得下"矩形，显示空间那份被排除；
     * 2. 无论候选顺序如何（含把显示空间那份排在前面），挑出来的都得是原图；
     * 3. 壁纸被缩放（矩形变小）时结论不变。
     */
    private fun verifyWallpaperSpace(): String {
        val position = WallpaperPosition(0f, 0f, 200f, 400f)
        val original = 200 to 400
        val display = 100 to 200

        val fitsOriginal = WallpaperSource.fits(original, position)
        val fitsDisplay = WallpaperSource.fits(display, position)
        val pickHead = WallpaperSource.chooseSource(listOf(original, display), position)
        val pickTail = WallpaperSource.chooseSource(listOf(display, original), position)
        // 放大壁纸后可见窗口变小，原图照样装得下，显示空间那份更装不下。
        val zoomed = WallpaperPosition(50f, 100f, 150f, 300f)
        val pickZoomed = WallpaperSource.chooseSource(listOf(display, original), zoomed)

        return if (fitsOriginal && !fitsDisplay && pickHead == 0 && pickTail == 1 && pickZoomed == 1) {
            "壁纸空间：正常（原图 $original 装得下裁切矩形、显示空间 $display 被排除；" +
                "两种顺序都挑到原图，缩放后不变）"
        } else {
            "壁纸空间：异常（原图装得下=$fitsOriginal 显示装得下=$fitsDisplay " +
                "顺序挑选=$pickHead/$pickTail 缩放后=$pickZoomed）"
        }
    }

    /**
     * 验证「crop_subject 与**已应用壁纸**同尺度」这条规则用到的纯函数。
     *
     * 一级「我的搭配」的当前卡片把两者当一对叠着画，位置由按壁纸贴图算出的矩阵给出 —— 尺度不一致时，
     * 原图与屏幕差得越多、错位越明显。这里钉住四件事：
     *
     * 1. 尺寸本来就一致 → `fitTo` 返回 null（**在本来就对的机器上是空操作**，不会引入缩放损失）；
     * 2. 宽高比一致 → 缩放后尺寸精确等于目标；
     * 3. 宽高比不一致 → `sameAspect` 为否（调用方据此**不**缩放，只记日志）；
     * 4. 居中裁的两种方向（4:3 裁成 20:9 是去上下；20:9 裁成 4:3 是去左右）。
     */
    private fun verifyDisplayAlignment(): String = runCatching {
        val base = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)   // 4:3
        val wide = Bitmap.createBitmap(2000, 900, Bitmap.Config.ARGB_8888)    // 20:9

        val noop = MaskComposer.fitTo(base, 2000, 1500) == null
        val scaled = MaskComposer.fitTo(base, 1000, 750)
        val scaleOk = scaled != null && scaled.width == 1000 && scaled.height == 750
        val aspectSame = MaskComposer.sameAspect(2000 to 1500, 1000 to 750)
        val aspectDiff = !MaskComposer.sameAspect(2000 to 1500, 2000 to 900)
        val croppedTall = MaskComposer.centerCropToAspect(base, 20f / 9f)
        val croppedWide = MaskComposer.centerCropToAspect(wide, 4f / 3f)

        val ok = noop && scaleOk && aspectSame && aspectDiff &&
            croppedTall != null && croppedTall.width == 2000 && croppedTall.height == 900 &&
            croppedWide != null && croppedWide.width == 1200 && croppedWide.height == 900

        // 端到端（走的就是 root 注入那条链路）：原图 2000×1500 的 4:3 照片、裁切矩形取 20:9 屏幕的可见窗口
        // (0,0)-(674,1500)，已应用壁纸是 600×1335 —— 裁完再对齐后必须**精确**等于 600×1335。
        // 用等比例的小图，避免测试里申请几十 MB 的位图。
        val photo = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        val cut = MaskComposer.cropToDisplay(photo, WallpaperPosition(0f, 0f, 674f, 1500f))
        val applied = 600 to 1335
        val endToEnd = cut != null &&
            MaskComposer.sameAspect(cut.width to cut.height, applied) &&
            MaskComposer.fitTo(cut, applied.first, applied.second)
                ?.let { it.width == 600 && it.height == 1335 } == true

        val okAll = ok && endToEnd
        listOfNotNull(base, wide, scaled, croppedTall, croppedWide, photo, cut).forEach { it.recycle() }
        if (okAll) {
            "画面对齐：正常（同尺寸=空操作；等比缩放精确；宽高比不同则拒缩放；居中裁两个方向正确；" +
                "「裁 674×1500 → 对齐 600×1335」端到端正确）"
        } else {
            "画面对齐：异常（空操作=$noop 缩放=$scaleOk 同比=$aspectSame 异比=$aspectDiff " +
                "裁高=${croppedTall?.width}×${croppedTall?.height} 裁宽=${croppedWide?.width}×${croppedWide?.height} " +
                "端到端=$endToEnd）"
        }
    }.getOrElse { "画面对齐：异常（$it）" }

    /** 锚点的兜底来源（hook 回写的记录）。 */    private fun prefsSize(context: Context): Pair<Int, Int>? = runCatching {
        val raw = MaskPrefs(context).wallpaperSize
        val parts = raw.split('x')
        if (parts.size != 2) return@runCatching null
        val width = parts[0].toIntOrNull() ?: return@runCatching null
        val height = parts[1].toIntOrNull() ?: return@runCatching null
        if (width <= 0 || height <= 0) null else width to height
    }.getOrNull()

    /**
     * 验证「候选 → 注入快照 → 候选清空 → 快照仍在（保护不断）→ 解除时一起清掉」这条链。
     *
     * 这是"候选器清空但保护不能断"的关键：hook 侧靠 provider 的 `present_*` 判断要不要继续兜底，
     * 而 `present_*` 认的是**注入快照**（2026-09-30 起；以前认"候选或快照"，那会让"只选图、
     * 还没点替换"的候选也被当成替换源 —— 见 MaskProvider）。纯文件操作，不需要真机。
     *
     * 只挑**没有任何真实数据**的角色做，自检绝不动用户已经选好的图。
     */
    private fun verifyCandidateAndSnapshot(context: Context): String {
        val role = MaskRole.selectable.firstOrNull {
            !MaskStore.exists(context, it) && !MaskStore.hasInjected(context, it)
        } ?: return "候选/快照：跳过（每个角色都有真实数据，自检不去动它）"

        val candidate = MaskStore.file(context, role)
        val snapshot = MaskStore.injectedFile(context, role)
        return runCatching {
            candidate.writeBytes(ByteArray(64) { 7 })

            val snapshotted = MaskStore.snapshotToInjected(context, role)
            val hadCandidate = MaskStore.exists(context, role)
            MaskStore.clear(context, role)

            val candidateGone = !MaskStore.exists(context, role)
            val stillAvailable = MaskStore.available(context, role)
            val injected = MaskStore.hasInjected(context, role)
            val listed = role in MaskStore.injectedRoles(context)

            MaskStore.clearInjected(context, role)
            val cleared = !MaskStore.hasInjected(context, role) && !MaskStore.available(context, role)

            val ok = snapshotted && hadCandidate && candidateGone &&
                stillAvailable && injected && listed && cleared
            if (ok) {
                "候选/快照：正常（${role.id} 候选清空后 provider 仍给得出图，解除时一并清掉）"
            } else {
                "候选/快照：异常（快照=$snapshotted 候选在=$hadCandidate 候选已清=$candidateGone " +
                    "仍可用=$stillAvailable 快照在=$injected 列表=$listed 已清空=$cleared）"
            }
        }.getOrElse { error -> "候选/快照：失败 ${error.message}" }.also {
            candidate.delete()
            snapshot.delete()
        }
    }

    /** 验证"壁纸 ∩ 遮罩"的合成：白遮罩处不透明并取到壁纸，黑遮罩处透明。 */
    private fun verifyCompose(): String = runCatching {
        val size = 64
        val wallpaper = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.BLACK)
            canvas.drawCircle(
                size / 2f,
                size / 2f,
                size / 4f,
                Paint().apply { color = Color.WHITE },
            )
        }
        val composed = HookRuntime.composeSubject(wallpaper, mask)
        val inside = composed.getPixel(size / 2, size / 2)
        val outside = composed.getPixel(2, 2)
        val ok = Color.alpha(inside) > 240 && Color.red(inside) > 240 && Color.alpha(outside) < 16
        composed.recycle()
        mask.recycle()
        wallpaper.recycle()
        if (ok) {
            "遮罩合成：正常（白遮罩处 alpha=${Color.alpha(inside)} 取到壁纸，" +
                "黑遮罩处 alpha=${Color.alpha(outside)} 透明）"
        } else {
            "遮罩合成：异常（内 alpha=${Color.alpha(inside)}，外 alpha=${Color.alpha(outside)}）"
        }
    }.getOrElse { error -> "遮罩合成：失败 ${error.message}" }

}
