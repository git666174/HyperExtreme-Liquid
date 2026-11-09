package com.frederic22.hyperextreme.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.frederic22.hyperextreme.data.LiquidFlag
import com.frederic22.hyperextreme.data.LiquidMask
import com.frederic22.hyperextreme.data.LiquidStore
import com.frederic22.hyperextreme.data.LiquidTune
import com.frederic22.hyperextreme.data.MaskPrefs
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 二级页「液态壁纸」（2026-11-09 建立；同日补自选素材；P5 补参数滑块与预设）。
 *
 * ## 页面结构
 *
 * 1. **素材**：预览 + 选择/更换图片 + 用系统壁纸；
 * 2. **总开关**：开启 / 关闭；
 * 3. **物理模式**：水波 / 果冻 + 三档预设（柔和/标准/强劲）；
 * 4. **参数调节**：折射强度、波速、回弹、触点大小、倾斜跟随（滑块）；
 * 5. **选区**：全图 / 局部（软椭圆）+ 位置、大小两个滑块；
 * 6. **生效范围**：锁屏 / 桌面 / 全局。
 *
 * ## 参数怎么到渲染器
 *
 * 页面只改 `MaskPrefs` + 推系统属性（`LiquidFlag.PROP_TUNE / PROP_MASK`），
 * **不重启渲染器**：SystemUI 侧每 30 帧对一次属性，滑块拖动 ~0.5s 后画面就变。
 * 系统属性写入走 `su setprop`，所以拖动过程用 220ms 防抖（见 [LaunchedEffect] 那段）。
 *
 * ## 参数按模式各存一份
 *
 * 切到水波/果冻会载入该模式自己的那份参数（[LiquidTune] 的类注释解释了原因），
 * 预设按钮写的就是当前模式那份。
 */
@Composable
internal fun LiquidWallpaperDetail(state: MaskUiState) {
    val ctx = LocalContext.current
    val haptic = rememberClickHaptic()
    val prefs = remember { MaskPrefs(ctx) }
    val scope = rememberCoroutineScope()

    var active by remember { mutableStateOf(prefs.liquidActive) }
    var mode by remember { mutableIntStateOf(prefs.liquidMode) }
    var scopeSel by remember { mutableIntStateOf(prefs.liquidScope) }
    var key by remember { mutableStateOf(prefs.liquidKey) }
    var busy by remember { mutableStateOf(false) }
    var tune by remember { mutableStateOf(prefs.liquidTune(prefs.liquidMode)) }
    var maskCfg by remember { mutableStateOf(prefs.liquidMask) }
    var quality by remember { mutableIntStateOf(prefs.liquidQuality) }
    var showEditor by remember { mutableStateOf(false) }

    /** 滑块拖动时只递增它；[LaunchedEffect] 负责防抖后再推属性。 */
    var pushTick by remember { mutableIntStateOf(0) }

    /** 把当前界面状态整组推给 SystemUI。 */
    fun push() {
        LiquidFlag.push(
            active = active,
            key = key.takeIf { it.isNotEmpty() },
            scope = scopeSel,
            mode = mode,
            tune = tune,
            mask = maskCfg,
            quality = quality,
        )
    }

    fun apply(nextActive: Boolean, nextMode: Int, nextScope: Int) {
        val modeChanged = nextMode != mode
        active = nextActive
        mode = nextMode
        scopeSel = nextScope
        if (modeChanged) {
            // 每模式一份参数：切模式 = 载入该模式那份（见 LiquidTune 类注释）
            tune = prefs.liquidTune(nextMode)
        }
        prefs.setLiquidActive(nextActive)
        prefs.setLiquidMode(nextMode)
        prefs.setLiquidScope(nextScope)
        push()
    }

    // 防抖：拖动滑块时 onValueChange 每帧都来，不能每次都 su setprop
    LaunchedEffect(pushTick) {
        if (pushTick == 0) return@LaunchedEffect
        delay(220)
        push()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val newKey = withContext(Dispatchers.IO) { LiquidStore.import(ctx, uri) }
            busy = false
            if (newKey == null) {
                Toast.makeText(ctx, "图片读取失败，换一张试试", Toast.LENGTH_LONG).show()
                return@launch
            }
            val old = key
            key = newKey
            prefs.setLiquidKey(newKey)
            // 遮罩是按屏幕涂抹的，但存在旧图 key 下；换图后直接回到全图，避免「看起来没生效」
            maskCfg = maskCfg.copy(on = false)
            prefs.setLiquidMask(maskCfg)
            push()
            if (old.isNotEmpty() && old != newKey) LiquidStore.delete(ctx, old)
            Toast.makeText(ctx, "素材已更新，锁屏查看", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { push() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ── 一、素材 ──
        SmallTitle(text = "素材")
        Card(insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val preview = remember(key) {
                    if (key.isEmpty()) null else LiquidStore.preview(ctx, key)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "液态壁纸素材",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = if (busy) "正在读取图片…" else "未选择图片\n当前使用系统壁纸",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            haptic()
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (key.isEmpty()) "选择图片" else "更换图片",
                            style = MiuixTheme.textStyles.button,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (key.isNotEmpty()) {
                        Button(
                            onClick = {
                                haptic()
                                key = ""
                                prefs.setLiquidKey("")
                                maskCfg = maskCfg.copy(on = false)
                                prefs.setLiquidMask(maskCfg)
                                push()
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary(
                                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                                disabledColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MiuixTheme.colorScheme.primary,
                                disabledContentColor = MiuixTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(
                                text = "用系统壁纸",
                                style = MiuixTheme.textStyles.button,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // ── 二、总开关 ──
        Card(insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (active) "液态壁纸已开启" else "液态壁纸",
                    style = MiuixTheme.textStyles.main,
                )
                Text(
                    text = "点按起涟漪、摇一摇整幅荡开、倾斜手机液面跟着偏。" +
                        "只作用于壁纸层，图标与锁屏元素不受影响。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(4.dp))
                val fg = MiuixTheme.colorScheme.onPrimary
                val bg = MiuixTheme.colorScheme.primary
                Button(
                    onClick = {
                        haptic()
                        if (state.fullyActivated) {
                            val next = !active
                            if (next && prefs.spatialActive) {
                                Toast.makeText(
                                    ctx,
                                    "空间壁纸正在生效：两者会抢同一层壁纸，建议先关闭空间壁纸",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            apply(nextActive = next, nextMode = mode, nextScope = scopeSel)
                            Toast.makeText(
                                ctx,
                                if (next) "液态壁纸已开启，锁屏查看" else "液态壁纸已关闭",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                ctx,
                                "以 LSPosed 模式运行时需要先激活模块",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(
                        color = bg,
                        disabledColor = bg,
                        contentColor = fg,
                        disabledContentColor = fg,
                    ),
                ) {
                    Text(
                        text = if (active) "关闭液态壁纸" else "开启液态壁纸",
                        style = MiuixTheme.textStyles.button,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ── 三、物理模式 ──
        SmallTitle(text = "物理模式")
        Card(insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("水波", mode == LiquidFlag.MODE_WATER) {
                        haptic()
                        apply(nextActive = active, nextMode = LiquidFlag.MODE_WATER, nextScope = scopeSel)
                    }
                    ChoiceChip("果冻", mode == LiquidFlag.MODE_JELLY) {
                        haptic()
                        apply(nextActive = active, nextMode = LiquidFlag.MODE_JELLY, nextScope = scopeSel)
                    }
                }
                Text(
                    text = if (mode == LiquidFlag.MODE_JELLY) {
                        "弹簧-阻尼粒子网格（软体力学）：按压处凹陷、四周因体积约束鼓起，" +
                            "松手后欠阻尼回弹 —— 解的是「形状」，不是水面。"
                    } else {
                        "二维高度场波动方程（流体表面波）：涟漪按波动方程传播、叠加、衰减，" +
                            "再由高度梯度重建法线做折射与高光。"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 四、质感 ──
        SmallTitle(text = "质感")
        Card(insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("柔和", quality == LiquidFlag.QUALITY_SOFT) {
                        haptic()
                        quality = LiquidFlag.QUALITY_SOFT
                        prefs.setLiquidQuality(quality)
                        push()
                    }
                    ChoiceChip("自然", quality == LiquidFlag.QUALITY_NATURAL) {
                        haptic()
                        quality = LiquidFlag.QUALITY_NATURAL
                        prefs.setLiquidQuality(quality)
                        push()
                    }
                    ChoiceChip("真实", quality == LiquidFlag.QUALITY_REAL) {
                        haptic()
                        quality = LiquidFlag.QUALITY_REAL
                        prefs.setLiquidQuality(quality)
                        push()
                    }
                }
                Text(
                    text = when (quality) {
                        LiquidFlag.QUALITY_SOFT ->
                            "柔和：仿真网格 160 宽、果冻 16 列，关掉色散与高光 —— 只保留形变，最省电。"
                        LiquidFlag.QUALITY_REAL ->
                            "真实：仿真 288 宽、果冻 24 列，完整色散 + 法线高光 + 菲涅尔。"
                        else ->
                            "自然：仿真 224 宽、果冻 20 列，保留高光与色散，去掉菲涅尔。"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 五、参数调节 ──
        SmallTitle(text = "参数调节")
        Card(insideMargin = PaddingValues(0.dp)) {
            Column {
                ParallaxSliderRow(
                    title = if (mode == LiquidFlag.MODE_JELLY) "形变幅度" else "折射强度",
                    text = "${tune.refract.toInt()} px",
                    value = tune.refract,
                    range = LIQUID_REFRACT_MIN..LIQUID_REFRACT_MAX,
                    steps = 64,
                    onValueChange = {
                        tune = tune.copy(refract = it)
                        pushTick++
                    },
                    onFinished = {
                        prefs.setLiquidTune(mode, tune)
                        push()
                    },
                )
                ParallaxSliderRow(
                    title = if (mode == LiquidFlag.MODE_JELLY) "刚度" else "波速",
                    text = fmt(tune.c2, 2),
                    value = tune.c2 * 100f,
                    range = 5f..24f,
                    steps = 18,
                    onValueChange = {
                        tune = tune.copy(c2 = it / 100f)
                        pushTick++
                    },
                    onFinished = {
                        prefs.setLiquidTune(mode, tune)
                        push()
                    },
                )
                ParallaxSliderRow(
                    title = if (mode == LiquidFlag.MODE_JELLY) "回弹阻尼" else "回弹（阻尼）",
                    text = fmt(tune.damp, 3),
                    value = tune.damp * 1000f,
                    range = 960f..999f,
                    steps = 38,
                    onValueChange = {
                        tune = tune.copy(damp = it / 1000f)
                        pushTick++
                    },
                    onFinished = {
                        prefs.setLiquidTune(mode, tune)
                        push()
                    },
                )
                ParallaxSliderRow(
                    title = if (mode == LiquidFlag.MODE_JELLY) "按压半径" else "触点大小",
                    text = "${(tune.sigma * 100f).toInt()}",
                    value = tune.sigma * 1000f,
                    range = 20f..150f,
                    steps = 129,
                    onValueChange = {
                        tune = tune.copy(sigma = it / 1000f)
                        pushTick++
                    },
                    onFinished = {
                        prefs.setLiquidTune(mode, tune)
                        push()
                    },
                )
                ParallaxSliderRow(
                    title = if (mode == LiquidFlag.MODE_JELLY) "重力影响" else "倾斜跟随",
                    text = "${(tune.level * 100f).toInt()}%",
                    value = tune.level * 100f,
                    range = 0f..100f,
                    steps = 99,
                    onValueChange = {
                        tune = tune.copy(level = it / 100f)
                        pushTick++
                    },
                    onFinished = {
                        prefs.setLiquidTune(mode, tune)
                        push()
                    },
                )
                Button(
                    onClick = {
                        haptic()
                        // 预置里只保留「当前模式默认」：柔和/强劲两档和「质感」重名了，已删除
                        tune = LiquidTune.preset(mode, 1)
                        prefs.setLiquidTune(mode, tune)
                        push()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Text(
                        text = "恢复默认参数",
                        style = MiuixTheme.textStyles.button,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ── 六、选区（涂抹）──
        SmallTitle(text = "选区")
        Card(insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("全图", !maskCfg.on) {
                        haptic()
                        maskCfg = maskCfg.copy(on = false)
                        prefs.setLiquidMask(maskCfg)
                        push()
                    }
                    ChoiceChip("涂抹", maskCfg.on) {
                        haptic()
                        if (key.isEmpty()) {
                            Toast.makeText(ctx, "先选一张图片，再涂抹选区", Toast.LENGTH_SHORT).show()
                        } else {
                            showEditor = true
                        }
                    }
                }
                Text(
                    text = if (maskCfg.on) {
                        "只有涂抹区域有液体。想改范围点「重新涂抹」重画一遍（会覆盖上一次）。"
                    } else {
                        "全图：整张壁纸都是液体。选「涂抹」进入全屏画布，用手指画出有液体的区域。"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (maskCfg.on) {
                    Button(
                        onClick = {
                            haptic()
                            showEditor = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(
                            color = MiuixTheme.colorScheme.surfaceContainerHigh,
                            disabledColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MiuixTheme.colorScheme.primary,
                            disabledContentColor = MiuixTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            text = "重新涂抹",
                            style = MiuixTheme.textStyles.button,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // ── 七、生效范围 ──
        SmallTitle(text = "生效范围")
        Card(insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("锁屏", scopeSel == MaskPrefs.SPATIAL_SCOPE_LOCK) {
                        haptic()
                        apply(nextActive = active, nextMode = mode, nextScope = MaskPrefs.SPATIAL_SCOPE_LOCK)
                    }
                    ChoiceChip("桌面", scopeSel == MaskPrefs.SPATIAL_SCOPE_DESKTOP) {
                        haptic()
                        apply(nextActive = active, nextMode = mode, nextScope = MaskPrefs.SPATIAL_SCOPE_DESKTOP)
                    }
                    ChoiceChip("全局", scopeSel == MaskPrefs.SPATIAL_SCOPE_GLOBAL) {
                        haptic()
                        apply(nextActive = active, nextMode = mode, nextScope = MaskPrefs.SPATIAL_SCOPE_GLOBAL)
                    }
                }
                Text(
                    text = "默认锁屏；「全局」= 锁屏和桌面各挂一层。切换后 1 秒内重建渲染器。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "参数按模式各存一份（切水波/果冻会载入对应那份）。" +
                "未选图片时用系统壁纸；重启后打开本页会自动补推开关状态。",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showEditor) {
        val editorImage = remember(key, showEditor) { LiquidStore.preview(ctx, key, 1080) }
        if (editorImage != null) {
            MaskEditorDialog(
                image = editorImage,
                onDismiss = { showEditor = false },
                onSave = { strokes ->
                    val dm = android.content.res.Resources.getSystem().displayMetrics
                    val mw = 480
                    val mh = (mw * dm.heightPixels / dm.widthPixels).coerceAtLeast(2)
                    val bmp = buildMaskBitmap(strokes, mw, mh)
                    if (bmp != null && key.isNotEmpty()) {
                        LiquidStore.saveMask(ctx, key, bmp)
                        bmp.recycle()
                        // stamp 换一个值 ⇒ 渲染器下一次读属性时重新拉遮罩
                        maskCfg = LiquidMask(
                            on = true,
                            stamp = (System.currentTimeMillis() / 1000L).toInt() and 0x7fffffff,
                        )
                        prefs.setLiquidMask(maskCfg)
                        push()
                        Toast.makeText(ctx, "选区已保存", Toast.LENGTH_SHORT).show()
                    }
                    showEditor = false
                },
            )
        }
    }
}

/** 折射强度的像素量程（与 LiquidTune 的解码范围一致）。 */
private const val LIQUID_REFRACT_MIN = 1500f
private const val LIQUID_REFRACT_MAX = 8000f

/** 小数格式化（Locale.US 防止小数点被本地化成逗号 —— 与 `formatScale` 同一个理由）。 */
private fun fmt(value: Float, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

/** 两/三个互斥选项的胶囊按钮：选中用主题色，未选中用浅底。 */
@Composable
private fun RowScope.ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (selected) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.primary
    }
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColorsPrimary(
            color = bg,
            disabledColor = bg,
            contentColor = fg,
            disabledContentColor = fg,
        ),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.button,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 全屏涂抹编辑器（2026-11-09）。
 *
 * 为什么是全屏：锁屏壁纸按 `CENTER_CROP` 铺满屏幕，只有**同比例**的画布上涂抹，
 * 笔迹与锁屏上的区域才一一对应。页内小预览框比例不对，涂出来会纵向拉伸。
 *
 * 笔迹用归一化坐标记（0..1），保存时再按目标分辨率栅格化 —— 与设备分辨率解耦。
 */
@Composable
private fun MaskEditorDialog(
    image: Bitmap,
    onDismiss: () -> Unit,
    onSave: (List<List<Offset>>) -> Unit,
) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var active by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        fun norm(p: Offset): Offset = Offset(
                            (p.x / size.width).coerceIn(0f, 1f),
                            (p.y / size.height).coerceIn(0f, 1f),
                        )
                        detectDragGestures(
                            onDragStart = { p -> active = listOf(norm(p)) },
                            onDrag = { change, _ ->
                                if (active.size < 600) active = active + norm(change.position)
                            },
                            onDragEnd = {
                                if (active.isNotEmpty()) strokes = strokes + listOf(active)
                                active = emptyList()
                            },
                            onDragCancel = { active = emptyList() },
                        )
                    },
            ) {
                val scale = maxOf(size.width / image.width, size.height / image.height)
                val dw = image.width * scale
                val dh = image.height * scale
                drawImage(
                    image = image.asImageBitmap(),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(
                        ((size.width - dw) / 2f).toInt(),
                        ((size.height - dh) / 2f).toInt(),
                    ),
                    dstSize = IntSize(dw.toInt(), dh.toInt()),
                )
                val paintAlpha = Color.White.copy(alpha = 0.45f)
                val stroke = Stroke(
                    width = size.width * 0.11f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                (strokes + listOf(active)).forEach { pts ->
                    if (pts.isEmpty()) return@forEach
                    if (pts.size == 1) {
                        drawCircle(
                            color = paintAlpha,
                            radius = size.width * 0.055f,
                            center = Offset(pts[0].x * size.width, pts[0].y * size.height),
                        )
                    } else {
                        val path = Path()
                        pts.forEachIndexed { i, p ->
                            val x = p.x * size.width
                            val y = p.y * size.height
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path = path, color = paintAlpha, style = stroke)
                    }
                }
            }

            Text(
                text = "用手指涂抹：白色区域会有液体",
                style = MiuixTheme.textStyles.body2,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp, start = 24.dp, end = 24.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { strokes = emptyList() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "清除",
                        style = MiuixTheme.textStyles.button,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "取消",
                        style = MiuixTheme.textStyles.button,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                Button(
                    onClick = { onSave(strokes) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "保存",
                        style = MiuixTheme.textStyles.button,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 把归一化笔迹栅格化成遮罩位图：黑底白笔（白 = 有液体）。 */
private fun buildMaskBitmap(strokes: List<List<Offset>>, w: Int, h: Int): Bitmap? = runCatching {
    if (w <= 0 || h <= 0) return null
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.BLACK)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = AndroidPaint.Style.STROKE
        strokeWidth = w * 0.11f
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }
    val fill = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = AndroidPaint.Style.FILL
    }
    strokes.forEach { pts ->
        if (pts.isEmpty()) return@forEach
        if (pts.size == 1) {
            canvas.drawCircle(pts[0].x * w, pts[0].y * h, w * 0.055f, fill)
        } else {
            val path = AndroidPath()
            pts.forEachIndexed { i, p ->
                val x = p.x * w
                val y = p.y * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }
    bmp
}.getOrNull()
