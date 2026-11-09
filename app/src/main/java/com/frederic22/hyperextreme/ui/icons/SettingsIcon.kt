// 由用户编辑后的 SVG 转回（icons/settings.svg）。
// 来源脉络：miuix-icons 的 Settings（Apache-2.0）→ 导出 SVG 给你改 → 转回 ImageVector。
// 想再改形状：直接编辑 ui/icons/SettingsIcon.kt 里的 pathData，或改 icons/settings.svg 后让我再转一次。
package com.frederic22.hyperextreme.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

val SettingsIcon: ImageVector
    get() {
        if (_settingsIcon != null) return _settingsIcon!!
        _settingsIcon = ImageVector.Builder(
            name = "SettingsIcon",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 96f,
            viewportHeight = 96f,
        ).apply {
            addPath(
                pathData = listOf(
                    PathNode.MoveTo(45.6172f, 8.23233f),
                    PathNode.CurveTo(46.8563f, 7.92256f, 48.0959f, 7.92256f, 49.335f, 8.23233f),
                    PathNode.CurveTo(49.9545f, 8.33559f, 50.7157f, 8.60701f, 51.6191f, 9.04581f),
                    PathNode.CurveTo(52.5227f, 9.48466f, 53.5429f, 10.0397f, 54.6787f, 10.7108f),
                    PathNode.LineTo(76.4404f, 23.0243f),
                    PathNode.LineTo(77.3701f, 23.5663f),
                    PathNode.CurveTo(78.3509f, 24.0826f, 79.1898f, 24.5601f, 79.8867f, 24.9989f),
                    PathNode.CurveTo(80.5837f, 25.4378f, 81.1387f, 25.8897f, 81.5518f, 26.3544f),
                    PathNode.CurveTo(82.481f, 27.4386f, 83.1269f, 28.5742f, 83.4883f, 29.7616f),
                    PathNode.CurveTo(83.6948f, 30.3812f, 83.8234f, 31.1944f, 83.875f, 32.2011f),
                    PathNode.CurveTo(83.9266f, 33.2079f, 83.9521f, 34.4086f, 83.9521f, 35.8026f),
                    PathNode.VerticalTo(59.7323f),
                    PathNode.CurveTo(83.9521f, 61.1263f, 83.9266f, 62.3404f, 83.875f, 63.373f),
                    PathNode.CurveTo(83.8234f, 64.4052f, 83.6947f, 65.2311f, 83.4883f, 65.8505f),
                    PathNode.CurveTo(83.1269f, 67.0895f, 82.481f, 68.1997f, 81.5518f, 69.1806f),
                    PathNode.CurveTo(81.0872f, 69.6968f, 80.5064f, 70.1744f, 79.8096f, 70.6132f),
                    PathNode.CurveTo(79.1126f, 71.052f, 77.9893f, 71.7111f, 76.4404f, 72.5888f),
                    PathNode.LineTo(54.6787f, 84.8251f),
                    PathNode.LineTo(53.2842f, 85.5995f),
                    PathNode.CurveTo(52.51f, 86.064f, 51.7872f, 86.451f, 51.1162f, 86.7607f),
                    PathNode.CurveTo(50.445f, 87.0704f, 49.8513f, 87.2775f, 49.335f, 87.3808f),
                    PathNode.CurveTo(48.0959f, 87.6389f, 46.8563f, 87.6389f, 45.6172f, 87.3808f),
                    PathNode.CurveTo(45.101f, 87.2775f, 44.507f, 87.0704f, 43.8359f, 86.7607f),
                    PathNode.CurveTo(43.1649f, 86.4509f, 42.4422f, 86.064f, 41.668f, 85.5995f),
                    PathNode.LineTo(40.2744f, 84.8251f),
                    PathNode.LineTo(18.5117f, 72.5888f),
                    PathNode.LineTo(17.6602f, 72.1239f),
                    PathNode.CurveTo(16.6792f, 71.556f, 15.8273f, 71.0398f, 15.1045f, 70.5751f),
                    PathNode.CurveTo(14.3817f, 70.1104f, 13.8134f, 69.6452f, 13.4004f, 69.1806f),
                    PathNode.CurveTo(12.4712f, 68.1997f, 11.8262f, 67.0895f, 11.4648f, 65.8505f),
                    PathNode.CurveTo(11.2584f, 65.231f, 11.1288f, 64.4053f, 11.0771f, 63.373f),
                    PathNode.CurveTo(11.0255f, 62.3404f, 11f, 61.1263f, 11f, 59.7323f),
                    PathNode.VerticalTo(35.8026f),
                    PathNode.CurveTo(11f, 34.4086f, 11.0255f, 33.2079f, 11.0771f, 32.2011f),
                    PathNode.CurveTo(11.1288f, 31.1944f, 11.2583f, 30.3812f, 11.4648f, 29.7616f),
                    PathNode.CurveTo(11.8262f, 28.5743f, 12.4712f, 27.4385f, 13.4004f, 26.3544f),
                    PathNode.CurveTo(13.7618f, 25.9414f, 14.2913f, 25.5149f, 14.9883f, 25.0761f),
                    PathNode.CurveTo(15.6853f, 24.6372f, 16.4729f, 24.1598f, 17.3506f, 23.6435f),
                    PathNode.CurveTo(17.7634f, 23.4371f, 18.1505f, 23.2307f, 18.5117f, 23.0243f),
                    PathNode.LineTo(40.2744f, 10.7108f),
                    PathNode.CurveTo(41.4101f, 10.0397f, 42.4296f, 9.48463f, 43.333f, 9.04581f),
                    PathNode.CurveTo(44.2363f, 8.60706f, 44.9977f, 8.33565f, 45.6172f, 8.23233f),
                    PathNode.Close,
                    PathNode.MoveTo(47.4766f, 32.3173f),
                    PathNode.CurveTo(44.6887f, 32.3173f, 42.1073f, 33.0142f, 39.7324f, 34.4081f),
                    PathNode.CurveTo(37.3575f, 35.8021f, 35.4731f, 37.6742f, 34.0791f, 40.0233f),
                    PathNode.CurveTo(32.6851f, 42.3725f, 31.9873f, 44.967f, 31.9873f, 47.8066f),
                    PathNode.CurveTo(31.9874f, 50.5944f, 32.6852f, 53.1758f, 34.0791f, 55.5507f),
                    PathNode.CurveTo(35.4731f, 57.9255f, 37.3575f, 59.8101f, 39.7324f, 61.204f),
                    PathNode.CurveTo(42.1073f, 62.5979f, 44.6887f, 63.2948f, 47.4766f, 63.2948f),
                    PathNode.CurveTo(50.3162f, 63.2948f, 52.9106f, 62.598f, 55.2598f, 61.204f),
                    PathNode.CurveTo(57.6088f, 59.8101f, 59.4801f, 57.9255f, 60.874f, 55.5507f),
                    PathNode.CurveTo(62.268f, 53.1758f, 62.9658f, 50.5944f, 62.9658f, 47.8066f),
                    PathNode.CurveTo(62.9658f, 44.967f, 62.268f, 42.3725f, 60.874f, 40.0233f),
                    PathNode.CurveTo(59.48f, 37.6742f, 57.6089f, 35.8021f, 55.2598f, 34.4081f),
                    PathNode.CurveTo(52.9106f, 33.0141f, 50.3162f, 32.3173f, 47.4766f, 32.3173f),
                    PathNode.Close,
                    PathNode.MoveTo(47.4766f, 39.1327f),
                    PathNode.CurveTo(49.9031f, 39.1327f, 51.9558f, 39.9714f, 53.6338f, 41.6493f),
                    PathNode.CurveTo(55.3116f, 43.3272f, 56.1504f, 45.3801f, 56.1504f, 47.8066f),
                    PathNode.CurveTo(56.1503f, 50.1811f, 55.3113f, 52.2202f, 53.6338f, 53.9237f),
                    PathNode.CurveTo(51.9558f, 55.6275f, 49.9031f, 56.4794f, 47.4766f, 56.4794f),
                    PathNode.CurveTo(45.1016f, 56.4794f, 43.0622f, 55.6275f, 41.3584f, 53.9237f),
                    PathNode.CurveTo(39.6549f, 52.2201f, 38.8028f, 50.1812f, 38.8027f, 47.8066f),
                    PathNode.CurveTo(38.8027f, 45.38f, 39.6546f, 43.3273f, 41.3584f, 41.6493f),
                    PathNode.CurveTo(43.0622f, 39.9714f, 45.1016f, 39.1327f, 47.4766f, 39.1327f),
                    PathNode.Close,
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
        }.build()
        return _settingsIcon!!
    }

private var _settingsIcon: ImageVector? = null
