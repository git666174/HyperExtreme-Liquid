package com.frederic22.hyperextreme

import android.app.Application
import android.os.SystemClock
import com.frederic22.hyperextreme.data.LiquidFlag
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.MaskStore
import com.frederic22.hyperextreme.data.ParallaxCoverFlag
import com.frederic22.hyperextreme.data.ParallaxFeed
import com.frederic22.hyperextreme.data.RasterFlag
import com.frederic22.hyperextreme.data.SpatialFlag
import com.frederic22.hyperextreme.spatial.SpatialDepth
import com.frederic22.hyperextreme.wallpaper.SpatialServiceMode
import java.io.File

/**
 * 进程启动时做一次**残留候选清理**。
 *
 * ## 为什么放在 `Application`（= 每次进程启动）
 *
 * 用户的要求（2026-10-01）：「我们候选了一张图，把软件关掉重进就自动清空候选 ——
 * 每次软件重启如果有残留的候选自动清空候选」。
 *
 * "软件重启"在 Android 上就是**本进程重新起来**，而 `Application#onCreate` 正好是那个时刻的
 * 唯一入口（比任何 Activity 都早、且一个进程只跑一次）——
 * 所以放在这里既不会漏，也不会像放在 Activity / 界面状态里那样**每次界面重建都清一次**
 * （那会把用户刚在二级页选好的图当场抹掉：`MaskDetailActivity` 和主页各自 `remember` 一份状态）。
 *
 * ## 只清候选，不动注入快照
 *
 * 候选 = "用户刚选、还没点替换"的临时状态；注入快照 = "系统里现在就是这份"。
 * 后者跨重启必须留着（界面靠它显示「已注入」、hook 靠它继续守着文件），所以 [MaskStore.clearCandidates]
 * 只删候选。
 *
 * 写一条 `diagNote`：设置页「设置 → 测试 → 最近操作」能看到"启动清理"。
 * 不影响任何 hook 行为 —— 这一步在界面起来之前就跑完了。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 进程诞生时刻（uptimeMillis，含 sleep 不计深睡，正好用于短时长）。
        // 启动遮罩的「最短展示时长」以它为基准 —— 见 MainActivity.holdFirstFrameForLaunchAnimation。
        processStartAt = SystemClock.uptimeMillis()
        // ⚠️⚠️ **只在主进程做启动清理**（2026-10-31 真机 bug，用户报「跳到服务页应用后回来，
        // 预览框不显示图」）。
        //
        // `SpatialWallpaperService` 跑在 `:wallpaper` 独立进程，而**每个进程都会跑一遍
        // `Application#onCreate`**。那个进程被拉起的时刻，恰好是「用户刚在系统页选了我们的壁纸、
        // 但 `getWallpaperInfo()` 还没变成我们」⇒ 下面的清理判据判成"没生效"⇒ 把刚选好的候选
        // （`source` 原图副本 + `spatialSourceKey` + `spatialLastKey`）连根清掉，
        // 顺手还把 `diagNote` 写回 prefs（跨进程整体覆盖）。
        // 结果就是：壁纸正常渲染，页面上却"没选过图"。
        // 顺带：`SpatialFlag.push` / `ParallaxCoverFlag.push` 也只该由主进程推一次。
        //
        // ⚠️ 判据必须用 [Application.getProcessName]（API 28+，minSdk 33）——
        // `applicationInfo.processName` 是**清单里的声明值**（application 节点没写
        // android:process 时恒为包名），**不反映当前进程**，用它判断会永远放行。
        val procName = Application.getProcessName()
        if (procName != null && procName != packageName) return
        runCatching {
            val prefs = MaskPrefs(this)
            // 桌面层级最终状态的系统属性同步（属性用 debug. 前缀、不持久化，重启后靠这里补回）。
            // **必须传 cover && active**：miwallpaper 侧的 ParallaxDesktopGuard 只读这个属性，
            // 它要的是「桌面层级最终是否生效」，不是单独的覆盖选项。
            ParallaxCoverFlag.push(prefs.parallaxCoverDesktop && prefs.parallaxBgActive)
            // 「空间壁纸」开关的系统属性同步（同上：debug.* 属性不持久化，重启后靠这里补回）。
            // SystemUI 侧读 prefs 不可靠（陈旧快照 + openRemoteFile 读不到），只认这两个属性。
            // 「显示区域」也要一起补推：渲染器在 SystemUI 进程读不到模块的 crop.json，
            // 只认这条属性；`debug.*` 不持久化，重启后不补就是默认（= 不裁剪）。
            val spatialKey = prefs.spatialKey.takeIf { it.isNotEmpty() }
            SpatialFlag.push(
                prefs.spatialActive,
                spatialKey,
                prefs.spatialScope,
                prefs.spatialLockDepth,
                spatialKey?.let {
                    com.frederic22.hyperextreme.spatial.SpatialCrop.encodeProp(
                        com.frederic22.hyperextreme.spatial.SpatialCrop.read(this, it),
                    )
                }.orEmpty(),
            )
            // 「光栅壁纸」同理：属性不持久化，重启后靠这里补回。
            //
            // ⚠️ **服务模式必须推 false**（2026-11-04）：那条链由系统壁纸页驱动，
            // 属性一亮 hook 链也会去挂 leash ⇒ 同一张图被画两遍（错位 + 掉帧）。
            // `rasterActiveId` 在服务模式下只是 UI 指针，不能当作"要渲染"的判据。
            val rasterLsposed = prefs.rasterMountMode == MaskPrefs.RASTER_MOUNT_LSPOSED
            RasterFlag.push(
                rasterLsposed && prefs.rasterActiveId.isNotEmpty(),
                prefs.rasterActiveId.takeIf { it.isNotEmpty() },
                prefs.rasterScope,
                prefs.rasterLockDepth,
                prefs.rasterMaxAngle,
                prefs.rasterSettle,
            )
            // 「液态壁纸」同理：`debug.*` 属性不持久化，重启后靠这里补回。
            LiquidFlag.push(
                prefs.liquidActive,
                prefs.liquidKey.takeIf { it.isNotEmpty() },
                prefs.liquidScope,
                prefs.liquidMode,
                prefs.liquidTune(prefs.liquidMode),
                prefs.liquidMask,
            )
            // 开关开着但图可能失效（重装/换图）——启动时再推一次给 miwallpaper。
            if (prefs.parallaxCoverDesktop) {
                Thread { runCatching { ParallaxFeed.push(this) } }.apply { isDaemon = true }.start()
            }
            val cleared = MaskStore.clearCandidates(this)
            // 「视差壁纸效果」的背景候选：**开启中（生效中）必须保留** ——
            // SystemUI 重启后要靠它重新显示、二级页的预览也要显示；
            // 未开启时按普通候选语义随启动清空（用户定的"回到软件重启候选清空"）。
            val parallaxCleared = !prefs.parallaxBgActive && MaskStore.parallaxExists(this) &&
                run { MaskStore.clearParallax(this); true }
            // ⚠️ 「空间壁纸的候选随启动清空」**已于 2026-11-04 移除**：
            // 二级页改成卡片式候选后，每张卡就是 `files/spatial/<key>/` 一个持久目录，
            // 不再是「一次性的选中状态」。旧逻辑在未生效时会删掉 `spatialSourceKey` 指向的
            // `source` 原图副本 —— 那会把一张正常候选变成"没有原图、不能重算"的残卡。
            // `spatialSourceKey` / `spatialLastKey` 两个键一并退役（字段保留，不再读写）。
            if (cleared.isNotEmpty() || parallaxCleared) {
                val detail = buildList {
                    if (cleared.isNotEmpty()) add(cleared.joinToString("、") { it.title })
                    if (parallaxCleared) add("背景")
                }.joinToString("、")
                prefs.setDiagNote("启动清理：已清空残留候选（$detail）")
            }
        }
    }

    /**
     * 空间壁纸是否**正在生效** —— 两条链都算（2026-10-31）。
     *
     * * LSPosed 链：`spatialActive`（渲染器由 SystemUI 侧属性驱动）；
     * * 系统壁纸服务链：`WallpaperManager.getWallpaperInfo()` 是不是我们 ——
     *   这条链**不写** `spatialActive`，只写 `SpatialServiceTarget`。
     *
     * 只在「挂载模式 = 服务」时才去查系统（多一次 binder，没必要给 LSPosed 用户加）。
     * 判据与 `SpatialWallpaperPage.locked` 保持一致：**生效中就保留候选**。
     */
    private fun spatialRunning(prefs: MaskPrefs): Boolean =
        prefs.spatialActive ||
            (prefs.spatialMountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE &&
                SpatialServiceMode.isRunning(this))

    companion object {
        /**
         * 进程创建时刻（`SystemClock.uptimeMillis()`）。
         *
         * 启动遮罩的「最短展示时长」以它为基准：`MainActivity` 在首帧前用
         * `processStartAt + LAUNCH_HOLD_MS` 决定还要不要继续压帧。取进程级时间戳而不是
         * Activity 自己的 onCreate，是因为要覆盖从点图标到 MainActivity 起来的那一段
         * （SplashActivity 的 ~100ms）—— 目标口径是「从点击到界面」的总时长。
         */
        @Volatile
        var processStartAt = 0L
            private set
    }
}
