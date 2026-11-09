// 从 **Material Icons**（Apache-2.0，`google/material-design-icons`）直接搬过来的两个图标，
// Round 字重的 24px 版，路径字符串与官方 SVG 逐字节一致：
//   * `check_circle_outline` —— 状态卡的"已激活"用
//   * `error_outline`        —— 状态卡的"未激活 / 无 Root"用
//
// 参考实现是 HyperMusicCover 的激活卡（`Icons.Rounded.CheckCircleOutline` / `Icons.Rounded.ErrorOutline`），
// 它用的是 `material-icons-extended` 依赖；本工程**没有**那个依赖、也不可能为两个图标加一个
// （离线构建 + 体积），所以把路径数据搬过来自己建 ImageVector。
//
// 路径**不手抄 PathNode**：交给 Compose 自带的 `PathParser` 解析 SVG 路径串
// —— 它和 `<vector>` 资源走的是同一条解析链（`pathData`），所以 `s / S / z` 这些
// 简写、隐式重复命令都由官方实现处理，不会因为手抄而画错。
package com.frederic22.hyperextreme.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** `check_circle_outline`：圆圈（描边）+ 勾。 */
private const val CheckCircleOutlinePath =
    "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 " +
        "18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm3.88-11.71L10 " +
        "14.17l-1.88-1.88c-.39-.39-1.02-.39-1.41 0-.39.39-.39 1.02 0 1.41l2.59 " +
        "2.59c.39.39 1.02.39 1.41 0L17.3 9.7c.39-.39.39-1.02 0-1.41-.39-.39-1.03-.39-1.42 0z"

/** `error_outline`：圆圈（描边）+ 感叹号。 */
private const val ErrorOutlinePath =
    "M12 7c.55 0 1 .45 1 1v4c0 .55-.45 1-1 1s-1-.45-1-1V8c0-.55.45-1 1-1zm-.01-5C6.47 " +
        "2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 " +
        "2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm1-3h-2v-2h2v2z"

/** 状态卡「已激活」的大图标（Material Icons `check_circle_outline`）。 */
val CheckCircleOutlineIcon: ImageVector
    get() {
        if (_checkCircleOutline != null) return _checkCircleOutline!!
        _checkCircleOutline = buildIcon("CheckCircleOutline", CheckCircleOutlinePath)
        return _checkCircleOutline!!
    }

/** 状态卡「未激活 / 无 Root」的大图标（Material Icons `error_outline`）。 */
val ErrorOutlineIcon: ImageVector
    get() {
        if (_errorOutline != null) return _errorOutline!!
        _errorOutline = buildIcon("ErrorOutline", ErrorOutlinePath)
        return _errorOutline!!
    }

/**
 * 24×24 的官方坐标系 → `ImageVector`。
 *
 * 用 `SolidColor(Color.Black)` 铺色是故意的：真正上色靠 `Icon(tint = …)` 的 `ColorFilter`
 * （`SrcIn` 会替换全部不透明像素的颜色、保留 alpha），所以这里填什么颜色都不影响最终观感，
 * 和 `ui/icons/` 里其它图标一个套路。
 */
private fun buildIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            pathFillType = PathFillType.NonZero,
        )
    }.build()

private var _checkCircleOutline: ImageVector? = null
private var _errorOutline: ImageVector? = null
