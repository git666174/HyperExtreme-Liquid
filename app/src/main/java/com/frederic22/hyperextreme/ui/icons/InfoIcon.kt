// 由用户编辑后的 SVG 转回（icons/info.svg）。
// 来源脉络：miuix-icons 的 Info（Apache-2.0）→ 导出 SVG 给你改 → 转回 ImageVector。
// 想再改形状：直接编辑 ui/icons/InfoIcon.kt 里的 pathData，或改 icons/info.svg 后让我再转一次。
package com.frederic22.hyperextreme.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

val InfoIcon: ImageVector
    get() {
        if (_infoIcon != null) return _infoIcon!!
        _infoIcon = ImageVector.Builder(
            name = "InfoIcon",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 96f,
            viewportHeight = 96f,
        ).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(48.0391f, 8f),
                    PathNode.CurveTo(55.2998f, 8f, 61.9861f, 9.80206f, 68.0977f, 13.4062f),
                    PathNode.CurveTo(74.2093f, 17.0106f, 79.0546f, 21.8688f, 82.6328f, 27.9805f),
                    PathNode.CurveTo(86.211f, 34.0921f, 88f, 40.7783f, 88f, 48.0391f),
                    PathNode.CurveTo(88f, 55.2998f, 86.2109f, 61.9861f, 82.6328f, 68.0977f),
                    PathNode.CurveTo(79.0546f, 74.2093f, 74.2093f, 79.0546f, 68.0977f, 82.6328f),
                    PathNode.CurveTo(61.9861f, 86.2109f, 55.2998f, 88f, 48.0391f, 88f),
                    PathNode.CurveTo(40.7783f, 88f, 34.0921f, 86.211f, 27.9805f, 82.6328f),
                    PathNode.CurveTo(21.8688f, 79.0546f, 17.0106f, 74.2093f, 13.4062f, 68.0977f),
                    PathNode.CurveTo(9.80206f, 61.9861f, 8f, 55.2998f, 8f, 48.0391f),
                    PathNode.CurveTo(8.00002f, 40.7783f, 9.80201f, 34.0921f, 13.4062f, 27.9805f),
                    PathNode.CurveTo(17.0106f, 21.8688f, 21.8688f, 17.0106f, 27.9805f, 13.4062f),
                    PathNode.CurveTo(34.0921f, 9.80201f, 40.7783f, 8.00002f, 48.0391f, 8f),
                    PathNode.Close,
                    PathNode.MoveTo(46.8643f, 38.3232f),
                    PathNode.CurveTo(46.1852f, 38.3232f, 45.6496f, 38.5323f, 45.2578f, 38.9502f),
                    PathNode.CurveTo(44.8661f, 39.368f, 44.67f, 39.864f, 44.6699f, 40.4385f),
                    PathNode.VerticalTo(68.5684f),
                    PathNode.CurveTo(44.67f, 69.2472f, 44.8791f, 69.7692f, 45.2969f, 70.1348f),
                    PathNode.CurveTo(45.7148f, 70.5004f, 46.2633f, 70.6836f, 46.9424f, 70.6836f),
                    PathNode.HorizontalTo(49.1357f),
                    PathNode.CurveTo(49.8148f, 70.6836f, 50.3504f, 70.5004f, 50.7422f, 70.1348f),
                    PathNode.CurveTo(51.1338f, 69.7692f, 51.33f, 69.2472f, 51.3301f, 68.5684f),
                    PathNode.VerticalTo(40.4385f),
                    PathNode.CurveTo(51.33f, 39.8118f, 51.1468f, 39.3028f, 50.7812f, 38.9111f),
                    PathNode.CurveTo(50.4156f, 38.5194f, 49.8411f, 38.3232f, 49.0576f, 38.3232f),
                    PathNode.HorizontalTo(46.8643f),
                    PathNode.Close,
                    PathNode.MoveTo(48.0391f, 25.3164f),
                    PathNode.CurveTo(46.8377f, 25.3164f, 45.8191f, 25.7346f, 44.9834f, 26.5703f),
                    PathNode.CurveTo(44.1477f, 27.4061f, 43.7295f, 28.3987f, 43.7295f, 29.5479f),
                    PathNode.CurveTo(43.7296f, 30.6969f, 44.1606f, 31.7026f, 45.0225f, 32.5645f),
                    PathNode.CurveTo(45.8843f, 33.4263f, 46.89f, 33.8574f, 48.0391f, 33.8574f),
                    PathNode.CurveTo(49.1882f, 33.8574f, 50.1808f, 33.4392f, 51.0166f, 32.6035f),
                    PathNode.CurveTo(51.8523f, 31.7678f, 52.2704f, 30.7492f, 52.2705f, 29.5479f),
                    PathNode.CurveTo(52.2705f, 28.3464f, 51.8653f, 27.3409f, 51.0557f, 26.5312f),
                    PathNode.CurveTo(50.246f, 25.7216f, 49.2405f, 25.3164f, 48.0391f, 25.3164f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
        }.build()
        return _infoIcon!!
    }

private var _infoIcon: ImageVector? = null
