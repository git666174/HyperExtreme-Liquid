package com.frederic22.hyperextreme.ui

import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.hook.SpatialGlCore
import com.frederic22.hyperextreme.data.SpatialControl
import com.frederic22.hyperextreme.data.RunLog
import com.frederic22.hyperextreme.spatial.SpatialCrop
import com.frederic22.hyperextreme.spatial.SpatialDepth
import com.frederic22.hyperextreme.spatial.SpatialLayers
import com.frederic22.hyperextreme.wallpaper.DynamicWallpaperPermission
import com.frederic22.hyperextreme.wallpaper.SpatialServiceMode
import com.frederic22.hyperextreme.wallpaper.SpatialServiceTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.Button

/**
 * 二级页「空间壁纸」（2026-10-22 建立）。
 *
 * ## 与「视差壁纸效果」完全独立
 *
 * 视差 = 两层平面各自按传感器平移（2D 仿射）；空间壁纸 = 深度图分 N 层 + 真 3D 透视转动（GL）。
 * **状态、开关、渲染管线互不影响**。
 *
 * ## 页面结构（按《03-UI与排版规范》）
 *
 * 1. **图片卡**：无标题（用户指定），1:1 预览框 + 「选择/更换图片」「清空选择」**同一行**；
 * 2. **动作卡**：「生成深度缓存」+「立即替换」（后者用主题色，与视差页一致）；
 * 3. **参数调整**：卡外 `SmallTitle` + 一张卡两行 ——「**缓存推理分辨率**」（下拉栏，
 *    768 / 896 / 1024，默认 768，存 `MaskPrefs`）与「清除所有深度缓存」（右侧箭头、**无 summary**），
 *    后者点击弹 `WindowDialog` 二次确认。
 */
@Composable
internal fun SpatialWallpaperDetail(state: MaskUiState) {
    val haptic = rememberClickHaptic()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var showClearConfirm by remember { mutableStateOf(false) }
    // 深度推理进行中：按钮禁用，防连点重复跑（一次 7~10s，重复跑会同时占两份内存）。
    var busy by remember { mutableStateOf(false) }
    // 功能冲突弹窗（2026-11-04 起三侧统一：视差 / 光栅任一在跑都不许应用）
    var showConflict by remember { mutableStateOf(false) }
    var conflictOther by remember { mutableStateOf<RunningWallpaper?>(null) }

    // 「缓存推理分辨率」：推理短边档位（768 / 896 / 1024），存在 MaskPrefs 里跨页面保留。
    val prefs = remember { MaskPrefs(ctx) }

    // ───────────── 候选卡片流（2026-11-04，取代原来的"1:1 预览 + 选择/更换/清空"）─────────────
    //
    // 一个候选 = `files/spatial/<key>/` 一个目录（见 `SpatialDepth.listCandidates`）。
    // 卡片流 item 0 固定是「新建」，其余按 [candidates] 铺开，与光栅壁纸页**同一套**。
    var candidates by remember { mutableStateOf<List<SpatialDepth.SpatialCandidate>>(emptyList()) }
    // 首轮扫描完成前不画卡片流（先占位，避免"从空到有"跳一下）。
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    // 居中的页号（0 = 新建卡）；由卡片流吸附到位后回报。
    var focused by remember { mutableIntStateOf(0) }
    // 删除编排：与光栅页同一套（确认弹窗 → 卡片缩小淡出 → 播完真删 → 补位）。
    var pendingDelete by remember { mutableStateOf<SpatialDepth.SpatialCandidate?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var shiftDir by remember { mutableFloatStateOf(0f) }
    var shiftFromPage by remember { mutableIntStateOf(-1) }
    var shiftToken by remember { mutableIntStateOf(0) }

    var shortSide by remember { mutableStateOf(prefs.spatialShortSide) }
    // 「空间壁纸生效范围」：0 锁屏（默认）/ 1 桌面 / 2 全局。**不影响层图生成**，只决定渲染器挂哪条 leash。
    var scopeChoice by remember { mutableStateOf(prefs.spatialScope) }
    // 「启用空间壁纸锁屏景深」（2026-10-29）：默认关；关时渲染逻辑与加它之前完全一致。
    // 生效时机与上面的「生效范围」一样 —— 生效中改只记下来，下一次替换才随属性一起推。
    var lockDepthChoice by remember { mutableStateOf(prefs.spatialLockDepth) }

    // 缓存二次确认：命中缓存后记下「哪个哈希 + 窗口截止时间」，10s 内再点才覆盖重算。
    var confirmKey by remember { mutableStateOf<String?>(null) }
    var confirmUntil by remember { mutableStateOf(0L) }

    // **正在生效**的键（LSPosed 链）。服务模式的"在渲染哪张"在 target 文件里，单独记。
    var activeKey by remember { mutableStateOf(prefs.spatialKey) }
    var serviceTargetKey by remember { mutableStateOf(SpatialServiceTarget.readKey(ctx).orEmpty()) }

    /** 当前**居中选中**的候选；null = 新建卡居中（没有选中任何图）。 */
    val selected = candidates.getOrNull(focused - 1)


    // ───────────── 挂载模式（2026-10-30，v1.5.82）─────────────
    //
    // 0 = LSPosed（原有的 hook 链，默认）；1 = 系统壁纸服务（自己的 WallpaperService，免 root）。
    // **两种模式互斥**，且「运行中」不许切换 —— 否则同一张图会被渲染两遍
    // （hook 版和 service 版的姿态/位移可能不一致）。
    var mountMode by remember { mutableStateOf(prefs.spatialMountMode) }
    // 「系统壁纸服务」是不是正在生效。**只在页面前台查**（用户 2026-10-30 明确：
    // 后台查不了就算了）。`WallpaperManager` 没有壁纸变化回调（ACTION_WALLPAPER_CHANGED
    // 是 protected broadcast），所以只能靠 onResume 重读。
    var serviceRunning by remember { mutableStateOf(SpatialServiceMode.isRunning(ctx)) }
    var showMountWarn by remember { mutableStateOf(false) }
    var noMoreWarn by remember { mutableStateOf(false) }

    /**
     * 生效中：选图相关的操作一律禁用（与视差页同一套规则）。
     *
     * 2026-10-31：**系统壁纸服务运行中同样算生效**。那条链不写 `spatialActive` /
     * `spatialKey`（`activeKey` 恒空），原来「更换图片 / 清空选择」在壁纸正渲染时仍可点
     * ⇒ 候选框能被清空 / 换成别的图，与屏幕上看到的完全对不上。现在两种模式的
     * 「生效中锁住候选框」语义一致（用户要求"与 LSPosed 模式逻辑相同"）。
     */
    /**
     * **正在渲染的那一张**的 key（2026-11-04 卡片式改造后统一）——
     * LSPosed 链看 `activeKey`；服务链看 target 文件（`SpatialServiceMode` 只写文件、
     * 不动 `spatialActive`，所以不能拿 `activeKey` 当判据）。
     * 空串 = 没有在渲染。
     */
    val inUseKey = if (mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE) {
        if (serviceRunning) serviceTargetKey else ""
    } else {
        activeKey
    }

    val locked = inUseKey.isNotEmpty() || serviceRunning

    /**
     * 当前模式是否**正在运行** —— 为 true 时「挂载模式」下拉锁死。
     *
     * 两条闭环都成立：
     * * LSPosed 运行中 → 必须点「关闭替换」才能改模式；
     * * 服务模式运行中 → 必须点「恢复到系统默认壁纸」才能改模式。
     */
    val isRunning = if (mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE) {
        serviceRunning
    } else {
        activeKey.isNotEmpty()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 用户可能在系统壁纸页里换了图/换了范围，回来必须重新对一次
                serviceRunning = SpatialServiceMode.isRunning(ctx)
                serviceTargetKey = SpatialServiceTarget.readKey(ctx).orEmpty()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 提交挂载模式（弹窗确认后才走这里）。 */
    fun commitMountMode(next: Int) {
        mountMode = next
        prefs.setSpatialMountMode(next)
        // 切到服务模式时确保 hook 链已停（幂等：本来就没开就什么也不做）
        if (next == MaskPrefs.SPATIAL_MOUNT_SERVICE && prefs.spatialActive) {
            SpatialControl.disable(ctx, "已切换到系统壁纸服务模式")
            activeKey = ""
        }
    }

    // 候选加载（2026-11-04）：扫 `files/spatial/*/` + 按 `spatialCandidateOrder` 排序。
    //
    // **正在渲染的那张永远排第一**（用户 2026-11-04：应用的下一次都排到第一）——
    // 与光栅页「正在应用的那组紧挨新建卡」同一条规则。
    LaunchedEffect(reload) {
        val list = withContext(Dispatchers.IO) {
            val order = prefs.spatialCandidateOrder
            val scanned = SpatialDepth.listCandidates(ctx, order)
            val inUse = if (mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE) {
                SpatialServiceTarget.readKey(ctx).orEmpty()
            } else {
                prefs.spatialKey
            }
            if (inUse.isNotEmpty()) {
                val hit = scanned.firstOrNull { it.key == inUse }
                if (hit != null) listOf(hit) + scanned.filterNot { it.key == inUse } else scanned
            } else {
                scanned
            }
        }
        // 重扫后**尽量停在原来那张**（按 key 找），找不到才夹到合法范围。
        val keepKey = candidates.getOrNull(focused - 1)?.key
        candidates = list
        if (!loaded) {
            // 「有就第一张，没有就新建」（用户 2026-11-04）。
            focused = if (list.isEmpty()) 0 else 1
            loaded = true
        } else {
            val idx = if (keepKey == null) -1 else list.indexOfFirst { it.key == keepKey }
            focused = if (idx >= 0) idx + 1 else focused.coerceIn(0, list.size)
        }
    }

    /**
     * 开启空间壁纸（同步）。
     *
     * **关闭不再走这里**：统一交给 [SpatialControl.disable]（改 prefs → 推属性 → toast →
     * 重启 miwallpaper），因为视差页的「功能冲突提醒」也要用同一套关闭动作。
     */
    fun enableSpatial(key: String) {
        prefs.setSpatialActive(key)
        activeKey = key
        // 应用即置顶，但**只写顺序、不动当前列表**（2026-11-05 用户明确）：
        // 点应用的这一刻卡片留在原位，退出页面重进时才刷新到第一个；
        // 关闭应用也不清顺序 ⇒ 重进仍在第一个（与光栅同一口径）。
        prefs.setSpatialCandidateOrder(
            listOf(key) + prefs.spatialCandidateOrder.filterNot { it == key },
        )
        // **SystemUI 侧读的是系统属性**（见 SpatialFlag：那里 prefs 通道不可用）。
        // scope 必须一起推 —— 生效中改过范围的话，到这里才真正生效（用户 2026-10-29 定）。
        //
        // ⚠️ 「同一张图改过显示区域」（2026-11-05）：crop 是**启动时读一次**的，
        // 而 SystemUI 轮询的判据是 `key` 变没变 —— key 没变连 `start()` 都不会调。
        // 这种情况必须走 `pushRestart`（关 → 等一个轮询周期 → 开），代价是闪一下。
        val cropStamp = SpatialCrop.fileOf(ctx, key).lastModified()
        // 「显示区域」必须**走属性**（渲染器在 SystemUI 进程，读不到模块的 crop.json）。
        val cropProp = SpatialCrop.encodeProp(SpatialCrop.read(ctx, key))
        val cropChanged = prefs.spatialAppliedCropKey == key &&
            prefs.spatialAppliedCropStamp != 0L &&
            prefs.spatialAppliedCropStamp != cropStamp
        if (cropChanged) {
            com.frederic22.hyperextreme.data.SpatialFlag.pushRestart(
                true,
                key,
                prefs.spatialScope,
                prefs.spatialLockDepth,
                cropProp,
            )
        } else {
            com.frederic22.hyperextreme.data.SpatialFlag.push(
                true,
                key,
                prefs.spatialScope,
                prefs.spatialLockDepth,
                cropProp,
            )
        }
        prefs.setSpatialAppliedCrop(key, cropStamp)
        android.util.Log.i(
            "HyperExtreme",
            "SPATIAL_UI enable key=$key active=${prefs.spatialActive} scope=${prefs.spatialScope}",
        )
        Toast.makeText(
            ctx,
            "空间壁纸已生效（${scopeLabel(prefs.spatialScope)}）",
            Toast.LENGTH_LONG,
        ).show()

        // 记「**壁纸基准**」（2026-10-29）：aod 侧的换壁纸检测拿它当比对值，
        // 判据一变就无条件关闭空间壁纸（没有开关）。判据要读模板文件 ⇒ 放后台、不阻塞点按；
        // 算不出来就留空 —— hook 侧会惰性补记，只是第一次换壁纸时不会自动关（可接受的降级）。
        val app = ctx.applicationContext ?: ctx
        Thread {
            runCatching {
                val version = com.frederic22.hyperextreme.data.WallpaperVersion.compute(app)
                if (version.usable) prefs.setSpatialWallpaperFp(version.value)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 真正干活：深度 →（紧接着）生成层图。分层失败**不阻断**深度结果。 */
    val runGenerate: (Uri, Boolean) -> Unit = { uri, force ->
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    // ③ 深度池用完立刻还：深度图尺寸 = 层图尺寸，两份工作集（20n + 56n）
                    // 同时常驻要 168MB，分层再要一块就撞堆上限（2026-10-31）。
                    runCatching {
                        try {
                            SpatialDepth.generate(ctx, uri, shortSide, force)
                        } finally {
                            SpatialDepth.dropPool()
                        }
                    }.map { depth ->
                        // ⑦ OOM 自愈：换图时最缺的是「先回收再分配」，而 ART 只在分配失败时 GC。
                        // 实测（run_log）：同一张图第一次 OutOfMemoryError、紧接着重试就能过。
                        depth to runCatching { SpatialLayers.generate(ctx, depth.dir, uri, force) }
                            .recoverCatching { e ->
                                if (e !is OutOfMemoryError) throw e
                                SpatialLayers.dropPool()
                                System.gc()
                                SpatialLayers.generate(ctx, depth.dir, uri, force)
                            }
                    }
                } finally {
                    // ⑧ 分层池用完立刻还（2026-11-03，配合 Manifest 的 largeHeap）。
                    // 池是 56n（1022×2268 ⇒ 130MB），生成完还常驻的话，主进程退到后台就成了
                    // lmkd 的首选目标 —— 大堆本来就让它在同 adj 档位里更靠前。
                    // 代价：下一次生成要多花几百毫秒重建池（分配 9 个数组 + 清零）。
                    // 放在 finally 里 ⇒ 分层失败/抛异常也会还。
                    SpatialLayers.dropPool()
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { pair ->
                    val depth = pair.first
                    val layerRes = pair.second
                    // 生成完重扫候选（这张卡的封面 / layers 可能刚落地）。
                    reload++
                    val dSec = "%.1f".format(depth.costMs / 1000f)
                    val layer = layerRes.getOrNull()
                    val totalMs = depth.costMs + (layer?.costMs ?: 0L)
                    val msg = when {
                        layer != null && depth.hitCache && layer.hit ->
                            "已生成（命中缓存）"
                        layer != null ->
                            "已生成（耗时 ${"%.1f".format(totalMs / 1000f)} 秒）"
                        else ->
                            "深度已生成（$dSec 秒），但分层失败：${layerRes.exceptionOrNull()?.message}"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    RunLog.append(
                        ctx,
                        "空间壁纸 · 生成",
                        buildList {
                            add("缓存目录：${depth.dir.absolutePath}")
                            add("推理短边：$shortSide")
                            add(if (depth.hitCache) "深度命中缓存" else "深度耗时 ${depth.costMs} ms")
                            if (layer != null) {
                                add(if (layer.hit) "层图命中" else "8 层耗时 ${layer.costMs} ms → ${layer.dir.absolutePath}")
                            } else {
                                add("分层失败：${layerRes.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" }}")
                            }
                        },
                        ok = layer != null,
                    )
                },
                onFailure = { e ->
                    val msg = e.message ?: e.javaClass.simpleName
                    Toast.makeText(ctx, "处理失败：$msg", Toast.LENGTH_LONG).show()
                    RunLog.append(
                        ctx,
                        "空间壁纸 · 生成",
                        listOf("失败：${e.javaClass.name}: $msg"),
                        ok = false,
                    )
                },
            )
        }
    }

    // 新建卡：**单选图 → 直接生成一张新卡**（2026-11-04 用户定，取代原来的三控件）。
    // 选完只写 `source` 原图副本，深度交给用户自己点「生成深度缓存」——
    // 与以前一致（一次推理 7~10s，不能因为点一下新建就烧掉）。
    // 「调整显示区域」独立页（2026-11-04）：回来重扫一次候选（crop.json 变了）。
    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { reload++ }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 算 key + 把**原图原始字节**拷进缓存目录（不重编码、不缩放）。
            val k = withContext(Dispatchers.IO) {
                runCatching {
                    val key = SpatialDepth.cacheKey(ctx, uri)
                    SpatialDepth.ensureSource(ctx, uri, File(SpatialDepth.cacheRoot(ctx), key))
                    key
                }.getOrNull()
            }
            if (k == null) {
                Toast.makeText(ctx, "导入失败：读不出这张图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 新卡排最前：写进 order 的第一位，重扫后自然落到「新建卡」右边。
            prefs.setSpatialCandidateOrder(
                listOf(k) + prefs.spatialCandidateOrder.filterNot { it == k },
            )
            reload++
        }
    }

    // ── 卡片尺寸（与光栅壁纸页**同一个口径**）──
    val screen = rememberScreenSizePx()
    val density = LocalDensity.current
    val cardWidthDp: Dp = with(density) { (screen.width * CARD_WIDTH_RATIO).toDp() }
    // 主按钮宽 = 卡宽 5/6（光栅那一套的定值）；两个按钮并排 + 12dp 间距。
    val buttonWidth: Dp = cardWidthDp * 5f / 6f
    val pagerPadding = pagerVPadding(cardWidthDp)
    val carouselHeight: Dp = with(density) {
        (screen.width * CARD_WIDTH_RATIO * screen.height / screen.width).toDp()
    } + pagerPadding * 2

    /**
     * 确认删除一张候选（2026-11-04）：只打标记，卡片自己 200ms 缩小淡出；
     * 播完由卡片流回调 [commitDelete] —— 与光栅页同一套编排。
     */
    fun deleteCandidate(c: SpatialDepth.SpatialCandidate) {
        pendingDelete = null
        if (deletingId != null) return
        deletingId = c.key
    }

    /**
     * 淡出播完：**直接从内存列表移除**（不重新读盘），并在同一帧把补位方向交给卡片流。
     * 磁盘上的 `<key>/` 目录（原图 + 深度 + 层图）一起删掉 —— 这是**不可恢复**的，
     * 所以上游有二次确认弹窗。
     */
    fun commitDelete() {
        val key = deletingId ?: return
        val idx = candidates.indexOfFirst { it.key == key }
        if (idx < 0) {
            deletingId = null
            return
        }
        val page = idx + 1          // item 索引（item 0 = 新建卡）
        val lastPage = candidates.size
        shiftDir = if (page < lastPage) 1f else -1f
        shiftFromPage = page
        shiftToken++
        val rest = candidates.filterNot { it.key == key }
        candidates = rest
        focused = focused.coerceIn(0, rest.size)
        deletingId = null
        prefs.setSpatialCandidateOrder(rest.map { it.key })
        scope.launch {
            withContext(Dispatchers.IO) {
                File(SpatialDepth.cacheRoot(ctx), key).deleteRecursively()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // ⚠️ **必须用 [rememberPageScroll]，不能用 `rememberScrollState()`**（2026-10-29 修）。
            // 顶栏的收起量是由 `DetailTabHost` 里的 `snapshotFlow { pageScroll.value }` 1:1 写进
            // `appBarState.heightOffset` 的 —— 内容滚的是**另一个** ScrollState，顶栏就完全
            // 不知道内容滚了多远 ⇒ 大标题永远不收起（用户实测："空间壁纸"大标题不会收）。
            // 其余三个二级页的列都用 `rememberPageScroll()`（见《MaskSwapScreen》LocalPageScroll 说明）。
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        // 一、卡片流（2026-11-04 取代原来的「1:1 预览 + 选择/更换/清空」三控件）：
        //    与光栅壁纸页**同一套**（`CoverCarousel`），item 0 = 新建卡。
        //    点新建卡只做一件事：选一张图 → 直接生成一张新卡。
        Spacer(Modifier.height(BLOCK_GAP))
        // 把 crop 换算成卡片的**归一化源矩形**（null = 整幅，不裁）。
        //
        // 卡片要反映「显示区域」：crop 的 uv 窗口 = 中心 ± (visibleHalf / zoom)，
        // 而 `visibleHalf` 来自 `Geometry`（只吃比例，所以拿 aspect 造一对假尺寸即可）。
        // 球面映射会让裁出的像素矩形比屏幕比例宽约 2.7%，卡片由 `ContentScale.Crop`
        // 自然吸收，肉眼看不出来 —— 不需要在卡片上跑 AGSL。
        fun srcOf(c: SpatialDepth.SpatialCandidate): RectF? {
            val cr = c.crop
            if (cr.cx == 0.5f && cr.cy == 0.5f && cr.zoom == 1f) return null
            val lw = 1000
            val lh = (1000f / c.aspect.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1)
            val g = SpatialGlCore.geometry(screen.width, screen.height, lw, lh)
            val halfW = g.visibleHalfX / cr.zoom
            val halfH = g.visibleHalfY / cr.zoom
            return RectF(cr.cx - halfW, cr.cy - halfH, cr.cx + halfW, cr.cy + halfH)
        }
        if (loaded) {
            CoverCarousel(
                items = candidates.map { CoverItem(it.key, it.cover, srcOf(it)) },
                initialPage = if (candidates.isEmpty()) 0 else 1,
                activeId = inUseKey,
                deletingId = deletingId,
                shiftDir = shiftDir,
                shiftFromPage = shiftFromPage,
                shiftToken = shiftToken,
                screen = screen,
                onFocus = { focused = it },
                onNew = {
                    haptic()
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                // 点图卡 = 将来「调整显示区域」的入口（UI 还没定）。**正在应用的那张不能编辑**：
                // 改显示区域会直接影响正在渲染的那一份（用户 2026-11-04）。
                onClick = { item ->
                    when {
                        item.id == inUseKey ->
                            Toast.makeText(ctx, "正在应用的图片无法编辑", Toast.LENGTH_SHORT).show()
                        else -> {
                            haptic()
                            cropLauncher.launch(SpatialCropActivity.intent(ctx, item.id))
                        }
                    }
                },
                onDelete = { item ->
                    val c = candidates.firstOrNull { it.key == item.id }
                    when {
                        c == null -> Unit
                        // 删除钮本来就不会出现在正在应用的那张上，这条只是兜底。
                        c.key == inUseKey ->
                            Toast.makeText(ctx, "请先取消应用", Toast.LENGTH_SHORT).show()
                        else -> {
                            haptic()
                            pendingDelete = c
                        }
                    }
                },
                onDeleteSettled = { commitDelete() },
                // 推理期间**收起所有删除钮**（用户 2026-11-04）：一次 7~10s，
                // 中途把正在算的那张删掉，结果就没处可写了。
                allowDelete = !busy,
            )
        } else {
            Spacer(Modifier.height(carouselHeight))
        }
        // 按钮上方的可见间距 = padding + 下面这条 Spacer，两者之和正好是 BLOCK_GAP。
        Spacer(Modifier.height(BLOCK_GAP - pagerPadding))

        // 二、两个按钮并排（2026-11-04 用户定）：
        //    宽 = 卡宽 5/6（与光栅主按钮**同一个值**）、间距 12dp、整行居中。
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 「生成深度缓存」：**常规色底 + 提示色字**（次要动作，与光栅「取消应用」同一档）。
                Button(
                    onClick = {
                        haptic()
                        val c = selected
                        when {
                            c == null -> Unit
                            // 旧版缓存只有深度、没有原图副本 ⇒ 重算深度拿不到 uri。
                            !c.hasSource -> Toast.makeText(
                                ctx,
                                "此图片为旧版缓存，请删除后重新导入",
                                Toast.LENGTH_LONG,
                            ).show()
                            // 命中缓存：10s 内再点一次才覆盖重算（2026-10-22 用户要求）。
                            c.hasLayers && !(
                                confirmKey == c.key &&
                                    SystemClock.elapsedRealtime() <= confirmUntil
                                ) -> {
                                confirmKey = c.key
                                confirmUntil = SystemClock.elapsedRealtime() + CACHE_CONFIRM_WINDOW_MS
                                Toast.makeText(
                                    ctx,
                                    "该图片已有缓存，如需重新生成并替换请在10s内再次点击",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            else -> {
                                confirmKey = null
                                val uri = Uri.fromFile(
                                    SpatialDepth.sourceFile(File(SpatialDepth.cacheRoot(ctx), c.key)),
                                )
                                runGenerate(uri, c.hasLayers)
                            }
                        }
                    },
                    modifier = Modifier.width(buttonWidth),
                    // 没选中 / 正在推理 / 生效中（会覆盖正在渲染的层图）⇒ 不可点。
                    // **判据与可点性保持原样**，这次只改配色。
                    enabled = selected != null && !busy && !locked,
                    // 与光栅浮页的「计算选中景深」**完全同一个形态**（用户 2026-11-04）：
                    // 只给常态的「正常底 + 主体字」，**禁用时的字色完全交给 miuix 默认**。
                    // 于是「新建页（没选中）」与「生成中（busy）」这两种不可点状态会自动置灰，
                    // 不需要自己判断是哪种禁用 —— 也不该自己传 disabledContentColor。
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MiuixTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = if (busy) "处理中…" else "生成深度缓存",
                        style = MiuixTheme.textStyles.button,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 主按钮四态（2026-11-04，逻辑照光栅页）：
                //   新建卡「新图片」/ 服务模式「恢复默认」·「跳转系统页」/ 当前图「取消应用」/ 其余「应用」。
                val serviceMode = mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE
                val isActive = selected != null && selected.key == inUseKey
                // 「取消 / 恢复」这类次要动作的配色判据：服务模式看"服务真在跑"，
                // 不能只看指针 —— 用户可能从系统壁纸页把壁纸换回去了。
                val secondary = if (serviceMode) serviceRunning else isActive
                val label = when {
                    selected == null -> "新图片"
                    serviceMode -> if (serviceRunning) "恢复默认" else "跳转系统页"
                    isActive -> "取消应用"
                    else -> "应用"
                }
                // 提示色 ⇄ 常态：颜色叠化过去，不是硬切（与光栅页一致）。
                val bg by animateColorAsState(
                    targetValue = if (secondary) {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    animationSpec = tween(BUTTON_FADE_MS),
                )
                val fg by animateColorAsState(
                    targetValue = if (secondary) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onPrimary
                    },
                    animationSpec = tween(BUTTON_FADE_MS),
                )
                Button(
                    onClick = {
                        // 振动统一提到最前（2026-11-04 修：原来只有「新图片」分支有 haptic，
                        // 应用 / 取消应用 / 服务模式那几条点了都没震动）。
                        haptic()
                        val c = selected
                        when {
                            c == null -> {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            }
                            // ── 系统壁纸服务模式：只走系统路径 ──
                            serviceMode -> if (serviceRunning) {
                                // `WallpaperManager.clear()` 是同步 binder，丢 IO 线程。
                                scope.launch {
                                    withContext(Dispatchers.IO) { SpatialServiceMode.disable(ctx) }
                                    serviceRunning = SpatialServiceMode.isRunning(ctx)
                                    serviceTargetKey = ""
                                    activeKey = ""
                                    reload++
                                    Toast.makeText(
                                        ctx, "已恢复到系统默认壁纸", Toast.LENGTH_LONG,
                                    ).show()
                                }
                            } else {
                                // 功能冲突（三侧统一）：跳系统页会把系统壁纸换掉 ⇒ 先关对方。
                                val other = WallpaperConflict.running(
                                    ctx, state, WallpaperChain.SPATIAL,
                                )
                                when {
                                    other != null -> {
                                        conflictOther = other
                                        showConflict = true
                                    }
                                    !DynamicWallpaperPermission.allowed(ctx) -> {
                                        Toast.makeText(
                                            ctx,
                                            "请先给予模块“动态壁纸服务”权限",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        DynamicWallpaperPermission.openSettings(ctx)
                                    }
                                    !c.hasLayers -> Toast.makeText(
                                        ctx, "请先生成深度缓存", Toast.LENGTH_SHORT,
                                    ).show()
                                    else -> {
                                        SpatialServiceMode.enable(ctx, c.key)
                                        serviceTargetKey = c.key
                                        Toast.makeText(
                                            ctx, "请在系统服务页设置生效范围", Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                            // ── LSPosed：关闭方向不做互斥检查 ──
                            isActive -> {
                                SpatialControl.disable(ctx)
                                activeKey = ""
                                reload++
                            }
                            else -> {
                                // 激活保险：未激活时点「应用」只会静默失败。
                                if (!state.fullyActivated) {
                                    Toast.makeText(
                                        ctx,
                                        "以LSPosed模式运行时需要激活模块，请先激活",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else if (!c.hasLayers) {
                                    Toast.makeText(ctx, "请先生成深度缓存", Toast.LENGTH_SHORT).show()
                                } else {
                                    val other = WallpaperConflict.running(
                                        ctx, state, WallpaperChain.SPATIAL,
                                    )
                                    if (other != null) {
                                        conflictOther = other
                                        showConflict = true
                                    } else {
                                        enableSpatial(c.key)
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.width(buttonWidth),
                    colors = ButtonDefaults.buttonColorsPrimary(
                        color = bg,
                        disabledColor = bg,
                        contentColor = fg,
                        disabledContentColor = fg,
                    ),
                ) {
                    Crossfade(targetState = label, animationSpec = tween(BUTTON_FADE_MS)) { t ->
                        Text(
                            text = t,
                            style = MiuixTheme.textStyles.button,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 三、参数调整（2026-11-04 用户要求：**去掉「参数调整」这个小标题**，
        //    卡片直接接在按钮下面 —— 间距仍按统一的 BLOCK_GAP）。
        Spacer(Modifier.height(BLOCK_GAP))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            // 「挂载模式」（2026-10-30 用户要求，**排在「缓存推理分辨率」上面**）：
            // 无 summary。两种模式互斥 ⇒ **运行中锁死**，必须先关掉当前模式才能改。
            DropdownRow(
                title = "挂载模式",
                options = MOUNT_MODE_LABELS,
                selectedIndex = mountMode.coerceIn(0, MOUNT_MODE_LABELS.lastIndex),
                enabled = !isRunning,
                onSelect = { index ->
                    if (index == mountMode) return@DropdownRow
                    // 切到服务模式：先弹「功能提醒」（用户勾过「不再弹」就直接生效）
                    if (index == MaskPrefs.SPATIAL_MOUNT_SERVICE && !prefs.spatialMountWarned) {
                        noMoreWarn = false
                        showMountWarn = true
                    } else {
                        commitMountMode(index)
                    }
                },
            )
            // 「缓存推理分辨率」（2026-10-22 用户要求，**排在「清除所有深度缓存」上面**）：
            // 下拉栏 = 设置页「主题」那一套 `DropdownRow`（官方 `OverlayListPopup` 积木）。
            DropdownRow(
                title = "缓存推理分辨率",
                summary = "数值越大，效果越好，速度越慢",
                options = SpatialDepth.SHORT_SIDES.map { it.toString() },
                selectedIndex = SpatialDepth.SHORT_SIDES.indexOf(shortSide).coerceAtLeast(0),
                onSelect = { index ->
                    val value = SpatialDepth.SHORT_SIDES[index]
                    shortSide = value
                    prefs.setSpatialShortSide(value)
                },
            )
            // 「空间壁纸生效范围」（2026-10-28 用户要求，**排在「清除所有深度缓存」上面**）：
            // 锁屏（默认）/ 桌面 / 全局。**无 summary**（用户指定）。
            // 只决定渲染器挂哪条壁纸 leash，**不影响层图生成** ⇒ 切换不需要重算缓存。
            // 两条 leash 完全独立（真机探针：挂锁屏那条时锁屏可见、桌面不可见）。
            DropdownRow(
                title = "空间壁纸生效范围",
                options = SPATIAL_SCOPE_LABELS,
                selectedIndex = scopeChoice.coerceIn(0, SPATIAL_SCOPE_LABELS.lastIndex),
                // 系统壁纸服务模式下**无效**（范围由系统壁纸页决定）⇒ 变灰，但 prefs 里的值保留
                enabled = mountMode == MaskPrefs.SPATIAL_MOUNT_LSPOSED,
                onSelect = { index ->
                    scopeChoice = index
                    prefs.setSpatialScope(index)
                    // **不立刻 push**（2026-10-29 用户定）：生效中改范围只记下来，
                    // 下次点「立即替换 / 关闭替换」时按新值一起推 —— 避免正在跑的时候
                    // 拆掉渲染器重建挂载点。提示语说明这一点。
                    if (activeKey.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            // 「启用空间壁纸锁屏景深」（2026-10-29 用户要求，**排在「生效范围」下面**）：
            // 开 = 主体层再画一份进 SystemUI 窗口的 keyguard_foreground_layer，压在锁屏时钟之上；
            // **无 summary**（用户指定）；默认关；关时渲染逻辑与加它之前完全一致。
            // 生效时机同「生效范围」：生效中改只写 prefs，下一次替换才随属性推送 —— 同一条 toast。
            // 「生效范围 = 桌面」时它没有作用对象（桌面没有时钟），按用户要求**照常可点、静默无效果**。
            SwitchRow(
                title = "启用空间壁纸锁屏景深",
                checked = lockDepthChoice,
                // 系统壁纸服务模式下**不可用**（那是 hook 链的能力）⇒ 变灰，值保留
                enabled = mountMode == MaskPrefs.SPATIAL_MOUNT_LSPOSED,
                onCheckedChange = { on ->
                    lockDepthChoice = on
                    prefs.setSpatialLockDepth(on)
                    if (activeKey.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        // 「清除所有深度缓存」**独立成一张卡**（2026-10-30 用户要求）+ 语义红 + 无箭头。
        // 颜色用 `MiuixTheme.colorScheme.error`（miuix 规范：浅 #E94634 / 深 #F12522），
        // 不自造色值 —— 它会跟着深浅主题自动切换。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = "清除所有深度缓存",
                // **无 summary**（用户 2026-10-22 指定）。
                summary = null,
                titleColor = BasicComponentColors(
                    color = MiuixTheme.colorScheme.error,
                    disabledColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                ),
                onClick = {
                    haptic()
                    showClearConfirm = true
                },
            )
        }
    }

    // 二次确认弹窗：**常驻组合树、用 show 控制**（规范要求，条件包裹会让退出动画播不出来）。
    // 正文按运行态分三档（2026-10-31 用户要求）：服务模式运行中 / LSPosed 运行中 / 未运行。
    // 判据用**实际运行态**（serviceRunning 优先），与下面 onConfirm 的动作保持同一口径。
    ClearCacheDialog(
        show = showClearConfirm,
        summary = when {
            serviceRunning -> "清除后，深度缓存需重新生成。当前空间壁纸正在运行，" +
                "点击“清除”后将自动恢复到系统默认壁纸。"
            activeKey.isNotEmpty() -> "清除后，深度缓存需重新生成。当前空间壁纸正在运行，" +
                "点击“清除”后将自动关闭替换。"
            else -> "清除后，深度缓存需重新生成。"
        },
        onDismissRequest = { showClearConfirm = false },
        onConfirm = {
            // 系统壁纸服务模式下**先恢复到系统默认壁纸**再删 —— 否则壁纸进程读不到层图，
            // 会黑屏或崩。这条也是「功能提醒」弹窗里对用户的承诺（2026-10-30）。
            if (mountMode == MaskPrefs.SPATIAL_MOUNT_SERVICE && serviceRunning) {
                SpatialServiceMode.disable(ctx)
                serviceRunning = SpatialServiceMode.isRunning(ctx)
            }
            // LSPosed 模式正在生效：**统一收口**到 SpatialControl.disable（2026-10-31 用户要求）——
            // 比原来手写的那三行多两件事：清壁纸基准 `spatialWallpaperFp`、400ms 后
            // `killall com.miui.miwallpaper` 复位残留层。`toastText = null` 是静默，
            // 避免和下面的「已清除 N 项深度缓存」叠成两条排队播。
            if (activeKey.isNotEmpty()) {
                SpatialControl.disable(ctx, toastText = null)
                activeKey = ""
            }
            // 删 `files/spatial/` 下所有子目录（逻辑原来在 SpatialDepth 里，这里直接走它）
            val n = SpatialDepth.clearAll(ctx)
            // 候选就是这些目录 ⇒ 列表、选中、顺序一起作废（生效态上面已经关掉了）。
            candidates = emptyList()
            focused = 0
            serviceTargetKey = ""
            prefs.setSpatialCandidateOrder(emptyList())
            prefs.setSpatialSourceKey(null)
            Toast.makeText(ctx, "已清除 $n 项深度缓存", Toast.LENGTH_SHORT).show()
            showClearConfirm = false
        },
    )

    // 「删除此图片？」确认框（2026-11-04，照搬光栅页「删除此搭配？」的形态）：
    // 标题 + 正文 + 取消 / 确认（**确认键用警示色**）。确认后才播缩小淡出，播完真删。
    SpatialDeleteDialog(
        show = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        onConfirm = { pendingDelete?.let { deleteCandidate(it) } },
    )

    // 「功能提醒」弹窗（切到系统壁纸服务模式时弹一次；勾了「不再弹」就不再出现）。
    MountModeWarnDialog(
        show = showMountWarn,
        noMoreWarn = noMoreWarn,
        onNoMoreWarnChange = { noMoreWarn = it },
        onDismissRequest = { showMountWarn = false },
        onConfirm = {
            if (noMoreWarn) prefs.setSpatialMountWarned(true)
            showMountWarn = false
            commitMountMode(MaskPrefs.SPATIAL_MOUNT_SERVICE)
        },
    )

    // 功能冲突弹窗（常驻组合树、用 show 控制 —— 条件包裹会让退出动画播不出来）。
    // 「关闭」只关视差、**不继续**本次替换；要再点一次「立即替换」才生效。
    FeatureConflictDialog(
        show = showConflict,
        summary = conflictOther?.let { WallpaperConflict.summary(it, WallpaperChain.SPATIAL) } ?: "",
        onDismissRequest = {
            showConflict = false
            conflictOther = null
        },
        onConfirmClose = {
            val other = conflictOther
            showConflict = false
            conflictOther = null
            if (other != null) scope.launch { WallpaperConflict.disable(ctx, state, other) }
        },
    )
}

/** 命中缓存后的二次确认窗口：10 秒内再点一次才覆盖重算（用户 2026-10-22 指定）。 */
private const val CACHE_CONFIRM_WINDOW_MS = 10_000L

/**
 * 生效范围的下拉档位文案，**顺序必须与 `MaskPrefs.SPATIAL_SCOPE_*` 的取值一一对应**
 * （0 锁屏 / 1 桌面 / 2 全局）。Array index 就是存进 prefs 的值。
 */
private val SPATIAL_SCOPE_LABELS = listOf("锁屏", "桌面", "全局")

/**
 * 挂载模式的档位文案，**顺序与 `MaskPrefs.SPATIAL_MOUNT_*` 一一对应**（2026-10-30）。
 *
 * "免Root" 是这一档的核心卖点：它不依赖 LSPosed / 属性通道 / su，普通用户装上就能用。
 */
private val MOUNT_MODE_LABELS = listOf("LSPosed", "系统壁纸服务（免Root）")

/** 取值 → 文案；越界时回退到默认档（锁屏），绝不抛。 */
private fun scopeLabel(v: Int): String =
    SPATIAL_SCOPE_LABELS.getOrElse(v) { SPATIAL_SCOPE_LABELS[0] }

/**
 * 「删除此图片？」确认框（2026-11-04，照搬光栅页「删除此搭配？」）。
 *
 * 结构照 [ClearCacheDialog]：`WindowDialog` + `SpaceBetween` + 两个 `TextButtonLike`，
 * **确认键用 miuix 警示主题色**（`colorScheme.error` 底 + `onError` 字）。
 */
@Composable
private fun SpatialDeleteDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = "删除此图片？",
        summary = "图片与深度缓存将被删除",
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "确认",
                    danger = true,
                    onClick = {
                        onConfirm()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}

/**
 * 「清除所有深度缓存」的二次确认弹窗。
 *
 * **正文走 `WindowDialog` 的 `summary` 参数**（miuix 官方正文位，自带官方字号 / 颜色 / 与标题的间距），
 * 不要自己在 `content` 里塞 `Text` —— 那样字号、行距、对齐都跟官方不一致。
 *
 * 正文**按运行态分三档**（2026-10-31 用户要求），由调用方算好传进来：
 * 服务模式运行中 / LSPosed 模式运行中 / 未运行。三档与 `onConfirm` 的实际动作一一对应。
 *
 * 按钮区沿用 `RestartDialog` 的写法：`SpaceBetween` + 两个 `TextButtonLike`（确认用 `primary`）。
 */
@Composable
private fun ClearCacheDialog(
    show: Boolean,
    summary: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = "请确认",
        summary = summary,
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "清除",
                    // 警示色（用户 2026-11-04）：清缓存是不可恢复的破坏性操作，
                    // 与「删除此搭配」同一档配色（`colorScheme.error` 底 + `onError` 字）。
                    danger = true,
                    onClick = {
                        onConfirm()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
