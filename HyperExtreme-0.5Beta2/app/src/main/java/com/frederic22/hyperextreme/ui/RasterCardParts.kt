package com.frederic22.hyperextreme.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Size
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.frederic22.hyperextreme.data.RasterStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 光栅壁纸**两处**共用的卡片零件（2026-11-04 抽出来）：
 * 一级页的卡片流（`RasterWallpaperPage`）和二级页的组合编辑浮页（`RasterGroupSheet`）
 * 用的是同一套"竖屏卡片"视觉，抽出来免得两边各写一份、改一处漏一处。
 *
 * 这里只放**纯视觉**的东西：尺寸比例、加号卡、描边、删除钮、封面解码。
 * 任何跟卡片流滚动 / 浮页状态有关的逻辑都留在各自的文件里。
 */

// ------------------------------------------------------------------ 比例

/** 卡宽 ÷ 屏宽（用户 2026-11-03 拍板：37% 与 55.5% 之间取 45%）。 */
internal const val CARD_WIDTH_RATIO = 0.45f

/** 卡片圆角 ÷ 卡宽（浮页槽位沿用同一比例：144dp 宽 ⇒ 19.6dp）。 */
internal const val CARD_RADIUS_RATIO = 0.136f

/** 「新建」卡的加号圆直径 ÷ 卡宽。 */
internal const val ADD_CIRCLE_RATIO = 0.27f

/** 「新建」卡的加号图标 ÷ 卡宽。 */
internal const val ADD_ICON_RATIO = 0.14f

/** 删除钮圆直径 ÷ 卡宽（范例图约 0.16 卡宽；540px 卡宽下 ≈ 29dp）。 */
internal const val DELETE_CIRCLE_RATIO = 0.16f

/** 删除钮圆心相对卡角的**内收**比例（0.5 = 数学角点，会看着跑到卡片外）。 */
internal const val CARD_BADGE_CENTER_RATIO = 0.25f

/** 删除钮里横杠的长度 ÷ 圆直径。 */
internal const val DELETE_BAR_WIDTH_RATIO = 0.44f

/** 卡片**常态**内描边：1dp 纯黑 alpha 0.1。 */
internal val CARD_STROKE = Color.Black.copy(alpha = 0.1f)
internal val CARD_STROKE_WIDTH = 1.dp

/** 删除钮出现/消失时长（一级页与浮页槽位共用，用户 2026-11-03 定 0.15s）。 */
internal const val BADGE_FADE_MS = 150

/** 删除钮显现的起始缩放（75% → 100%）。 */
internal const val BADGE_FROM_SCALE = 0.75f

/** 卡片**选中**描边：3dp 提示色（miuix 库内表达选中就是这条 squircle 描边；用户 2026-11-04 加粗）。 */
internal val SELECTED_STROKE_WIDTH = 3.dp

/**
 * 封面 / 缩略图**渐显**时长（用户 2026-11-04：解码完直接贴上会"白一下"，要 0.15s 过渡）。
 *
 * 解码在 IO 线程（`rememberCover` 的 `produceState`），位图到位那一刻才第一次组合出 `Image`；
 * 没有这一步就是从"底色"硬切到"图"，观感上闪一下。
 */
internal const val COVER_FADE_MS = 150

// ------------------------------------------------------------------ 屏幕

/**
 * 卡片上下 / 右侧要留的**余量**：删除钮圆心压在卡角上，圆顶会探出卡片
 * `(0.5 − 内收比例) × 直径`。把卡片放进可滚动容器时必须用 **`contentPadding`**
 * 把它留出来 —— 用 `Modifier.padding` 只是把 viewport 缩小，内容照样被裁。
 */
internal fun cardBadgeOverflow(cardW: Dp): Dp =
    cardW * DELETE_CIRCLE_RATIO * (0.5f - CARD_BADGE_CENTER_RATIO) + 1.dp

/**
 * 屏幕尺寸（px）。用 `maximumWindowMetrics` —— 它是**整块显示区**，
 * 含状态栏与导航栏；`screenHeightDp` 那种去掉系统栏的高度会让卡片比例偏矮。
 */
@Composable
internal fun rememberScreenSizePx(): Size {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(cfg.screenWidthDp, cfg.screenHeightDp, density) {
        runCatching {
            val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            Size(b.width(), b.height())
        }.getOrElse {
            with(density) {
                Size(cfg.screenWidthDp.dp.roundToPx(), cfg.screenHeightDp.dp.roundToPx())
            }
        }
    }
}

// ------------------------------------------------------------------ 零件

/** 卡片封面：异步解码、离开组合即释放（懒加载列表会回收不可见的 item）。 */
@Composable
internal fun rememberCover(file: File?, src: RectF? = null): Bitmap? {
    // key 必须带上 src：crop 改了要重新解码，否则卡片仍显示旧裁剪（2026-11-05）。
    val key = (file?.absolutePath ?: "") + "|" + (src?.toShortString() ?: "")
    val bmp by produceState<Bitmap?>(initialValue = null, key) {
        value = file?.let { withContext(Dispatchers.IO) { RasterStore.decodeCard(it, src) } }
    }
    return bmp
}

/**
 * 浮页里卡片该用的底色。
 *
 * ⚠️ **不能直接用 `surfaceContainerHigh`**：这套主题下它和浮页底色**完全一样**（#242424），
 * 空槽会整块融进背景（用户 2026-11-04 报的）。相册那个浮页是「底 #242424 / 卡 #434343」，
 * 这里按同样的关系算：底色往白色 lerp 14% ⇒ 本机正好 #434343，换动态取色主题也能自适应。
 */
@Composable
internal fun rememberCardSurface(): Color {
    val cs = MiuixTheme.colorScheme
    return if (cs.surface.luminance() < 0.5f) {
        lerp(cs.surfaceContainerHigh, Color.White, 0.14f)
    } else {
        cs.surface
    }
}

/** 「新建」卡：深色底 + 蓝色圆形加号（视觉对齐系统锁屏编辑里的那张）。 */
@Composable
internal fun NewCard(
    cardW: Dp,
    cardH: Dp,
    radius: Dp,
    modifier: Modifier,
    /** 卡片底色。默认 `surfaceContainerHigh`（一级页卡片流用这个就够），浮页传 [rememberCardSurface]。 */
    surface: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
    /**
     * 能不能点。卡片流里只有居中的那张才可点（2026-11-05 用户要求）；
     * 浮页里这张卡不参与居中逻辑，保持默认的恒可点。
     */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(cardW, cardH)
            .squircleClip(radius)
            .background(surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(cardW * ADD_CIRCLE_RATIO)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = "新建",
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(cardW * ADD_ICON_RATIO),
            )
        }
    }
}

/**
 * 红色圆 + 白色横杠的删除钮（横杠自绘 —— 图标库的 `Remove` 不是这个形状）。
 *
 * 位置、缩放、透明度动画全由调用方决定：`modifier` 里自己写 `align` / `offset` /
 * `graphicsLayer`，这里只管长相。
 */
@Composable
internal fun RemoveBadge(
    diameter: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.error)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val w = size.width * DELETE_BAR_WIDTH_RATIO
            val h = 1.8.dp.toPx()
            drawRoundRect(
                color = Color.White,
                topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                size = GeomSize(w, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f),
            )
        }
    }
}

/**
 * 沿着 squircle 轮廓画一圈描边，**画在内容之上**。
 *
 * ⚠️ 不能直接用 miuix 的 `Modifier.squircleBorder`：它是 `onDrawBehind`，画在子内容**下面**，
 *    卡片里图片一铺满就整条盖掉了（v1.6.18 踩过）。这里复用它的 `Path.addSquircleRect`
 *    （几何完全同源），只把绘制时机换成 `onDrawWithContent`。
 *
 * `width` / `color` 是**函数**：调用方可以直接传 `animateDpAsState` / `animateColorAsState`
 * 的值，读取发生在绘制阶段 ⇒ 颜色/线宽变化只重绘、不重组整张卡。
 * （miuix 的 `TabRow` 选中指示用的也是这个"延迟读取"思路。）
 */
internal fun Modifier.squircleStroke(
    width: () -> Dp,
    color: () -> Color,
    radius: Dp,
): Modifier = drawWithCache {
    val path = Path()
    onDrawWithContent {
        drawContent()
        val w = width().toPx()
        if (w <= 0f) return@onDrawWithContent
        val innerW = size.width - w
        val innerH = size.height - w
        if (innerW <= 0f || innerH <= 0f) return@onDrawWithContent
        path.rewind()
        path.addSquircleRect(
            width = innerW,
            height = innerH,
            cornerRadius = (radius.toPx() - w / 2f).coerceAtLeast(0f),
            extension = SquircleDefaults.Extension,
            squircleEnabled = true,
        )
        translate(w / 2f, w / 2f) {
            drawPath(path, color(), style = Stroke(width = w))
        }
    }
}

/**
 * 卡片里的图（铺满裁切）；没有图就**只留底色**（不再显示「未选图」，用户 2026-11-04）。
 *
 * 图到位时走 [COVER_FADE_MS] 渐显：解码是异步的，位图回来那一帧直接贴上去会"白一下"。
 * `remember(bitmap)` 保证**换了图**（重新解码得到新对象）才重播一次，普通重组不重放。
 */
@Composable
internal fun CardImage(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    if (bitmap != null) {
        var shown by remember(bitmap) { mutableStateOf(false) }
        LaunchedEffect(bitmap) { shown = true }
        val a by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(durationMillis = COVER_FADE_MS),
            label = "coverFade",
        )
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            // alpha 用 graphicsLayer 施加在**画出来的结果**上，不动布局与裁切。
            modifier = modifier.graphicsLayer { alpha = a },
            contentScale = ContentScale.Crop,
        )
    }
}
