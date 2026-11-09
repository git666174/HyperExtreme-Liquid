package com.frederic22.hyperextreme.ui

import android.graphics.Bitmap
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RasterControl
import com.frederic22.hyperextreme.data.RasterFlag
import com.frederic22.hyperextreme.data.RasterGroup
import com.frederic22.hyperextreme.data.RasterStore
import com.frederic22.hyperextreme.wallpaper.DynamicWallpaperPermission
import com.frederic22.hyperextreme.wallpaper.RasterServiceTarget
import com.frederic22.hyperextreme.wallpaper.RasterWallpaperService
import com.frederic22.hyperextreme.wallpaper.SpatialServiceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 二级页「光栅壁纸」（2026-11-03 立项，2026-11-03 第三版）。
 *
 * 四块内容，从上到下：
 * 1. **组的横向卡片流** —— 一个框 = 一个组，最左边是「新建」；
 * 2. **三态主按钮** —— 见下；
 * 3. **操作卡** —— 编辑当前组 / 算主体遮罩；
 * 4. **遮罩预览** —— 验证用，渲染链接进来之前先看抠像对不对。
 *
 * ## 进场定位（2026-11-03 用户要求）
 *
 * 已经有一套或多套搭配时，进页面**直接停在第一套**（`RasterStore.list` 按创建时间倒序
 * ⇒ 第一个 = 最新建的那套）；一套都没有才停在「新建」卡。
 * 为此首次扫描完成前不组合卡片流（用等高占位），否则会先露「新建」卡再跳走，闪一帧。
 *
 * ## 三态主按钮（2026-11-03 用户要求，对齐系统「系统个性化」）
 *
 * | 中间是什么 | 文案 | 态 |
 * |---|---|---|
 * | 新建卡 | 新搭配 | 主色可点，打开新建浮页 |
 * | 当前使用中的组 | 正在使用 | 禁用（miuix 自带浅灰） |
 * | 其他组 | 应用 | 主色可点，写入 `MaskPrefs.rasterActiveId` |
 *
 * 「当前使用」目前只是一个 **id 指针**（渲染链还没做，点「应用」不产生壁纸效果）；
 * 将来渲染链接进来直接读这一份。指向的组消失时自动清空，不留幽灵状态。
 *
 * 非当前组的卡片右上角是**红色圆 + 白色减号**的删除钮（点它弹确认框，确认后才删整组）。
 * 卡片右上角原来那个「N 张」角标 2026-11-03 按用户要求撤掉了。
 *
 * ## 卡片尺寸不是写死的像素
 *
 * 全部**按当前设备的屏幕分辨率换算**（`WindowManager.maximumWindowMetrics`，含状态栏/导航栏）：
 *
 * | 量 | 比例 | 依据 |
 * |---|---|---|
 * | 卡宽 | 屏宽 × **0.45** | 用户 2026-11-03 拍板（37% 与 55.5% 的折中） |
 * | 卡高 | 卡宽 × 屏高/屏宽 | 卡片是整屏预览，比例必须等于屏幕 |
 * | 角半径 | 卡宽 × **0.136** | 45% 下正好 ≈ 24dp，与系统锁屏编辑那张一致 |
 * | 视觉空隙 | **25dp** | 相邻两卡之间的空白（用户 2026-11-03 拍板）；布局间隔由它反推 |
 *
 * 换机型时只跟分辨率走，不依赖任何固定 dp。
 *
 * ## 圆角用的是澎湃那种「平滑过渡」而不是纯圆弧
 *
 * 用 miuix 自带的 `Modifier.squircleClip(radius)`（`miuix-squircle` 模块，
 * `SquirclePath` 用三次贝塞尔 + `SQUIRCLE_CONTROL = 0.643` 画连续曲率）。
 * 纯 `RoundedCornerShape` 在四个角会出现曲率突变，放大看能看出「方一下再圆」。
 *
 * ## 滚动：LazyRow（2026-11-03 从 Pager 重写）
 *
 * **为什么换掉 Pager**：Pager 没有「删除一项」的布局动画，之前用 `translationX` 手工补位，
 * 反复出现「闪一下 / 左侧瞬移 / 新建卡不缩小」。
 *
 * **为什么不给卡片用 `Modifier.animateItem()`**（v1.7.1 踩过）：placement 动画只在绘制层
 * 平移，而 `layoutInfo` 已经是**终点**。缩放是按 `centerPos`（由 `layoutInfo` 算出）来的，
 * 于是卡片会「先放大、再移动」。补位现在改成**一个 0→1 的进度同时驱动位移和缩放**。
 *
 * 居中判定：只用 `item.offset`（零点在**内容**起点 ⇒ `offset == 0` 就是居中）：
 *
 * ```
 * centerPos = firstVisibleItem.index − firstVisibleItem.offset / pageStep
 * ```
 *
 * ⚠️ 别用 `firstVisibleItemScrollOffset`：它的零点在**容器**起点，差一个 contentPadding
 * （本机 330px ≈ 0.57 页），算出来的页号永远落不到整数上，吸附判定与 `onFocus` 全会错。
 *
 * 吸附：自研 `SnapPageFlingBehavior` —— 一次最多一页 + 临界阻尼弹簧（带释放速度）。
 *
 * ## 数据
 *
 * 组全部落盘（`files/raster/<id>/`，见 [RasterStore]）。页面只做两件事：
 * 进页面 / 浮页关掉之后重扫一次目录，其余状态交给浮页自己管。
 *
 * @author 22Frederic
 */
@Composable
internal fun RasterWallpaperDetail(state: MaskUiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberClickHaptic()

    // 组列表：磁盘即真相，每次 reload 都重扫（几毫秒）。
    var groups by remember { mutableStateOf<List<RasterGroup>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    // 首次扫描是否完成。**完成前不组合卡片流** —— 否则第一帧会先露出「新建」卡，
    // 扫描回来再跳到第一套，进页面能看到明显的一闪。
    var loaded by remember { mutableStateOf(false) }
    var located by remember { mutableStateOf(false) }
    // 当前聚焦的 item 下标：0 = 新建卡，i+1 = groups[i]
    var focused by remember { mutableIntStateOf(0) }
    // 浮页：null = 新建；非 null = 编辑该组
    var sheetOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RasterGroup?>(null) }
    // 本次打开浮页是否要**同时拉起系统选图**（只有新建时true，用户 2026-11-03）。
    var autoPick by remember { mutableStateOf(false) }
    // 待删除的组；非 null 时弹确认框（确认后才真删）。
    var pendingDelete by remember { mutableStateOf<RasterGroup?>(null) }
    // 正在播删除动画的组 id：卡片 + 删除钮一起缩小淡出，**播完才真删**（用户 2026-11-03）。
    var deletingId by remember { mutableStateOf<String?>(null) }
    // 补位（2026-11-04 定案）：删完之后**不给其他卡做布局动画**（`animateItem` 的 placement
    // 动画只在绘制层平移，`layoutInfo` 却已经是终点 ⇒ 缩放按"已居中"算、卡片先放大再移动）。
    // 改成一个 0→1 的进度，位移与缩放都从它推出来，两者永远同源。
    //   shiftDir = +1 右侧补位（被删项右边的卡整体左移一页）
    //   shiftDir = −1 左侧补位（删最后一项 ⇒ 滚动位置 clamp，整体右移一页）
    var shiftDir by remember { mutableFloatStateOf(0f) }
    // 被删项的 page（右侧补位只影响它右边的项；左侧补位影响全部）。
    var shiftFromPage by remember { mutableIntStateOf(-1) }
    // 每次补位 +1：卡片流用它在**创建时**就把进度建成 0，避免晚一帧才归零（那一帧会闪）。
    var shiftToken by remember { mutableIntStateOf(0) }

    val prefs = remember { MaskPrefs(ctx) }
    // 「当前使用中的组」——空串 = 一套都没应用。
    var activeId by remember { mutableStateOf(prefs.rasterActiveId) }

    LaunchedEffect(reload) {
        // 顺序**持久化**在 `rasterGroupOrder` 里（2026-11-05）：应用 / 新建时把 id 提到最前，
        // 关闭应用**不动它** —— 以前是加载时按 activeId 临时排序，一关就跳回原来的位置。
        val list = withContext(Dispatchers.IO) { RasterStore.list(ctx, prefs.rasterGroupOrder) }
        // 指向的组已经不存在（被删 / 目录损坏）⇒ 清掉指针并关渲染器，不留幽灵状态：
        // 否则 SystemUI 里那条链会抱着一个读不到图的 key 一直画黑屏。
        var active = prefs.rasterActiveId
        if (active.isNotEmpty() && list.none { it.id == active }) {
            prefs.setRasterActiveId(null)
            // 幽灵状态：基准也一起清，否则 hook 侧还会拿旧判据做换壁纸检测。
            prefs.setRasterWallpaperFp("")
            RasterFlag.push(false, null, prefs.rasterScope)
            active = ""
            activeId = ""
        }
        groups = list
        // 进页面定位（2026-11-03 用户要求）：有组就停在**第一套**，一套都没有才停在「新建」卡。
        // 配合上面的排序，第一套 = 正在应用的那套（没有应用的组时 = 最新建的那套）。
        // ⚠️ 必须和 `loaded` 在同一个快照里写完：`GroupCarousel` 是在 `loaded` 之后才组合的，
        // 它用 `initialFirstVisibleItemIndex` 建 LazyListState —— 晚一帧就会出现「新建卡滑走」。
        if (!located) {
            located = true
            focused = if (groups.isEmpty()) 0 else 1
        }
        loaded = true
    }

    // 组被删掉（浮页里删空）后 focused 可能越界 ⇒ 收敛到最后一格。
    LaunchedEffect(groups.size) {
        if (focused > groups.size) focused = groups.size
    }

    // 被删的组真正从列表里消失之后才清 `deletingId` —— 提前清会让 removeAnim 从 0 弹回 1（闪一下）。
    LaunchedEffect(groups, deletingId) {
        if (deletingId != null && groups.none { it.id == deletingId }) deletingId = null
    }

    // 「正在使用」指向的组已经不存在（比如在浮页里被删空）⇒ 清掉指针，别显示幽灵状态。
    LaunchedEffect(loaded, groups, activeId) {
        if (loaded && activeId.isNotEmpty() && groups.none { it.id == activeId }) {
            prefs.setRasterActiveId(null)
            activeId = ""
        }
    }

    val selected = groups.getOrNull(focused - 1)

    // 「参数调整」三项（2026-11-03 从空间壁纸页整套搬来，删掉了用不到的「缓存推理分辨率」）。
    var mountMode by remember { mutableIntStateOf(prefs.rasterMountMode) }
    var scopeChoice by remember { mutableIntStateOf(prefs.rasterScope) }
    var lockDepth by remember { mutableStateOf(prefs.rasterLockDepth) }
    // 「最大幅度角度」（度，15..45，默认 25）：从第一张擦到最后一张需要的横向转角。
    var maxAngle by remember { mutableIntStateOf(prefs.rasterMaxAngle) }
    // 「停顿时就近归位」（默认开）：关掉后可以停在两张之间不动。
    var settle by remember { mutableStateOf(prefs.rasterSettle) }

    // ───────────── 系统壁纸服务模式（2026-11-04）─────────────
    //
    // 「系统壁纸服务」是不是正在生效。**只在前台查**（用户 2026-10-30 对空间壁纸页的定案，
    // 这里照抄）：`WallpaperManager` 没有壁纸变化回调（`ACTION_WALLPAPER_CHANGED` 是
    // protected broadcast），只能靠 ON_RESUME 重读。
    //
    // ⚠️ 判据必须带 service class：本包有两个 `WallpaperService`（空间 / 光栅），
    //    只比包名会让两条链互相误判「我的服务正在跑」。
    var serviceRunning by remember {
        mutableStateOf(SpatialServiceMode.isRunning(ctx, RasterWallpaperService::class.java))
    }
    var showMountWarn by remember { mutableStateOf(false) }
    var noMoreWarn by remember { mutableStateOf(false) }
    // 「删除所有搭配」确认框。
    var showClearAll by remember { mutableStateOf(false) }
    // 功能冲突（2026-11-04）：视差 / 空间任一在跑时不许应用。
    var showConflict by remember { mutableStateOf(false) }
    var conflictOther by remember { mutableStateOf<RunningWallpaper?>(null) }

    /**
     * **正在渲染的那一组**的 id；没在生效 / 没在跑时是空串。
     *
     * 只有这一组不能删：它被删掉，壁纸进程读不到图（服务模式直接黑屏、hook 模式空帧）。
     * 其余组**随便删**（用户 2026-11-04 纠正：原来用 `locked` 把所有组都拦了，
     * 没在用的配套也弹「请先取消应用」）。
     *
     * 服务模式额外要求 `serviceRunning`：用户可能已经从系统壁纸页换回默认壁纸，
     * 这时 `activeId` 还指着旧组，但那组已经不在渲染，删掉没有任何影响。
     */
    val inUseId = when {
        mountMode == RASTER_MOUNT_SERVICE -> if (serviceRunning) activeId else ""
        else -> activeId
    }

    /** 当前模式是否**正在运行** —— 为 true 时「挂载模式」下拉锁死。 */
    val isRunning = if (mountMode == RASTER_MOUNT_SERVICE) serviceRunning else activeId.isNotEmpty()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 用户可能在系统壁纸页里换了图 / 换了范围，回来必须重新对一次。
                serviceRunning = SpatialServiceMode.isRunning(ctx, RasterWallpaperService::class.java)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSheet(g: RasterGroup?) {
        haptic()
        editing = g
        // 新建时要把系统选图**一起拉起来**（用户 2026-11-03）：选中几张就导入几张，
        // 直接关掉选择器则连浮页一起收回（见 `RasterGroupSheet` 的 `autoPick`）。
        autoPick = g == null
        sheetOpen = true
    }

    /**
     * 把某一套设为「正在使用」，按挂载模式走两条完全不同的路。
     *
     * * **LSPosed**：写 prefs 指针 + 推系统属性（渲染器每秒轮询一次）。
     * * **系统壁纸服务**：写**独立**的 target 文件 + 拉起系统页；**绝不推属性**
     *   （推了 hook 链也会画，同一张图被渲染两遍）。
     */
    fun applyGroup(g: RasterGroup) {
        // 服务模式**不早退**：即使 id 没变也要重新写 target + 拉起系统页
        //（用户可能刚从系统页把壁纸换回去了，这时点「跳转系统页」必须能重来）。
        if (mountMode != RASTER_MOUNT_SERVICE && g.id == activeId) return
        // **功能冲突检测**（2026-11-04 用户要求，三侧统一）：
        // 视差 / 空间任一在跑就不许应用 —— 三条链都靠"压住系统壁纸"工作，同时开会互相盖。
        // 弹窗复用 `FeatureConflictDialog`，正文里的名字取实际在跑的那条。
        val other = WallpaperConflict.running(ctx, state, WallpaperChain.RASTER)
        if (other != null) {
            conflictOther = other
            showConflict = true
            return
        }
        haptic()
        prefs.setRasterActiveId(g.id)
        activeId = g.id
        // 应用即置顶，但**只写顺序、不动当前列表**（2026-11-05 用户明确）：
        // 点应用的这一刻卡片留在原位，退出页面重进时才刷新到第一个；
        // 关闭应用也不清顺序 ⇒ 重进仍在第一个。
        prefs.setRasterGroupOrder(listOf(g.id) + prefs.rasterGroupOrder.filterNot { it == g.id })
        if (mountMode == RASTER_MOUNT_SERVICE) {
            // 权限预检（与空间壁纸页同一条，2026-11-03）：没有「动态壁纸服务」权限时
            // `LiveWallpaperChange` 会**静默 finish**（startActivity 不抛异常），
            // 用户看到的就是「点了一下，什么都没发生」。
            if (!DynamicWallpaperPermission.allowed(ctx)) {
                Toast.makeText(ctx, "请先给予模块“动态壁纸服务”权限", Toast.LENGTH_LONG).show()
                DynamicWallpaperPermission.openSettings(ctx)
                return
            }
            RasterServiceTarget.write(ctx, g.id, prefs.rasterMaxAngle, prefs.rasterSettle)
            SpatialServiceMode.openPicker(ctx, RasterWallpaperService::class.java)
            Toast.makeText(ctx, "请在系统服务页设置生效范围", Toast.LENGTH_LONG).show()
        } else {
            // 景深开关随「应用」一起下发（生效中改它只写 prefs，下一次应用才生效 —— 与空间壁纸同语义）。
            RasterFlag.push(
                true, g.id, prefs.rasterScope, prefs.rasterLockDepth,
                prefs.rasterMaxAngle, prefs.rasterSettle,
            )
            // 记「壁纸基准」（2026-11-05）：aod 侧的换壁纸检测拿它当比对值，判据一变就无条件关闭
            // 光栅壁纸。判据要读模板文件 ⇒ 放后台、不阻塞点按；算不出来就留空 —— hook 侧会
            // 惰性补记，只是第一次换壁纸时不会自动关（与空间同款可接受降级）。
            val app = ctx.applicationContext ?: ctx
            Thread {
                runCatching {
                    val version = com.frederic22.hyperextreme.data.WallpaperVersion.compute(app)
                    if (version.usable) prefs.setRasterWallpaperFp(version.value)
                }
            }.apply { isDaemon = true }.start()
            // 与空间壁纸**逐字同构**的生效提示（2026-11-04 用户要求）：
            // 范围文案取自 `prefs`（生效中改范围是下次替换才推，这里读到的就是将要生效的值）。
            Toast.makeText(
                ctx,
                "光栅壁纸已生效（${scopeLabel(prefs.rasterScope)}）",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * 取消应用 / 恢复默认。
     *
     * 服务模式下必须走 `WallpaperManager.clear()`（同步 binder，几百 ms ⇒ 丢 IO 线程），
     * 否则系统壁纸还是我们的 service，只是 target 被清空 ⇒ 壁纸进程画不出东西 = 黑屏。
     */
    fun clearActive() {
        if (activeId.isEmpty() && !serviceRunning) return
        haptic()
        if (mountMode == RASTER_MOUNT_SERVICE) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    SpatialServiceMode.disable(ctx) { RasterServiceTarget.clear(it) }
                }
                serviceRunning = SpatialServiceMode.isRunning(ctx, RasterWallpaperService::class.java)
                prefs.setRasterActiveId(null)
                // 壁纸基准随关闭一起清 —— 下次应用时重记（与空间同语义）。
                prefs.setRasterWallpaperFp("")
                activeId = ""
                Toast.makeText(ctx, "已恢复到系统默认壁纸", Toast.LENGTH_LONG).show()
            }
            return
        }
        // hook 模式与「换壁纸自动关闭」共用同一个收口（`RasterControl`）：
        // 清 activeId + 清基准 + 推属性，SystemUI 轮询到 active=0 自己拆层。
        RasterControl.disable(ctx, "光栅壁纸已关闭")
        activeId = ""
    }

    /**
     * 提交挂载模式（弹窗确认后才走这里，与空间壁纸页同一形态）。
     *
     * 切到服务模式时**主动关掉 hook 链**，否则同一张图会被渲染两遍（一条挂在 leash 上、
     * 一条走系统壁纸），而且两边的姿态口径不同，肉眼就是错位 + 掉帧。
     * 切回 LSPosed **不自动恢复** —— 与空间壁纸一致：用户要重新点一次「应用」。
     */
    fun commitMountMode(next: Int) {
        mountMode = next
        prefs.setRasterMountMode(next)
        if (next == RASTER_MOUNT_SERVICE && activeId.isNotEmpty()) {
            RasterFlag.push(false, null, prefs.rasterScope)
            prefs.setRasterActiveId(null)
            // 基准跟着模式切换一起清：hook 链已经关了，基准留着只会让下次误判。
            prefs.setRasterWallpaperFp("")
            activeId = ""
        }
    }

    /** 确认删除：只打标记，卡片自己 200ms 缩小淡出；播完由卡片流回调 [commitDelete]。 */
    fun deleteGroup(g: RasterGroup) {
        pendingDelete = null
        if (deletingId != null) return
        deletingId = g.id
    }

    /**
     * 淡出播完：**直接从内存列表移除**（不重新读盘），并在**同一帧**把补位方向交给卡片流。
     *
     * 为什么不等布局动画：`animateItem` 的 placement 动画只在绘制层平移，`layoutInfo`
     * 已经是终点 —— 缩放按 `centerPos` 算就会"先放大再移动"（用户 2026-11-04 指出）。
     * 现在位移/缩放都由卡片流的同一个 0→1 进度推出，两者锁死。
     *
     * 方向判定：被删页右边还有卡 ⇒ `+1`（右边的卡整体左移一页）；
     * 否则（删最后一项）⇒ `−1`（数据变短、滚动位置 clamp，整体右移一页）。
     */
    fun commitDelete() {
        val id = deletingId ?: return
        val idx = groups.indexOfFirst { it.id == id }
        if (idx < 0) return
        val g = groups[idx]
        val page = idx + 1          // item 索引（item 0 = 新建卡）
        val lastPage = groups.size  // 页数 = 组数 + 1，末页索引 = 组数
        shiftDir = if (page < lastPage) 1f else -1f
        shiftFromPage = page
        shiftToken++
        groups = groups.filterNot { it.id == id }
        // 顺序表里也要摘掉，否则下次重扫会把它当成"不在 order 里"的残余（虽然会被 found 过滤掉，
        // 但表会越积越长）。
        prefs.setRasterGroupOrder(prefs.rasterGroupOrder.filterNot { it == id })
        deletingId = null
        scope.launch {
            withContext(Dispatchers.IO) { RasterStore.deleteGroup(g) }
        }
    }

    val screen = rememberScreenSizePx()
    val density = LocalDensity.current
    // 卡宽（dp）—— 主按钮的宽度按它的 5/6 算（用户 2026-11-03 定）。
    val cardWidthDp: Dp = with(density) { (screen.width * CARD_WIDTH_RATIO).toDp() }
    val buttonWidth: Dp = cardWidthDp * 5f / 6f
    // Pager 纵向余量 = 删除钮探出卡顶的量 + 1dp（跟随卡宽动态算）。
    val pagerPadding = pagerVPadding(cardWidthDp)
    // 只给「首次扫描中」的占位用，高度与卡片流一致（卡片流内部还有自己一套同源换算）。
    val carouselHeight: Dp = with(density) {
        (screen.width * CARD_WIDTH_RATIO * screen.height / screen.width).toDp()
    } + pagerPadding * 2

    // 与空间壁纸页**逐字一致**的手写滚动容器（2026-11-04 用户对比后定的）：
    // `TabColumn` 的 bottom 是 120dp（给悬浮底栏留位），滚到底会露出一大块空白；
    // 空间页自己写的是 32dp。两页必须同口径，否则一页"贴底"、一页"空一大块"。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        // 顶部不再放 SmallTitle（用户 2026-11-03：不要「我的光栅」这个标题），
        // 大标题到卡片流也是 [BLOCK_GAP]（用户 2026-11-03：所有竖向间距统一 24dp）。
        Spacer(Modifier.height(BLOCK_GAP))

        if (loaded) {
            CoverCarousel(
                items = groups.map { CoverItem(it.id, it.coverFile) },
                initialPage = if (groups.isEmpty()) 0 else 1,
                activeId = activeId,
                deletingId = deletingId,
                shiftDir = shiftDir,
                shiftFromPage = shiftFromPage,
                shiftToken = shiftToken,
                screen = screen,
                onFocus = { focused = it },
                onNew = { openSheet(null) },
                // **正在应用的那一组不能编辑**（用户 2026-11-04）：浮页里能移除图 / 重排 /
                // 换主体，改完正在渲染的那份就读不到图或对不上号。其余组照常编辑。
                onClick = { item ->
                    val g = groups.firstOrNull { it.id == item.id }
                    when {
                        g == null -> Unit
                        g.id == inUseId ->
                            Toast.makeText(ctx, "正在应用的组合无法编辑", Toast.LENGTH_SHORT).show()
                        else -> openSheet(g)
                    }
                },
                // 生效中**只拦正在渲染的那一组**：删了它壁纸进程会读不到图
                //（服务模式直接黑屏、hook 模式空帧）。其余组照删（用户 2026-11-04 纠正）。
                onDelete = { item ->
                    val g = groups.firstOrNull { it.id == item.id }
                    when {
                        g == null -> Unit
                        g.id == inUseId ->
                            Toast.makeText(ctx, "请先取消应用", Toast.LENGTH_SHORT).show()
                        else -> {
                            haptic()
                            pendingDelete = g
                        }
                    }
                },
                onDeleteSettled = { commitDelete() },
            )
        } else {
            Spacer(Modifier.height(carouselHeight))
        }

        // 卡片流上下都留动态余量（= 删除钮探出量 + 1dp）：这层余量是**必需的** ——
        // 删除钮圆心在卡角上、圆顶会探出卡片，Pager 页面若正好等于卡片高度会把它裁掉
        //（v1.6.21 去掉纵向 padding 后就是这个症状）。
        // 按钮上方的可见间距 = padding + 下面的 Spacer，两者之和正好是 [BLOCK_GAP]。
        Spacer(Modifier.height(BLOCK_GAP - pagerPadding))

        // 卡片下方的主操作按钮（2026-11-03）：四态 ——
        //   新建卡「新搭配」/ LSPosed 当前组「取消应用」/ LSPosed 其余组「应用」/
        //   **服务模式未运行「跳转系统页」/ 服务模式运行中「恢复默认」**（2026-11-04 用户定）。
        // 2026-11-03 晚：「老老实实圆角矩形」—— 宽 = 中间卡片的 5/6，
        // 圆角 / 高度 / 内边距 / 字号全部用 miuix 默认值（不再自己拧）。
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val isActive = selected != null && selected.id == activeId
            val serviceMode = mountMode == RASTER_MOUNT_SERVICE
            // 「取消 / 恢复」这类**次要动作**的配色判据：服务模式看的是"服务真在跑"，
            // 不能只看 id 指针 —— 用户可能从系统壁纸页把壁纸换回去了。
            val secondary = if (serviceMode) serviceRunning else isActive
            val label = when {
                selected == null -> "新搭配"
                serviceMode -> if (serviceRunning) "恢复默认" else "跳转系统页"
                // 「正在使用」改成可点的「取消应用」（用户 2026-11-03）。
                isActive -> "取消应用"
                else -> "应用"
            }
            // 提示色 ⇄ 常态：颜色**叠化**过去（用户 2026-11-03），不是硬切。
            // 已应用那套 = **正常按钮色（灰底）+ 提示色文字**，表示这是次要的"取消"操作。
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
                    val g = selected
                    when {
                        g == null -> openSheet(null)
                        // 服务模式：运行中 = 恢复默认；未运行 = 拿当前这组去跳转系统页。
                        // ⚠️ 不能复用下面的 `g.id == activeId` 早退 —— 服务模式下 id 指针
                        // 可能还指着旧组（用户从系统页换过壁纸），那会变成"点了没反应"。
                        serviceMode -> if (serviceRunning) clearActive() else applyGroup(g)
                        g.id == activeId -> clearActive()
                        else -> applyGroup(g)
                    }
                },
                // 三态都可点（2026-11-03 用户要求取消应用也能点）。
                // ⚠️ miuix `Button` 自己会 `fillMaxWidth`，只给 `minWidth` 是**挡不住**的
                //   —— 必须在 modifier 上把宽度卡死（用户 2026-11-03 连着两次问"怎么还这么宽"）。
                modifier = Modifier.width(buttonWidth),
                // 高度 / 圆角 / 内边距全走 miuix 默认。
                // 默认 `buttonColors()` 是**次级灰**；「新搭配 / 应用」要的是提示色（primary 蓝）。
                colors = ButtonDefaults.buttonColorsPrimary(
                    color = bg,
                    disabledColor = bg,
                    contentColor = fg,
                    disabledContentColor = fg,
                ),
            ) {
                // 文案也叠化（应用 ⇄ 正在使用）。`Crossfade` 没有 contentAlignment 参数，
                // 用 `fillMaxWidth + textAlign` 把长短两种文案都摆正。
                Crossfade(
                    targetState = label,
                    animationSpec = tween(BUTTON_FADE_MS),
                ) { text ->
                    Text(
                        text = text,
                        // 文字大小用 miuix 默认的按钮字号（用户 2026-11-03）。
                        style = MiuixTheme.textStyles.button,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(BLOCK_GAP))

        // 「参数调整」整套照空间壁纸页搬来（2026-11-03 用户要求）：
        // **卡外标题不要**、**「缓存推理分辨率」删掉**（光栅用不到），
        // 其余两项的「空间壁纸」字样都换成「光栅壁纸」。
        // ⚠️ 渲染链还没做 ⇒ 这三项目前只存值、不生效（落点见 `MaskPrefs.raster*`）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            DropdownRow(
                title = "挂载模式",
                options = RASTER_MOUNT_MODE_LABELS,
                selectedIndex = mountMode.coerceIn(0, RASTER_MOUNT_MODE_LABELS.lastIndex),
                // 运行中锁死（两条闭环：LSPosed 必须先点「取消应用」；服务模式必须先点
                // 「恢复默认」）—— 与空间壁纸页同一语义。
                enabled = !isRunning,
                onSelect = { index ->
                    if (index == mountMode) return@DropdownRow
                    // 切到服务模式：先弹「功能提醒」（用户勾过「不再弹」就直接生效）。
                    // ⚠️ 光栅的「不再弹」是**独立的值**（`rasterMountWarned`）。
                    if (index == RASTER_MOUNT_SERVICE && !prefs.rasterMountWarned) {
                        noMoreWarn = false
                        showMountWarn = true
                    } else {
                        commitMountMode(index)
                    }
                },
            )
            DropdownRow(
                title = "光栅壁纸生效范围",
                options = RASTER_SCOPE_LABELS,
                selectedIndex = scopeChoice.coerceIn(0, RASTER_SCOPE_LABELS.lastIndex),
                // 系统壁纸服务模式下范围由系统壁纸页决定 ⇒ 变灰，但值保留（照搬空间壁纸那套）。
                enabled = mountMode == RASTER_MOUNT_LSPOSED,
                onSelect = { index ->
                    scopeChoice = index
                    prefs.setRasterScope(index)
                    // **不立刻 push**（2026-11-04 用户要求与空间壁纸逐字一致）：
                    // 生效中改范围只记下来，下次点「应用 / 取消应用」时按新值一起推 ——
                    // 避免正在跑的时候拆掉渲染器重建挂载点。
                    if (activeId.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            SwitchRow(
                title = "启用光栅壁纸锁屏景深",
                checked = lockDepth,
                enabled = mountMode == RASTER_MOUNT_LSPOSED,
                onCheckedChange = { on ->
                    lockDepth = on
                    prefs.setRasterLockDepth(on)
                    // 同一条提示（照抄空间壁纸）：生效中改开关要等下一次替换才下发。
                    if (activeId.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            // 「停顿时就近归位」（2026-11-04 用户要求，紧贴「锁屏景深」下面）：
            // 开 = 静止后补完当前一擦；关 = **停在两张之间不动**。
            // 两种挂载模式都有效 ⇒ 不灰（服务版走 target 的 `settle`）。
            SwitchRow(
                title = "停顿时就近归位",
                checked = settle,
                onCheckedChange = { on ->
                    settle = on
                    prefs.setRasterSettle(on)
                    // 同一条提示：生效中改要等下一次替换才下发。
                    if (activeId.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    // 服务模式正在跑：顺手写进 target（壁纸进程下次可见时重读）。
                    if (serviceRunning && activeId.isNotEmpty()) {
                        RasterServiceTarget.write(ctx, activeId, maxAngle, on)
                    }
                },
            )
            // 「最大幅度角度」（2026-11-04 用户要求，排在「锁屏景深」下面）：
            // 从第一张擦到最后一张需要的**横向转角**，15~45°、步进 1°、默认 25°。
            // 直接复用视差页那套 `ParallaxSliderRow`（同名同量程同默认，避免两处口径漂移）。
            // 两种挂载模式都有效（服务版也吃这个值）⇒ 不灰。
            ParallaxSliderRow(
                title = "最大幅度角度",
                text = "${maxAngle}°",
                value = maxAngle.toFloat(),
                range = MaskPrefs.RASTER_ANGLE_MIN.toFloat()..MaskPrefs.RASTER_ANGLE_MAX.toFloat(),
                steps = sliderSteps(
                    MaskPrefs.RASTER_ANGLE_MIN.toFloat()..MaskPrefs.RASTER_ANGLE_MAX.toFloat(),
                ),
                onValueChange = { maxAngle = it.roundToInt() },
                onFinished = {
                    // 拖动过程只改内存；松手才落盘（与视差页的 persist 时机一致）。
                    prefs.setRasterMaxAngle(maxAngle)
                    if (activeId.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次替换时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    // 服务模式正在跑：把新角度写进 target —— 壁纸进程下次可见时重读。
                    if (serviceRunning && activeId.isNotEmpty()) {
                        RasterServiceTarget.write(ctx, activeId, maxAngle, settle)
                    }
                },
            )
        }

        // 「清除所有搭配」独立成一张卡 + 语义红 + 无箭头（照空间壁纸「清除所有深度缓存」的样式）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = "清除所有搭配",
                summary = null,
                titleColor = BasicComponentColors(
                    color = MiuixTheme.colorScheme.error,
                    disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                ),
                onClick = {
                    haptic()
                    showClearAll = true
                },
            )
        }
    }

    // 浮页**常驻组合树、用 show 控制**（《03》§3.9.6）：条件包起来 miuix 来不及播退出动画。
    RasterGroupSheet(
        show = sheetOpen,
        group = editing,
        autoPick = autoPick,
        onDismiss = {
            sheetOpen = false
            editing = null
            autoPick = false
            reload++
        },
    )

    // 删除确认（2026-11-03）：不允许一点就删，整组图片缓存都跟着没了。
    DeleteRasterDialog(
        show = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        onConfirm = { pendingDelete?.let { deleteGroup(it) } },
    )

    // 「删除所有搭配」确认（2026-11-03）：正文前半句固定，**后缀按实际运行态分三档**
    // （2026-11-04 用户要求，句式照空间壁纸页的「清除所有深度缓存」）。
    // 判据用**实际运行态**（serviceRunning 优先），与下面 onConfirm 的动作保持同一口径。
    RasterClearAllDialog(
        show = showClearAll,
        summary = when {
            serviceRunning -> "所有搭配内的图片缓存将被删除。当前光栅壁纸正在运行，" +
                "点击“清除”后将自动恢复到系统默认壁纸。"
            activeId.isNotEmpty() -> "所有搭配内的图片缓存将被删除。当前光栅壁纸正在运行，" +
                "点击“清除”后将自动关闭替换。"
            else -> "所有搭配内的图片缓存将被删除。"
        },
        onDismissRequest = { showClearAll = false },
        onConfirm = {
            showClearAll = false
            scope.launch {
                // **先停再删**：服务模式运行中，壁纸进程还在读 `files/raster/<id>`，
                // 直接删会让它读到空目录 → 黑屏。与空间壁纸页「清除所有深度缓存」同一条承诺。
                if (serviceRunning) {
                    withContext(Dispatchers.IO) {
                        SpatialServiceMode.disable(ctx) { RasterServiceTarget.clear(it) }
                    }
                    serviceRunning = SpatialServiceMode.isRunning(ctx, RasterWallpaperService::class.java)
                }
                // LSPosed 运行中：先关属性（幂等），再删。
                // ⚠️ 服务模式下**不再多推一次 false**：两条链互斥，属性本来就该是关着的；
                //    上面那档文案也只承诺「恢复到系统默认壁纸」，不承诺「关闭替换」。
                if (mountMode != RASTER_MOUNT_SERVICE && activeId.isNotEmpty()) {
                    RasterFlag.push(false, null, prefs.rasterScope)
                }
                val n = withContext(Dispatchers.IO) {
                    val count = RasterStore.deleteAll(ctx)
                    prefs.setRasterActiveId(null)
                    // 组全没了，顺序表一起清空（与空间「清除所有深度缓存」同一口径）。
                    prefs.setRasterGroupOrder(emptyList())
                    count
                }
                activeId = ""
                reload++
                Toast.makeText(ctx, "已清除 $n 项搭配", Toast.LENGTH_SHORT).show()
            }
        },
    )

    // 「功能提醒」弹窗（切到系统壁纸服务模式时弹一次；勾了「不再弹」就不再出现）。
    // ⚠️ 与空间壁纸**各自独立**：`rasterMountWarned` 是另一个 prefs 值。
    // 正文**完整照抄**空间壁纸那段（2026-11-04 用户要求"抄完整"），只把名词与按钮名换成光栅的：
    // 「空间壁纸」→「光栅壁纸」、「跳转到服务页设置」→「跳转系统页」、「恢复到系统默认壁纸」→「恢复默认」。
    MountModeWarnDialog(
        show = showMountWarn,
        noMoreWarn = noMoreWarn,
        onNoMoreWarnChange = { noMoreWarn = it },
        onDismissRequest = { showMountWarn = false },
        onConfirm = {
            if (noMoreWarn) prefs.setRasterMountWarned(true)
            showMountWarn = false
            commitMountMode(RASTER_MOUNT_SERVICE)
        },
        summary = "作为系统壁纸服务加载时，光栅壁纸锁屏景深不可用，锁屏时钟不支持柔光玻璃效果，" +
            "生效范围请在点击“跳转系统页”后在系统壁纸服务页进行选择。" +
            "如需关闭，请在锁屏编辑自行更换壁纸或点击“恢复默认”。",
    )

    // 功能冲突弹窗（常驻组合树、用 show 控制 —— 条件包裹会让退出动画播不出来）。
    // 「关闭」只关对方、**不继续**本次应用；要再点一次「应用」才生效（与空间 / 视差页同语义）。
    FeatureConflictDialog(
        show = showConflict,
        summary = conflictOther?.let { WallpaperConflict.summary(it, WallpaperChain.RASTER) } ?: "",
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

/**
 * 「删除此搭配？」确认框（2026-11-03）。
 *
 * 标题 / 正文 / 按钮语义由用户定死：
 * * 标题「删除此搭配？」
 * * 正文「搭配内的图片缓存将被删除」
 * * 取消 / 确认两个键，**确认键用 miuix 警示主题色**（`colorScheme.error` 底 + `onError` 字）
 *
 * 结构照 [FeatureConflictDialog]：正文走 `WindowDialog` 的 `summary`（官方正文位），
 * 按钮区 `SpaceBetween` + 两个 `TextButtonLike`。
 */
@Composable
private fun DeleteRasterDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = "删除此搭配？",
        summary = "搭配内的图片缓存将被删除",
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
 * 「删除所有搭配？」确认框（2026-11-03，用户给定文案）。
 *
 * * 标题「删除所有搭配？」
 * * 正文「所有搭配内的图片缓存将被删除」
 * * 取消 / 确认，确认键用 miuix 警示色
 *
 * 结构与 [DeleteRasterDialog] 一致。
 */
@Composable
private fun RasterClearAllDialog(
    show: Boolean,
    summary: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        title = "删除所有搭配？",
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

/** 「参数调整」里「挂载模式」的选项（照空间壁纸页，2026-11-03 整套搬来）。 */
private val RASTER_MOUNT_MODE_LABELS = listOf("LSPosed", "系统壁纸服务（免Root）")

/** 挂载模式里 LSPosed 的下标（值与 [MaskPrefs.RASTER_MOUNT_LSPOSED] 同一份，防漂移）。 */
private const val RASTER_MOUNT_LSPOSED = MaskPrefs.RASTER_MOUNT_LSPOSED

/** 挂载模式里「系统壁纸服务」的下标（与 [MaskPrefs.rasterMountMode] 同值域）。 */
private const val RASTER_MOUNT_SERVICE = MaskPrefs.RASTER_MOUNT_SERVICE

/** 「光栅壁纸生效范围」的选项（锁屏 / 桌面 / 全局）。 */
private val RASTER_SCOPE_LABELS = listOf("锁屏", "桌面", "全局")

/** 取值 → 文案；越界时回退到默认档（锁屏），绝不抛。与空间壁纸页同名同语义。 */
private fun scopeLabel(v: Int): String =
    RASTER_SCOPE_LABELS.getOrElse(v) { RASTER_SCOPE_LABELS[0] }
