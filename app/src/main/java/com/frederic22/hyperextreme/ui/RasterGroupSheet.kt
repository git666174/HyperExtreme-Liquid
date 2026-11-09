package com.frederic22.hyperextreme.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import com.frederic22.hyperextreme.aicr.AicrVision
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RasterGroup
import com.frederic22.hyperextreme.data.RasterStore
import com.frederic22.hyperextreme.data.RunLog
import com.frederic22.hyperextreme.ui.nav.NavBarBottomGap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** 一个光栅组最多几张（用户 2026-11-03 定：最多 4 张）。 */
private const val MAX_ITEMS = 4

/**
 * 提交下限：不足 [MIN_ITEMS] 张不许点 ✓（新建、编辑同一套判定）。
 *
 * 2026-11-09 由 2 放开到 1（用户要求「去掉至少两张的限制」）：
 * 单张也能成组 —— 此时没有「左右倾斜换图」的擦除过渡，但液态着色器仍靠
 * `uTime` / `uPan` 持续流动、晃动手机有推浪，观感是「单图液态壁纸」。
 * **0 张仍然拦住**：空组既没意义，也没有缓存目录可建。
 */
private const val MIN_ITEMS = 1

/**
 * 槽位尺寸 = 卡片流**中间态卡片**的 80%（用户 2026-11-04 定）。
 * 卡宽 = 屏宽 × 0.45 ⇒ 槽宽 = 屏宽 × 0.45 × 0.8；槽高按屏幕比例算（竖屏预览）。
 */
private const val SLOT_SCALE = 0.8f

/** 槽位之间的间距（同卡片流）。 */
private val SLOT_GAP = 12.dp

/** 景深四个按钮各自代表的任务（用来显示「处理中…」）。 */
private enum class DepthTask { ComputeSelected, DeleteSelected, ComputeAll, DeleteAll }

/**
 * 单张「景深主体」的处理结果（2026-11-05）。
 *
 * `code` 只用来计数：0 = 失败、1 = 已有缓存、2 = 本次新算出来。
 * `detail` 直接来自 `AicrVision.Result.detail`，带具体失败原因 —— 以前它被折成 0/2 丢掉了，
 * 用户只能看到一句「景深主体计算失败」，没法判断是绑不上、首次下载还是返回空。
 */
private data class DepthStep(
    val code: Int,
    val detail: String,
    val waitedMs: Long = 0,
)

/** 卡片"让位"的时长（换位后 `vig` 动到新槽位的补间）。 */
private const val SLOT_MOVE_MS = 150

/** 拖拽自动滚动的触发带（离行的左右边缘多近开始滚）。 */
private val DRAG_EDGE = 40.dp

/** 自动滚动的速度：刚进边缘 1.5dp/帧 → 贴死边缘 5dp/帧。 */
private val DRAG_SLOW = 1.5.dp
private val DRAG_FAST = 5.dp

/** 选中描边的叠化时长（用户 2026-11-04：150ms）。 */
private const val SLOT_STROKE_MS = 150

/** 槽位上删除钮圆心相对卡角的内收比例（同卡片流的 `DELETE_CENTER_RATIO`）。 */
private const val SHEET_BADGE_INSET = 0.25f

/**
 * 光栅组的编辑浮页（2026-11-03）。
 *
 * 新建和编辑**走同一个浮页**：`group == null` 就是新建。列表为空时只是没有行，
 * 其余完全一样 —— 这样「导入 → 排序 → 完成」只有一条路径，不用维护两套状态机。
 *
 * ## 排序是自研的
 *
 * miuix 0.9.4 **没有**排序 / Reorderable 组件（全部 4890 个 class + 官方 54 个组件清单都没有），
 * 所以这里是 `LazyColumn` + `detectDragGesturesAfterLongPress` + `Modifier.animateItem()`
 * 手写的一套：长按任意位置起拖，越过半行就换位，其余行由 `animateItem` 自动让位。
 *
 * 视觉照系统相册的「自定义排序」：灰底 + 白色圆角卡，左侧三横线抓手，右侧按钮。
 * 相册那个右侧是蓝色勾（显示/隐藏），我们没有隐藏概念，按用户要求换成**红色圆 + 白叉**（移除）。
 *
 * ## 状态与落盘
 *
 * 组和顺序都只维护在浮页内部（`working` + [names]），每次会改动列表的操作
 * （导入 / 删除 / 拖完松手 / 关闭）都立刻写回 `meta.json`。浮页关掉之后
 * 页面那边只要重扫一遍目录就行，不需要来回传状态。
 */
@Composable
internal fun RasterGroupSheet(
    show: Boolean,
    group: RasterGroup?,
    /** 打开时是否**同时拉起系统选图**（新建流程用，用户 2026-11-03）。 */
    autoPick: Boolean = false,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberClickHaptic()

    val names = remember { mutableStateListOf<String>() }
    // 打开时的快照 —— 取消（✕）时靠它把本次改动作废（用户 2026-11-03：编辑点叉不存任何更改）。
    val original = remember { mutableStateListOf<String>() }
    /**
     * 本次会话里**导入过的全部文件**（包含后来又被移除的）。
     *
     * 为什么单独记（2026-11-04 真机发现的缓存重复）：`finish()` 原来只有两条清理路径 ——
     * 取消时删 `names - original`、保存时删 `original - names`。**「本次导入 → 保存前又移除」
     * 两边都不覆盖** ⇒ 文件留在磁盘上、却不进 `meta.items` ⇒ 成为孤儿；而 `import(keep = names)`
     * 又刻意让孤儿不参与判重 ⇒ 同一张图能再导入一份，用户看到「一张图缓存两次」。
     *
     * 有了这个集合，保存时删 `addedEver - names`、取消时删 `addedEver`，孤儿不再产生。
     */
    val addedEver = remember { mutableStateListOf<String>() }
    var working by remember { mutableStateOf<RasterGroup?>(null) }
    var importing by remember { mutableStateOf(false) }
    // 选中的图片（**按文件名存**，不按下标 —— 移除一张后下标会整体前移）。
    // **同时只能选一张**（用户 2026-11-04 改回单选）：点别的就替换，点自己取消。
    var selectedName by remember { mutableStateOf<String?>(null) }
    // 缓存状态版本号：计算 / 删除主体之后 +1，用来重算"哪些张有主体缓存"。
    var cacheVersion by remember { mutableIntStateOf(0) }
    // 正在跑的景深动作（null = 空闲）。按钮文案靠它显示「处理中…」，四个按钮也靠它禁用。
    var runningTask by remember { mutableStateOf<DepthTask?>(null) }
    // 这次弹出的系统选择器是不是「新建时自动拉起」的 —— 取消时要连浮页一起收回。
    var autoPickArmed by remember { mutableStateOf(false) }
    // 每次打开都重建一次工作副本：内容随 Dialog 关闭而销毁，不重建会拿到上上次的列表。
    LaunchedEffect(show, group?.id) {
        if (!show) return@LaunchedEffect
        val g = group ?: RasterStore.create(ctx)
        working = g
        names.clear()
        names.addAll(g.items)
        original.clear()
        original.addAll(g.items)
        addedEver.clear()
        selectedName = null
        cacheVersion++
    }

    fun persist() {
        val g = working ?: return
        RasterStore.writeMeta(g.copy(items = names.toList()))
    }

    /**
     * 收尾。[save]=false = **取消，什么都不存**（用户 2026-11-03）。
     *
     * | 场景 | ✕ 取消 | ✓ 保存 |
     * |---|---|---|
     * | 新建 | 整组目录删掉（连已导入的图） | 写 meta；空组则删目录 |
     * | 编辑 | 删掉本次**新导入的全部**文件（含已被移除的）；`meta.json` 原样不动 | 写 meta + 删「原有但被移除」+ 删「本次新增但被移除」 |
     *
     * ⚠️ 因此浮页里**不能即时写盘**：导入 / 删除行 / 拖拽排序都只改内存里的 [names]，
     * 真正的落盘与物理删除全部推迟到这一函数。
     */
    fun finish(save: Boolean = true) {
        val g = working
        if (g != null) {
            when {
                !save && group == null -> RasterStore.deleteGroup(g)
                // 取消：本次导入的一个都不留。用 [addedEver] 而不是 `names - original` ——
                // 后者漏掉了"导入后又移除"的那些（它们既不在 names 也不在 original）。
                !save -> addedEver.forEach { RasterStore.deleteItem(g, it) }
                names.isEmpty() -> RasterStore.deleteGroup(g)
                else -> {
                    persist()
                    // 新建的组排到第一个（2026-11-05）：顺序持久化在 `rasterGroupOrder` 里，
                    // 页面 onDismiss 的 reload 会按它重排。编辑已有组不动顺序。
                    if (group == null) {
                        val p = MaskPrefs(ctx)
                        p.setRasterGroupOrder(listOf(g.id) + p.rasterGroupOrder.filterNot { it == g.id })
                    }
                    // 原有、且现在不在列表里 ⇒ 被移除
                    original.filterNot { it in names }.forEach { RasterStore.deleteItem(g, it) }
                    // 本次导入、但保存前又被移除 ⇒ 也不该留在磁盘上（2026-11-04 修）
                    addedEver.filterNot { it in names }.forEach { RasterStore.deleteItem(g, it) }
                }
            }
        }
        onDismiss()
    }

    /** 选完图后的统一入口（单选 / 多选 / 自动拉起三条路径都走这里）。 */
    fun handlePicked(uris: List<Uri>) {
        val wasAuto = autoPickArmed
        autoPickArmed = false
        if (uris.isEmpty()) {
            // 系统选择器被关掉、一张都没选：如果是**新建时自动拉起**的那一次，
            // 整个新建流程一起收回（用户 2026-11-03）；浮页里手动点「导入图片」再取消则保持浮页。
            if (wasAuto) finish()
            return
        }
        val g = working ?: return
        // 兜底再截一刀：系统选择器那边已经按剩余空位限过张数了。
        val room = (MAX_ITEMS - names.size).coerceAtLeast(0)
        if (room == 0) return
        importing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                // `keep = 当前列表` —— 已从列表移除、但文件还没删的那张**不参与判重**
                //（用户 2026-11-04：删掉后哈希没释放，同一张再也导不进来）。
                RasterStore.import(ctx, g, uris.take(room), keep = names.toList())
            }
            // SnapshotStateList 只能在主线程改。**这里不写盘** —— 等用户点 ✓ 才落盘，
            // 点 ✕ 时这些新导入的文件会被删掉（用户 2026-11-03：取消不存任何更改）。
            names.addAll(r.added)
            // 记一笔「本次导入过的」，收尾时用它清理"导入后又移除"的孤儿（见 [addedEver]）。
            addedEver.addAll(r.added)
            importing = false
            when {
                // 只有一张重复时说"这张"（用户 2026-11-04），多张才报数量。
                r.skipped == 1 -> AppToast.show(ctx, "这张图片已经在里面了")
                r.skipped > 1 -> AppToast.show(ctx, "已跳过 ${r.skipped} 张重复图片")
            }
        }
    }

    /**
     * 计算若干张图的「景深主体」并落缓存（2026-11-04；2026-11-05 补失败原因与首次下载等待）。
     *
     * 走 `AicrVision.segmentFile()` —— 直连小米澎湃 AI 引擎（`com.xiaomi.aicr`）做抠图，
     * 返回的 RGBA 抠像直接存成 `<图片名>.subject.png`。单张要跑几秒，
     * 所以**已有缓存的直接跳过**，只补还没算过的。
     *
     * 失败原因（`AicrVision.Result.detail`）会：
     * 1. 取第一条拼进 toast —— 系统 Toast 约两行，只放一句；
     * 2. 全部写进 `run_log.txt`（`RunLog`），用户可以直接把文件发回来。
     */
    fun compute(targets: List<String>, task: DepthTask) {
        val g = working ?: return
        if (runningTask != null || targets.isEmpty()) return
        haptic()
        runningTask = task
        scope.launch {
            var fresh = 0
            var cached = 0
            var waited = 0L
            /** 图名 → 结果（保持顺序，写 run_log 用）。 */
            val notes = LinkedHashMap<String, String>()
            for (name in targets) {
                val step = withContext(Dispatchers.IO) {
                    if (g.subjectFileOf(name).isFile) {
                        DepthStep(1, "已有缓存")
                    } else {
                        val file = g.fileOf(name)
                        // **直接把原图文件丢给 AICR**（不解码 / 不缩放 / 不重编码）。
                        // 兜底：万一服务不认这个格式或尺寸，再解一份位图走 PNG 中转。
                        val direct = AicrVision.segmentFile(ctx, file)
                        val directOk = direct.bitmap != null || direct.mask != null
                        var detail = direct.detail
                        var fallbackWait = 0L
                        val m = if (directOk) {
                            direct.bitmap ?: direct.mask
                        } else {
                            val src = RasterStore.decodeForMask(file)
                            if (src == null) {
                                detail = "${direct.detail}；原图也解不开（格式不支持或文件损坏）"
                                null
                            } else {
                                val fb = AicrVision.segment(ctx, src)
                                src.recycle()
                                fallbackWait = fb.waitedMs
                                val ok = fb.bitmap != null || fb.mask != null
                                detail = if (ok) {
                                    "直送失败（${direct.detail}），PNG 中转成功"
                                } else {
                                    "${direct.detail}；兜底也失败：${fb.detail}"
                                }
                                fb.bitmap ?: fb.mask
                            }
                        }
                        val wait = direct.waitedMs + fallbackWait
                        if (m == null) {
                            DepthStep(0, detail, wait)
                        } else if (RasterStore.saveSubject(g, name, m)) {
                            DepthStep(2, direct.detail, wait)
                        } else {
                            DepthStep(0, "抠图成功但存盘失败（磁盘空间不足？）", wait)
                        }
                    }
                }
                when (step.code) {
                    1 -> {
                        cached++
                        notes[name] = "已有缓存"
                    }
                    2 -> {
                        fresh++
                        notes[name] = step.detail
                    }
                    else -> notes[name] = "失败：${step.detail}"
                }
                waited += step.waitedMs
            }
            runningTask = null
            cacheVersion++
            val failed = targets.size - fresh - cached
            val firstFail = notes.values
                .firstOrNull { it.startsWith("失败：") }
                ?.removePrefix("失败：")
            val msg = when {
                fresh == 0 && cached == 0 ->
                    "景深主体计算失败：${firstFail ?: "未知原因"}" +
                        if (failed > 1) "（共 $failed 张失败）" else ""
                fresh == 0 -> "$cached 张已有缓存"
                cached == 0 ->
                    "已计算 $fresh 张景深主体" +
                        if (waited >= 3000) "（首次下载等待 ${waited / 1000} 秒）" else ""
                else -> "已计算 $fresh 张，$cached 张已有缓存"
            }
            AppToast.show(ctx, msg, long = failed > 0)
            RunLog.append(
                ctx,
                "光栅壁纸 · 景深主体",
                buildList {
                    add("组 ${g.id}，目标 ${targets.size} 张（新算 $fresh / 缓存 $cached / 失败 $failed）")
                    notes.forEach { (n, v) -> add("$n → $v") }
                    if (waited > 0) add("等待 AI 模型合计 ${waited / 1000} 秒")
                },
                ok = failed == 0,
            )
        }
    }

    /** 删掉若干张的主体缓存（连旧格式的灰度遮罩）。用户 2026-11-04：不弹确认，点了就删。 */
    fun deleteSubjects(targets: List<String>, task: DepthTask) {
        val g = working ?: return
        if (targets.isEmpty() || runningTask != null) return
        haptic()
        runningTask = task
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                targets.count { RasterStore.deleteSubject(g, it) }
            }
            runningTask = null
            cacheVersion++
            AppToast.show(ctx, if (n == 0) "没有可删除的景深主体" else "已删除 $n 张景深主体")
        }
    }

    // ───────────── 导入「遮罩 / 主体」（2026-11-04）─────────────
    //
    // 用户 2026-11-04：编辑 / 新建浮页里的「导入图片」改成「导入选中图片的遮罩或主体」，
    // 批量的「导入图片」完全交给**空槽（= 新建卡）**负责 —— 有几个空位选几张，满 4 张没有空槽。
    var maskBusy by remember { mutableStateOf(false) }
    /** 点「导入遮罩或主体」时选中的那张（回调里要用，不能依赖那一刻的 `selectedName`）。 */
    var maskTarget by remember { mutableStateOf<String?>(null) }
    /** 尺寸不一致、等用户确认的那一次导入。 */
    var pendingMask by remember { mutableStateOf<PendingMask?>(null) }

    /** 真正写盘（尺寸本来就一致，或用户点了「确定」）。 */
    fun applyMask(name: String, uri: Uri, kind: RasterStore.SubjectKind) {
        val g = working ?: return
        maskBusy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { RasterStore.applySubject(ctx, g, name, uri, kind) }
            maskBusy = false
            cacheVersion++
            AppToast.show(
                ctx,
                when {
                    !ok -> "导入失败"
                    kind == RasterStore.SubjectKind.MASK -> "已导入遮罩"
                    else -> "已导入主体"
                },
            )
        }
    }

    /** 选完图：探测 → 判定 →（尺寸不一致就弹窗，否则直接写）。 */
    fun importMask(name: String, uri: Uri) {
        val g = working ?: return
        if (maskBusy) return
        maskBusy = true
        scope.launch {
            val probe = withContext(Dispatchers.IO) { RasterStore.probeSubject(ctx, g, name, uri) }
            maskBusy = false
            if (probe == null || probe.kind == RasterStore.SubjectKind.INVALID) {
                // 用户 2026-11-04 定死的文案（彩色 + 无 alpha = 判定不出来）。
                AppToast.show(ctx, "无法检测出该图片为遮罩或主体")
                return@launch
            }
            if (probe.sizeMatch) applyMask(name, uri, probe.kind)
            else pendingMask = PendingMask(name, uri, probe)
        }
    }

    val maskPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val name = maskTarget
        maskTarget = null
        if (uri != null && name != null) importMask(name, uri)
    }

    // 系统控件里能选几张 = 剩余空位（用户 2026-11-03 要求）。
    // `PickMultipleVisualMedia` 的 maxItems 在构造时校验必须 > 1，只剩 1 个空位时退回单选。
    val room = (MAX_ITEMS - names.size).coerceAtLeast(0)
    val multiContract = remember(room) {
        ActivityResultContracts.PickMultipleVisualMedia(room.coerceAtLeast(2))
    }
    val multiPicker = rememberLauncherForActivityResult(multiContract) { handlePicked(it) }
    val singlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> handlePicked(if (uri == null) emptyList() else listOf(uri)) }

    // 新建流程：浮页与系统选图**同时**弹出（用户 2026-11-03）。
    //
    // ⚠️ **不能复用 `multiPicker`**（2026-11-04 的 bug：关掉浮页后第一次点新建，最多只能选 2 张）。
    //    原因：`room` 是由 `names.size` 现算的，而 `names` 要到下面的 `LaunchedEffect` 里才被清空；
    //    浮页刚打开的那一帧 `names` 还是**上一次残留**的（比如 2 张）⇒ 这一帧的 `multiContract`
    //    是 `maxItems = 2`。自动拉起就发生在这一帧：`rememberLauncherForActivityResult` 内部是
    //    `rememberUpdatedState(contract)`，还没重组过，拿到的仍是旧 contract。
    //    新建一定是**空组**（room 恒 = MAX_ITEMS），所以这条路径单独用一个固定的 contract。
    val freshContract = remember {
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ITEMS)
    }
    val freshPicker = rememberLauncherForActivityResult(freshContract) { handlePicked(it) }

    // 必须放在 launcher 注册之后才拿得到 picker；只在「新建 + 还没有图」时触发一次。
    LaunchedEffect(show, group?.id) {
        if (!show || group != null || !autoPick) return@LaunchedEffect
        if (names.isNotEmpty()) return@LaunchedEffect
        autoPickArmed = true
        freshPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    WindowBottomSheet(
        show = show,
        title = if (group == null) "新建光栅壁纸组合" else "编辑光栅壁纸组合",
        onDismissRequest = { finish(save = false) },
        startAction = {
            IconButton(onClick = { haptic(); finish(save = false) }) {
                SheetActionIcon(SheetIconKind.Close, "关闭")
            }
        },
        endAction = {
            IconButton(onClick = {
                haptic()
                // 2026-11-09 起下限放开到 1 张（用户要求）：单张也能提交，走「单图液态」形态。
                // **新建和编辑走同一条判定** —— 编辑时删空也一样拦住，
                // 不会顺手把整组删掉（用户 2026-11-04 要求）。
                if (names.size < MIN_ITEMS) {
                    AppToast.show(ctx, "至少选择一张壁纸")
                } else {
                    finish()
                }
            }) {
                SheetActionIcon(SheetIconKind.Check, "完成")
            }
        },
    ) {
    // 槽位尺寸 = 卡片流**中间态卡片**的 80%（用户 2026-11-04 定）。
    // 卡宽 = 屏宽 × 0.45，所以槽宽 = 屏宽 × 0.45 × 0.8；高度按屏幕比例 = 竖屏预览。
    val screen = rememberScreenSizePx()
    val density = LocalDensity.current
    val slotW: Dp = with(density) { (screen.width * CARD_WIDTH_RATIO * SLOT_SCALE).toDp() }
    val slotH: Dp = with(density) {
        (screen.width * CARD_WIDTH_RATIO * SLOT_SCALE * screen.height / screen.width).toDp()
    }
    val radius: Dp = slotW * CARD_RADIUS_RATIO
    // 卡片底色（暗色下比浮页底亮一档，见 rememberCardSurface 的注释）。
    val surfaceColor = rememberCardSurface()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 底部留白与悬浮底栏一致（NavBarBottomGap = 26dp）。
            .padding(bottom = NavBarBottomGap),
    ) {
        // ── 槽位行 ──（2026-11-04）
        // `Row + horizontalScroll`，**不用 LazyRow**：它的 scroll anchoring 和 `animateItem`
        // 会跟"拖拽里自己 scrollBy"互相修正 —— 表现为「拖到第一时列表跳到最后」
        // 「端点自动滚时首/末卡闪烁」。Row 的布局顺序在拖拽期间完全不动，没有二次修正。
        //
        // ## 位置模型（整套拖拽的地基）
        // 每张图有一个**浮点槽位** `vig`，它的可视位置恒等于 `vig × step`：
        //
        //     translationX = (vig − index) × step
        //
        // 注意 `index` 只出现在**补偿项**里 ⇒ **重排 `names` 在视觉上是空操作**
        // （index 变了，translation 同步补回来）。所以「让位动画」只剩一件事：
        // 把受影响卡的 `vig` 动到它的新 index。也不会再出现「换位后先闪回旧位置」。
        val scroll = rememberScrollState()
        // 每张图一个浮点槽位（按**文件名**存）。普通 HashMap + MutableFloatState：
        // 只在绘制阶段读 ⇒ 写它只触发重绘，不触发重组。
        val vigs = remember { HashMap<String, MutableFloatState>() }
        var dragName by remember { mutableStateOf<String?>(null) }
        // 被拖卡左边缘在**整条带子**里的 x（px，内容坐标）。
        var dragX by remember { mutableFloatStateOf(0f) }
        // 手指在**视口**里的 x —— 自动滚动按它判边缘（按卡片判会让"仅长按"就把列表弹走）。
        var pointerX by remember { mutableFloatStateOf(0f) }
        // 手指真的动过之后才允许自动滚动。
        var dragMoved by remember { mutableStateOf(false) }
        val stepPx = with(density) { (slotW + SLOT_GAP).toPx() }
        val edgePx = with(density) { DRAG_EDGE.toPx() }
        val slowPx = with(density) { DRAG_SLOW.toPx() }
        val fastPx = with(density) { DRAG_FAST.toPx() }
        // 删除钮圆顶会探出卡片 ⇒ 行要比卡片高一点（**不能靠 Modifier.padding 缩 viewport**）。
        val badgePad = cardBadgeOverflow(slotW)

        fun vigOf(name: String, index: Int): MutableFloatState =
            vigs.getOrPut(name) { mutableFloatStateOf(index.toFloat()) }

        /** 跨过半格就重排 —— 视觉上是空操作，只是让"洞"跟着走、其余卡开始让位。 */
        fun reorderLive(name: String) {
            val at = names.indexOf(name)
            if (at < 0) return
            val to = (dragX / stepPx).roundToInt().coerceIn(0, names.lastIndex)
            if (to != at) names.add(to, names.removeAt(at))
        }

        /** 松手：`vig` 停在手指处，`dragName = null` 之后由 LaunchedEffect 动到最近的槽位。 */
        fun release() {
            dragMoved = false
            dragName = null
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewportPx = constraints.maxWidth.toFloat()

            // 拖到边缘**自动横向滚动**：手指进左右 40dp 才滚，越深越快。
            // 滚多少就把 `dragX` 补回多少，卡片才会一直贴着手指。
            LaunchedEffect(dragName) {
                val n = dragName ?: return@LaunchedEffect
                while (dragName == n) {
                    withFrameNanos { }
                    if (!dragMoved) continue
                    val depth = when {
                        pointerX < edgePx -> edgePx - pointerX
                        viewportPx - pointerX < edgePx -> edgePx - (viewportPx - pointerX)
                        else -> 0f
                    }
                    if (depth <= 0f) continue
                    val dir = if (pointerX < edgePx) -1f else 1f
                    val t = (depth / edgePx).coerceIn(0f, 1f)
                    val before = scroll.value
                    scroll.scrollBy(dir * (slowPx + (fastPx - slowPx) * t))
                    // `ScrollState.value` 是整像素 ⇒ 差值也是 Int，补回时转 Float。
                    val moved = (scroll.value - before).toFloat()
                    if (moved != 0f) {
                        dragX = (dragX + moved).coerceIn(0f, names.lastIndex * stepPx)
                        vigOf(n, names.indexOf(n)).floatValue = dragX / stepPx
                        reorderLive(n)
                    }
                }
            }

            // 滚动视口是这层 Box；Row 才是内容。
            // ⚠️ 末尾留白写在 **Row 自己的 `padding(end)`** 上 —— 此时它计入**内容宽度**，
            //    滚动到底时末卡右边缘离内容右边界只剩徽标探出的那一点（≈6.8dp，看起来就是贴边）；
            //    写在 `horizontalScroll` 外面只会把**视口**缩小，内容照样贴死、徽标照样被切。
            //    之前用 `Spacer(badgePad)` + `spacedBy` 会白多出 12dp（用户 2026-11-04 指出右端没贴边）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(slotH + badgePad * 2)
                    .horizontalScroll(scroll),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.padding(end = badgePad),
                    horizontalArrangement = Arrangement.spacedBy(SLOT_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 0 until MAX_ITEMS) {
                        val name = names.getOrNull(i)
                        val dragging = name != null && name == dragName
                        val vig = name?.let { vigOf(it, i) }
                        // ⚠️ `key(name)` 必须要有：不加的话换位后"第 i 个组合槽"会直接换一份内容，
                        //    状态（封面解码 / 徽标动画 / 手势节点）全串到别的图上 —— 这就是抽搐。
                        key(name ?: "empty@$i") {
                            // 它的 index 变了 ⇒ 把 `vig` 动到新位置，**这就是让位动画**。
                            // 被拖的那张跳过：它的 `vig` 由手指直接写，不能同时被动画抢。
                            LaunchedEffect(i, dragging) {
                                if (vig == null || dragging) return@LaunchedEffect
                                animate(
                                    initialValue = vig.floatValue,
                                    targetValue = i.toFloat(),
                                    animationSpec = tween(durationMillis = SLOT_MOVE_MS),
                                ) { v, _ -> vig.floatValue = v }
                            }
                            SlotCard(
                                file = name?.let { working?.fileOf(it) },
                                slotW = slotW,
                                slotH = slotH,
                                radius = radius,
                                selected = name != null && name == selectedName,
                                surface = surfaceColor,
                                modifier = Modifier
                                    .zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer {
                                        // 绘制阶段读 `vig` ⇒ 拖拽/让位期间只重绘、不重组、不重排布局。
                                        translationX =
                                            if (vig == null) 0f else (vig.floatValue - i) * stepPx
                                    }
                                    .then(
                                        if (name == null) {
                                            Modifier
                                        } else {
                                            Modifier.pointerInput(name) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { at0 ->
                                                        haptic()
                                                        val at = names.indexOf(name)
                                                        if (at < 0) {
                                                            return@detectDragGesturesAfterLongPress
                                                        }
                                                        // 长按即选中（用户 2026-11-04）。
                                                        selectedName = name
                                                        dragName = name
                                                        dragMoved = false
                                                        dragX = at * stepPx
                                                        vigOf(name, at).floatValue = at.toFloat()
                                                        // 手指在视口里的 x = 该槽位左边 + 手指在卡内的 x。
                                                        pointerX = at * stepPx - scroll.value + at0.x
                                                    },
                                                    onDrag = { change, drag ->
                                                        change.consume()
                                                        dragMoved = true
                                                        pointerX += drag.x
                                                        // 左右都是墙：0 = 行首；lastIndex = 最后一张有图的卡。
                                                        dragX = (dragX + drag.x)
                                                            .coerceIn(0f, names.lastIndex * stepPx)
                                                        vigOf(name, names.indexOf(name)).floatValue =
                                                            dragX / stepPx
                                                        reorderLive(name)
                                                    },
                                                    onDragEnd = { release() },
                                                    onDragCancel = { release() },
                                                )
                                            }
                                        },
                                    ),
                                onClick = {
                                    if (name == null) {
                                        // 空槽 = **唯一的批量导入入口**（用户 2026-11-04）：
                                        // 有几个空位选几张；只剩 1 个空位时退回单选
                                        //（`PickMultipleVisualMedia` 构造要求 maxItems > 1）。
                                        haptic()
                                        val req = PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        )
                                        if (room <= 1) singlePicker.launch(req) else multiPicker.launch(req)
                                    } else {
                                        // 有图 = 切换选中（**单选**）：点别的替换，点自己取消。
                                        haptic()
                                        selectedName = if (selectedName == name) null else name
                                    }
                                },
                                onRemove = {
                                    haptic()
                                    val at = names.indexOf(name)
                                    if (at >= 0) names.removeAt(at)
                                    if (selectedName == name) selectedName = null
                                    cacheVersion++
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── 景深主体 ──（2026-11-04）
        // 两行两列：上排「选中」的计算 + 删除，下排「所有」的计算 + 删除。
        // 行距 = 列距 = 12dp；样式 = 常规按钮底 + 彩色字（计算 primary / 删除 error）。
        val cachedNames = remember(cacheVersion, names.toList()) {
            working?.let { g -> names.filter { g.subjectFileOf(it).isFile } } ?: emptyList()
        }
        val sel = selectedName
        val selCached = sel != null && sel in cachedNames
        val selUncached = sel != null && sel !in cachedNames && sel in names
        val anyUncached = names.any { it !in cachedNames }
        val anyCached = cachedNames.isNotEmpty()
        val idle = runningTask == null && !importing && !maskBusy

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DepthButton(
                text = "计算选中景深",
                tint = MiuixTheme.colorScheme.primary,
                enabled = idle && selUncached,
                running = runningTask == DepthTask.ComputeSelected,
                modifier = Modifier.weight(1f),
                onClick = { sel?.let { compute(listOf(it), DepthTask.ComputeSelected) } },
            )
            DepthButton(
                text = "删除选中景深",
                tint = MiuixTheme.colorScheme.error,
                enabled = idle && selCached,
                running = runningTask == DepthTask.DeleteSelected,
                modifier = Modifier.weight(1f),
                onClick = { sel?.let { deleteSubjects(listOf(it), DepthTask.DeleteSelected) } },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DepthButton(
                text = "计算所有景深",
                tint = MiuixTheme.colorScheme.primary,
                enabled = idle && anyUncached,
                running = runningTask == DepthTask.ComputeAll,
                modifier = Modifier.weight(1f),
                onClick = { compute(names.toList(), DepthTask.ComputeAll) },
            )
            DepthButton(
                text = "删除所有景深",
                tint = MiuixTheme.colorScheme.error,
                enabled = idle && anyCached,
                running = runningTask == DepthTask.DeleteAll,
                modifier = Modifier.weight(1f),
                onClick = { deleteSubjects(names.toList(), DepthTask.DeleteAll) },
            )
        }

        // 用户 2026-11-04：批量的「导入图片」**整块删掉** —— 入口只留空槽（新建卡），
        // 满 4 张时没有空槽，也就不需要「已到上限」的提示。
        // 这里换成本次需求的主角：给**选中的那一张**导入 / 更换遮罩或主体。
        val selHasSubject = sel?.let { s -> working?.subjectFileOf(s)?.isFile == true } == true
        TextButton(
            text = when {
                maskBusy -> "处理中…"
                selHasSubject -> "更换选中图片的遮罩或主体"
                else -> "导入选中图片的遮罩或主体"
            },
            onClick = {
                haptic()
                maskTarget = selectedName
                maskPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            enabled = idle && selectedName != null,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )

        // 「尺寸与壁纸不一致」（照搬 `MaskDetailActivity` 那条，用户 2026-11-04 要求）。
        // 正文按探测结果区分「遮罩 / 主体」；「确定」= 继续（遮罩天然缩放到原图、主体显式缩放）。
        val pm = pendingMask
        WindowDialog(
            title = "尺寸与壁纸不一致",
            summary = if (pm == null) "" else {
                val what = if (pm.probe.kind == RasterStore.SubjectKind.MASK) "遮罩" else "主体"
                "所选$what ${pm.probe.srcW}×${pm.probe.srcH}，" +
                    "原图 ${pm.probe.dstW}×${pm.probe.dstH}。\n" +
                    "尺寸不一致时${what}可能错位。仍要继续吗？"
            },
            show = pm != null,
            onDismissRequest = { pendingMask = null },
        ) {
            val dismissState = LocalDismissState.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        pendingMask = null
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "确定",
                    primary = true,
                    onClick = {
                        val p = pm
                        pendingMask = null
                        dismissState?.invoke()
                        if (p != null) applyMask(p.name, p.uri, p.probe.kind)
                    },
                )
            }
        }
    }
    }
}

/** 尺寸不一致、等用户确认的那一次「导入遮罩 / 主体」。 */
private data class PendingMask(
    val name: String,
    val uri: Uri,
    val probe: RasterStore.SubjectProbe,
)

/**
 * 景深相关按钮：**常规按钮底**（`buttonColors()` 的次级灰）+ **彩色文字**。
 *
 * 用户 2026-11-04 定的样式：计算 = 主题色字、删除 = 警示色字；不可点时由 miuix 的禁用色接管。
 */
@Composable
private fun DepthButton(
    text: String,
    tint: Color,
    enabled: Boolean,
    /** 这一次是不是它在跑 —— 是的话文案直接换成「处理中…」（不再在按钮下面加小字）。 */
    running: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(contentColor = tint),
    ) {
        Text(
            text = if (running) "处理中…" else text,
            style = MiuixTheme.textStyles.button,
            maxLines = 1,
        )
    }
}

/**
 * 一个槽位：空 ⇒ 「新建」卡（复用卡片流那套）；有图 ⇒ 封面 + 描边，选中时加删除钮。
 *
 * 视觉规范照 miuix：库内**没有**"可选卡片"组件，但它表达选中用的是 squircle 描边
 * （`TabRow` 的选中指示就是 `squircleBorder` 的延迟读取重载）。这里用同源的
 * [squircleStroke]（画在**内容之上**，否则会被图片盖住），颜色/线宽都走 `animate*AsState`，
 * 读取发生在绘制阶段 ⇒ 动画只重绘、不重组。
 */
@Composable
private fun SlotCard(
    file: java.io.File?,
    slotW: Dp,
    slotH: Dp,
    radius: Dp,
    selected: Boolean,
    surface: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    if (file == null) {
        NewCard(
            cardW = slotW,
            cardH = slotH,
            radius = radius,
            modifier = modifier,
            surface = surface,
            onClick = onClick,
        )
        return
    }
    val cover = rememberCover(file)
    val stroke by animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary else CARD_STROKE,
        animationSpec = tween(SLOT_STROKE_MS),
    )
    val strokeW by animateDpAsState(
        targetValue = if (selected) SELECTED_STROKE_WIDTH else CARD_STROKE_WIDTH,
        animationSpec = tween(SLOT_STROKE_MS),
    )
    // 删除钮的显现/消失动画：和一级页卡片流完全一致（150ms、75%→100%、以圆心为中心）。
    val badge by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = BADGE_FADE_MS),
    )
    // 外层不裁切：删除钮圆心压在卡角上，探出去的那一点不能被裁掉。
    Box(modifier = modifier.size(slotW, slotH)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .squircleClip(radius)
                .background(surface)
                .squircleStroke({ strokeW }, { stroke }, radius)
                .clickable(onClick = onClick),
        ) {
            CardImage(cover, Modifier.fillMaxSize())
        }
        if (badge > 0f) {
            val d = slotW * DELETE_CIRCLE_RATIO
            RemoveBadge(
                diameter = d,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 和卡片流同一个位置：圆心落在卡角的角弧上。
                    .zIndex(1f)
                    .offset(x = d * SHEET_BADGE_INSET, y = -d * SHEET_BADGE_INSET)
                    .graphicsLayer {
                        val sc = BADGE_FROM_SCALE + (1f - BADGE_FROM_SCALE) * badge
                        scaleX = sc
                        scaleY = sc
                        alpha = badge
                    },
                onClick = onRemove,
            )
        }
    }
}

// ------------------------------------------------------------------ 顶栏图标

/** 浮页顶栏两个动作图标。 */
private enum class SheetIconKind { Close, Check }

/**
 * 浮页顶栏的关闭 / 完成图标（2026-11-03）。
 *
 * **自绘**，不用图标库：`MiuixIcons.Basic` 里那对 ✕ / ✓ 的笔画粗细差一截
 * （用户一眼看出"叉和勾线条粗细不一样"），统一成 2dp 圆头线最省事。
 */
@Composable
private fun SheetActionIcon(kind: SheetIconKind, contentDescription: String) {
    val tint = MiuixTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val stroke = 2.dp.toPx()
        when (kind) {
            SheetIconKind.Close -> {
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.22f, size.height * 0.22f),
                    end = Offset(size.width * 0.78f, size.height * 0.78f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.78f, size.height * 0.22f),
                    end = Offset(size.width * 0.22f, size.height * 0.78f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            SheetIconKind.Check -> {
                // 图形占 60% 画布（原来只有 40%，比顶栏图标小一圈，用户 2026-11-03 指出）。
                val path = Path().apply {
                    moveTo(size.width * 0.20f, size.height * 0.54f)
                    lineTo(size.width * 0.40f, size.height * 0.74f)
                    lineTo(size.width * 0.80f, size.height * 0.28f)
                }
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
