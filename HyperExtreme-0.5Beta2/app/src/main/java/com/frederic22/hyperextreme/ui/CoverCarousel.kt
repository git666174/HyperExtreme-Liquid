package com.frederic22.hyperextreme.ui

import android.graphics.RectF
import android.util.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 通用封面卡片流（2026-11-04 从 `RasterWallpaperPage` 抽出来）。
 *
 * 光栅壁纸的「搭配卡」和空间壁纸的「候选图卡」是**同一套视觉与手感**：
 * 竖版 45% 屏宽、squircle 描边、红色删除钮、一次最多走一页的临界阻尼吸附、
 * 删除后按方向补位。抽成一份，免得两边各写一套、改一处漏一处。
 *
 * 这里只处理**卡片本身**：item 0 固定是「新建」，其余按 [items] 铺开。
 * 数据从哪来、点了做什么，全部由调用方通过回调决定。
 */

/** 一张卡要显示的东西：稳定 id（列表 key）+ 封面文件（null = 只有底色）。 */
internal data class CoverItem(
    val id: String,
    val cover: File?,
    /**
     * 归一化源矩形（0..1），null = 整幅。
     *
     * 空间壁纸用它把「显示区域」反映到卡片封面上（2026-11-05）；光栅恒 null。
     * 只影响显示，不落盘。
     */
    val src: RectF? = null,
)

// ------------------------------------------------------------------ 尺寸 / 节奏

/** 相邻两张卡之间的**视觉空隙**（用户 2026-11-03 拍板：25dp）。 */
private val GAP_VISUAL = 25.dp

/** 相邻卡最小缩放（用户 2026-11-03 拍板：0.85）。 */
private const val CARD_MIN_SCALE = 0.85f

/** 确认删除后「卡片缩小 + 淡出」的时长（用户 2026-11-03：0.2s）。 */
private const val DELETE_CARD_MS = 200

/** 卡片流 / 主按钮 / 下方卡片之间的统一竖间距（用户 2026-11-03：**全部 24dp**）。 */
internal val BLOCK_GAP = 24.dp

/** 主按钮「提示色 ⇄ 常态」的叠化时长（用户 2026-11-03）。两页共用同一个值。 */
internal const val BUTTON_FADE_MS = 200

/**
 * Pager 的纵向 `contentPadding` —— **动态值**（用户 2026-11-03 要求）：
 * 删除钮圆顶探出卡顶的量 `(0.5 − CARD_BADGE_CENTER_RATIO) × d` 再加 1dp 冗余。
 *
 * 本机：卡宽 180dp ⇒ d = 28.8dp ⇒ 探出 7.2dp ⇒ padding = 8.2dp。
 * 它同时参与「按钮上方的可见间距」计算（`BLOCK_GAP − padding`），所以视觉间距恒为 24dp。
 */
internal fun pagerVPadding(cardW: Dp): Dp = cardBadgeOverflow(cardW)

/** 吸附判定的容差（单位：页）。吸附到位后 `centerPos` 与整数页的差远小于它。 */
private const val SETTLE_EPS = 0.02f

/** 甩动速度阈值：超过它才「再走一页」，否则就近吸附（对齐 Pager 的手感）。 */
private val FLING_TRIGGER_VELOCITY = 125.dp

/** 喂给吸附弹簧的初速度上限（px/s）：极快甩动时不至于把弹簧顶得冲过头。 */
private const val MAX_SETTLE_VELOCITY = 8000f

/** 吸附收尾的"何时算到位"（px）：太小会在最后几帧以亚像素速度爬，看起来是顿一下。 */
private const val SETTLE_SNAP_PX = 1.5f

/**
 * 吸附弹簧的固有角频率 ω = √stiffness。
 * 弹簧用 `StiffnessMediumLow = 400` ⇒ ω = 20 rad/s。
 * 用来算「不冲过头」的初速度上限：v ≤ ω × 剩余距离。
 */
private const val SETTLE_OMEGA = 20f

/** 收尾补正（把落点精确贴到中线）的时长：不做瞬移，否则端点回来那一下很突兀。 */
private const val SETTLE_FIX_MS = 180

// ------------------------------------------------------------------ 卡片流

/**
 * 横向卡片流。item 0 固定是「新建」，其余是 [items]。
 *
 * 缩放只改绘制、不改布局 —— 这样卡片之间的间距在视觉上始终等于 [gap]，
 * 不会因为缩放把间距一起放大/缩小。
 *
 * @param activeId **正在应用**的那一张：居中时**不出现删除钮**（光栅「在用的组」/ 空间「在用的图」）
 * @param deletingId 正在播删除动画的那一张；播完回调 [onDeleteSettled]
 * @param shiftDir 补位方向：+1 右侧补位 / −1 左侧补位 / 0 无
 * @param shiftFromPage 被删项的 page（item 索引）
 * @param shiftToken 每次补位 +1，用来重建进度（必须**创建时**就是 0，晚一帧会闪）
 */
@Composable
internal fun CoverCarousel(
    items: List<CoverItem>,
    initialPage: Int,
    activeId: String,
    deletingId: String?,
    shiftDir: Float,
    shiftFromPage: Int,
    shiftToken: Int,
    screen: Size,
    onFocus: (Int) -> Unit,
    onNew: () -> Unit,
    onClick: (CoverItem) -> Unit,
    onDelete: (CoverItem) -> Unit,
    /** 卡片缩小淡出播完，可以移除数据了。 */
    onDeleteSettled: () -> Unit,
    /**
     * 允不允许删（默认允许）。传 false 时**所有删除钮立即收起** ——
     * 空间壁纸在「生成深度缓存」期间要求隐藏（用户 2026-11-04）：
     * 推理要 7~10s，中途删掉正在处理的那张会让结果无处可写。
     */
    allowDelete: Boolean = true,
) {
    val density = LocalDensity.current
    val haptic = rememberClickHaptic()

    // ── 尺寸全部由屏幕分辨率推出（见 `RasterCardParts` 文件头的比例表）──
    val cardWPx = screen.width * CARD_WIDTH_RATIO
    val cardHPx = cardWPx * screen.height / screen.width
    val radiusPx = cardWPx * CARD_RADIUS_RATIO
    val cardW: Dp = with(density) { cardWPx.toDp() }
    val cardH: Dp = with(density) { cardHPx.toDp() }
    val radius: Dp = with(density) { radiusPx.toDp() }
    // 布局间隔由**视觉空隙**反推（相邻两卡缩到 0.85 后左右各缩进一点，所以布局只留一点）。
    val gapPx = with(density) { GAP_VISUAL.toPx() } - cardWPx * (1f - CARD_MIN_SCALE) / 2f
    val gap: Dp = with(density) { gapPx.toDp() }
    val pagerPadding = pagerVPadding(cardW)
    // 一页的中心距（卡宽 + 布局间隔）。
    val pageStep = cardWPx + gapPx

    // ⚠️ 2026-11-03 从 Pager 换成 **LazyRow**；2026-11-04 又去掉了 `Modifier.animateItem`：
    // placement 动画只在绘制层平移，而 `layoutInfo` 已经是终点 ⇒ 缩放（按 `centerPos` 算）
    // 会先跳到 1.0、卡片"先放大再移动"。现在补位完全由下面的 [shift] 进度驱动。
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)

    // 补位进度：0 = 还停在原位（视觉上没动），1 = 已经落位。
    // ⚠️ 必须在**创建时**就是 0（`remember(shiftToken)`），不能建完再 `snapTo(0)` ——
    //    那会晚一帧，最先画出来的那一帧是"已落位"的样子，看着就是闪一下 / 瞬移。
    val shift = remember(shiftToken) { Animatable(if (shiftToken == 0) 1f else 0f) }
    // 补位进行中：删除钮要等它走完再出现（用户 2026-11-03 的规则：放大收敛后才显示）。
    var shifting by remember(shiftToken) { mutableStateOf(shiftToken != 0) }
    LaunchedEffect(shiftToken) {
        if (shiftToken == 0) return@LaunchedEffect
        shift.animateTo(
            targetValue = 1f,
            // 与松手吸附同一条曲线：临界阻尼、不过冲 ⇒ 等效"手指把补位卡拖到中间"。
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
        shifting = false
    }

    // 删除编排：卡片自己 200ms 缩小淡出，播完交给页面层"移除数据 + 给出补位方向"。
    LaunchedEffect(deletingId) {
        if (deletingId == null) return@LaunchedEffect
        delay(DELETE_CARD_MS.toLong())
        onDeleteSettled()
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewportPx = constraints.maxWidth.toFloat()
        // 左右留白 = (视口 − 卡宽)/2 ⇒ 每项左边缘对齐 contentPadding 后正好居中。
        val sidePx: Float = ((viewportPx - cardWPx) / 2f).coerceAtLeast(0f)
        val side: Dp = with(density) { sidePx.toDp() }

        // ── 居中坐标（浮点页号，0 = 新建卡居中）──
        // ⚠️ **不能**用 `firstVisibleItemIndex / firstVisibleItemScrollOffset` 那套：
        //    `firstVisibleItemScrollOffset` 的零点在**容器**起点，而 `item.offset` 的零点在
        //    **内容**起点（两者差一个 contentPadding = 330px ≈ 0.57 页）。v1.7.0 就是这么算的，
        //    结果页号整体偏半页、永远落不到整数上：吸附判定 `abs(centerPos - page) < eps`
        //    永不成立 ⇒ **删除钮一直不出现**；`onFocus` 回报错位 ⇒ **下方按钮不跟着换**
        //    （用户 2026-11-03 报的两个 bug，都是这一处）。
        //
        // 用 `item.offset` 是可靠的：Compose 自己的 `SnapPosition.Center` 就是按
        //   desired = (viewport − before − after)/2 − itemSize/2
        // 算的，本布局里 before = after = (viewport − 卡宽)/2 ⇒ **desired 恰好 = 0**，
        // 也就是「某项 offset == 0 ⇔ 它正好居中」。于是
        //     p = index − offset / pageStep
        // 与 contentPadding、viewport 宽都无关，纯像素反推。
        val centerPos = remember(pageStep) {
            derivedStateOf {
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                if (first == null) Float.NaN else first.index - first.offset / pageStep
            }
        }
        // 页数会随增删变化，但 `settledPage` 必须是**单实例**（否则每项 remember 会抓着旧对象）。
        val pageCountState = rememberUpdatedState(items.size)
        // 只有「已经吸附到位」才认页号：滚动中一律 -1，避免按钮文案在滑动过程中乱跳。
        // `remember(centerPos)` 是必须的：布局尺寸变化时会重建 `centerPos` 这个 State 实例，
        // 这里若还抓着旧实例，判定就永远停在旧坐标上。
        val settledPage = remember(centerPos) {
            derivedStateOf {
                if (listState.isScrollInProgress) return@derivedStateOf -1
                val p = centerPos.value
                if (p.isNaN()) return@derivedStateOf -1
                val nearest = p.roundToInt()
                if (abs(p - nearest) < SETTLE_EPS) {
                    nearest.coerceIn(0, pageCountState.value)
                } else {
                    -1
                }
            }
        }
        // 居中页回调：只报「已经吸附到位」的整数页。
        //
        // ⚠️ **绝不能**在这里 `snapTo` 修正位置：会和 LazyRow 自己的 clamp 互相触发补偿滚动，
        //    表现为整条带子瞬移（v1.6.31 踩过）。
        //
        // ⚠️ 用 `snapshotFlow` 而不是在组合里读 `settledPage.value`：读进组合的话，
        //    归位那一帧会让**整条卡片流连同所有 item 重组**，正好压在手感收尾上
        //    ——「归位时顿一下」有一部分就是它（2026-11-04）。
        val focusCb by rememberUpdatedState(onFocus)
        LaunchedEffect(settledPage) {
            snapshotFlow { settledPage.value }
                .distinctUntilChanged()
                .collect { p -> if (p >= 0) focusCb(p) }
        }

        // 松手后的吸附：**一次最多走一页**（Pager 的手感）—— 甩得再猛也只前进/后退一张，
        // 用临界阻尼弹簧收尾（带速度连续性，先快后慢、不过冲）。
        // `rememberSnapFlingBehavior` 是"能甩几页甩几页"，没有阻尼也没有一页的限制，
        // 所以这里自己实现（用户 2026-11-03：「手指滑一下会甩老远，没有阻尼和吸附感」）。
        val fling = remember(pageStep, items.size) {
            SnapPageFlingBehavior(
                pageStep = pageStep,
                lastPage = items.size,
                centerOf = { centerPos.value },
                triggerVelocity = with(density) { FLING_TRIGGER_VELOCITY.toPx() },
            )
        }

        // 一张卡的绘制变换：**位移和缩放同源**。
        //
        // `ext`（单位：页）是这一项"视觉上离它该在的位置差多少页"：
        // - 右侧补位：被删项右边的卡在布局里已经左移一页 ⇒ ext 从 +1 走到 0。
        //   ⚠️ 判定用 `page >= shiftFromPage`（不是 `>`）：这些卡的**新**索引 = 旧索引 − 1，
        //   所以「刚补到中心那张」的新页码正好等于被删页码。用 `>` 会把它漏掉 ⇒ 它不位移、
        //   直接出现在中心（就是"瞬移"）。
        // - 左侧补位：滚动 clamp 让所有卡右移一页 ⇒ ext 从 −1 走到 0（含新建卡）。
        fun itemTransform(page: Int): Modifier = Modifier.graphicsLayer {
            val ext = if (
                shiftDir != 0f && (shiftDir < 0f || page >= shiftFromPage)
            ) {
                (1f - shift.value) * shiftDir
            } else {
                0f
            }
            val p = centerPos.value
            val d = if (p.isNaN()) 1f else (page - p + ext).coerceIn(-1f, 1f)
            val s = 1f - (1f - CARD_MIN_SCALE) * abs(d)
            scaleX = s
            scaleY = s
            if (ext != 0f) translationX = ext * pageStep
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardH + pagerPadding * 2),
            // 上下留动态余量：删除钮圆顶探出卡片，尺寸等于卡片高度时会被裁掉。
            contentPadding = PaddingValues(horizontal = side, vertical = pagerPadding),
            horizontalArrangement = Arrangement.spacedBy(gap),
            flingBehavior = fling,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = "new") {
                // 「新建」卡同样**只有居中时才可点**（2026-11-05 用户要求：
                // 列表里只有中间那张能点，侧卡点了完全无响应，切换靠滑）。
                val newSettled by remember(centerPos, shiftToken) {
                    derivedStateOf { settledPage.value == 0 && !shifting }
                }
                NewCard(
                    cardW = cardW,
                    cardH = cardH,
                    radius = radius,
                    modifier = itemTransform(0),
                    enabled = newSettled,
                    onClick = { haptic(); onNew() },
                )
            }
            itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                val page = i + 1
                // 删除钮只在「居中 + 吸附到位 + 补位动画播完 + 非当前应用」时出现。
                // ⚠️ 判定放在**每一项自己的** derivedStateOf 里：`settledPage` 一变，
                //    只有"离开中心"和"进入中心"这两项的结果会变 ⇒ 只有它们重组，
                //    不会整条列表一起重组（归位那一帧的卡顿）。
                val settled by remember(page, shiftToken) {
                    derivedStateOf { settledPage.value == page && !shifting }
                }
                CoverCard(
                    item = item,
                    cardW = cardW,
                    cardH = cardH,
                    radius = radius,
                    // 只有**居中且吸附到位**的卡能点（2026-11-05 用户要求）。
                    // 滚动中 `settledPage` 是 -1 ⇒ 一律不可点，避免滑动误触。
                    clickable = settled,
                    showDelete = allowDelete && settled && item.id != activeId,
                    // 确认删除后：卡片与删除钮一起缩小 + 淡出。
                    removing = item.id == deletingId,
                    modifier = itemTransform(page),
                    onClick = { haptic(); onFocus(page); onClick(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

/** 封面卡：图铺满，向内一圈 1dp 纯黑 15% 描边；居中的非当前项才带红色删除钮。 */
@Composable
private fun CoverCard(
    item: CoverItem,
    cardW: Dp,
    cardH: Dp,
    radius: Dp,
    clickable: Boolean,
    showDelete: Boolean,
    removing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val cover = rememberCover(item.cover, item.src)
    // 删除动画：整张卡（连上面的删除钮）一起缩小 + 淡出，播完由调用方删数据、列表自动补位。
    val removeAnim by animateFloatAsState(
        targetValue = if (removing) 0f else 1f,
        animationSpec = tween(durationMillis = DELETE_CARD_MS),
    )
    // 删除钮：出现/消失都走 150ms（用户 2026-11-03）—— 大小 75% → 100%、透明度 0 → 1。
    // `graphicsLayer` 的默认 transformOrigin 就是元素中心 ⇒ 正好以圆心为缩放中心。
    val deleteAnim by animateFloatAsState(
        targetValue = if (showDelete) 1f else 0f,
        animationSpec = tween(durationMillis = BADGE_FADE_MS),
    )
    // 外层**不裁切**：删除钮要压在卡片角上，溢出的半个圆不能被切掉。
    Box(
        modifier = modifier
            .size(cardW, cardH)
            .graphicsLayer {
                // 确认删除后整张卡（连删除钮）一起缩小淡出。
                scaleX = removeAnim
                scaleY = removeAnim
                alpha = removeAnim
            },
    ) {
        // 卡片本体：图铺满，描边**自己画在内容之后**（`squircleStroke`，两页共用）。
        // ⚠️ miuix 的 `squircleBorder` 用的是 `onDrawBehind` —— 画在子内容**下面**，
        //    图片一铺满就把它整条盖住（这就是上一版"描边没生效"的原因）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .squircleClip(radius)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .squircleStroke({ CARD_STROKE_WIDTH }, { CARD_STROKE }, radius)
                // `enabled = false` 时不消费触摸：侧卡点击完全无响应，也不会抢走
                // LazyRow 的拖动（2026-11-05 用户要求）。
                .clickable(enabled = clickable, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // 封面（带 0.15s 渐显）；没加载出来 / 空内容时**只留底色**。
            CardImage(cover, Modifier.fillMaxSize())
        }
        if (deleteAnim > 0f) {
            // 红色圆 + 白色横杠，**圆心刚好落在卡片右上角的角点上**（用户 2026-11-03 定的位置）。
            // `clickable` 在子级会消费这次点击 ⇒ 不会连带触发整卡的点击。
            val d = cardW * DELETE_CIRCLE_RATIO
            RemoveBadge(
                diameter = d,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // ⚠️ 必须 `zIndex(1f)`：卡片本体带 `squircleClip`（离屏 layer），
                    //    实测会把后声明的删除钮盖在下面（只露出卡片顶边之上那半圆）。
                    .zIndex(1f)
                    // 圆心落在卡角** visually 的边上**：squircle 的角本来就往里收了，
                    // 圆心摆数学角点会看着跑到卡片外面（用户 2026-11-03 指出）。
                    .offset(
                        x = d * CARD_BADGE_CENTER_RATIO,
                        y = -d * CARD_BADGE_CENTER_RATIO,
                    )
                    .graphicsLayer {
                        val s = BADGE_FROM_SCALE + (1f - BADGE_FROM_SCALE) * deleteAnim
                        scaleX = s
                        scaleY = s
                        alpha = deleteAnim
                    },
                onClick = onDelete,
            )
        }
    }
}

/**
 * 卡片流松手后的吸附：**一次最多走一页**，用临界阻尼弹簧收尾。
 *
 * 为什么不用 `rememberSnapFlingBehavior`：那套是「按速度自由滑行 + 最后就近吸附」，
 * 甩一下能连翻好几张，滑行段又是纯 decay —— 体感就是「甩老远、没有阻尼和吸附」
 * （用户 2026-11-03 反馈）。
 *
 * 这里改成 Pager 的语义：
 * - 速度超过 [triggerVelocity] ⇒ 从当前页再走一页（已经拖过大半页也一样走这一页）；
 * - 速度不够 ⇒ 就近吸附（`round`）；
 * - 位移用 `Animatable` + `spring(dampingRatio = 1f)`，并把**释放时的速度**喂进去，
 *   所以离开手指那一帧不会突然「粘住」，减速连续、临界阻尼不过冲。
 */
private class SnapPageFlingBehavior(
    private val pageStep: Float,
    private val lastPage: Int,
    private val centerOf: () -> Float,
    private val triggerVelocity: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val start = centerOf()
        if (start.isNaN()) return 0f
        val dir = when {
            initialVelocity > triggerVelocity -> 1
            initialVelocity < -triggerVelocity -> -1
            else -> 0
        }
        val raw = when (dir) {
            1 -> floor(start).toInt() + 1
            -1 -> ceil(start).toInt() - 1
            else -> start.roundToInt()
        }
        val target = raw.coerceIn(0, lastPage.coerceAtLeast(0))
        val delta = (target - start) * pageStep
        if (abs(delta) < 0.5f) return 0f
        // 初速度不能超过「临界阻尼刚好不冲过头」的那个值（ω·剩余距离，ω = √400 = 20）。
        // 超了就会越过目标 —— 在端点上表现为 overscroll 被拉出去、再"弹回来"，
        // 那一下很突兀（用户 2026-11-04）。短距离猛甩时这条会生效，长距离一般碰不到。
        val vCap = minOf(MAX_SETTLE_VELOCITY, SETTLE_OMEGA * abs(delta) * 0.9f)
        val anim = Animatable(0f)
        var dragged = 0f
        anim.animateTo(
            targetValue = delta,
            initialVelocity = initialVelocity.coerceIn(-vCap, vCap),
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = Spring.StiffnessMediumLow,
                // **别在最后几帧以亚像素速度爬**：`scrollBy` 是按整像素消费的，速度掉到
                // 1px/帧 以下时画面会「停住两三帧、再咔一下」—— 就是归位时那点顿
                //（用户 2026-11-04）。剩下的交给下面的收尾补正一次走完。
                visibilityThreshold = SETTLE_SNAP_PX,
            ),
        ) {
            val step = value - dragged
            dragged = value
            scrollBy(step)
        }
        // 收尾补正：等一帧让布局追上，再按**实际**中心把落点补到精确的页位置。
        // 每帧 `scrollBy` 都是整像素消费，累积下来能让末页差几像素
        // —— 就是「甩到最左/最右端偶发不居中」。
        // ⚠️ 补正也要**动起来**：直接 `scrollBy(off)` 是一帧瞬移，端点回来那一下会"啪"一下
        //    （用户 2026-11-04）。改成 [SETTLE_FIX_MS] 的 tween，读起来是轻轻贴到位。
        withFrameNanos { }
        val off = (target - centerOf()) * pageStep
        if (abs(off) > 0.5f && abs(off) < pageStep) {
            val fix = Animatable(0f)
            var done = 0f
            fix.animateTo(
                targetValue = off,
                animationSpec = tween(
                    durationMillis = SETTLE_FIX_MS,
                    easing = FastOutSlowInEasing,
                ),
            ) {
                scrollBy(value - done)
                done = value
            }
        }
        return 0f
    }
}
