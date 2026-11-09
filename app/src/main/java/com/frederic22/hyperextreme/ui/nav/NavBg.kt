// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
//
package com.frederic22.hyperextreme.ui.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import com.frederic22.hyperextreme.data.MaskPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 「设置 → 测试 → 使用自定义背景」的实现（2026-10-06，**测试功能**，用户明确"仅供测试使用"）。
 *
 * 目的：给底栏那套玻璃（`miuix-blur` 的 `textureBlur` + 自定义高光）一个**真实图案**的背景，
 * 看它在高频内容下的表现 —— 纯色底看不出降采样降级、交叉渐变带那些问题。
 *
 * ## 层次（关键：为什么底栏一行都不用改）
 *
 * 页面内容层是 `Box(fillMaxSize().layerBackdrop(backdrop).background(surface))`，
 * 底栏玻璃录的就是这一层。所以照片只要作为**该 Box 的第一个子节点**画进去，就会被玻璃采样到：
 *
 * ```
 * Box(... .background(surface))   ← 自绘底色保留不动（没图时它就是底色，零回归）
 *  ├ 本背景层                      ← 新增：不透明铺满，正好盖住底色
 *  ├ HorizontalPager               ← 内容
 *  └ 底部渐隐纱
 * ```
 *
 * ## 模糊为什么"算一次、存起来"
 *
 * 若用 `Modifier.blur` 直接挂在背景上，`layerBackdrop` 每次重录（滑动页面时每帧）都要重跑模糊算力。
 * 所以改成：**只在半径变化时**把图录进 [GraphicsLayer]、挂 [BlurEffect]、`toImageBitmap()` 读回成
 * 一张 [ImageBitmap] 存进内存并落盘；平时绘制直接用这张算好的图 ⇒ **渲染时零模糊开销**。
 * 落盘那份让**冷启动也不用重算**（文件名带半径，自描述）。
 *
 * ## 已知代价（用户知情后选的）
 *
 * ① 每次半径变化 = 解码 + 模糊 + **GPU 读回** + JPEG 编码，拖动滑块会卡（所以有 0.5s 防抖）；
 * ② [BlurEffect] 在大半径下可能被 Android 内部钳制 / 从高斯退化，50px 的实际效果以实测为准；
 * ③ 常驻内存 ≈ 原图 + 模糊图（阈值内最大 3500×2000，各约 28MB）。
 */
private const val NAV_BG_TAG = "NavBg"

/** 半径变化的防抖（ms）—— 用户 2026-10-06 定：拖动过程中只更新数值，松手后算一次。 */
private const val NavBgBlurDebounceMs = 500L

/**
 * 解码阈值（px）：**长边 > 3500 且 短边 > 2000** 才压缩（用户明确是"与"不是"或"）。
 *
 * 于是 4000×1500 / 3000×2500 都**不压**，只有 4000×3000 这种两边都超的才压。
 * 3500×2000 仍远大于屏幕（1200×2670），所以压完不会糊。
 */
private const val NavBgMaxLongEdge = 3500
private const val NavBgMaxShortEdge = 2000

/** 落盘目录（`filesDir` 下，与 `MaskStore` 那条导入链路完全分开）。 */
private const val NavBgDirName = "navbg"
private const val NavBgOriginalName = "original.jpg"
private const val NavBgBlurPrefix = "blur_"

/** 模糊结果的 JPEG 质量 —— 图已经糊了，PNG 编码慢一个量级、文件大一个量级，不划算。 */
private const val NavBgJpegQuality = 90

/**
 * 背景的状态 + 模糊管线。
 *
 * 用 [rememberNavBgState] 造（要在组合里拿 [rememberGraphicsLayer] / density）。
 * 全 App 只造一份（`MaskSwapShell`），设置页与背景层共用。
 */
internal class NavBgState internal constructor(
    private val context: Context,
    private val prefs: MaskPrefs,
    private val scope: CoroutineScope,
    private val layer: GraphicsLayer,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
) {
    /** 开关（持久化）。关掉时半径归零 + 目录删干净。 */
    var enabled by mutableStateOf(prefs.navBgEnabled)
        private set

    /** 模糊半径，px、0~50 整数档。设置页右侧那个实时数值直接读它。 */
    var radiusPx by mutableFloatStateOf(prefs.navBgRadiusPx.toFloat())
        private set

    /** 所选图片的**原相册文件名**（设置页那行 summary 显示用）。 */
    var fileName by mutableStateOf(prefs.navBgFileName)
        private set

    /** 当前该画的那张（已模糊）；null = 没图。 */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * 手指正按在滑块那一行上（[SliderTouchGesture] 维护）。
     *
     * 页面据此给 `pagerGestureOverride(enabled = !sliderTouching)` —— **必须的**：
     * pager 的横向手势在 Main 阶段比子节点先拿到事件，不关掉的话拖滑块会变成拖页面。
     */
    var sliderTouching by mutableStateOf(false)
        internal set

    /** 滑块行的窗口坐标矩形（设置页用 [reportSliderRect] 喂进来）。 */
    var sliderRectInRoot: Rect? by mutableStateOf(null)
        internal set

    /** 承载 pager 的那个 Box 的窗口坐标原点（[reportRootOrigin] 喂进来），用来统一坐标。 */
    var rootOriginInWindow: Offset by mutableStateOf(Offset.Zero)
        internal set

    private var blurJob: Job? = null

    private val dir: File get() = File(context.filesDir, NavBgDirName)
    private val originalFile: File get() = File(dir, NavBgOriginalName)
    private fun blurredFile(radius: Int) = File(dir, "$NavBgBlurPrefix$radius.jpg")

    /** 开关切换。关掉 = 半径归零 + 目录删干净（用户 2026-10-06 定的行为）。 */
    fun applyEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        prefs.setNavBgEnabled(value)
        blurJob?.cancel()
        if (!value) {
            radiusPx = 0f
            prefs.setNavBgRadiusPx(0)
            fileName = ""
            prefs.setNavBgFileName("")
            image = null
            runCatching { dir.deleteRecursively() }
                .onFailure { Log.w(NAV_BG_TAG, "删除 ${dir.absolutePath} 失败", it) }
        } else {
            reload()
        }
    }

    /**
     * 半径变化 → 数值立刻更新；模糊结果防抖后重算。
     *
     * @param persist 是否顺手落盘。**拖动过程中要传 false** —— [MaskPrefs] 用的是同步
     *   `commit()`，每挪 1px 写一次磁盘会把拖动卡成"一顿一顿"；抬手时由 [persistRadius] 补一次。
     */
    fun setRadius(px: Float, persist: Boolean = true) {
        val q = px.roundToInt().coerceIn(0, MaskPrefs.NAV_BG_RADIUS_MAX).toFloat()
        if (q == radiusPx) return
        radiusPx = q
        if (persist) prefs.setNavBgRadiusPx(q.toInt())
        scheduleBlur()
    }

    /** 抬手（或非拖动路径的最终值）时把半径写进 prefs。 */
    fun persistRadius() {
        prefs.setNavBgRadiusPx(radiusPx.roundToInt())
    }

    /** 选了新图。 */
    fun onPicked(uri: Uri) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { queryDisplayName(uri) }
            val stored = withContext(Dispatchers.IO) { importOriginal(uri) }
            if (!stored) {
                Log.w(NAV_BG_TAG, "导入失败：$uri")
                return@launch
            }
            fileName = name
            prefs.setNavBgFileName(name)
            blurJob?.cancel()
            if (radiusPx < 0.5f) {
                // 半径 0：直接用刚存下的那份，不用算。
                image = withContext(Dispatchers.IO) { loadOriginal()?.asImageBitmap() }
                withContext(Dispatchers.IO) { clearOtherBlurs(keepRadius = null) }
            } else {
                scheduleBlur()
            }
        }
    }

    /**
     * 冷启动 / 刚打开开关：优先用**落盘**的模糊结果（文件名自带半径，自描述），
     * 命中就零算力；没命中才重算一次。
     */
    fun reload() {
        blurJob?.cancel()
        if (!enabled || !originalFile.isFile) {
            image = null
            return
        }
        val radius = radiusPx.toInt()
        val hit = blurredFile(radius)
        if (hit.isFile) {
            scope.launch {
                val cached = withContext(Dispatchers.IO) { decode(hit) }
                if (cached != null) {
                    Log.d(NAV_BG_TAG, "命中落盘模糊结果 radius=$radius")
                    image = cached.asImageBitmap()
                } else {
                    scheduleBlur()
                }
            }
        } else if (radius < 1) {
            scope.launch { image = withContext(Dispatchers.IO) { loadOriginal()?.asImageBitmap() } }
        } else {
            scheduleBlur()
        }
    }

    /** 防抖重算：取消上一次排队 ⇒ 一次拖动只算一次。 */
    private fun scheduleBlur() {
        if (!enabled) return
        blurJob?.cancel()
        blurJob = scope.launch {
            delay(NavBgBlurDebounceMs)
            val src = withContext(Dispatchers.IO) { loadOriginal() }
            if (src == null) {
                Log.w(NAV_BG_TAG, "缺少 ${originalFile.absolutePath}，无法算模糊")
                return@launch
            }
            val radius = radiusPx
            if (radius < 0.5f) {
                image = src.asImageBitmap()
                withContext(Dispatchers.IO) { clearOtherBlurs(keepRadius = null) }
                return@launch
            }
            val out = blurOnce(src.asImageBitmap(), radius)
            src.recycle()
            if (out == null) {
                Log.w(NAV_BG_TAG, "模糊失败（radius=$radius）")
                return@launch
            }
            image = out
            // 落盘：写临时文件再 rename，避免中途被杀留下半张；顺带清掉其它半径的那几张。
            withContext(Dispatchers.IO) {
                writeJpeg(out, blurredFile(radius.toInt()))
                clearOtherBlurs(keepRadius = radius.toInt())
            }
            Log.d(NAV_BG_TAG, "模糊完成 radius=$radius ${out.width}x${out.height}")
        }
    }

    /**
     * 录进 [layer] → 挂 [BlurEffect] → 读回。
     *
     * 必须在**主线程**（碰 [GraphicsLayer]），所以解码那步由调用方放到 IO。
     * `record` 自带一个录制用的 `DrawScope`，不需要真的处在某次 draw 过程中。
     */
    private suspend fun blurOnce(src: ImageBitmap, radius: Float): ImageBitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        return try {
            layer.renderEffect = null
            layer.record(density, layoutDirection, IntSize(w, h)) {
                drawImage(src, dstSize = IntSize(w, h), filterQuality = FilterQuality.Low)
            }
            // ⚠️ 第三参是 Compose 的 `TileMode`（不是 `Modifier.blur` 那个
            // `BlurredEdgeTreatment`）—— 后者是 `Modifier.blur` 把它翻译成 TileMode 之后的产物。
            // `Clamp` 对应 `BlurredEdgeTreatment.Rectangle`：边缘外推、不会有透明晕边。
            // 背景是**铺满**的，用 `Decal`（= Unbounded）会让采样跑到图外面，反而更慢。
            layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            val out = layer.toImageBitmap()
            layer.renderEffect = null
            out
        } catch (t: Throwable) {
            Log.w(NAV_BG_TAG, "blurOnce 抛异常", t)
            null
        }
    }

    // ---- 文件操作（都在 IO 线程调用） ----

    private fun queryDisplayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "已选择图片"
    }

    /**
     * 拷进 `filesDir/navbg/original.jpg`，超过阈值才压。
     *
     * ⚠️ 不复用 `MaskStore.cachePicked`：那个硬编码 `cacheDir/picked.png`，是「锁屏遮罩替换」
     * 导入链路的临时缓存，两边共用会互相覆盖。
     *
     * @return 是否成功。
     */
    private fun importOriginal(uri: Uri): Boolean = runCatching {
        dir.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return false
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(w, h)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return false
        val stored = try {
            writeJpeg(bitmap.asImageBitmap(), originalFile)
        } finally {
            bitmap.recycle()
        }
        Log.d(
            NAV_BG_TAG,
            "原图 ${w}x$h → 存 ${opts.inSampleSize} 倍采样、${bounds.outWidth}x${bounds.outHeight}",
        )
        stored
    }.getOrElse {
        Log.w(NAV_BG_TAG, "importOriginal 失败", it)
        false
    }

    /** **与**判据：只有长边和短边**都**超阈值才降采样。 */
    private fun sampleSize(w: Int, h: Int): Int {
        if (w <= NavBgMaxLongEdge && h <= NavBgMaxShortEdge) return 1
        var s = 1
        while (w / (s * 2) >= NavBgMaxLongEdge || h / (s * 2) >= NavBgMaxShortEdge) s *= 2
        return s
    }

    private fun loadOriginal(): Bitmap? =
        originalFile.takeIf { it.isFile }?.let { decode(it) }

    private fun decode(file: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    /** 写 JPEG：先写 `.tmp` 再 rename，避免中途被杀留下半张。 */
    private fun writeJpeg(bitmap: ImageBitmap, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, NavBgJpegQuality, out)
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            target.delete()
            tmp.renameTo(target)
        }
        target.isFile
    }.getOrElse {
        Log.w(NAV_BG_TAG, "写 ${target.absolutePath} 失败", it)
        false
    }

    /** 只留 `blur_<keepRadius>.jpg`，其余半径的结果删掉（最多留一张）。 */
    private fun clearOtherBlurs(keepRadius: Int?) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (!f.name.startsWith(NavBgBlurPrefix)) return@forEach
                if (keepRadius != null && f.name == "$NavBgBlurPrefix$keepRadius.jpg") return@forEach
                f.delete()
            }
        }
    }
}

/** 组合里造一份 [NavBgState]，并在进来时做一次冷启动恢复。 */
@Composable
internal fun rememberNavBgState(prefs: MaskPrefs, scope: CoroutineScope): NavBgState {
    val context = LocalContext.current
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val state = remember { NavBgState(context, prefs, scope, layer, density, layoutDirection) }
    // 只在第一次进来时恢复一次（之后的开关 / 半径 / 换图由各自入口触发）。
    LaunchedEffect(Unit) { state.reload() }
    return state
}

/**
 * 背景层本体：**必须画在 `layerBackdrop` 那一层里、且是第一个子节点**。
 *
 * 声明顺序 = 绘制顺序，所以它在自绘底色（`Box.background(surface)`）之上、内容之下；
 * 图不透明 ⇒ 正好盖住底色。`matchParentSize` 是 `BoxScope` 专有的，不会给这个层再触发一次测量。
 * 没有图时什么都不画 ⇒ 那层底色原样露出，行为与加这个功能之前完全一致。
 */
@Composable
internal fun BoxScope.NavBgLayer(state: NavBgState) {
    val image = state.image ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize(),
    )
}

/**
 * 报告 [NavBgState.sliderRectInRoot]：滑块那一行的窗口坐标矩形（由设置页在
 * `onGloballyPositioned` 里喂进来）。[SliderTouchGesture] 用它做命中测试。
 */
internal fun Modifier.reportSliderRect(state: NavBgState): Modifier =
    onGloballyPositioned { c ->
        val topLeft = c.positionInRoot()
        state.sliderRectInRoot = Rect(topLeft, c.size.toSize())
    }

/** 报告承载 pager 的那个 Box 的窗口坐标原点，好把指针位置换算到同一套坐标里。 */
internal fun Modifier.reportRootOrigin(state: NavBgState): Modifier =
    onGloballyPositioned { c -> state.rootOriginInWindow = c.positionInRoot() }

/**
 * 接管滑块那一行上的**横向拖动**（2026-10-06 修"滑块拖不动"）。
 *
 * ## 为什么不能直接用 miuix `Slider` 自带的拖动
 *
 * `miuix-blur` 之外的 `miuix` 里，`Slider` 是用 `Modifier.draggable(Orientation.Horizontal)`
 * 实现的；而横向翻页的 `PagerSwipeNode.onPointerEvent` **只处理 `PointerEventPass.Main`**，
 * 指针命中路径在 Main 阶段是**父先子后** —— pager 是滑块的祖先，于是它先越过水平 slop 并
 * `consume()`，滑块那个 `draggable` 永远等不到未被消费的事件 ⇒ **怎么拖都没反应**。
 * （按钮不受影响，因为 buttons 不需要 slop。）
 *
 * ## 这里的做法
 *
 * 把这个 modifier 挂在** pager 的祖先**上（也就是记录层那个 Box），用
 * `PointerEventPass.Initial` 观察 —— Initial 阶段是**外层先于内层**，所以我们比 pager 更早
 * 看到事件：
 *
 * ① 落点若在滑块矩形内（本函数按窗口坐标命中测试，见 [reportSliderRect]）就接管这一串事件、
 *    逐帧 `consume()`，并把 x 映射成半径；
 * ② 同时把 [NavBgState.sliderTouching] 置 true，页面用 `pagerGestureOverride(enabled = !sliderTouching)`
 *    把翻页手势关掉，避免拖滑块时页面跟着横滑；
 * ③ 抬手清标记。
 *
 * 落在滑块之外的触摸**一律不碰**，所以页面滚动、按钮、翻页全都照旧。
 */
internal fun Modifier.SliderTouchGesture(state: NavBgState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val rect = state.sliderRectInRoot
            val origin = state.rootOriginInWindow
            if (rect == null || rect.width <= 0f || !rect.contains(down.position + origin)) continue
            state.sliderTouching = true
            down.consume()
            // **绝对定位**：半径 = 手指在轨道上的横向位置（不是"从按下点算位移"）。
            // 相对位移那版按住之后拇指会停在原地、跟着手指的**增量**走，手感上是"不跟手"。
            // 绝对定位下"按住轨道就跳到那一点、之后 1:1 跟着走"，和系统滑块一致。
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (ch.changedToUpIgnoreConsumed()) {
                    ch.consume()
                    break
                }
                val fraction = ((ch.position.x + origin.x - rect.left) / rect.width)
                    .coerceIn(0f, 1f)
                ch.consume()
                // 拖动中**不落盘**：`MaskPrefs` 用的是同步 `commit()`，每挪 1px 写一次磁盘
                // 会把拖动卡成"一顿一顿"。抬手时才写。
                state.setRadius(fraction * MaskPrefs.NAV_BG_RADIUS_MAX, persist = false)
            }
            state.persistRadius()
            state.sliderTouching = false
        }
    }
}

