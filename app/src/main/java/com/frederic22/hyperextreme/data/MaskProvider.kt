package com.frederic22.hyperextreme.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把替换图和配置以 ContentProvider 的形式暴露给被 hook 的进程。
 *
 * com.miui.aod 与模块 app 不是同一个 uid，`/data/data/com.frederic22.hyperextreme/files` 它读不到，
 * 所以 hook 侧一律走 `ContentResolver.openInputStream` / `openFileDescriptor`。
 */
class MaskProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // 灭亮屏兜底：若保护还残留（收尾没跑到），灭亮屏走完一轮时结束它（见 [ScreenDisarm]）。
        // provider 的 onCreate 早于 Application.onCreate，而进程只要是"被 hook 叫起来的"也会走到这里，
        // 所以在这儿注册覆盖面最广。模块 app 和 hook 进程各注册一次，各自幂等。
        context?.let { appContext ->
            ScreenDisarm.start(appContext) {
                // 灭亮屏走完一轮（若保护还在）就结束 —— **固定行为，没有开关**
                //（「灭屏后结束保护」那个开关 2026-09-25 就从界面撤了，
                // 2026-09-29 用户要求"逻辑固定在代码里、不留开关"，于是连 prefs 键一起删了）。
                MaskPrefs(appContext).protecting
            }
        }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val appContext = requireNotNull(context)
        // 「空间壁纸」的层图：`content://…/spatial/<16位哈希>/layer3.webp`。
        // SystemUI 侧的 GL 渲染器读不到模块私有目录，只能走 provider 流式拿。
        // ⚠️ provider 是导出的，路径必须严格白名单（哈希 16 位 hex + 8 个固定文件名），
        //    否则能被拼成 `../../shared_prefs/…` 之类读走别的文件。
        if (uri.pathSegments.firstOrNull() == SPATIAL_SEGMENT) {
            val key = uri.pathSegments.getOrNull(1).orEmpty()
            val name = uri.pathSegments.getOrNull(2).orEmpty()
            if (!key.matches(SPATIAL_KEY_RE) || !name.matches(SPATIAL_LAYER_RE)) {
                throw FileNotFoundException("非法的空间壁纸路径: $uri")
            }
            val file = java.io.File(java.io.File(appContext.filesDir, "spatial/$key/layers"), name)
            if (!file.isFile) throw FileNotFoundException("层图不存在: $uri")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        // 「光栅壁纸」的图与 meta（2026-11-04）：`content://…/raster/<组id>/meta`、
        // `content://…/raster/<组id>/<序号>`、`content://…/raster/<组id>/subject/<序号>`。
        // 渲染器在 SystemUI 进程里读不到模块私有目录，只能走 provider 流式拿（与空间壁纸同一套路）。
        // ⚠️ provider 是导出的，路径必须严格白名单（组 id 是纯数字时间戳、序号是 0~9 的小整数），
        //    否则能被拼成 `../../shared_prefs/…` 读走别的文件。
        if (uri.pathSegments.firstOrNull() == RASTER_SEGMENT) {
            val id = uri.pathSegments.getOrNull(1).orEmpty()
            val what = uri.pathSegments.getOrNull(2).orEmpty()
            if (!id.matches(RASTER_ID_RE)) throw FileNotFoundException("非法的光栅壁纸路径: $uri")
            val dir = java.io.File(java.io.File(appContext.filesDir, "raster"), id)
            if (what == "meta") {
                val f = java.io.File(dir, "meta.json")
                if (!f.isFile) throw FileNotFoundException("meta 不存在: $uri")
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            // `subject/<序号>` = 该图的**主体抠像**（`<图名>.subject.png`）。
            // 光栅景深层（RasterOverlay）读它；没算过主体的图直接 404，上层按"透明"处理
            //（用户 2026-11-04：没算景深的图主体透明 = 等于没开）。
            val isSubject = what == "subject"
            val indexStr = if (isSubject) uri.pathSegments.getOrNull(3).orEmpty() else what
            if (!indexStr.matches(RASTER_INDEX_RE)) throw FileNotFoundException("非法的光栅序号: $uri")
            val names = runCatching {
                val text = java.io.File(dir, "meta.json").readText()
                val arr = org.json.JSONObject(text).optJSONArray("items")
                    ?: throw FileNotFoundException("meta 里没有 items: $uri")
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrElse { throw FileNotFoundException("meta 读不出来: $uri") }
            val name = names.getOrNull(indexStr.toInt()) ?: throw FileNotFoundException("序号越界: $uri")
            // 文件名校验：只允许 `RasterStore` 生成的那种名字，防止 meta 被改写后读走别的文件。
            if (!name.matches(RASTER_FILE_RE)) throw FileNotFoundException("非法的图片名: $uri")
            val f = if (isSubject) {
                java.io.File(dir, "$name.subject.png")
            } else {
                java.io.File(dir, name)
            }
            if (!f.isFile) throw FileNotFoundException("文件不存在: $uri")
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        // 「液态壁纸」素材（2026-11-09）：`content://…/liquid/<16位哈希>/base`。
        // 渲染器在 SystemUI 进程，读不到模块私有目录，只能走 provider 流式拿（与空间/光栅同套路）。
        // ⚠️ provider 是导出的，路径必须严格白名单（哈希 16 位 hex + 固定文件名 base）。
        if (uri.pathSegments.firstOrNull() == LIQUID_SEGMENT) {
            val key = uri.pathSegments.getOrNull(1).orEmpty()
            val name = uri.pathSegments.getOrNull(2).orEmpty()
            if (!key.matches(LIQUID_KEY_RE) || (name != "base" && name != "mask")) {
                throw FileNotFoundException("非法的液态壁纸路径: $uri")
            }
            val f = if (name == "mask") LiquidStore.maskFile(appContext, key) else LiquidStore.baseFile(appContext, key)
            if (!f.isFile) throw FileNotFoundException("液态壁纸素材不存在: $uri")
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        // 「视差壁纸效果」的背景图 —— SystemUI 侧（hook/ParallaxBg）显示时从这儿读流。
        if (uri.pathSegments.lastOrNull() == PARALLAX_SEGMENT) {
            val bg = MaskStore.parallaxFile(appContext)
            if (!bg.isFile) throw FileNotFoundException("还没有选择背景图")
            return ParcelFileDescriptor.open(bg, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val role = roleOf(uri) ?: throw FileNotFoundException("未支持的 uri: $uri")
        // 保护期（= 上次替换还没落地）里，hook 要的**永远是注入快照那份**。
        //
        // 2026-09-30 修：这里原来是"候选优先、快照兜底"，于是保护期内用户新选的候选
        // （还没点「立即替换」）会被 hook 当成替换源拿去用/写 —— 锁屏会提前变样，
        // 系统模板文件也可能被写进这张没生效的图。快照才是"上次真正替换的那份"，
        // 保护期内一切读写都该以它为准。
        // 保护期外没有快照（`METHOD_RELEASE` / `METHOD_CLEAR` 会清掉），候选优先即等价于原来的行为。
        val protecting = MaskPrefs(appContext).protecting
        val candidates = if (protecting) {
            listOf(MaskStore.injectedFile(appContext, role), MaskStore.file(appContext, role))
        } else {
            listOf(MaskStore.file(appContext, role), MaskStore.injectedFile(appContext, role))
        }
        candidates.firstOrNull { it.isFile }?.let {
            return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        throw FileNotFoundException("尚未设置图片: ${role.id}")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val appContext = requireNotNull(context)
        val prefs = MaskPrefs(appContext)
        return when (method) {
            // 只读「解除景深遮挡限制」—— 与 METHOD_CONFIG 的区别是**零副作用**：
            // 不跑 syncFiles、不跑换壁纸检测、不上报。hook 侧拿不到 Context 的场景
            // （比如 DepthAvoidEvaluator$Threshold 的构造函数）也能安全调用。
            METHOD_DEPTH -> Bundle().apply {
                putBoolean(KEY_DEPTH_OCCLUSION, prefs.depthOcclusionRemoved)
            }

            // 只读「视差效果覆盖桌面」（2026-10-21）—— 同样是零副作用轻量方法。
            // miwallpaper 进程里 `getRemotePreferences` 与 `openRemoteFile` 都不可用
            // （实测：原文 -1 字符、读值 null），只能走 provider；每 500ms 调一次，
            // 绝不能走 METHOD_CONFIG（那会跑 syncFiles）。
            METHOD_PARALLAX_COVER -> Bundle().apply {
                putBoolean(KEY_PARALLAX_COVER_DESKTOP, prefs.parallaxCoverDesktop)
            }

            METHOD_CONFIG -> Bundle().apply {
                putLong(KEY_REVISION, prefs.revision)
                putBoolean(KEY_VERBOSE, prefs.verboseLog)
                // 「解除景深遮挡限制」。hook 侧走 `HookRuntime.depthOcclusionRemoved` 读，
                // **刻意不复用 `HookRuntime.config`** —— 那条会顺手跑文件替换链。
                putBoolean(KEY_DEPTH_OCCLUSION, prefs.depthOcclusionRemoved)
                putBoolean(KEY_PROTECTING, prefs.protecting)
                // 给 com.android.systemui 侧读的开关（它只在锁屏长按菜单出现时才读一次）。
                putBoolean(KEY_LOCKSCREEN_BUTTON_TOP, prefs.lockScreenButtonOnTop)
                // 「打开多任务时隐藏状态栏」（2026-10-16）—— 消费方：SystemUI 侧的 RecentsStatusBar
                //（每次「进入多任务」事件时现读一次，无缓存）。
                putBoolean(KEY_HIDE_STATUSBAR_IN_RECENTS, prefs.hideStatusBarInRecents)
                // 「视差壁纸效果」开启状态 —— 消费方：SystemUI 侧的 ParallaxBg（自愈时按它决定插不插背景层）。
                putBoolean(KEY_PARALLAX_ACTIVE, prefs.parallaxBgActive)
                // 「视差壁纸效果 → 参数调整」（2026-10-13）：5 个参数 —— 消费方同样是 ParallaxBg
                //（TTL 3s + 传感器帧轮询 → 参数最迟 ~4s 生效，无需重启、无需重开替换）。
                putBoolean(KEY_PARALLAX_REVERSE, prefs.parallaxReverse)
                putFloat(KEY_PARALLAX_SUBJECT_SCALE, prefs.parallaxSubjectScale)
                putFloat(KEY_PARALLAX_BG_SCALE, prefs.parallaxBgScale)
                putFloat(KEY_PARALLAX_SUBJECT_AMP, prefs.parallaxSubjectAmp)
                putFloat(KEY_PARALLAX_BG_AMP, prefs.parallaxBgAmp)
                // 第 6 个参数（2026-10-13）：「恢复原位等待时长」（秒；静止判定窗口）。
                putFloat(KEY_PARALLAX_HOME_WAIT, prefs.parallaxHomeWait)
                // 第 7 个参数（2026-10-13）：「最大幅度角度」（度；转动的满量程角）。
                putFloat(KEY_PARALLAX_GYRO_DEG, prefs.parallaxGyroDeg)
                // 「壁纸改变后自动关闭替换」（2026-10-15）的开关与「视差基准」——
                // 消费方：aod 侧 hook 的换壁纸检测（视差分支）。
                putBoolean(KEY_PARALLAX_AUTO_OFF, prefs.parallaxAutoOff)
                putString(KEY_PARALLAX_FP, prefs.parallaxWallpaperFp)
                // 「空间壁纸」的开启状态与**壁纸基准**（2026-10-29）—— 消费方：aod 侧的换壁纸检测
                //（`HookRuntime` 的 spatial 分支）。**没有开关**：检测到变化就无条件关。
                putBoolean(KEY_SPATIAL_ACTIVE, prefs.spatialActive)
                putString(KEY_SPATIAL_FP, prefs.spatialWallpaperFp)
                // 「光栅壁纸」的开启状态与**壁纸基准**（2026-11-05）—— 消费方同样是 aod 侧的
                // 换壁纸检测（`HookRuntime` 的 raster 分支）。同样**没有开关**。
                putBoolean(KEY_RASTER_ACTIVE, prefs.rasterActiveId.isNotEmpty())
                putString(KEY_RASTER_FP, prefs.rasterWallpaperFp)
                // 「视差效果覆盖桌面」（2026-10-21）—— 消费方：miwallpaper / SystemUI 的解锁动画抑制。
                putBoolean(KEY_PARALLAX_COVER_DESKTOP, prefs.parallaxCoverDesktop)
                // 「强制锁屏元素深浅模式」—— 主开关 + 6 个元素的档位（消费方：SystemUI 侧的深浅注入）。
                putBoolean(KEY_FORCE_TONE_MODE, prefs.forceToneMode)
                putInt(KEY_FORCE_TONE_STATUS_BAR, prefs.forceToneStatusBar)
                putInt(KEY_FORCE_TONE_CLOCK, prefs.forceToneClock)
                putInt(KEY_FORCE_TONE_SHORTCUT, prefs.forceToneShortcut)
                putInt(KEY_FORCE_TONE_FINGERPRINT, prefs.forceToneFingerprint)
                putInt(KEY_FORCE_TONE_BOTTOM_HINT, prefs.forceToneBottomHint)
                putInt(KEY_FORCE_TONE_SIGNATURE, prefs.forceToneSignature)
                putString(KEY_INJECTED_WALLPAPER, prefs.injectedWallpaperMd5)
                MaskRole.entries.forEach { role ->
                    // `present_*` = **注入快照存在**（2026-09-30 改）。
                    //
                    // 原来算的是"候选或快照任一存在"（`MaskStore.available`），于是用户**只是选了一张图**
                    // （候选就位、还没点「立即替换」）时 hook 也认为"这份图该顶替"——
                    // 配合 [openFile] 的候选优先，灭亮屏时它就被写进了系统文件。
                    // hook 只该关心"我上次替换落地的那份还在不在"，所以这里认快照。
                    putBoolean(KEY_PRESENT_PREFIX + role.id, MaskStore.hasInjected(appContext, role))
                }
            }

            // hook 侧回写"我已经把文件替换好了"，界面据此显示真实注入状态。
            METHOD_REPORT -> {
                val done = extras?.getBoolean(KEY_DONE, false) == true
                if (done) {
                    // 指纹只进不退：hook 算不出来（返回空）时不要把一个好指纹覆盖成 weak。
                    val incoming = extras?.getString(KEY_WALLPAPER_MD5).orEmpty()
                    when {
                        incoming.isNotEmpty() -> prefs.setInjectedWallpaper(incoming)
                        prefs.injectedWallpaperMd5.isEmpty() ->
                            prefs.setInjectedWallpaper(MaskPrefs.FINGERPRINT_WEAK)
                    }
                    // 注入完成时重申一次保护（正常流程里 App 收尾会立刻结束它；若收尾没跑到，
                    // 灭亮屏 / 换壁纸检测会兜底收掉）。
                    prefs.setProtecting(true)
                    // 注入完成 = 收尾：把候选转成注入快照，然后清空候选项。
                    // 只有真的清掉了才自增 revision —— 否则会和 syncFiles 互相触发。
                    val cleared = MaskRole.selectable.count { role ->
                        MaskStore.snapshotToInjected(appContext, role) && run {
                            MaskStore.clear(appContext, role)
                            true
                        }
                    } > 0
                    if (cleared) prefs.bumpRevision()
                }
                prefs.recordInjection(
                    processName = arg ?: "unknown",
                    at = System.currentTimeMillis(),
                    revision = extras?.getLong(KEY_REVISION, -1L) ?: -1L,
                    detail = extras?.getString(KEY_DETAIL).orEmpty(),
                )
                Bundle.EMPTY
            }

            // 解除保护：连注入快照一起清掉（系统里的文件本身不会被改动）。
            // arg 是原因描述，会记进"上次操作"，界面才能说清是为什么解除的。
            METHOD_CLEAR -> {
                MaskRole.selectable.forEach { MaskStore.clear(appContext, it) }
                MaskStore.clearInjected(appContext)
                prefs.setInjectedWallpaper("")
                prefs.setProtecting(false)
                prefs.bumpRevision()
                prefs.recordInjection(
                    processName = arg ?: DISARM_BY_USER,
                    at = System.currentTimeMillis(),
                    revision = prefs.revision,
                    detail = "保护已解除：候选项与注入快照都已清空（$CLEAR_HINT）",
                )
                Bundle.EMPTY
            }

            // 只结束"保护"这个动作：**保留**注入快照和指纹。
            //
            // 为什么不能直接用 METHOD_CLEAR：快照没了，界面就显示不出"已注入"了。
            // 所以这里只把 protecting 置 false —— hook 会立刻停止替换，系统文件保持替换后的内容。
            METHOD_RELEASE -> {
                // 灭亮屏走完一轮就结束保护 —— 固定行为，没有开关（原 `releaseOnScreenOn` 已删）。
                if (prefs.protecting) {
                    prefs.setProtecting(false)
                    // 保护结束 = 替换已经落地（景深层重新解码过了），候选和注入快照都没有必要再留着 ——
                    // 顺手做掉原来那个「清除替换记录」按钮的事，用户不用额外点一次。
                    // 注意只清我们自己的文件，**不动系统里那份已经替换好的内容**。
                    MaskRole.entries.forEach { role ->
                        MaskStore.clear(appContext, role)
                        MaskStore.clearInjected(appContext, role)
                    }
                    prefs.setInjectedWallpaper("")
                    prefs.recordInjection(
                        processName = arg ?: RELEASE_BY_SCREEN_ON,
                        at = System.currentTimeMillis(),
                        revision = prefs.revision,
                        detail = "保护已结束：灭亮屏后景深层已重新解码，替换已生效；候选与注入快照已一并清除。",
                    )
                    prefs.bumpRevision()
                }
                Bundle.EMPTY
            }

            // 只更新"当前壁纸指纹"，不碰文件、不改 revision。
            //
            // 用在两种不该解除保护、但指纹得换掉的场合：
            // 1. 判据种类变了（之前只能算文件摘要，现在读得到模板配置）；
            // 2. 之前是 weak 占位，现在能算出真指纹了。
            METHOD_FINGERPRINT -> {
                val value = extras?.getString(KEY_WALLPAPER_MD5).orEmpty()
                if (value.isNotEmpty()) prefs.setInjectedWallpaper(value)
                Bundle.EMPTY
            }

            // 回写"抓到的壁纸尺寸"。arg 带空间前缀：
            //   `src:宽x高`       = 原图空间（`templates/current/wallpaper`）→ 只有它能当对齐锚点；
            //   `src:宽x高@请求号` = 同上，但这是 hook 对 App 那次「探测请求」的回答：
            //                        `0x0@请求号` 表示"探测失败"（App 收到就不用再等）；
            //   `display:宽x高`   = 显示空间（裁切矩形那份）→ 只记下来做对照，绝不进锚点。
            // 为什么要分：显示空间那份的尺寸 = screenWidth / scale，会随壁纸缩放/移动变化，
            // 拿它当锚点就会「提示的分辨率跟着锁屏显示区域变」（用户实测踩过）。
            METHOD_WALLPAPER_SIZE -> {
                val value = arg.orEmpty()
                when {
                    value.startsWith(SIZE_PREFIX_SOURCE) -> {
                        val payload = value.removePrefix(SIZE_PREFIX_SOURCE)
                        val token = payload.substringAfterLast('@', "").toLongOrNull() ?: 0L
                        val size = payload.substringBefore('@')
                        if (token > 0L) {
                            prefs.setProbeAck(token)
                            if (size != "0x0") prefs.setWallpaperSize(size, token)
                        } else if (size != "0x0") {
                            prefs.setWallpaperSize(size)
                        }
                    }

                    value.startsWith(SIZE_PREFIX_DISPLAY) ->
                        prefs.setDisplayWallpaperSize(value.removePrefix(SIZE_PREFIX_DISPLAY))
                }
                Bundle.EMPTY
            }

            // hook 完成一次「立即替换」的落盘后回执（arg = 请求号）。
            METHOD_APPLY_ACK -> {
                arg?.trim()?.toLongOrNull()?.let { prefs.setApplyAck(it) }
                Bundle.EMPTY
            }

            // SystemUI 侧的灭亮屏 hook 回执（arg = 请求号）。
            METHOD_FLASH_ACK -> {
                arg?.trim()?.toLongOrNull()?.let { prefs.setFlashAck(it) }
                Bundle.EMPTY
            }

            // SystemUI 侧「视差壁纸效果」开 / 关执行完成的回执（arg = 请求号）。
            METHOD_PARALLAX_ACK -> {
                arg?.trim()?.toLongOrNull()?.let { prefs.setParallaxAck(it) }
                Bundle.EMPTY
            }

            // 只更新「视差基准」（hook 侧惰性补记 / 判据升级时用）。不碰开关与指令号。
            METHOD_PARALLAX_FP -> {
                val value = extras?.getString(KEY_PARALLAX_FP).orEmpty()
                if (value.isNotEmpty()) prefs.setParallaxWallpaperFp(value)
                Bundle.EMPTY
            }

            // 只更新「空间壁纸基准」（同上；2026-10-29）。
            METHOD_SPATIAL_FP -> {
                val value = extras?.getString(KEY_SPATIAL_FP).orEmpty()
                if (value.isNotEmpty()) prefs.setSpatialWallpaperFp(value)
                Bundle.EMPTY
            }

            // **换壁纸 → 无条件关闭空间壁纸**（2026-10-29，用户定：不做开关）。
            //
            // 收尾与手动「关闭替换」**共用同一套**（`SpatialControl.disable`）：
            // 改 prefs（active/key + 清基准）→ 推系统属性 → toast → 延迟重启壁纸进程。
            // 区别只有文案。`disable` 内部会兜底切主线程（`call` 跑在 binder 线程）。
            METHOD_SPATIAL_AUTO_OFF -> {
                SpatialControl.disable(appContext, "检测到壁纸变化，空间壁纸已自动关闭")
                val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                prefs.setDiagNote(
                    "空间壁纸自动关闭：$at → 检测到换壁纸${if (arg.isNullOrBlank()) "" else "（$arg）"}",
                )
                Bundle.EMPTY
            }

            // **换壁纸 → 无条件关闭光栅壁纸**（2026-11-05，与空间同语义）。
            //
            // 收尾与手动「取消应用」共用同一套（`RasterControl.disable`）：
            // 清 active/key + 清基准 → 推系统属性（SystemUI 轮询到 active=0 自己拆层）。
            // 服务模式不额外 `WallpaperManager.clear()` —— 系统已经把壁纸换掉了。
            METHOD_RASTER_AUTO_OFF -> {
                RasterControl.disable(appContext, "检测到壁纸变化，光栅壁纸已自动关闭")
                val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                prefs.setDiagNote(
                    "光栅壁纸自动关闭：$at → 检测到换壁纸${if (arg.isNullOrBlank()) "" else "（$arg）"}",
                )
                Bundle.EMPTY
            }

            // 只更新「光栅壁纸基准」（hook 侧惰性补记 / 判据升级用）。不碰开关与指令号。
            METHOD_RASTER_FP -> {
                val value = extras?.getString(KEY_RASTER_FP).orEmpty()
                if (value.isNotEmpty()) prefs.setRasterWallpaperFp(value)
                Bundle.EMPTY
            }

            // 「壁纸改变后自动关闭替换」：hook 侧检测到换壁纸后来这儿收尾 ——
            // 双重检查（开关还开着、视差还在生效）后置状态 + 清基准 + 生成指令号；
            // **广播由 hook 侧拿到 token 后自行发出**（写状态在前、发广播在后，与 App 的
            // 「关闭替换」同一顺序；接收侧以 config 为准，广播只是触发）。
            METHOD_PARALLAX_AUTO_OFF -> {
                if (prefs.parallaxBgActive && prefs.parallaxAutoOff) {
                    prefs.setParallaxBgActive(false)
                    prefs.setParallaxWallpaperFp("")
                    val token = prefs.requestParallax()
                    val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    prefs.setDiagNote("视差自动关闭：$at → 检测到换壁纸${if (arg.isNullOrBlank()) "" else "（$arg）"}")
                    Bundle().apply { putLong(KEY_PARALLAX_TOKEN, token) }
                } else {
                    Bundle.EMPTY
                }
            }

            // aod 侧 hook 对「点选图前的景深检查」的回应（arg = `<wallpaper_effect_type_2>@<请求号>`，
            // 读取失败是 `-1@<请求号>`）。值与回执号在 prefs 里**原子写**，见 `MaskPrefs.setDepthProbeResult`。
            METHOD_DEPTH_STATE -> {
                val value = arg.orEmpty()
                val token = value.substringAfterLast('@', "").toLongOrNull() ?: 0L
                val type = value.substringBefore('@').trim().toIntOrNull()
                if (token > 0L && type != null) prefs.setDepthProbeResult(type, token)
                Bundle.EMPTY
            }

            // 写一行诊断（`diagNote`）：给**另一个进程**（`com.android.systemui`）用。
            //
            // 锁屏按钮置顶那条链全在 SystemUI 里，"到底跑没跑、卡在哪一步"模块 app 这边看不到。
            // 让它把结论回写到这里，用户在设置页「最近操作」就能看见（见 hook/LockScreenButton）。
            METHOD_NOTE -> {
                val value = arg.orEmpty()
                if (value.isNotEmpty()) prefs.setDiagNote(value)
                Bundle.EMPTY
            }

            else -> Bundle.EMPTY
        }
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only provider")

    private fun roleOf(uri: Uri): MaskRole? {
        val segments = uri.pathSegments
        val id = segments.lastOrNull() ?: return null
        return MaskRole.entries.firstOrNull { it.id == id }
    }

    companion object {
        const val AUTHORITY = "com.frederic22.hyperextreme.liquid.provider"
        const val METHOD_CONFIG = "config"
        /** 只读「解除景深遮挡限制」开关（零副作用，见 [call]）。 */
        const val METHOD_DEPTH = "depth"

        /** 「视差效果覆盖桌面」的只读查询（2026-10-21，miwallpaper 侧专用）。 */
        const val METHOD_PARALLAX_COVER = "parallax_cover"
        const val METHOD_REPORT = "report"
        const val METHOD_CLEAR = "clear"
        const val METHOD_RELEASE = "release"
        const val METHOD_FINGERPRINT = "fingerprint"
        const val METHOD_WALLPAPER_SIZE = "wallpaper_size"

        /** hook 完成「立即替换」落盘后的回执（arg = 请求号）。 */
        const val METHOD_APPLY_ACK = "apply_ack"

        /** SystemUI 侧「替后自动灭亮屏」的回执（arg = 请求号）。 */
        const val METHOD_FLASH_ACK = "flash_ack"

        /** SystemUI 侧「视差壁纸效果」开 / 关执行完成的回执（arg = 请求号）。 */
        const val METHOD_PARALLAX_ACK = "parallax_ack"

        /** hook 侧：更新「视差基准」/ 「换壁纸自动关闭视差」的收尾（2026-10-15 加）。 */
        const val METHOD_PARALLAX_FP = "parallax_fp"
        const val METHOD_PARALLAX_AUTO_OFF = "parallax_auto_off"

        /** 只更新「空间壁纸基准」（2026-10-29；惰性补记 / 判据升级用）。 */
        const val METHOD_SPATIAL_FP = "spatial_fp"

        /** **换壁纸 → 无条件关闭空间壁纸**（2026-10-29）。arg = 判据详情，仅用于 diagNote。 */
        const val METHOD_SPATIAL_AUTO_OFF = "spatial_auto_off"

        /** 只更新「光栅壁纸基准」（2026-11-05；惰性补记 / 判据升级用）。 */
        const val METHOD_RASTER_FP = "raster_fp"

        /** **换壁纸 → 无条件关闭光栅壁纸**（2026-11-05）。arg = 判据详情，仅用于 diagNote。 */
        const val METHOD_RASTER_AUTO_OFF = "raster_auto_off"

        /** [METHOD_PARALLAX_AUTO_OFF] 回执 bundle 里的指令号键（与广播 extra 同名，hook 拿到后发广播用）。 */
        const val KEY_PARALLAX_TOKEN = "token"

        /** aod 侧 hook 对「点选图前的景深检查」的回应（arg = `<值>@<请求号>`，值 -1 = 读取失败）。 */
        const val METHOD_DEPTH_STATE = "depth_state"

        /** 写一行诊断进 `diagNote`（arg = 文本）。供 SystemUI 侧的锁屏按钮逻辑回写状态。 */
        const val METHOD_NOTE = "note"

        /** [METHOD_WALLPAPER_SIZE] 的 arg 前缀：原图空间（可当锚点）。 */
        const val SIZE_PREFIX_SOURCE = "src:"

        /** [METHOD_WALLPAPER_SIZE] 的 arg 前缀：显示空间（只能当诊断参照）。 */
        const val SIZE_PREFIX_DISPLAY = "display:"
        const val KEY_REVISION = "revision"
        const val KEY_PROTECTING = "protecting"
        const val KEY_VERBOSE = "verbose"
        /** 「解除景深遮挡限制」——消费方是编辑器进程里的 `HierarchyImageAvoidController`。 */
        const val KEY_DEPTH_OCCLUSION = "depth_occlusion_removed"
        const val KEY_PRESENT_PREFIX = "present_"
        /** 「使自定义锁屏按钮显示在上层」—— 消费方是 SystemUI 进程里的 `LockScreenButton`。 */
        const val KEY_LOCKSCREEN_BUTTON_TOP = "lockscreen_button_on_top"

        /** 「打开多任务时隐藏状态栏」—— 消费方是 SystemUI 进程里的 `RecentsStatusBar`（默认关）。 */
        const val KEY_HIDE_STATUSBAR_IN_RECENTS = "hide_statusbar_in_recents"
        /** 「视差壁纸效果」开启状态 —— 消费方是 SystemUI 进程里的 `ParallaxBg`（自愈时读它）。 */
        const val KEY_PARALLAX_ACTIVE = "parallax_bg_active"

        /** 「视差壁纸效果 → 参数调整」（2026-10-13 加）：5 个参数键 —— 消费方同样是 `ParallaxBg`。 */
        const val KEY_PARALLAX_REVERSE = "parallax_reverse"
        const val KEY_PARALLAX_SUBJECT_SCALE = "parallax_subject_scale"
        const val KEY_PARALLAX_BG_SCALE = "parallax_bg_scale"
        const val KEY_PARALLAX_SUBJECT_AMP = "parallax_subject_amp"
        const val KEY_PARALLAX_BG_AMP = "parallax_bg_amp"

        /** 第 6 个参数（2026-10-13）：「恢复原位等待时长」（秒）。 */
        const val KEY_PARALLAX_HOME_WAIT = "parallax_home_wait"

        /** 第 7 个参数（2026-10-13）：「最大幅度角度」（度）。 */
        const val KEY_PARALLAX_GYRO_DEG = "parallax_gyro_deg"

        /** 「壁纸改变后自动关闭替换」开关 + 「视差基准」壁纸判据（2026-10-15 加）。 */
        const val KEY_PARALLAX_AUTO_OFF = "parallax_auto_off_on_wallpaper_change"
        const val KEY_PARALLAX_FP = "parallax_wallpaper_fp"

        /** 「空间壁纸」的开启状态与壁纸基准（消费方：aod 侧的换壁纸检测；2026-10-29）。 */
        const val KEY_SPATIAL_ACTIVE = "spatial_active"
        const val KEY_SPATIAL_FP = "spatial_wallpaper_fp"

        /** 「光栅壁纸」的开启状态与壁纸基准（消费方：aod 侧的换壁纸检测；2026-11-05）。 */
        const val KEY_RASTER_ACTIVE = "raster_active"
        const val KEY_RASTER_FP = "raster_wallpaper_fp"

        /** 「视差效果覆盖桌面」（2026-10-21 加，默认关）。 */
        const val KEY_PARALLAX_COVER_DESKTOP = "parallax_cover_desktop"

        /** 「强制锁屏元素深浅模式」—— 主开关 + 7 个元素档位（0=保持系统判定/1=浅色/2=深色）。 */
        const val KEY_FORCE_TONE_MODE = "force_tone_mode"
        const val KEY_FORCE_TONE_STATUS_BAR = "force_tone_status_bar"
        const val KEY_FORCE_TONE_CLOCK = "force_tone_clock"
        const val KEY_FORCE_TONE_SHORTCUT = "force_tone_shortcut"
        const val KEY_FORCE_TONE_FINGERPRINT = "force_tone_fingerprint"
        const val KEY_FORCE_TONE_BOTTOM_HINT = "force_tone_bottom_hint"
        const val KEY_FORCE_TONE_SIGNATURE = "force_tone_signature"
        const val KEY_DETAIL = "detail"
        const val KEY_DONE = "done"
        const val KEY_WALLPAPER_MD5 = "wallpaper_md5"
        const val KEY_INJECTED_WALLPAPER = "injected_wallpaper_md5"

        /** `content://authority/parallax`：「视差壁纸效果」的背景图（SystemUI 侧读流显示）。 */
        const val PARALLAX_SEGMENT = "parallax"

        /** 空间壁纸层图：`content://<authority>/spatial/<16位哈希>/layerN.webp`。 */
        const val SPATIAL_SEGMENT = "spatial"

        /** 「光栅壁纸」：`raster/<组id>/meta`、`raster/<组id>/<序号>`、`raster/<组id>/subject/<序号>`。 */
        const val RASTER_SEGMENT = "raster"
        const val LIQUID_SEGMENT = "liquid"
        private val LIQUID_KEY_RE = Regex("^[0-9a-f]{16}$")
        private val RASTER_ID_RE = Regex("^[0-9]{6,20}$")
        private val RASTER_INDEX_RE = Regex("^[0-9]$")

        /**
         * 图片文件名白名单。
         *
         * 指纹有两种长度（2026-11-04）：
         * * **16 hex** = 新格式，SHA-256 前 8 字节（当前写入口径）；
         * * **32 hex** = 旧格式，双路 dHash。只为了**老组还能显示**，判重已不认它
         *   （`RasterStore.hashOf` 只认 16），用户清掉旧缓存后就没了。
         */
        private val RASTER_FILE_RE =
            Regex("^img_[0-9]+_([0-9a-f]{16}|[0-9a-f]{32})\\.[a-z0-9]{2,5}$")
        private val SPATIAL_KEY_RE = Regex("^[0-9a-f]{16}$")
        private val SPATIAL_LAYER_RE = Regex("^(layer[0-9]\\.png|depth\\.png|meta\\.json)$")

        /** [METHOD_CLEAR] 的 arg 缺省值 —— 当前调用方（hook 换壁纸检测）恒传原因串，此为兜底。 */
        const val DISARM_BY_USER = "本应用"

        /** [METHOD_CLEAR] 的 arg：hook 检测到用户换了壁纸。 */
        const val DISARM_BY_WALLPAPER_CHANGE = "hook（换壁纸）"

        /** [METHOD_RELEASE] 的 arg：灭亮屏走完一轮，替换落地，保护到期自然结束。 */
        const val RELEASE_BY_SCREEN_ON = "自动（灭亮屏完成）"

        /** 解除保护后系统里的文件不会被改动（哪里用到就在文案里说清楚）。 */
        const val CLEAR_HINT = "系统里的文件未改动"

        fun configUri(): Uri = Uri.parse("content://$AUTHORITY")

        fun imageUri(role: MaskRole): Uri = Uri.parse("content://$AUTHORITY/role/${role.id}")
    }
}
