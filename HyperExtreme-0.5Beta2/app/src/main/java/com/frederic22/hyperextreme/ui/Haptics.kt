package com.frederic22.hyperextreme.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 按钮点击的振动反馈 —— 模块内所有按钮统一走这一个入口。
 *
 * **用哪一档**：[HapticFeedbackType.ContextClick]。它在 Android 上映射到
 * `HapticFeedbackConstants.CONTEXT_CLICK`，语义就是"点击了一个控件"，比 `LongPress` 轻、
 * 比 `TextHandleMove` 更像按钮，和系统按钮点一下的手感是同一档。
 *
 * **为什么走 Compose 的 [LocalHapticFeedback] 而不是 `Vibrator`**：
 * 1. 它最终落到 `View.performHapticFeedback`，**不需要 `VIBRATE` 权限**；
 * 2. 它尊重系统的「触感反馈」总开关 —— 用户把全局触感关掉就不振，这才是该有的行为
 *    （用 `FLAG_IGNORE_GLOBAL_SETTING` 硬振是错的）。
 *
 * **别和组件自带的振动叠在一起**：miuix 的 `Switch` / `Checkbox` / `RadioButton` 内部已经各自振过
 * （`HapticFeedbackType.ToggleOn` / `ToggleOff`，见 miuix 源码 `basic/Switch.kt` 的
 * `toggleable { onValueChange = { … performHapticFeedback(…) } }`）。所以：
 * 1. 在它们的 `onCheckedChange` 里再补一次 = 一次点击连振两下，**不要这么做**；
 * 2. 但**整行**的点击是另一条路（`BasicComponent` 的 `onClick`；点开关本体时被子组件消费、不会触发），
 *    那条路上加 `haptic()` 是对的 —— 设置页的 `SwitchRow` 就是这么把两条路分开的。
 *
 * 用法：
 * ```
 * val haptic = rememberClickHaptic()
 * TextButton(onClick = { haptic(); doSomething() })
 * ```
 * 约定：`haptic()` 写在 onClick 的**第一行** —— 振动代表"这个按钮被按了"，
 * 与后面的业务逻辑是否成立（比如忙碌时被拦下）无关。
 */
@Composable
internal fun rememberClickHaptic(): () -> Unit {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback) {
        { hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick) }
    }
}
