package com.frederic22.hyperextreme.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.frederic22.hyperextreme.data.RestartTargets
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「选择要重启的应用」多选框 —— 顶栏右上角那个重启按钮弹的就是它。
 *
 * 2026-09-29 从 `MaskSwapShell` 抽成共用的一份：一级页面（四个页签的顶栏）和二级页
 * （独立 Activity 的顶栏）都要弹它，两边各写一遍迟早写歪。逻辑一字未改。
 *
 * 取消 / 点空白处都只是关掉弹窗（`onDismissRequest`），只有「重启」会回调 [onConfirm]
 * 并把**选中的包名**交出去（一个都没选时交空列表，由上层提示"请先选择"）。
 */
@Composable
internal fun RestartDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    WindowDialog(
        title = "选择要重启的应用",
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        val dismissState = LocalDismissState.current
        // 默认一个都不选（用户指定）—— 勾了哪个就重启哪个。
        var selected by remember { mutableStateOf(emptySet<String>()) }
        Column(modifier = Modifier.fillMaxWidth()) {
            RestartTargets.forEach { target ->
                val checked = target.packageName in selected
                val toggle = {
                    selected = if (checked) {
                        selected - target.packageName
                    } else {
                        selected + target.packageName
                    }
                }
                // 整行可点：点文字和点勾选框是一个意思。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = toggle)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = target.label,
                        // 与弹窗标题同号：`WindowDialog` 的标题用的就是 `Text` 的默认样式
                        //（`MiuixTheme.textStyles.main`，见 agent.md §3.9）。
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.weight(1f),
                    )
                    Checkbox(state = ToggleableState(checked), onClick = toggle)
                }
            }
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
                    text = "重启",
                    primary = true,
                    onClick = {
                        val packages = selected.toList()
                        onDismissRequest()
                        dismissState?.invoke()
                        onConfirm(packages)
                    },
                )
            }
        }
    }
}
