package com.frederic22.hyperextreme.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 开发者主页（酷安）—— 原 `MaskTabs` 里的 `DeveloperProfileUrl`，2026-10-31 随弹窗一起搬来时改名。
 * 链接本身没变。
 */
internal const val DeveloperCoolapkUrl = "https://www.coolapk.com/u/3268364"

/** 项目仓库（README / Issue 都在这儿）。 */
internal const val DeveloperGithubUrl = "https://github.com/22Frederic/HyperExtreme"

/** B 站主页。 */
internal const val DeveloperBilibiliUrl = "https://space.bilibili.com/204757951"

/**
 * 三个平台，**顺序即弹窗里的显示顺序**（用户 2026-10-31 指定：Github → 哔哩哔哩 → 酷安）。
 * 文案按用户给的原话，`Github` 的拼写照抄，不要"纠正"成 GitHub。
 */
private val PlatformEntries = listOf(
    "打开Github" to DeveloperGithubUrl,
    "打开哔哩哔哩" to DeveloperBilibiliUrl,
    "打开酷安" to DeveloperCoolapkUrl,
)

/**
 * 「选择平台」弹窗（2026-10-31）—— 关于页开发者卡点一下弹它，**不再直接跳酷安**。
 *
 * 三个平台**直接可点**（用户原话），所以没有确认按钮，只在底部留一个「取消」。
 * 行的排版照 [RestartDialog]：整行可点、12dp 圆角、12dp 内边距、`textStyles.main`
 * （与 `WindowDialog` 标题同号）。
 *
 * 点某一项 = 关弹窗 + 打开链接，顺序与 [RestartDialog] 的确认一致：
 * 先 `onDismissRequest` 再 `dismissState.invoke()`，让退出动画正常播。
 */
@Composable
internal fun PlatformDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onPick: (String) -> Unit,
) {
    WindowDialog(
        title = "选择平台",
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            PlatformEntries.forEach { (label, url) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onDismissRequest()
                            dismissState?.invoke()
                            onPick(url)
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 单按钮右对齐（项目里其余弹窗都是「取消 / 确认」两个 ⇒ SpaceBetween）。
                horizontalArrangement = Arrangement.End,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
