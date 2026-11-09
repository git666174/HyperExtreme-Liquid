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
import com.frederic22.hyperextreme.data.MaskPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页「液态壁纸」（2026-11-09 建立，同日补上自选图片）。
 *
 * ## 页面结构
 *
 * 1. **素材卡**：预览框 + 「选择/更换图片」「用系统壁纸」；
 * 2. **总开关**：「开启 / 关闭液态壁纸」；
 * 3. **物理模式**：水波 / 果冻；
 * 4. **生效范围**：锁屏 / 桌面 / 全局。
 *
 * ## 素材怎么走
 *
 * 选图 → [LiquidStore.import]（压到 1080 宽、应用 EXIF 旋转、sha256 前 16 位当 key）
 * → `files/liquid/<key>/base.jpg` → 属性里带上 key → SystemUI 侧 `LiquidRenderer`
 * 通过 `content://…/liquid/<key>/base` 取图。
 *
 * **不选图时**（key 为空）渲染器退回系统当前壁纸（`theme_magic/.../lock_wallpaper.jpg`），
 * 所以「换素材」这件事从第一天起就是可选的，不是必经流程。
 *
 * ## 属性推送
 *
 * 四个值（active / key / scope / mode）**同批推送**：SystemUI 侧把它们读成一组，
 * 任一项晚半拍就会用上新旧混合的配置。`LiquidFlag.push` 就是这样设计的。
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

    /** 把当前界面状态整组推给 SystemUI（属性通道，见类注释）。 */
    fun push() {
        LiquidFlag.push(active, key.takeIf { it.isNotEmpty() }, scopeSel, mode)
    }

    fun apply(nextActive: Boolean, nextMode: Int, nextScope: Int) {
        active = nextActive
        mode = nextMode
        scopeSel = nextScope
        prefs.setLiquidActive(nextActive)
        prefs.setLiquidMode(nextMode)
        prefs.setLiquidScope(nextScope)
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
            // 旧素材在属性推送后就不再被引用（渲染器下一次重建读的是新 key），可以安全删。
            if (old.isNotEmpty() && old != newKey) LiquidStore.delete(ctx, old)
            Toast.makeText(ctx, "素材已更新，锁屏查看", Toast.LENGTH_SHORT).show()
        }
    }

    // 进页面补推一次：覆盖「属性被清掉 / 模块刚更新」的场景。
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
                        "低频大尺度形变 + 回弹：按下去整块「捏」一下，像果冻。"
                    } else {
                        "高频涟漪：点在哪、波从哪散开，松手后自然衰减。"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 四、生效范围 ──
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
                    text = "默认锁屏；「全局」= 锁屏和桌面各挂一层。" +
                        "切换后 1 秒内重建渲染器，不需要重开开关。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "手感调参在 LiquidGlCore.WATER / JELLY（折射强度 refract、波速 c2、阻尼 damp、触点半径 sigma）。" +
                "未选图片时用系统壁纸；重启手机后打开本页会自动补推开关状态。",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

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
