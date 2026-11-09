package com.frederic22.hyperextreme.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.frederic22.hyperextreme.applyAppSystemBars
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.requestHighRefreshRate
import com.frederic22.hyperextreme.resolveAppDark
import com.frederic22.hyperextreme.spatial.SpatialCrop
import com.frederic22.hyperextreme.spatial.SpatialDepth
import com.frederic22.hyperextreme.hook.SpatialGlCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlurColors

/**
 * 「调整显示区域」—— **独立 Activity**（2026-11-04），吃系统转场动画。
 *
 * ## 为什么是独立 Activity
 *
 * 用户明确要求；顺带解决了两件事：
 * * 全屏沉浸（点图隐藏顶栏 + 状态栏）不会污染二级页的 `DetailTabHost` 状态；
 * * 系统默认的进入/退出转场，比 Compose 路由更像系统相册。
 *
 * ## 预览公式 = 渲染公式
 *
 * AGSL 里那段和 `SpatialGlCore.FRAG` **同一套**球面映射 + `SpatialCrop` 的仿射，
 * 只是去掉了位移场（预览是静止姿态，`uShift = 0`）。所以「预览里看到的就是壁纸上显示的」。
 *
 * ## 交互
 *
 * * 单指拖动 / 双指缩放（1×~2×，下限保证画面永远铺满）；
 * * 单击画面切顶栏 + 状态栏（`AnimatedVisibility` 淡入淡出，不位移）；
 * * 顶栏玻璃与底部按钮都用 miuix `textureBlur`；设备不支持 AGSL 时按钮退回半透明黑底。
 */
internal class SpatialCropActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_KEY = "key"

        fun intent(ctx: Context, key: String): Intent =
            Intent(ctx, SpatialCropActivity::class.java).putExtra(EXTRA_KEY, key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppSystemBars(resolveAppDark(this, MaskPrefs(this).themeMode))
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        setContent {
            val prefs = remember { MaskPrefs(this) }
            val themeMode = remember { ThemeMode.of(prefs.themeMode) }
            val controller = remember(themeMode) {
                ThemeController(colorSchemeMode = themeMode.toColorSchemeMode())
            }
            MiuixTheme(controller = controller, textStyles = HyperTextStyles) {
                CropScreen(key = key, onClose = { finish() })
            }
        }
    }

    /**
     * 无论「确认」还是返回键退出，都回报 OK —— 空间页那边只拿它当「回来重扫一次」的信号，
     * 真正的落盘只在「确认显示区域」里发生，所以这里不会造成误保存。
     */
    override fun finish() {
        runCatching { setResult(RESULT_OK) }
        super.finish()
    }

    override fun onDestroy() {
        // ⚠️ 沉浸态退出前必须把系统栏恢复回来，否则返回二级页时状态栏不见了。
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        super.onDestroy()
    }
}

/**
 * 预览用的 AGSL：与 `SpatialGlCore.FRAG` 同一套球面 + 显示区域仿射（无位移场）。
 *
 * **4 taps 超采样**（2026-11-04）：预览解码的是原图（2802 宽），屏幕只显示其中
 * 69%（约 1938 px）压到 1200 物理像素上 —— **降采样 1.62×**。单层双线性只有 2×2 个
 * 采样点，1.6 倍降采样必然出摩尔纹和细线断裂（观感就是「插值没插好的锯齿」）。
 * 在屏幕空间取 4 个点（间距 1px，各自走双线性）再平均 ⇒ 等效 4×4 盒式滤波，
 * 覆盖范围正好盖住降采样比。
 */
private const val CROP_AGSL = """
    uniform shader uTex;
    uniform float2 uSize;      // 屏幕像素
    uniform float2 uTexSize;   // 纹理像素
    uniform float2 uK;         // tan(半角) 与 aspect（同 SpatialGlCore.geometry）
    uniform float2 uSpan;      // 图像覆盖的球面半角
    uniform float2 uUvScale;   // 1/zoom
    uniform float2 uUvOffset;  // c − 0.5/zoom

    // 屏幕像素 → 纹理 uv（球面映射 + 显示区域仿射）。
    float2 uvAt(float2 fragCoord) {
        // 屏幕像素 → NDC（y 向上）
        float2 ndc = float2(fragCoord.x / uSize.x * 2.0 - 1.0, 1.0 - fragCoord.y / uSize.y * 2.0);
        float2 q0 = float2(atan(ndc.x * uK.x) / uSpan.x, atan(ndc.y * uK.y) / uSpan.y);
        // y 反转：GL 的 v=0 在底部，屏幕 y 在顶部（与渲染 shader 一致）
        float2 uv = float2(q0.x * 0.5 + 0.5, 0.5 - q0.y * 0.5);
        return uv * uUvScale + uUvOffset;
    }

    half4 main(float2 fragCoord) {
        // 4 taps：对角 ±0.5 屏幕像素，平均位置仍是当前像素中心。
        half4 c  = uTex.eval(uvAt(fragCoord + float2(-0.5, -0.5)) * uTexSize);
        c += uTex.eval(uvAt(fragCoord + float2( 0.5, -0.5)) * uTexSize);
        c += uTex.eval(uvAt(fragCoord + float2(-0.5,  0.5)) * uTexSize);
        c += uTex.eval(uvAt(fragCoord + float2( 0.5,  0.5)) * uTexSize);
        return c * 0.25;
    }
"""

/** 「确认显示区域」的圆角：与 miuix `Button` 默认圆角同值（16dp），**圆角矩形而非胶囊**。 */
private val CROP_BUTTON_CORNER = 16.dp

// ── 投影：参数与系统底栏（`Os4FloatingNavigationBar`）**完全一致** ──
//
// 不用 `graphicsLayer.shadowElevation`：那套光源固定在屏幕上方，只有下缘有影，
// 做不出「光从看屏幕的方向打过来」的四周均匀效果。改成自绘：一个大一圈的圆角块
// 交给 `Modifier.blur` 模糊，四周同强度、糊度可调（代价只有一个离屏层）。

/** 投影的高斯模糊半径。 */
private val CROP_SHADOW_BLUR = 28.dp

/** 投影的黑色透明度。 */
private const val CROP_SHADOW_ALPHA = 0.13f

/**
 * **平滑圆角**（squircle）的 `Shape`，给 `textureBlur` / 投影层用。
 *
 * 为什么需要它：miuix 的 `Button` 本体是 `squircleSurface`（SDF 平滑圆角）画的，
 * 而 `textureBlur(shape = …)` 收的是普通 Compose `Shape`。用 `RoundedCornerShape`
 * 会和按钮本体的轮廓对不上 —— 圆角处玻璃「鼓出来」一点（2026-11-04 用户指出）。
 *
 * `extension = 0f` 与 miuix `Button` 内部调用 `squircleSurface` 时传的值一致，
 * 这样玻璃、投影、按钮三者几何完全相同。
 */
private class CropSquircleShape(private val corner: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path()
        path.addSquircleRect(
            width = size.width,
            height = size.height,
            cornerRadius = with(density) { corner.toPx() },
            extension = 0f,
            squircleEnabled = true,
        )
        return Outline.Generic(path)
    }
}

@Composable
private fun CropScreen(key: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = rememberClickHaptic()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(SpatialCrop.DEFAULT) }
    // 顶栏 + 状态栏是否可见（点一下画面切换）。
    var barsVisible by remember { mutableStateOf(true) }
    // 画布实际尺寸（物理像素）—— 手势的 clamp 和 shader 必须用**同一个**几何。
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 载入：原图副本（没有就退回背景层图）+ 已存参数。
    LaunchedEffect(key) {
        val loaded = withContext(Dispatchers.IO) {
            val dir = File(SpatialDepth.cacheRoot(ctx), key)
            val src = SpatialDepth.sourceFile(dir).takeIf { it.isFile }
                ?: File(dir, "layers/layer0.png").takeIf { it.isFile }
            // 2000：屏幕宽 1200，放到 2× 时需要 2400 的有效像素；2000 是内存与清晰度的折中（约 21MB）。
            val bmp = src?.let { SpatialDepth.decodePreview(it, 2000) }
            bmp to SpatialCrop.read(ctx, key)
        }
        bitmap = loaded.first
        crop = loaded.second
        android.util.Log.i(
            "HyperExtreme",
            "CROP 加载 key=$key bitmap=${loaded.first?.width}x${loaded.first?.height} crop=$crop",
        )
    }

    // 状态栏跟随顶栏显隐（拖动不触发，只有单击切）。
    LaunchedEffect(barsVisible) {
        val window = (view.context as? ComponentActivity)?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsVisible) c.show(WindowInsetsCompat.Type.systemBars())
        else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // 屏幕尺寸换算（预览 shader 与手势都用它）。
    val screen = rememberScreenSizePx()
    val cardWidthDp = with(density) { (screen.width * CARD_WIDTH_RATIO).toDp() }
    // 按钮宽度 = 「应用」按钮宽（卡宽 5/6 = 150dp）。两个按钮同宽，中间 12dp。
    val applyButtonWidth = cardWidthDp * 5f / 6f
    val buttonWidth = applyButtonWidth

    // 几何：**画布实测尺寸** + 纹理尺寸 ⇒ 屏幕可见的 uv 范围（手势 clamp 与 shader 共用）。
    // 用实测尺寸而不是 `screen`：edge-to-edge 下两者通常相同，但导航栏/挖孔时机可能差几像素，
    // 差一点就会让 clamp 和实际采样对不上（表现为拖到边上露一丝黑）。
    val geo = remember(canvasSize, bitmap) {
        val b = bitmap
        if (b == null || canvasSize.width <= 0 || canvasSize.height <= 0) {
            null
        } else {
            SpatialGlCore.geometry(canvasSize.width, canvasSize.height, b.width, b.height)
        }
    }

    // 老参数可能越界（旧版本按 0.5/zoom 存过更宽的 cx），几何一就绪就**修正一次**。
    var cropCorrected by remember { mutableStateOf(false) }
    LaunchedEffect(geo) {
        val g = geo ?: return@LaunchedEffect
        if (cropCorrected) return@LaunchedEffect
        crop = SpatialCrop.clampWith(
            crop, g.visibleHalfX, g.visibleHalfY, g.shiftMarginX, g.shiftMarginY,
        )
        cropCorrected = true
    }

    val backdrop = if (isRuntimeShaderSupported()) {
        rememberLayerBackdrop { drawContent() }
    } else {
        null
    }
    // 玻璃叠色（2026-11-04 用户定稿，两处**用不同的色**）：
    // * 顶栏 = `surface`（主题表面色：浅色白、深色深灰）+ alpha 0.5 —— 中性磨砂，不抢画面；
    // * 按钮 = **主题强调色 `primary`** + alpha 0.6 —— 带色调，让主操作"抓人"。
    // 透明度一路调过 0.8 → 0.35 → 0.40 → 现在的两档。
    //
    // ⚠️ `blurColors` 是 @Composable，**不能**塞进 `remember { }` 的 lambda；
    // 它内部自带 remember，每次重组调用是安全的。
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.5f)
    val buttonTint = MiuixTheme.colorScheme.primary.copy(alpha = 0.6f)
    // 「恢复默认」用 `onPrimaryVariant` 那一组（浅色底 + 深色字，见 miuix 官方色板）。
    // ⚠️ `onPrimaryVariant` 本身是**浅色**，所以字必须用 `primaryVariant`（深），
    // 两者同色会看不见 —— miuix 没有为这一组提供现成的 `buttonColors*` 封装。
    val resetTint = MiuixTheme.colorScheme.onPrimaryVariant.copy(alpha = 0.6f)
    val resetContent = MiuixTheme.colorScheme.primaryVariant
    val barGlassColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = barTint)),
    )
    val buttonGlassColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = buttonTint)),
    )
    val resetGlassColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = resetTint)),
    )
    // 按钮的**平滑圆角**：玻璃层、投影层、按钮本体三者几何必须完全相同，
    // 否则圆角处玻璃会比按钮"鼓"出来（用户 2026-11-04 指出）。
    val buttonShape = remember { CropSquircleShape(CROP_BUTTON_CORNER) }

    val shader = remember { RuntimeShader(CROP_AGSL) }
    // 绘制用的 Paint：用原生 `android.graphics.Paint`（比 Compose 的包装类少一层、
    // `shader` 直接收 `android.graphics.Shader`，没有 value class 的转换歧义）。
    //
    // ⚠️ `isFilterBitmap` 在这里**不是**决定锯齿的那个开关：AGSL 的 `uTex.eval()`
    // 会被编译成 `sample(uTex, coord)`，采样参数由**子 shader 自己**决定，
    // 而 Paint 的过滤只作用于「用这个 paint 画图元、图元需要缩放」的路径 ——
    // 我们画的是铺满画布的矩形（1:1），所以它整条链上都不参与。
    // 真正的开关在下面 `BitmapShader.setFilterMode(...)`。
    val drawPaint = remember {
        android.graphics.Paint().apply {
            isFilterBitmap = true   // 留着无害；不指望它
            isAntiAlias = true
        }
    }
    // ⚠️ **必须在这里**（组合阶段）设置 input shader，不能用 LaunchedEffect：
    // LaunchedEffect 在组合提交之后才启动，而 Canvas 的 draw 在同一帧已经画过一遍 ——
    // 那时 uTex 还是空的 ⇒ 全黑；之后又没有东西触发重绘，于是「进页面黑屏、缩放一下才出图」
    //（2026-11-04 用户报的）。`remember(bitmap)` 同步执行，天然早于 draw。
    //
    val texReady = remember(shader, bitmap) {
        val b = bitmap
        if (b == null) {
            false
        } else {
            shader.setInputShader(
                "uTex",
                BitmapShader(
                    b,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP,
                ).apply {
                    // ⚠️ **锯齿的真正开关**（2026-11-04 定案）：
                    // 三参构造传的是 `FILTER_MODE_DEFAULT`（= 0，「未指定」），
                    // 在 Skia 里落到 `kNearest`。而 AGSL 的 `eval` → `sample()`
                    // 只看子 shader 自己的采样选项，**绕开 Paint 的 filterBitmap**，
                    // 所以之前把双线性开在 Paint 上完全没用。
                    // 显式给 LINEAR 才是双线性（API 33 公开，minSdk 正好是 33）。
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                },
            )
            true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── 图片层（content 快照给顶栏 / 按钮做玻璃）──
        val imageModifier = if (backdrop != null) {
            Modifier.fillMaxSize().layerBackdrop(backdrop)
        } else {
            Modifier.fillMaxSize()
        }
        Box(modifier = imageModifier.onSizeChanged { canvasSize = it }) {
            Canvas(Modifier.fillMaxSize()) {
                if (!texReady) return@Canvas
                val bmp = bitmap ?: return@Canvas
                val g = geo ?: return@Canvas
                shader.setFloatUniform("uSize", size.width, size.height)
                shader.setFloatUniform("uTexSize", bmp.width.toFloat(), bmp.height.toFloat())
                shader.setFloatUniform("uK", g.kx, g.ky)
                shader.setFloatUniform("uSpan", g.spanX, g.spanY)
                val sc = SpatialCrop.scaleOf(crop)
                shader.setFloatUniform("uUvScale", sc, sc)
                shader.setFloatUniform(
                    "uUvOffset",
                    SpatialCrop.offsetXOf(crop),
                    SpatialCrop.offsetYOf(crop),
                )
                // ⚠️ **不能用 `drawRect(brush = brush)`**（2026-11-04 的真根因）：
                // Compose 的 `Paint()` 默认 `filterQuality = None`，而
                // `AndroidPaint.setFilterQuality` 的实现就是 `paint.setFilterBitmap(quality != None)`
                // ⇒ 默认 **setFilterBitmap(false) = 最近邻**；`BitmapShader` 的三参构造传的是
                // `FILTER_MODE_DEFAULT`（跟随 Paint）⇒ 整条链全程最近邻。
                // 所以之前 4 taps 只是「4 个硬点取平均」，等于没插值（锯齿自然还在）。
                // 双线性在 `drawPaint` 构造时就打开了（`isFilterBitmap = true`）。
                drawPaint.shader = shader
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, drawPaint)
                }
            }
        }

        // ── 手势层（铺满，压在图片上）：拖动 / 缩放 / 单击 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                // key 用 geo：它一变（图 / 画布尺寸就绪）就要重建手势，
                // 否则闭包捕获的是旧的 null，拖动会直接 return。
                .pointerInput(geo) {
                    val g = geo ?: return@pointerInput
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w <= 0f || h <= 0f) return@detectTransformGestures
                        var c = crop
                        // 拖动：1px = (1/zoom)/W 的 uv；向右拖 = 显示区域左移。
                        c = c.copy(
                            cx = c.cx - pan.x * (1f / c.zoom) / w,
                            cy = c.cy - pan.y * (1f / c.zoom) / h,
                        )
                        if (zoomChange != 1f) {
                            // 以手势中心为锚点缩放：手指下的 uv 点保持不动。
                            val px = centroid.x / w
                            val py = centroid.y / h
                            val uvX = c.cx + (px - 0.5f) / c.zoom
                            val uvY = c.cy + (py - 0.5f) / c.zoom
                            val nz = (c.zoom * zoomChange)
                                .coerceIn(SpatialCrop.ZOOM_MIN, SpatialCrop.ZOOM_MAX)
                            c = c.copy(
                                cx = uvX - (px - 0.5f) / nz,
                                cy = uvY - (py - 0.5f) / nz,
                                zoom = nz,
                            )
                        }
                        // 边界 = 「屏幕可见 uv 半宽 ÷ zoom + 位移余量」，
                        // 不是旧的 0.5/zoom（那个在 zoom = 1 时把 cx 锁死在 0.5）。
                        crop = SpatialCrop.clampWith(
                            c, g.visibleHalfX, g.visibleHalfY, g.shiftMarginX, g.shiftMarginY,
                        )
                    }
                }
                .pointerInput(Unit) {
                    // 单击切顶栏；拖动不会走到这里（transform 手势会先消费）。
                    detectTapGestures { barsVisible = !barsVisible }
                },
        )

        // ── 顶栏：淡入淡出（不位移），玻璃保留 ──
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // 玻璃参数 = 官方 `BlurredBar` 的均匀模糊分支：25f + surface 80%。
            val barModifier = if (backdrop != null) {
                Modifier
                    .fillMaxWidth()
                    .textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = barGlassColors,
                    )
            } else {
                Modifier.fillMaxWidth().background(barTint)
            }
            // **直接用 miuix `TopAppBar`**（2026-11-04 用户要求）：
            // 标题居中、`title3` 字号、返回钮 16dp padding、内容色走 `onSurface` ——
            // 高度 52dp / 标题 padding 26dp 全都是 `TopAppBarDefaults` 的规范值，
            // 与二级页完全一致，不用手写、也不会在浅色模式下白字压白玻璃。
            SmallTopAppBar(
                title = "显示区域",
                modifier = barModifier,
                color = Color.Transparent,
                navigationIcon = {
                    IconButton(onClick = { haptic(); onClose() }) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        }

        // ── 底部两个按钮（2026-11-05）：一行、间距 12dp、各 = 「应用」按钮宽（150dp）──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 「恢复默认」：只把**内存里的编辑状态**重置为默认，不写盘、不弹 toast ——
                // 用户还要点「确认显示区域」才真正保存（2026-11-05 用户明确）。
                GlassButton(
                    text = "恢复默认",
                    width = buttonWidth,
                    shape = buttonShape,
                    backdrop = backdrop,
                    glassColors = resetGlassColors,
                    fallbackTint = resetTint,
                    contentColor = resetContent,
                    onClick = {
                        haptic()
                        crop = SpatialCrop.DEFAULT
                    },
                )
                GlassButton(
                    text = "确认显示区域",
                    width = buttonWidth,
                    shape = buttonShape,
                    backdrop = backdrop,
                    glassColors = buttonGlassColors,
                    fallbackTint = buttonTint,
                    contentColor = Color.White,
                    onClick = {
                        haptic()
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                SpatialCrop.write(ctx, key, crop)
                            }
                            android.widget.Toast.makeText(
                                ctx,
                                // 正在应用的那张本来就点不进来（页面层拦掉了），
                                // 能改的都是"下一次应用才生效"的卡 —— 不需要再解释生效时机
                                //（2026-11-05 用户要求）。
                                if (ok) "显示区域已保存" else "保存失败",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            onClose()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 显示区域页的玻璃按钮（2026-11-05）。
 *
 * 两个按钮**几何完全一致**：同一个 `shape`（平滑圆角）、同一个宽度、同一个自然高度
 * （miuix `Button` = `max(MinHeight 40dp, 行高 24 + insideMargin 13×2)` ≈ 50dp）。
 * 只有叠色和字色不同 —— 用户要求它们看起来是一对。
 *
 * 投影参数与系统底栏（`Os4FloatingNavigationBar`）一致：高斯 28dp / 黑 0.13。
 * `matchParentSize()` 不参与 Box 测量 ⇒ 阴影层自动等于按钮尺寸，不改变按钮高度。
 */
@Composable
private fun GlassButton(
    text: String,
    width: Dp,
    shape: Shape,
    backdrop: Backdrop?,
    glassColors: BlurColors,
    fallbackTint: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(CROP_SHADOW_BLUR, BlurredEdgeTreatment.Unbounded)
                .background(Color.Black.copy(alpha = CROP_SHADOW_ALPHA), shape),
        )
        val m = if (backdrop != null) {
            Modifier
                .width(width)
                .textureBlur(
                    backdrop = backdrop,
                    shape = shape,
                    blurRadius = 18f,
                    colors = glassColors,
                )
        } else {
            Modifier.width(width).background(color = fallbackTint, shape = shape)
        }
        Button(
            onClick = onClick,
            modifier = m,
            // 按钮自己的底必须透明，否则会把玻璃层整个盖住。
            colors = ButtonDefaults.buttonColors(
                color = Color.Transparent,
                contentColor = contentColor,
            ),
        ) {
            Text(text = text, style = MiuixTheme.textStyles.button)
        }
    }
}
