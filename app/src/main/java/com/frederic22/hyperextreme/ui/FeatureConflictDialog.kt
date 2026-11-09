package com.frederic22.hyperextreme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「功能冲突提醒」弹窗（2026-10-29）—— 空间壁纸 / 视差壁纸**互斥**用。
 *
 * ## 什么时候弹
 *
 * 只在「要**开启**某个功能、而另一个已经在生效」时弹；关闭方向不检查（关闭永远不会冲突）。
 * 而且必须放在**最后**：调用方先把「本来能开启」的条件全部走完（模块已激活 / 非 busy /
 * 有可替换的缓存键 …），这些条件不满足时它们自己会弹对应的 toast，不该被冲突弹窗盖住。
 *
 * ## 按钮语义（用户 2026-10-29 定）
 *
 * * **取消**：什么都不做。
 * * **关闭**：只把对方关掉（含它自带的 toast / 壁纸进程重启），**不继续**本次替换。
 *   用户要再点一次「立即替换」才真正生效 —— 这条是刻意的，避免一次点击连锁做两件事。
 *
 * 写法照 `ClearCacheDialog`：正文走 `WindowDialog` 的 `summary`（官方正文位），
 * 按钮区 `SpaceBetween` + 两个 `TextButtonLike`（确认用 `primary`）。
 */
@Composable
internal fun FeatureConflictDialog(
    show: Boolean,
    summary: String,
    onDismissRequest: () -> Unit,
    onConfirmClose: () -> Unit,
) {
    WindowDialog(
        title = "功能冲突提醒",
        summary = summary,
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        onDismissRequest()
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "关闭",
                    primary = true,
                    onClick = {
                        onConfirmClose()
                        dismissState?.invoke()
                    },
                )
            }
        }
    }
}
