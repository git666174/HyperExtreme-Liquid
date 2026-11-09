// 由用户编辑后的 SVG 转回（icons/tune.svg）。
// 来源脉络：miuix-icons 的 Tune（Apache-2.0）→ 导出 SVG 给你改 → 转回 ImageVector。
// 想再改形状：直接编辑 ui/icons/TuneIcon.kt 里的 pathData，或改 icons/tune.svg 后让我再转一次。
package com.frederic22.hyperextreme.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

val TuneIcon: ImageVector
    get() {
        if (_tuneIcon != null) return _tuneIcon!!
        _tuneIcon = ImageVector.Builder(
            name = "TuneIcon",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 96f,
            viewportHeight = 96f,
        ).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(48.7302f, 71.5294f),
                    PathNode.CurveTo(48.7302f, 68.879f, 48.0811f, 66.4314f, 46.7829f, 64.1866f),
                    PathNode.CurveTo(45.4847f, 61.9418f, 43.7133f, 60.1704f, 41.4685f, 58.8722f),
                    PathNode.CurveTo(39.2237f, 57.574f, 36.7761f, 56.9249f, 34.1257f, 56.9249f),
                    PathNode.CurveTo(31.4753f, 56.9249f, 29.0412f, 57.574f, 26.8235f, 58.8722f),
                    PathNode.CurveTo(24.6058f, 60.1704f, 22.8343f, 61.9418f, 21.5091f, 64.1866f),
                    PathNode.CurveTo(20.1839f, 66.4314f, 19.5212f, 68.879f, 19.5212f, 71.5294f),
                    PathNode.CurveTo(19.5212f, 74.1798f, 20.1839f, 76.6139f, 21.5091f, 78.8316f),
                    PathNode.CurveTo(22.8343f, 81.0493f, 24.6058f, 82.8208f, 26.8235f, 84.146f),
                    PathNode.CurveTo(29.0412f, 85.4712f, 31.4753f, 86.1339f, 34.1257f, 86.1339f),
                    PathNode.CurveTo(36.7761f, 86.1339f, 39.2237f, 85.4712f, 41.4685f, 84.146f),
                    PathNode.CurveTo(43.7133f, 82.8208f, 45.4847f, 81.0493f, 46.7829f, 78.8316f),
                    PathNode.CurveTo(48.0811f, 76.6139f, 48.7302f, 74.1798f, 48.7302f, 71.5294f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(26.499f, 71.5294f),
                    PathNode.CurveTo(26.499f, 69.4199f, 27.2428f, 67.6079f, 28.7303f, 66.0933f),
                    PathNode.CurveTo(30.2178f, 64.5788f, 32.0163f, 63.8215f, 34.1258f, 63.8215f),
                    PathNode.CurveTo(36.2894f, 63.8215f, 38.115f, 64.5788f, 39.6025f, 66.0933f),
                    PathNode.CurveTo(41.09f, 67.6079f, 41.8337f, 69.4199f, 41.8337f, 71.5294f),
                    PathNode.CurveTo(41.8337f, 73.639f, 41.09f, 75.451f, 39.6025f, 76.9656f),
                    PathNode.CurveTo(38.115f, 78.4801f, 36.2894f, 79.2374f, 34.1258f, 79.2374f),
                    PathNode.CurveTo(32.0163f, 79.2374f, 30.2178f, 78.4801f, 28.7303f, 76.9656f),
                    PathNode.CurveTo(27.2428f, 75.451f, 26.499f, 73.639f, 26.499f, 71.5294f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(23.6592f, 68.0405f),
                    PathNode.HorizontalTo(10.2718f),
                    PathNode.CurveTo(9.67681f, 68.0405f, 9.14943f, 68.2298f, 8.68966f, 68.6085f),
                    PathNode.CurveTo(8.22989f, 68.9871f, 8f, 69.6362f, 8f, 70.5557f),
                    PathNode.VerticalTo(72.4219f),
                    PathNode.CurveTo(8f, 73.3414f, 8.21636f, 73.9905f, 8.64909f, 74.3691f),
                    PathNode.CurveTo(9.08181f, 74.7478f, 9.62272f, 74.9371f, 10.2718f, 74.9371f),
                    PathNode.HorizontalTo(23.6592f),
                    PathNode.VerticalTo(68.0405f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(88f, 72.4219f),
                    PathNode.VerticalTo(70.5557f),
                    PathNode.CurveTo(88f, 69.6362f, 87.7701f, 68.9871f, 87.3103f, 68.6085f),
                    PathNode.CurveTo(86.8506f, 68.2298f, 86.3232f, 68.0405f, 85.7282f, 68.0405f),
                    PathNode.HorizontalTo(45.3225f),
                    PathNode.VerticalTo(75.0182f),
                    PathNode.LineTo(85.7282f, 74.9371f),
                    PathNode.CurveTo(86.3773f, 74.9371f, 86.9182f, 74.7478f, 87.3509f, 74.3691f),
                    PathNode.CurveTo(87.7836f, 73.9905f, 88f, 73.3414f, 88f, 72.4219f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(88.0001f, 25.363f),
                    PathNode.VerticalTo(23.4969f),
                    PathNode.CurveTo(88.0001f, 22.5774f, 87.7702f, 21.9283f, 87.3104f, 21.5496f),
                    PathNode.CurveTo(86.8506f, 21.171f, 86.3232f, 20.9817f, 85.7282f, 20.9817f),
                    PathNode.HorizontalTo(72.3408f),
                    PathNode.VerticalTo(27.8782f),
                    PathNode.HorizontalTo(85.7282f),
                    PathNode.CurveTo(86.3773f, 27.8782f, 86.9182f, 27.6889f, 87.351f, 27.3103f),
                    PathNode.CurveTo(87.7837f, 26.9317f, 88.0001f, 26.2826f, 88.0001f, 25.363f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(50.6775f, 20.9817f),
                    PathNode.HorizontalTo(10.2718f),
                    PathNode.CurveTo(9.67681f, 20.9817f, 9.14943f, 21.171f, 8.68966f, 21.5496f),
                    PathNode.CurveTo(8.22989f, 21.9283f, 8f, 22.5774f, 8f, 23.4969f),
                    PathNode.VerticalTo(25.363f),
                    PathNode.CurveTo(8f, 26.2826f, 8.21636f, 26.9317f, 8.64909f, 27.3103f),
                    PathNode.CurveTo(9.08181f, 27.6889f, 9.62272f, 27.8782f, 10.2718f, 27.8782f),
                    PathNode.HorizontalTo(50.6775f),
                    PathNode.VerticalTo(20.9817f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(76.3975f, 24.4706f),
                    PathNode.CurveTo(76.3975f, 21.8201f, 75.7484f, 19.3725f, 74.4503f, 17.1278f),
                    PathNode.CurveTo(73.1521f, 14.883f, 71.3806f, 13.1115f, 69.1359f, 11.8134f),
                    PathNode.CurveTo(66.8911f, 10.5152f, 64.4435f, 9.86609f, 61.7931f, 9.86609f),
                    PathNode.CurveTo(59.1426f, 9.86609f, 56.7085f, 10.5152f, 54.4908f, 11.8134f),
                    PathNode.CurveTo(52.2731f, 13.1115f, 50.5016f, 14.883f, 49.1764f, 17.1278f),
                    PathNode.CurveTo(47.8512f, 19.3725f, 47.1886f, 21.8201f, 47.1886f, 24.4706f),
                    PathNode.CurveTo(47.1886f, 27.121f, 47.8512f, 29.5551f, 49.1764f, 31.7728f),
                    PathNode.CurveTo(50.5016f, 33.9905f, 52.2731f, 35.762f, 54.4908f, 37.0872f),
                    PathNode.CurveTo(56.7085f, 38.4124f, 59.1426f, 39.075f, 61.7931f, 39.075f),
                    PathNode.CurveTo(64.4435f, 39.075f, 66.8911f, 38.4124f, 69.1359f, 37.0872f),
                    PathNode.CurveTo(71.3806f, 35.762f, 73.1521f, 33.9905f, 74.4503f, 31.7728f),
                    PathNode.CurveTo(75.7484f, 29.5551f, 76.3975f, 27.121f, 76.3975f, 24.4706f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(54.1664f, 24.4706f),
                    PathNode.CurveTo(54.1664f, 22.3611f, 54.9101f, 20.549f, 56.3976f, 19.0345f),
                    PathNode.CurveTo(57.8851f, 17.52f, 59.6836f, 16.7627f, 61.7932f, 16.7627f),
                    PathNode.CurveTo(63.9568f, 16.7627f, 65.7823f, 17.52f, 67.2698f, 19.0345f),
                    PathNode.CurveTo(68.7573f, 20.549f, 69.5011f, 22.3611f, 69.5011f, 24.4706f),
                    PathNode.CurveTo(69.5011f, 26.5801f, 68.7573f, 28.3922f, 67.2698f, 29.9067f),
                    PathNode.CurveTo(65.7823f, 31.4212f, 63.9568f, 32.1785f, 61.7932f, 32.1785f),
                    PathNode.CurveTo(59.6836f, 32.1785f, 57.8851f, 31.4212f, 56.3976f, 29.9067f),
                    PathNode.CurveTo(54.9101f, 28.3922f, 54.1664f, 26.5801f, 54.1664f, 24.4706f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
        }.build()
        return _tuneIcon!!
    }

private var _tuneIcon: ImageVector? = null
