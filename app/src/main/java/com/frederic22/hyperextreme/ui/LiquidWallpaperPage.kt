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

        // ── 三、物理模式 + 预设 ──
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
                        "低频大尺度形变 + 回弹：按下去整块「捏」一下，像果冻。"
                    } else {
                        "高频涟漪：点在哪、波从哪散开，松手后自然衰减。"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                // 三档预设：一键把当前模式那五个参数换成对应手感
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetChip("柔和", tune == LiquidTune.SOFT) {
                        haptic()
                        tune = LiquidTune.preset(mode, 0)
                        prefs.setLiquidTune(mode, tune)
                        pushTick++
                    }
                    PresetChip("标准", tune == LiquidTune.preset(mode, 1)) {
                        haptic()
                        tune = LiquidTune.preset(mode, 1)
                        prefs.setLiquidTune(mode, tune)
                        pushTick++
                    }
                    PresetChip("强劲", tune == LiquidTune.STRONG) {
                        haptic()
                        tune = LiquidTune.preset(mode, 2)
                        prefs.setLiquidTune(mode, tune)
                        pushTick++
                    }
                }
            }
        }

        // ── 四、参数调节 ──
        SmallTitle(text = "参数调节")
        Card(insideMargin = PaddingValues(0.dp)) {
            Column {
                ParallaxSliderRow(
                    title = "折射强度",
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
                    title = "波速",
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
                    title = "回弹（阻尼）",
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
                    title = "触点大小",
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
                    title = "倾斜跟随",
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
            }
        }

        // ── 五、选区 ──
        SmallTitle(text = "选区")
        Card(insideMargin = PaddingValues(0.dp)) {
            Column {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChoiceChip("全图", !maskCfg.on) {
                        haptic()
                        maskCfg = maskCfg.copy(on = false)
                        prefs.setLiquidMask(maskCfg)
                        push()
                    }
                    ChoiceChip("局部", maskCfg.on) {
                        haptic()
                        maskCfg = maskCfg.copy(on = true)
                        prefs.setLiquidMask(maskCfg)
                        push()
                    }
                }
                if (maskCfg.on) {
                    ParallaxSliderRow(
                        title = "选区位置",
                        text = "${(maskCfg.centerY * 100f).toInt()}%",
                        value = maskCfg.centerY,
                        range = 0f..1f,
                        steps = 19,
                        onValueChange = {
                            maskCfg = maskCfg.copy(centerY = it)
                            pushTick++
                        },
                        onFinished = {
                            prefs.setLiquidMask(maskCfg)
                            push()
                        },
                    )
                    ParallaxSliderRow(
                        title = "选区大小",
                        text = "${(maskCfg.radius * 100f).toInt()}%",
                        value = maskCfg.radius,
                        range = 0.15f..0.60f,
                        steps = 8,
                        onValueChange = {
                            maskCfg = maskCfg.copy(radius = it)
                            pushTick++
                        },
                        onFinished = {
                            prefs.setLiquidMask(maskCfg)
                            push()
                        },
                    )
                } else {
                    Text(
                        text = "全图：整张壁纸都是液体。切到「局部」可把液体限制在一块软椭圆区域里。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // ── 六、生效范围 ──
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

/** 预设胶囊：与 [ChoiceChip] 同款，只是没有「未选中」的浅底态（点一下就是一个预设）。 */
@Composable
private fun RowScope.PresetChip(text: String, selected: Boolean, onClick: () -> Unit) {
    ChoiceChip(text = text, selected = selected, onClick = onClick)
}
