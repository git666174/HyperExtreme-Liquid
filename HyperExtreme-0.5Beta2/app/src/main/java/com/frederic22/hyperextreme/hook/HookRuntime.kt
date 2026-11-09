package com.frederic22.hyperextreme.hook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.frederic22.hyperextreme.data.MaskComposer
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskProvider
import com.frederic22.hyperextreme.data.MaskRole
import com.frederic22.hyperextreme.data.MaskStore
import com.frederic22.hyperextreme.data.ScreenDisarm
import com.frederic22.hyperextreme.data.SystemUiPackageName
import com.frederic22.hyperextreme.data.WallpaperSource
import com.frederic22.hyperextreme.data.WallpaperVersion
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 被 hook 进程里的运行时状态：配置快照、替换图缓存，以及**真正把系统文件替换掉**。
 *
 * 语义（"只写不删"，2026-10-15 定稿）：
 * 1. 保护期内把自己的图画成 PNG 写进系统真正使用的那几个路径，并按系统的习惯放开权限；
 * 2. 之后继续用 hook 兜底：编辑器重新生成这些文件时立刻改写回我们的内容，避免被覆盖回去；
 * 3. 模块**不再对系统文件做任何删除或回写**（原"替换前备份 / 还原"整组逻辑已按用户要求删除）。
 *
 * 真实路径映射：
 * | 角色 | 路径 |
 * | --- | --- |
 * | `subject` | `<filesDir>/templates/current/subject` |
 * | `subject_mask` | `<filesDir>/templates/current/subject_mask` |
 * | `crop_subject` | `/data/system/theme_magic/users/<uid>/wallpaper/crop_subject` |
 */
internal object HookRuntime {

    /** 配置快照的存活时间。编辑器读一次图会连续命中多个 hook 点，没必要每次都走 binder。 */
    private const val CONFIG_TTL_MS = 1000L

    private const val STATE_FILE = ".glass_mask_state"

    /** 没有变化时也定期回写一次状态，界面才能显示"已注入"。 */
    private const val REPORT_INTERVAL_MS = 30_000L

    @Volatile
    private var application: Application? = null

    @Volatile
    private var processName: String = "?"

    @Volatile
    private var logger: ((String) -> Unit)? = null

    @Volatile
    private var configAt = 0L

    @Volatile
    private var configValue: HookConfig? = null

    @Volatile
    private var desiredCache: Pair<Long, Bitmap>? = null

    @Volatile
    private var lastReportAt = 0L

    /**
     * 框架把模块加载进"它自己的进程"时置位。界面和它同进程，读到就是"已激活"，
     * 不需要目标应用先跑起来 —— 别的模块"勾上就显示已激活"就是靠这一点。
     */
    @Volatile
    var selfInjected = false
        private set

    private val bitmaps = ConcurrentHashMap<String, CachedBitmap>()

    /** [syncedRevision] 是上一次已经写进磁盘的状态，存在 `.glass_mask_state` 里跨进程共享。 */
    private var syncedRevision = Long.MIN_VALUE

    data class HookConfig(
        val revision: Long,
        val verbose: Boolean,
        val injectedWallpaperMd5: String,
        /**
         * 现在还要不要替换。
         *
         * 保护的主路收尾 = 「立即替换」完成时 App 直接结束（`releaseInjection`）；灭亮屏
         * （见 [ScreenDisarm]）与换壁纸检测是**兜底**，覆盖"收尾没跑到"的残留。为 false 时
         * 所有替换型 hook 直接放行，让编辑器自己写（会覆盖回它的版本，这是预期行为：
         * 用户在编辑器里再点应用，就该听编辑器的）。
         */
        val protecting: Boolean,
        val present: Set<String>,
        /** 「视差壁纸效果」是否生效中（供换壁纸检测的视差分支用）。 */
        val parallaxActive: Boolean,
        /** 「壁纸改变后自动关闭替换」开关。 */
        val parallaxAutoOff: Boolean,
        /** 「视差基准」壁纸判据（空 = 尚未记录，检测时惰性补记）。 */
        val parallaxWallpaperFp: String,
        /**
         * 「空间壁纸」是否生效中（2026-10-29，供换壁纸检测的 spatial 分支用）。
         *
         * 注意这条走 **config（provider）通道**，与 SystemUI 侧读的 `SpatialFlag` 系统属性
         * 是两套 —— aod 进程只认 config。
         */
        val spatialActive: Boolean,
        /**
         * 「空间壁纸基准」壁纸判据（空 = 尚未记录，检测时惰性补记）。
         *
         * 空间壁纸**没有配套开关**：用户 2026-10-29 定的是"检测到换壁纸就无条件关"
         * （换壁纸会带来大量不确定行为，不关就得逐个擦屁股）。
         */
        val spatialWallpaperFp: String,
        /**
         * 「光栅壁纸」是否生效中（2026-11-05，供换壁纸检测的 raster 分支用）。
         *
         * 与空间壁纸同构：只看 `raster_active_id` 非空，**没有开关**。
         */
        val rasterActive: Boolean,
        /**
         * 「光栅壁纸基准」壁纸判据（空 = 尚未记录，检测时惰性补记）。
         *
         * 与空间一样**没有配套开关**：检测到换壁纸就无条件关。
         */
        val rasterWallpaperFp: String,
    ) {
        /** 该角色在模块里设置了图片 —— 没有开关，设了就是生效。 */
        fun has(role: MaskRole): Boolean = role.id in present
    }

    private data class CachedBitmap(val revision: Long, val bitmap: Bitmap)

    private data class StoredState(val revision: Long)

    /**
     * 由 [HookEntry] 在 onModuleLoaded 时注入框架日志出口。
     * 日志必须走框架接口：API 102 的模块不允许使用旧的 `de.robv.android.xposed` API。
     */
    fun installLogger(processName: String, sink: (String) -> Unit) {
        this.processName = processName
        this.logger = sink
    }

    fun markSelfInjected() {
        selfInjected = true
    }

    /**
     * 等 Application 就绪后，把"已激活"写进模块自己的 SharedPreferences。
     * 万一界面在另一个 classloader 里拿不到上面的静态标记，也能靠这条记录显示已激活。
     */
    fun reportSelfActivationAsync() {
        Thread {
            repeat(20) {
                val context = appContext()
                if (context != null) {
                    runCatching {
                        val prefs = MaskPrefs(context)
                        prefs.recordInjection(
                            processName = SELF_PROCESS_LABEL,
                            at = System.currentTimeMillis(),
                            revision = prefs.revision,
                            detail = "LSPosed 已加载模块（勾选即激活）",
                        )
                    }
                    return@Thread
                }
                Thread.sleep(500L)
            }
        }.start()
    }

    /**
     * 在**目标应用进程**里也装一份"灭亮屏 → 结束保护"的观察者。
     *
     * 模块 app 可能早就被系统杀掉了（用户正在锁屏界面上），那时只有这里注册的接收者还活着。
     * 两边都注册一份、各自幂等，解除动作统一走 provider，所以谁先醒都行。
     *
     * Application 就绪前拿不到 Context，所以和 [reportSelfActivationAsync] 一样轮询等一会儿。
     */
    fun installScreenDisarmAsync() {
        Thread {
            repeat(20) {
                val context = appContext()
                if (context != null) {
                    ScreenDisarm.start(context) { shouldReleaseOnScreenOn(context) }
                    return@Thread
                }
                Thread.sleep(500L)
            }
        }.start()
    }

    /**
     * 给 [ScreenDisarm] 的判据：本进程看到的"还在保护"。
     *
     * 必须走 provider 的配置快照 —— hook 跑在 com.miui.aod 里，直接 `MaskPrefs(context)` 读的是
     * 目标应用自己的 SharedPreferences，跟模块那份不是一个文件。
     */
    private fun shouldReleaseOnScreenOn(context: Context): Boolean {
        val current = config(context) ?: return false
        // 灭亮屏走完一轮（若保护还在）就结束 —— 固定行为，没有开关（`releaseOnScreenOn` 已删；
        // 保护主路收尾在替换完成时，这里只是兜底）。
        return current.protecting
    }

    fun appContext(): Context? {
        application?.let { return it }
        val resolved = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
        if (resolved != null) {
            application = resolved
        }
        return resolved
    }

    /** 取配置；配置一变就顺手把文件同步到磁盘上。 */
    fun config(context: Context): HookConfig? {
        val now = SystemClock.elapsedRealtime()
        configValue?.let { if (now - configAt < CONFIG_TTL_MS) return it }

        val snapshot = runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_CONFIG,
                null,
                null,
            )
        }.getOrNull()

        val parsed = snapshot?.let { data ->
            HookConfig(
                revision = data.getLong(MaskProvider.KEY_REVISION, 0L),
                verbose = data.getBoolean(MaskProvider.KEY_VERBOSE, false),
                injectedWallpaperMd5 = data.getString(MaskProvider.KEY_INJECTED_WALLPAPER).orEmpty(),
                protecting = data.getBoolean(MaskProvider.KEY_PROTECTING, false),
                present = MaskRole.entries
                    .filter { data.getBoolean(MaskProvider.KEY_PRESENT_PREFIX + it.id, false) }
                    .map { it.id }
                    .toSet(),
                parallaxActive = data.getBoolean(MaskProvider.KEY_PARALLAX_ACTIVE, false),
                parallaxAutoOff = data.getBoolean(MaskProvider.KEY_PARALLAX_AUTO_OFF, true),
                parallaxWallpaperFp = data.getString(MaskProvider.KEY_PARALLAX_FP).orEmpty(),
                spatialActive = data.getBoolean(MaskProvider.KEY_SPATIAL_ACTIVE, false),
                spatialWallpaperFp = data.getString(MaskProvider.KEY_SPATIAL_FP).orEmpty(),
                rasterActive = data.getBoolean(MaskProvider.KEY_RASTER_ACTIVE, false),
                rasterWallpaperFp = data.getString(MaskProvider.KEY_RASTER_FP).orEmpty(),
            )
        }

        configAt = now
        configValue = parsed

        if (parsed != null) {
            runCatching { syncFiles(context, parsed) }
                .onFailure { error -> log("同步文件失败：$error") }
            checkWallpaperChanged(context, parsed)
            if (now - lastReportAt > REPORT_INTERVAL_MS) {
                lastReportAt = now
                runCatching { report(context, parsed, "运行中（revision=${parsed.revision}）") }
            }
        }
        return parsed
    }

    // ---------------------------------------------------------------- 「解除景深遮挡限制」（与替换链解耦）

    private var occlusionAt = 0L
    private var occlusionValue: Boolean? = null

    /**
     * 只读「解除景深遮挡限制」这个开关 —— **刻意不复用 [config]**。
     *
     * [config] 会顺手跑 `syncFiles` / `checkWallpaperChanged` / `report`，那是"文件替换"那条链的
     * 副作用；而这个开关只管编辑器界面状态，跟替换链无关。所以这里单独走一次 provider 快照、
     * 自己带 TTL 缓存，**不碰 [configValue]、不产生任何副作用**（用户在界面上动开关，下一次
     * 快照过期就会读到，不需要重启编辑器）。
     *
     * 返回 null = provider 没返回配置 ⇒ 调用方应直接放行，别改写任何东西。
     */
    fun depthOcclusionRemoved(context: Context): Boolean? {
        val now = SystemClock.elapsedRealtime()
        occlusionValue?.let { if (now - occlusionAt < CONFIG_TTL_MS) return it }

        val snapshot = runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_CONFIG,
                null,
                null,
            )
        }.getOrNull()

        val parsed = snapshot?.getBoolean(MaskProvider.KEY_DEPTH_OCCLUSION, false)
        occlusionAt = now
        occlusionValue = parsed
        return parsed
    }

    // ---------------------------------------------------------------- 远程偏好（无 Context 用）

    /**
     * 模块偏好的**只读远程句柄** —— 走 libxposed 的 `getRemotePreferences`，由 [HookEntry] 在
     * 装 hook 时绑进来。
     *
     * 为什么需要它：有些 hook 点**拿不到 Context**（比如 `DepthAvoidEvaluator$Threshold` 的构造函数
     * —— 参数只有一个 double），而 [appContext] 用的是反射 hidden API，在 SystemUI 进程里并不可靠
     * （2026-09-28 真机踩过：拿不到就整条链静默死掉）。远程偏好是框架提供的正式通道，
     * 不需要 Context、也不需要走 provider。
     */
    private var remotePreferences: android.content.SharedPreferences? = null

    /** 兜底通道：把模块 `shared_prefs/<name>.xml` 原文读回来（[XposedInterface.openRemoteFile]）。 */
    private var remotePreferencesText: (() -> String?)? = null

    fun bindRemotePreferences(preferences: android.content.SharedPreferences?) {
        remotePreferences = preferences
    }

    fun bindRemotePreferencesFile(reader: () -> String?) {
        remotePreferencesText = reader
    }

    /**
     * 读「解除景深遮挡限制」—— 远程偏好优先，读不到再**直接读 prefs 文件**（两条都不需要 Context）。
     *
     * 为什么要两条：`getRemotePreferences` 是框架通道，但**绑定时机**很关键
     * （HyperChanger 是在 `onPackageReady` 里现取的，我们一开始放在 `onModuleLoaded`，
     * 一旦那时拿到的是 null，SystemUI 里又没有可靠的 Context 兜底 ⇒ 整个功能静默失效）。
     * 文件那条用 `openRemoteFile("shared_prefs/<name>.xml")`，不依赖 SharedPreferences 实现。
     */
    fun occlusionRemovedRemote(): Boolean {
        // 两条通道**取或**：任一条说"开着"就算开着。某条通道读不到/过期只会返回 false，
        // 不会把另一条的好消息盖掉（开关关着时两条都是 false，语义仍然正确）。
        remotePreferences?.let { prefs ->
            runCatching { prefs.getBoolean(MaskPrefs.KEY_DEPTH_OCCLUSION, false) }
                .getOrDefault(false)
                .let { if (it) return true }
        }
        val xml = runCatching { remotePreferencesText?.invoke() }.getOrNull() ?: return false
        return Regex("name=\"${MaskPrefs.KEY_DEPTH_OCCLUSION}\"\\s+value=\"true\"")
            .containsMatchIn(xml)
    }

    /**
     * 通用远程偏好读取（**int**）：框架通道优先，读不到再读 prefs 文件原文；都没有返回 null。
     *
     * 与 [occlusionRemovedRemote] 同一套两条通道。给「强制锁屏元素深浅模式」当
     * "hook 点拿不到 Context 时"的兜底 —— 那条链的主通道是 provider（见 `LockScreenTone`），
     * 这里只是免得某个覆盖点在极端情况下静默失效。
     */
    fun remoteInt(key: String): Int? {
        remotePreferences?.let { prefs ->
            runCatching { if (prefs.contains(key)) prefs.getInt(key, 0) else null }
                .getOrNull()
                ?.let { return it }
        }
        val xml = runCatching { remotePreferencesText?.invoke() }.getOrNull() ?: return null
        return Regex("name=\"$key\"\\s+value=\"(-?\\d+)\"").find(xml)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 通用远程偏好读取（**boolean**）。
     *
     * ⚠ 2026-10-21 修正：改成 **prefs 文件原文优先**。原因：`getRemotePreferences` 是框架缓存，
     * App 刚改完开关它仍可能返回旧值（实测：开关已 true，miwallpaper 侧仍读到 false，
     * 「视差效果覆盖桌面」完全不生效）。文件原文是实时的，框架通道只作兜底。
     * 300ms 缓存：`updateKeyguardWallpaperRatio` 这类每帧调用不会反复读文件，
     * 开关切换最多 300ms 后生效（用户无感）。
     */
    fun remoteBoolean(key: String): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        boolCache[key]?.let { (at, value) ->
            if (now - at < REMOTE_BOOL_CACHE_MS) return value
        }
        val value = readRemoteBoolean(key)
        boolCache[key] = now to value
        return value
    }

    /** 通用远程偏好读取（**String**）。文件原文优先（同 [remoteBoolean] 的理由），框架通道兜底。 */
    fun remoteString(key: String): String? {
        val xml = runCatching { remotePreferencesText?.invoke() }.getOrNull()
        if (xml != null) {
            Regex("name=\"$key\"\\s+value=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)?.let { return it }
        }
        return remotePreferences?.let { prefs ->
            runCatching { if (prefs.contains(key)) prefs.getString(key, null) else null }.getOrNull()
        }
    }

    private fun readRemoteBoolean(key: String): Boolean? {
        runCatching { remotePreferencesText?.invoke() }.getOrNull()?.let { xml ->
            Regex("name=\"$key\"\\s+value=\"(true|false)\"")
                .find(xml)?.groupValues?.get(1)?.let { return it.toBoolean() }
        }
        remotePreferences?.let { prefs ->
            runCatching { if (prefs.contains(key)) prefs.getBoolean(key, false) else null }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    /** 读系统属性（`android.os.SystemProperties` 是 @hide，反射取）。读不到返回空串。 */
    fun systemProperty(key: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
    }.getOrDefault("")

    /** [remoteBoolean] 的短缓存（key → 时间戳 + 值）。 */
    private val boolCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean?>>()
    private const val REMOTE_BOOL_CACHE_MS = 300L

    /** 诊断用：把 prefs 文件原文整段读出来（读不到返回 null）。 */
    fun remotePrefsTextOrNull(): String? = runCatching { remotePreferencesText?.invoke() }.getOrNull()

    private var unlockAt = 0L
    private var unlockValue = false
    private var unlockLogged = false

    /**
     * 「解除景深遮挡限制」的**统一实时读取**（1 秒缓存）—— 「景深」那些 hook 都用它。
     *
     * 两条来源，**取或**（2026-10-01 v11 改：原来是"远程偏好能用就只信它"，
     * 结果真机上远程偏好一直返回 false —— 日志里连续 10+ 次
     * `景深开关通道：…当前=false` —— 整套景深 hook 因此静默空转）：
     *
     * 1. **远程偏好**（[occlusionRemovedRemote]）：不需要 Context。`DepthAvoidEvaluator$Threshold`
     *    的构造函数 hook 只有它可用。⚠️ **不能只信它**。
     * 2. **provider**（[depthOcclusionViaProvider] → `MaskProvider.METHOD_DEPTH`）：模块 app 自己的
     *    `SharedPreferences`，最权威；**零副作用**（不跑 syncFiles / 换壁纸检测 / 上报），
     *    所以可以放心地每次都问（需要 Context）。
     *
     * 首次（以及结果翻转时）会把两条通道各自的值打出来 —— 那是"开关到底读到没有"的直接证据。
     */
    fun depthUnlockEnabled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - unlockAt < 1_000L) return unlockValue
        unlockAt = now
        val remote = occlusionRemovedRemote()
        val context = appContext()
        val provider = context?.let { depthOcclusionViaProvider(it) } ?: false
        val value = remote || provider
        if (!unlockLogged || value != unlockValue) {
            unlockLogged = true
            log(
                "景深开关：远程偏好=$remote，provider=$provider" +
                    "${if (context == null) "（appContext=null，provider 读不了）" else ""}" +
                    " ⇒ 生效=$value",
            )
        }
        unlockValue = value
        return value
    }

    /**
     * 只读「解除景深遮挡限制」—— 走 provider 的 `METHOD_DEPTH`。
     *
     * **刻意不用 [depthOcclusionRemoved]**（那个走 `METHOD_CONFIG`，会顺手跑
     * `syncFiles` / 换壁纸检测 / 上报，那是"文件替换"那条链的副作用）。
     */
    private fun depthOcclusionViaProvider(context: Context): Boolean = runCatching {
        context.contentResolver
            .call(MaskProvider.configUri(), MaskProvider.METHOD_DEPTH, null, null)
            ?.getBoolean(MaskProvider.KEY_DEPTH_OCCLUSION, false) == true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 换壁纸检测（替换链兜底 + 视差自动关闭）

    /** 轮询判据的最小间隔。真正灵敏的是 [onTemplateInfoWritten] 那个事件钩子，这里只是兜底。 */
    private const val VERSION_CHECK_INTERVAL_MS = 3_000L

    private var lastVersionAt = 0L

    /**
     * 兜底检查：壁纸判据和记录的不一样就动作 —— 分两条独立的链：
     *
     * * **替换链**：保护期内换壁纸 → 解除保护。真正的"用户换壁纸"信号靠 [onTemplateInfoWritten]；
     *   这条负责覆盖"用户根本没打开编辑器、而是在系统设置里换了壁纸"之类的情况
     *   （那时编辑器会自己刷新模板配置，配置又会被下一次 hook 点读到）。
     * * **视差链**：「壁纸改变后自动关闭替换」开关开着、且视差生效中 → 自动关闭视差。
     *
     * 指纹是 [MaskPrefs.FINGERPRINT_WEAK] 时不做自动动作 —— 没有可靠判据的情况下乱动作，
     * 比不动作更糟（1.4 就是这么把保护提前结束掉的）。
     */
    private fun checkWallpaperChanged(context: Context, config: HookConfig) {
        val replaceWatch = replaceWatchOf(config)
        val parallaxWatch = parallaxWatchOf(config)
        val spatialWatch = spatialWatchOf(config)
        val rasterWatch = rasterWatchOf(config)
        if (!replaceWatch && !parallaxWatch && !spatialWatch && !rasterWatch) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVersionAt < VERSION_CHECK_INTERVAL_MS) return
        lastVersionAt = now

        val version = runCatching { WallpaperVersion.compute(context) }.getOrNull() ?: return
        if (!version.usable) return
        if (replaceWatch) checkReplaceBranch(context, config, version)
        if (parallaxWatch) checkParallaxBranch(context, config, version)
        if (spatialWatch) checkSpatialBranch(context, config, version)
        if (rasterWatch) checkRasterBranch(context, config, version)
    }

    /**
     * 事件钩子：编辑器每写一次 `constant_template_editor_info` 都会进来。
     *
     * 这是"用户点了应用"的唯一咽喉（`TemplateApiImpl.writeTemplateInfo`），拿它的入参直接算判据，
     * 比定期回读 Settings 更快也更准。同一个壁纸重新应用时判据不变，不会误动作。
     */
    fun onTemplateInfoWritten(templateInfo: String?) {
        val context = appContext() ?: return
        val config = config(context) ?: return
        val replaceWatch = replaceWatchOf(config)
        val parallaxWatch = parallaxWatchOf(config)
        val spatialWatch = spatialWatchOf(config)
        val rasterWatch = rasterWatchOf(config)
        if (!replaceWatch && !parallaxWatch && !spatialWatch && !rasterWatch) return

        val version = runCatching { WallpaperVersion.computeFrom(context, templateInfo) }.getOrNull() ?: return
        if (!version.usable) return
        if (replaceWatch) checkReplaceBranch(context, config, version)
        if (parallaxWatch) checkParallaxBranch(context, config, version)
        if (spatialWatch) checkSpatialBranch(context, config, version)
        if (rasterWatch) checkRasterBranch(context, config, version)
    }

    /** 替换链的守护条件：保护期内 + 基准指纹可用（空 / weak 都不自动解除）。 */
    private fun replaceWatchOf(config: HookConfig): Boolean = config.protecting &&
        config.injectedWallpaperMd5.isNotEmpty() &&
        config.injectedWallpaperMd5 != MaskPrefs.FINGERPRINT_WEAK

    /** 视差链的守护条件：视差生效中 + 「壁纸改变后自动关闭替换」开着。 */
    private fun parallaxWatchOf(config: HookConfig): Boolean =
        config.parallaxActive && config.parallaxAutoOff

    /**
     * 空间壁纸的守护条件：**只看是否生效中**（2026-10-29）。
     *
     * ⚠️ **不能在这里要求基准非空** —— 基准为空 / weak 时的"惰性补记"就在
     * [checkSpatialBranch] 里，把条件放到这里会让补记永远执行不到，
     * 于是"老版本升上来"那种情况就永久不检测了（2026-10-29 实测踩到）。
     * 视差的 `parallaxWatchOf` 也是同样的写法：只看开关，基准判断在分支里。
     *
     * 另外，空间壁纸**没有开关** —— 用户定的是"检测到换壁纸就无条件关"。
     */
    private fun spatialWatchOf(config: HookConfig): Boolean = config.spatialActive

    /**
     * 光栅壁纸的守护条件：**只看是否生效中**（2026-11-05，与空间完全同构）。
     *
     * 同样不能在这里要求基准非空 —— 惰性补记在 [checkRasterBranch] 里，
     * 放这里会让"老版本升上来"的用户永久不检测。
     */
    private fun rasterWatchOf(config: HookConfig): Boolean = config.rasterActive

    /** 替换链：判据变化 → 解除保护；判据升级（换尺子）→ 只更新指纹。 */
    private fun checkReplaceBranch(context: Context, config: HookConfig, version: WallpaperVersion.Version) {
        val stored = config.injectedWallpaperMd5
        if (version.value == stored) return
        if (WallpaperVersion.kindOf(version.value) != WallpaperVersion.kindOf(stored)) {
            // 换了尺子（比如之前只算得出文件摘要，现在读得到模板配置了），不是换了壁纸：只更新指纹。
            log("壁纸判据升级（${version.detail}），只更新指纹不解除保护")
            updateFingerprint(context, version)
            return
        }
        log("壁纸已变化（${version.detail}），解除保护")
        disarm(context, MaskProvider.DISARM_BY_WALLPAPER_CHANGE)
    }

    /**
     * 视差链：壁纸判据变化 → 自动关闭视差。
     *
     * 基准（`parallax_wallpaper_fp`）为空 / weak 时**惰性补记**当前判据、不动作
     * （保守：宁可下轮再比，也不拿一个来路不明的基准乱关）；判据升级（换尺子）→ 只更新基准。
     */
    private fun checkParallaxBranch(context: Context, config: HookConfig, version: WallpaperVersion.Version) {
        val base = config.parallaxWallpaperFp
        if (base.isEmpty() || base == MaskPrefs.FINGERPRINT_WEAK) {
            log("视差基准：首次记录（${version.detail}）")
            updateParallaxFingerprint(context, version)
            return
        }
        if (version.value == base) return
        if (WallpaperVersion.kindOf(version.value) != WallpaperVersion.kindOf(base)) {
            log("视差基准：判据升级（${version.detail}），只更新基准不关闭")
            updateParallaxFingerprint(context, version)
            return
        }
        log("视差壁纸：壁纸已变化（${version.detail}），自动关闭替换")
        parallaxAutoOffFromWallpaper(context, version.detail)
    }

    /** 指纹变了但壁纸没变（判据升级 / weak 占位被真判据替换）时，只更新替换链指纹。 */
    private fun updateFingerprint(context: Context, version: WallpaperVersion.Version) {
        runCatching {
            val extras = Bundle().apply {
                putString(MaskProvider.KEY_WALLPAPER_MD5, version.value)
            }
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_FINGERPRINT,
                null,
                extras,
            )
        }.onFailure { error -> log("更新壁纸指纹失败：$error") }
    }

    /** 更新「视差基准」指纹（惰性补记 / 判据升级时用；不碰开关与指令号）。 */
    private fun updateParallaxFingerprint(context: Context, version: WallpaperVersion.Version) {
        runCatching {
            val extras = Bundle().apply {
                putString(MaskProvider.KEY_PARALLAX_FP, version.value)
            }
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_PARALLAX_FP,
                null,
                extras,
            )
        }.onFailure { error -> log("更新视差基准失败：$error") }
    }

    /**
     * **空间壁纸：壁纸判据变化 → 无条件自动关闭**（2026-10-29）。
     *
     * 与 [checkParallaxBranch] 同构，差别只有两处：
     *
     * 1. **没有开关**（用户定：检测到就关）；
     * 2. **不需要广播** —— SystemUI 侧读的是 `SpatialFlag` 系统属性，provider 改完状态后
     *    它自己轮询到 `active=0` 就会停渲染器、拆层、把系统主体插回去。
     *
     * 判据的三条保护与视差逐字一致：基准空 / weak ⇒ 惰性补记不动作（升级保护）；
     * 判据升级（换尺子）⇒ 只更新基准不关闭。
     */
    private fun checkSpatialBranch(context: Context, config: HookConfig, version: WallpaperVersion.Version) {
        val base = config.spatialWallpaperFp
        if (base.isEmpty() || base == MaskPrefs.FINGERPRINT_WEAK) {
            log("空间壁纸基准：首次记录（${version.detail}）")
            updateSpatialFingerprint(context, version)
            return
        }
        if (version.value == base) return
        if (WallpaperVersion.kindOf(version.value) != WallpaperVersion.kindOf(base)) {
            log("空间壁纸基准：判据升级（${version.detail}），只更新基准不关闭")
            updateSpatialFingerprint(context, version)
            return
        }
        log("空间壁纸：壁纸已变化（${version.detail}），自动关闭")
        spatialAutoOffFromWallpaper(context, version.detail)
    }

    /** 更新「空间壁纸基准」指纹（惰性补记 / 判据升级时用；不碰开关与指令号）。 */
    private fun updateSpatialFingerprint(context: Context, version: WallpaperVersion.Version) {
        runCatching {
            val extras = Bundle().apply {
                putString(MaskProvider.KEY_SPATIAL_FP, version.value)
            }
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_SPATIAL_FP,
                null,
                extras,
            )
        }.onFailure { error -> log("更新空间壁纸基准失败：$error") }
    }

    /**
     * **光栅壁纸：壁纸判据变化 → 无条件自动关闭**（2026-11-05，与空间同构）。
     *
     * 三条保护逐字一致：基准空 / weak ⇒ 惰性补记不动作（升级保护）；
     * 判据升级（换尺子）⇒ 只更新基准不关闭。
     */
    private fun checkRasterBranch(context: Context, config: HookConfig, version: WallpaperVersion.Version) {
        val base = config.rasterWallpaperFp
        if (base.isEmpty() || base == MaskPrefs.FINGERPRINT_WEAK) {
            log("光栅壁纸基准：首次记录（${version.detail}）")
            updateRasterFingerprint(context, version)
            return
        }
        if (version.value == base) return
        if (WallpaperVersion.kindOf(version.value) != WallpaperVersion.kindOf(base)) {
            log("光栅壁纸基准：判据升级（${version.detail}），只更新基准不关闭")
            updateRasterFingerprint(context, version)
            return
        }
        log("光栅壁纸：壁纸已变化（${version.detail}），自动关闭")
        rasterAutoOffFromWallpaper(context, version.detail)
    }

    /** 更新「光栅壁纸基准」指纹（惰性补记 / 判据升级时用；不碰开关与指令号）。 */
    private fun updateRasterFingerprint(context: Context, version: WallpaperVersion.Version) {
        runCatching {
            val extras = Bundle().apply {
                putString(MaskProvider.KEY_RASTER_FP, version.value)
            }
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_RASTER_FP,
                null,
                extras,
            )
        }.onFailure { error -> log("更新光栅壁纸基准失败：$error") }
    }

    /**
     * 「换壁纸 → 关闭空间壁纸」的收尾。
     *
     * 全部动作在 **provider 进程**里完成（改 prefs → 推系统属性 → toast → 延迟重启壁纸进程），
     * 与 App 手动关「关闭替换」共用 `SpatialControl.disable` —— 本进程只负责发起 + 失效 config 缓存。
     */
    private fun spatialAutoOffFromWallpaper(context: Context, detail: String) {
        runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_SPATIAL_AUTO_OFF,
                detail,
                null,
            )
            configAt = 0L
            configValue = null
            lastVersionAt = 0L
            lastReportAt = SystemClock.elapsedRealtime()
        }.onFailure { error -> log("自动关闭空间壁纸失败：$error") }
    }

    /**
     * 「换壁纸 → 关闭光栅壁纸」的收尾。
     *
     * 全部动作在 **provider 进程**里完成（改 prefs → 推系统属性 → toast），
     * 与 App 手动「取消应用」共用 `RasterControl.disable` —— 本进程只负责发起 + 失效 config 缓存。
     */
    private fun rasterAutoOffFromWallpaper(context: Context, detail: String) {
        runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_RASTER_AUTO_OFF,
                detail,
                null,
            )
            configAt = 0L
            configValue = null
            lastVersionAt = 0L
            lastReportAt = SystemClock.elapsedRealtime()
        }.onFailure { error -> log("自动关闭光栅壁纸失败：$error") }
    }

    /**
     * 「换壁纸 → 自动关闭视差」的收尾：先请 provider 写权威状态（置 false + 清基准 + 生成指令号），
     * 再由本进程把广播发给 SystemUI —— 与 App 的「关闭替换」同一顺序（状态在前、广播在后），
     * SystemUI 侧以 config 为准、广播只是触发。广播发失败也没关系：SystemUI 自愈
     * （入窗 / 对账时读 config）会兜底移除背景层。
     */
    private fun parallaxAutoOffFromWallpaper(context: Context, detail: String) {
        runCatching {
            val result = context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_PARALLAX_AUTO_OFF,
                detail,
                null,
            )
            val token = result?.getLong(MaskProvider.KEY_PARALLAX_TOKEN, 0L) ?: 0L
            if (token > 0L) {
                context.sendBroadcast(
                    Intent(ParallaxBg.ACTION).apply {
                        setPackage(SystemUiPackageName)
                        putExtra(ParallaxBg.EXTRA_TOKEN, token)
                        putExtra(ParallaxBg.EXTRA_ON, false)
                    },
                )
            }
            configAt = 0L
            configValue = null
            lastVersionAt = 0L
            lastReportAt = SystemClock.elapsedRealtime()
        }.onFailure { error -> log("自动关闭视差失败：$error") }
    }

    /** 解除保护：清候选项 + 注入快照 + 指纹，并让界面能立刻看到（provider 会 bump revision）。 */
    private fun disarm(context: Context, reason: String) {
        runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_CLEAR,
                reason,
                null,
            )
            configAt = 0L
            configValue = null
            lastVersionAt = 0L
            lastReportAt = SystemClock.elapsedRealtime()
        }.onFailure { error -> log("解除保护失败：$error") }
    }

    // ---------------------------------------------------------------- 替换图

    /** 模块里用户选的那张图；revision 变化时自动重新解码。 */
    fun moduleImage(context: Context, role: MaskRole, revision: Long): Bitmap? {
        bitmaps[role.id]?.let { cached ->
            if (cached.revision == revision) return cached.bitmap
        }
        val loaded = runCatching {
            context.contentResolver.openInputStream(MaskProvider.imageUri(role))?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull() ?: return null
        bitmaps[role.id] = CachedBitmap(revision, loaded)
        return loaded
    }

    /**
     * 该角色对应的最终位图：
     * * `subject_mask` → 用户给的灰度遮罩；
     * * `subject` / `crop_subject` → 用户给的主体抠图；没给主体但有遮罩时，用"壁纸 ∩ 遮罩"现场合成。
     */
    fun desiredBitmap(context: Context, config: HookConfig, role: MaskRole): Bitmap? {
        if (role == MaskRole.SUBJECT_MASK) {
            return moduleImage(context, MaskRole.SUBJECT_MASK, config.revision)
        }
        return desiredSubject(context, config)
    }

    fun desiredSubject(context: Context, config: HookConfig): Bitmap? {
        desiredCache?.let { if (it.first == config.revision) return it.second }

        val subject = if (config.has(MaskRole.SUBJECT)) {
            moduleImage(context, MaskRole.SUBJECT, config.revision)
        } else {
            null
        }
        if (subject != null) {
            desiredCache = config.revision to subject
            return subject
        }

        if (config.has(MaskRole.SUBJECT_MASK)) {
            val mask = moduleImage(context, MaskRole.SUBJECT_MASK, config.revision) ?: return null
            val wallpaper = readCurrentWallpaper(context) ?: return null
            val composed = runCatching { composeSubject(wallpaper, mask) }.getOrNull() ?: return null
            desiredCache = config.revision to composed
            return composed
        }
        return null
    }

    /** 和 TemplatePreviewImageComposer.composeSubjectImage 同一套做法，见 [MaskComposer]。 */
    internal fun composeSubject(wallpaper: Bitmap, mask: Bitmap): Bitmap =
        MaskComposer.compose(wallpaper, mask)

    /**
     * 把主体裁成显示空间（`crop_subject` 该有的样子）。
     *
     * 裁切矩形读的是当下的模板配置 —— 编辑器缩放/移动壁纸后会把 `positionInfo` 写进去，
     * 所以这里能跟着变。拿不到就返回 null，调用方退回"原样写入"并记一条日志。
     *
     * 顺便校验一次"主体和裁切矩形在不在同一个空间"：矩形来自原图坐标，主体也该是原图空间
     * （尺寸 ≥ 矩形右下角）。装不下就说明主体不是编辑器用的那个尺寸，裁出来必然错位 ——
     * 这种情况明确写进日志，别让用户对着一个错位的锁屏猜原因。
     */
    private fun cropSubjectForDisplay(context: Context, subject: Bitmap): Bitmap? = runCatching {
        val position = WallpaperVersion.positionOf(WallpaperVersion.readTemplateInfo(context))
        if (position != null && position.valid &&
            !WallpaperSource.fits(subject.width to subject.height, position)
        ) {
            log(
                "主体 ${subject.width}×${subject.height} 装不下裁切矩形 $position：" +
                    "它多半不是「原图空间」的尺寸（`templates/current/wallpaper` 那份），crop_subject 会对不齐",
            )
        }
        var cropped = MaskComposer.cropToDisplay(subject, position)
        // 尺度对齐：一级预览的当前卡片把「已应用壁纸」和 crop_subject 当一对叠画，
        // 位置来自按壁纸贴图算出的矩阵 —— 两者像素尺寸要一致，否则原图与屏幕差得越多、错位越明显。
        val applied = WallpaperSource.appliedLockWallpaper(context)?.let { MaskStore.readSize(it) }
        if (cropped == null && applied != null) {
            val aspect = applied.first.toFloat() / applied.second
            cropped = MaskComposer.centerCropToAspect(subject, aspect)
            if (cropped != null) {
                vlog("没有裁切矩形：按已应用壁纸的宽高比居中裁 → ${cropped.width}×${cropped.height}")
            }
        }
        if (cropped != null && applied != null) {
            val size = cropped.width to cropped.height
            if (MaskComposer.sameAspect(size, applied)) {
                MaskComposer.fitTo(cropped, applied.first, applied.second)?.let { scaled ->
                    vlog("crop_subject 对齐到已应用壁纸：${size.first}×${size.second} → ${scaled.width}×${scaled.height}")
                    if (cropped !== subject) cropped.recycle()
                    cropped = scaled
                }
            } else {
                vlog("crop_subject $size 与已应用壁纸 ${applied.first}×${applied.second} 宽高比不一致，不缩放")
            }
        }
        cropped
    }.getOrNull()

    /**
     * 合成主体要用的"当前壁纸" —— **必须是原图空间**那份。
     *
     * 1. 按 [WallpaperSource] 的候选顺序找，原图空间（`templates/current/wallpaper`）优先，
     *    配置里记着的原图路径兜底；
     * 2. 都读不到时退到 provider：root 侧探测会把挑好的那张拷进模块的 filesDir，
     *    只有它能跨进程读（`content://…/wallpaper`）。
     *
     * 为什么强调空间：合成是「壁纸 ∩ 遮罩」，而遮罩在**原图坐标**里；拿显示空间那份（已被裁到
     * 屏幕比例、尺寸随缩放变化）来合成，主体就会整体错位，`crop_subject` 跟着错。
     */
    private fun readCurrentWallpaper(context: Context): Bitmap? {
        val candidate = WallpaperSource.resolveAny(context)
        if (candidate != null && candidate.sourceSpace) {
            runCatching { BitmapFactory.decodeFile(candidate.path) }.getOrNull()?.let { bitmap ->
                reportWallpaperSize(context, bitmap, sourceSpace = true)
                return bitmap
            }
        }
        if (candidate != null && !candidate.sourceSpace) {
            log(
                "只找到显示空间的壁纸（${candidate.tag} ${candidate.path}）——它和遮罩不在同一坐标系，" +
                    "不能拿来合成主体；请在锁屏编辑里应用一次景深壁纸，让 `templates/current/wallpaper` 重新生成",
            )
        }
        return null
    }

    /** 上次回写过的壁纸尺寸，重复的就不必再走一次 binder。 */
    private var reportedWallpaperSize: String? = null

    /** 已经处理过的「探测请求号」——同一次请求不重复探测。 */
    private var lastProbedToken = 0L

    /**
     * 按模块 App 的请求做一次**壁纸原图探测**（点「选择图片」时由 App 发起）。
     *
     * 为什么放在 hook 里：`<filesDir>/templates/current/wallpaper` 是宿主自己的私有文件，
     * 只有本进程读得到 —— 没有 su、也不受跨 App 命名空间/SELinux 影响（真机上 su 那条路会
     * 因为"应用侧看不到 /data/user_de/0/com.miui.aod"而拿不到）。
     *
     * 规则（用户要求）：**只读 `templates/current/wallpaper` 这一份**，不挑候选、不做几何兜底；
     * 读不到就回 `0x0@请求号` 让 App 别再等。
     */
    fun probeWallpaperIfRequested(context: Context) {
        val token = runCatching { probeTokenRemote() }.getOrDefault(0L)
        probeWallpaperForToken(context, token)
    }

    /**
     * 宿主进程里做一次探测：**只读 `templates/current/wallpaper` 这一份**，带上请求号回写。
     *
     * 两个来源：
     * * **主路**：`TemplateFileProvider.call` 的 `arg` —— 模块 App 用
     *   `content call … --method callGetHistoryTemplates --arg <请求号>` 直接点名，
     *   不依赖远程偏好是否实时（真机上那两条通道都可能停在旧快照上）；
     * * 兜底：`onPackageReady` 启动线程里从 prefs 读到的请求号（[probeWallpaperIfRequested]）。
     *
     * 文件可能正被编辑器重写（删→重建），所以读不到会重试 3 次；仍读不到才回 `0x0@请求号`，
     * 让 App 别再干等。
     */
    fun probeWallpaperForToken(context: Context, token: Long) {
        if (token <= 0L || token <= lastProbedToken) return
        lastProbedToken = token
        val file = File(File(context.filesDir, "templates/current"), "wallpaper")
        repeat(3) { attempt ->
            val size = runCatching { MaskStore.readSize(file) }.getOrNull()
            if (size != null) {
                log(
                    "壁纸探测（请求 $token，第 ${attempt + 1} 次）：" +
                        "${file.absolutePath} = ${size.first}×${size.second}",
                )
                reportWallpaperSize(context, size.first, size.second, sourceSpace = true, token = token)
                return
            }
            runCatching { Thread.sleep(150L) }
        }
        log("壁纸探测（请求 $token）：${file.absolutePath} 不存在或解码失败")
        reportWallpaperSize(context, 0, 0, sourceSpace = true, token = token)
    }

    /**
     * 宿主进程**刚起来**时调一次：如果模块 App 正在等探测，就尽快响应。
     *
     * `onPackageReady` 那一刻 Application 可能还没建好（[appContext] 仍为 null），所以轮询几秒。
     */
    fun probeWallpaperOnStart() {
        if (runCatching { probeTokenRemote() }.getOrDefault(0L) <= lastProbedToken) return
        val worker = Thread {
            var i = 0
            while (i < 30) {
                val context = appContext()
                if (context != null) {
                    probeWallpaperIfRequested(context)
                    break
                }
                runCatching { Thread.sleep(100L) }
                i++
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    /** 已经处理过的「景深检查请求号」——同一次请求不重复读。 */
    private var lastDepthProbedToken = 0L

    /**
     * 响应模块 App 的「点选图前的景深检查」：读 `wallpaper_effect_type_2` 并带请求号回写
     * （入口：`TemplateFileProvider.call` 拦截里的 `--arg depth=<请求号>`）。
     *
     * 判据用系统自己的那把尺：`depthEffectEnableInner = (2 == value)` —— 值 `2` = 景深开着。
     * 读取失败回写 `-1`（App 视作"未知"放行，不拦人）。
     *
     * [context] 为 null（进程刚被拉起、Application 还没建好）时**轮询等待几秒再读** ——
     * 与 [probeWallpaperOnStart] 同一招；否则冷启动路径上这次检查会永远超时、等于没检查。
     */
    fun probeDepthState(context: Context?, token: Long) {
        if (token <= 0L || token <= lastDepthProbedToken) return
        if (context != null) {
            lastDepthProbedToken = token
            reportDepthState(context, token)
            return
        }
        val worker = Thread {
            var i = 0
            while (i < 30) {
                val ctx = appContext()
                if (ctx != null) {
                    lastDepthProbedToken = token
                    reportDepthState(ctx, token)
                    return@Thread
                }
                runCatching { Thread.sleep(100L) }
                i++
            }
            log("景深检查（请求 $token）：拿不到 Context，放弃")
        }
        worker.isDaemon = true
        worker.start()
    }

    /** 读系统的景深开关值 —— `wallpaper_effect_type_2`（`2` = 开）；读取失败返回 `-1`。 */
    private fun readWallpaperEffectType(context: Context): Int = runCatching {
        android.provider.Settings.Secure.getInt(
            context.contentResolver,
            SETTING_WALLPAPER_EFFECT_TYPE,
            0,
        )
    }.getOrElse {
        // 少数实现把它存成字符串（Settings.Secure 两种都可能）：兜底再按字符串读一次。
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                SETTING_WALLPAPER_EFFECT_TYPE,
            )?.trim()?.toIntOrNull() ?: -1
        }.getOrDefault(-1)
    }

    private fun reportDepthState(context: Context, token: Long) {
        val value = readWallpaperEffectType(context)
        log("景深检查（请求 $token）：wallpaper_effect_type_2 = $value（2 = 开）")
        runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_DEPTH_STATE,
                "$value@$token",
                null,
            )
        }.onFailure { error -> log("回写景深状态失败：$error") }
    }

    /**
     * 读「探测请求号」：远程偏好 + prefs 文件原文两条取**较大值**。
     *
     * 为什么还要读文件原文：`getRemotePreferences` 拿到的句柄可能停在旧快照上，而 App 是刚
     * `commit()` 的新值；`openRemoteFile` 读原文永远是实时的（和景深开关那套是同一个思路）。
     */
    private fun probeTokenRemote(): Long {
        var token = 0L
        remotePreferences?.let { prefs ->
            runCatching { prefs.getLong(MaskPrefs.KEY_PROBE_TOKEN, 0L) }
                .getOrDefault(0L)
                .let { if (it > token) token = it }
        }
        val xml = runCatching { remotePreferencesText?.invoke() }.getOrNull()
        if (!xml.isNullOrEmpty()) {
            Regex("name=\"${MaskPrefs.KEY_PROBE_TOKEN}\"\\s+value=\"(\\d+)\"")
                .find(xml)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.let { if (it > token) token = it }
        }
        return token
    }

    /**
     * 把抓到的壁纸尺寸回写给模块，带上**空间标记**。
     *
     * 界面导入替换图时要用**原图**的尺寸当对齐锚点（不是屏幕尺寸、也不是裁切矩形那份的尺寸，
     * 见 [WallpaperSource.currentSize]）。而模块 app 进程自己往往读不到那些文件 ——
     * 本进程（com.miui.aod）读得到，顺手带回去；显示空间的尺寸只作诊断对照，别进锚点。
     */
    private fun reportWallpaperSize(context: Context, bitmap: Bitmap, sourceSpace: Boolean) =
        reportWallpaperSize(context, bitmap.width, bitmap.height, sourceSpace)

    /**
     * [token] > 0 = 这是对 App 某次「探测请求」的回答，必须回写（哪怕值和上次一样），
     * App 侧靠它结束等待（`0x0@token` 表示探测失败）。
     */
    private fun reportWallpaperSize(
        context: Context,
        width: Int,
        height: Int,
        sourceSpace: Boolean,
        token: Long = 0L,
    ) {
        val value = (if (sourceSpace) MaskProvider.SIZE_PREFIX_SOURCE else MaskProvider.SIZE_PREFIX_DISPLAY) +
            "${width}x${height}" + if (token > 0L) "@$token" else ""
        if (token <= 0L && value == reportedWallpaperSize) return
        reportedWallpaperSize = value
        runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_WALLPAPER_SIZE,
                value,
                null,
            )
        }.onFailure { error -> log("回写壁纸尺寸失败：$error") }
    }

    // ---------------------------------------------------------------- 落盘

    /** 已经处理过的「注入请求号」——同一次请求不重复落盘。 */
    private var lastAppliedToken = 0L

    /**
     * 「立即替换」的 **hook 落盘**（2026-10-04：不再走 root 脚本）。
     *
     * 流程：App 先把 `protecting=true` + 指纹 + 注入快照落好、bump `revision`，再用 provider 调用
     * （`--arg apply=<请求号>`）把本进程叫起来 —— 这里收到后：
     * 1. `syncFiles()` 把 `subject` / `subject_mask` / `crop_subject` 写进系统真正使用的那几个路径
     *    （本进程就是宿主，权限和编辑器一致，不需要 su）；
     * 2. `refreshDepth()` 切一次 `wallpaper_effect_type_2`，让 SystemUI 重新解码景深层
     *    （aod 有 `WRITE_SECURE_SETTINGS`，和 root 脚本里的 `refresh_depth` 同一招）；
     * 3. 回执给 App（`METHOD_APPLY_ACK`）。
     */
    fun applyInjection(context: Context, token: Long) {
        if (token <= 0L || token <= lastAppliedToken) return
        lastAppliedToken = token
        val config = config(context)
        val hasImage = config != null &&
            (config.has(MaskRole.SUBJECT) || config.has(MaskRole.SUBJECT_MASK))
        if (config == null || !config.protecting || !hasImage) {
            log(
                "hook 注入：跳过（config=${config != null} protecting=${config?.protecting} " +
                    "有候选图=$hasImage）",
            )
        } else {
            syncFiles(context, config)
            refreshDepth()
        }
        runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_APPLY_ACK,
                token.toString(),
                null,
            )
        }.onFailure { error -> log("注入回执失败：$error") }
    }

    /**
     * 让 SystemUI 重新解码景深层 —— 与 root 脚本里的 `refresh_depth` 同一招：
     * 把 `wallpaper_effect_type_2` 切到 0 再切回原值（净变化为零），
     * `KeyguardPanelViewController` 上的 `MiuiSettingObserver` 会因此重跑 `updateDeductedImageView()`。
     *
     * aod 声明了 `WRITE_SECURE_SETTINGS`（反编译清单里核对过），所以这里不需要 su。
     */
    private fun refreshDepth() {
        val context = appContext() ?: return
        runCatching {
            val key = "wallpaper_effect_type_2"
            val current = android.provider.Settings.Secure.getString(context.contentResolver, key)
            if (current == null) {
                log("触发重新解码：读不到 $key，跳过")
                return@runCatching
            }
            android.provider.Settings.Secure.putString(context.contentResolver, key, "0")
            Thread.sleep(120L)
            android.provider.Settings.Secure.putString(context.contentResolver, key, current)
            log("已触发 SystemUI 重新解码景深层（$key: $current → 0 → $current）")
        }.onFailure { error -> log("触发重新解码失败：$error") }
    }

    /** 把系统真正使用的文件替换掉。 */
    fun syncFiles(context: Context, config: HookConfig) {
        readState(context)?.let { stored ->
            if (syncedRevision == Long.MIN_VALUE) {
                syncedRevision = stored.revision
            }
        }
        if (syncedRevision == Long.MIN_VALUE) {
            syncedRevision = -1L
        }

        val revisionChanged = config.revision != syncedRevision

        // ⚠️ **正向写入必须处于"注入中"（`protecting`）才做** —— 2026-09-30 修的 bug。
        //
        // 之前这里只看 `revision` 变没变，而 `revision` 在**两处**都会动：
        //   ① 用户点「立即替换」 → `RootReplacer` 收尾时 bump；
        //   ② **用户只是选了一张图** → `MaskUiState.importRole` 也 bump（这是为了刷新 hook 的位图缓存）。
        // 于是 ② 也会满足这里的条件：候选图**还没点「立即替换」**，只要 `aod` 进程被唤醒一次
        // （灭亮屏时 Keyguard 重新解码模板，必然命中 hook 点 → [config] → 这里），
        // 候选就被**直接写进了系统模板文件** —— 用户看到的就是"我还没点替换，图自己上去了"。
        //
        // `protecting` 正是"用户点过替换"的标记（`RootReplacer` 先 arm 再跑脚本），
        // 界面文案本来也是「点「立即替换」生效」，所以拿它当闸门最贴切。
        //
        // 注意：**跳过时不要在下面推进 `syncedRevision`** —— 留着"待处理"状态，
        // 等真的进了保护期再补写一次（写入是幂等的，等于多一层兜底）。
        val applyRequested = revisionChanged && config.protecting
        if (!applyRequested) return

        val applied = mutableListOf<String>()
        if (applyRequested) {
            if (config.has(MaskRole.SUBJECT_MASK)) {
                desiredBitmap(context, config, MaskRole.SUBJECT_MASK)?.let { mask ->
                    if (replaceFile(targetFile(context, MaskRole.SUBJECT_MASK), mask)) {
                        applied += MaskRole.SUBJECT_MASK.id
                    }
                }
            }
            desiredSubject(context, config)?.let { subject ->
                // 「只给了主体」时，顺手补一份配套的 `subject_mask`（2026-10-04 用户要求"保险一点"）：
                // 用主体 alpha 生成"白剪影"（和编辑器 `convertSubjectMask` 同款），
                // 这样系统/编辑器之后拿 mask 重建主体时，得到的就是我们这张主体本身。
                if (!config.has(MaskRole.SUBJECT_MASK)) {
                    MaskComposer.whiteSilhouette(subject)?.let { mask ->
                        if (replaceFile(targetFile(context, MaskRole.SUBJECT_MASK), mask)) {
                            applied += MaskRole.SUBJECT_MASK.id
                            vlog(
                                "只给了主体 → 已按主体 alpha 生成配套 subject_mask " +
                                    "(${mask.width}×${mask.height})",
                            )
                        }
                        mask.recycle()
                    }
                }
                if (replaceFile(targetFile(context, MaskRole.SUBJECT), subject)) {
                    applied += MaskRole.SUBJECT.id
                }
                // crop_subject 不是"subject 的副本"：它是把 subject 按壁纸的裁切矩形裁一刀之后的
                // **显示空间**版本（编辑器 applyEditPageTemplate 里那步 cropBitmap(bitmap, positionInfo, false)）。
                // SystemUI 拿到之后不带任何变换直接全屏画，所以不裁就会在壁纸被缩放/移动时对不齐。
                val cropped = cropSubjectForDisplay(context, subject)
                if (replaceFile(targetFile(context, MaskRole.CROP_SUBJECT), cropped ?: subject)) {
                    applied += MaskRole.CROP_SUBJECT.id
                }
                if (cropped != null && cropped !== subject) cropped.recycle()
                if (cropped == null) {
                    vlog("crop_subject 拿不到壁纸裁切矩形，原样写入（壁纸被缩放/移动过时会对不齐）")
                }
            }
        }
        syncedRevision = config.revision
        writeState(context, config.revision)
        lastReportAt = SystemClock.elapsedRealtime()
        if (applied.isNotEmpty()) {
            // 顺手记下"注入时的壁纸判据"：hook 后面靠它判断用户有没有换壁纸。
            // 拿不到就留空，provider 会（在已有值为空时）写 weak 占位 —— 那样就不自动解除。
            val version = runCatching { WallpaperVersion.compute(context) }.getOrNull()
            report(
                context,
                config,
                "已替换 ${applied.joinToString()}",
                done = true,
                wallpaperMd5 = version?.value?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** 把 PNG 写进目标路径，并按系统的习惯放开权限。 */
    internal fun replaceFile(target: File, bitmap: Bitmap): Boolean = runCatching {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
        }
        openPermissions(target)
        vlog("已替换 ${target.absolutePath}")
        true
    }.getOrElse { error ->
        log("写入 ${target.absolutePath} 失败：$error")
        false
    }

    /** 系统自己写这些文件后会 chmod 0777，这里保持一致，免得读取方没权限。 */
    private fun openPermissions(file: File) {
        runCatching {
            file.setReadable(true, false)
            file.setWritable(true, false)
            file.setExecutable(true, false)
        }
    }

    /** 模板文件都走 BitmapUtil#toFile 落盘，给任意路径写我们的图（写回兜底用）。 */
    fun writeBitmap(path: String, bitmap: Bitmap): Boolean = runCatching {
        val target = File(path)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
        }
        openPermissions(target)
        true
    }.getOrElse { error ->
        log("写入 $path 失败：$error")
        false
    }

    // ---------------------------------------------------------------- 状态与上报

    private fun stateFile(context: Context) =
        File(File(context.filesDir, "templates/current"), STATE_FILE)

    private fun readState(context: Context): StoredState? = runCatching {
        val file = stateFile(context)
        if (!file.isFile) return null
        // 兼容旧格式 `revision:restoreToken`：只取冒号前的 revision（restore 概念已删）。
        val revision = file.readText().trim().substringBefore(':').toLongOrNull() ?: return null
        StoredState(revision)
    }.getOrNull()

    private fun writeState(context: Context, revision: Long) {
        runCatching {
            val file = stateFile(context)
            file.parentFile?.mkdirs()
            file.writeText(revision.toString())
        }
    }

    private fun report(
        context: Context,
        config: HookConfig,
        detail: String,
        done: Boolean = false,
        wallpaperMd5: String? = null,
    ) {
        runCatching {
            val extras = Bundle().apply {
                putLong(MaskProvider.KEY_REVISION, config.revision)
                putString(MaskProvider.KEY_DETAIL, detail)
                putBoolean(MaskProvider.KEY_DONE, done)
                putString(MaskProvider.KEY_WALLPAPER_MD5, wallpaperMd5)
            }
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_REPORT,
                processName,
                extras,
            )
        }.onFailure { error -> log("上报注入状态失败：$error") }
    }

    private fun targetFile(context: Context, role: MaskRole): File = when (role) {
        MaskRole.SUBJECT -> File(File(context.filesDir, "templates/current"), "subject")
        MaskRole.SUBJECT_MASK -> File(File(context.filesDir, "templates/current"), "subject_mask")
        MaskRole.CROP_SUBJECT -> File(
            "/data/system/theme_magic/users/${currentUserId()}/wallpaper/crop_subject",
        )
    }

    /**
     * 公共 SDK 的 stub 里没有 `UserHandle.myUserId()`（MIUI 是用系统 SDK 编的），
     * 用 uid 反推：userId = uid / 100000，副用户同样成立。
     */
    private fun currentUserId(): Int = Process.myUid() / 100_000

    /**
     * 路径 → 替换角色。**只认"系统真正在用的那一份"**。
     *
     * `subject` / `subject_mask` 必须是 `<filesDir>/templates/current/` 下那份 ——
     * `templates/history/<时间戳>/` 里的是**各历史搭配自己的素材副本**，把那里也当成替换目标，
     * 历史搭配的遮罩就会被换成当前这一套、跟它自己的壁纸对不上（真机现象）。
     *
     * `crop_subject` 仍按名字匹配：它只有 `theme_magic` 一份是真身，history 里那份是派生副本，
     * 而调用方（`HookEntry.isDerived`）对 `crop_subject` 本来就有全放行。
     */
    fun roleOfPath(path: String?): MaskRole? {
        if (path.isNullOrEmpty()) return null
        return when (path.substringAfterLast('/')) {
            "subject_mask" -> MaskRole.SUBJECT_MASK.takeIf { isCurrentTemplateDir(path) }
            "subject" -> MaskRole.SUBJECT.takeIf { isCurrentTemplateDir(path) }
            "crop_subject" -> MaskRole.CROP_SUBJECT
            else -> null
        }
    }

    /** 路径是不是"系统当前正在用的那份模板文件"（`<filesDir>/templates/current/`）。 */
    private fun isCurrentTemplateDir(path: String): Boolean =
        path.substringBeforeLast('/', "").endsWith("/templates/current")

    /** 路径是不是历史搭配目录（`<filesDir>/templates/history/<时间戳>/`）。 */
    fun isHistoryPath(path: String?): Boolean =
        !path.isNullOrEmpty() && path.contains("/templates/history/")

    fun log(message: String) {
        val sink = logger ?: return
        runCatching { sink("[$processName] $message") }
    }

    fun vlog(message: String) {
        if (configValue?.verbose == true) log(message)
    }

    /** 界面显示"已注入 xxx"时用的标签。 */
    const val SELF_PROCESS_LABEL = "本应用（框架已注入）"

    /**
     * 系统设置里「景深开关」的键 —— 值 `2` = 开着
     * （SystemUI 侧判定就是 `depthEffectEnableInner = (2 == value)`，见 `KeyguardPanelViewController`）。
     */
    const val SETTING_WALLPAPER_EFFECT_TYPE = "wallpaper_effect_type_2"
}
