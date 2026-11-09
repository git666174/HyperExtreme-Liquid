package com.frederic22.hyperextreme.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * 按 HyperOS 的字重校正过的 miuix 样式表。
 *
 * **为什么需要它**：miuix 的 `defaultTextStyles()` 把 14 个样式的 `fontWeight` 全部留成 `null`，
 * 而 `TextStyle.fontWeight == null` 在 Compose 里就是默认的 `FontWeight.Normal`（400）。
 * HyperOS 系统界面用的则是 **MiSans Medium（500）** —— 同一个字体族（都走系统的
 * `sans-serif` → MiSans）、同一个字号，差的只有这一档字重，所以应用内的字看起来
 * 总会比系统显示的细一档。
 *
 * 这里**只补没有指定字重的那些**：已经是 `Bold` 的（`subtitle`）保持不动，
 * 尊重 miuix 原本的层级设计 —— 原则是"补缺失"，而不是把整套字重往上推。
 */
internal val HyperTextStyles: TextStyles = defaultTextStyles().let { base ->
    TextStyles(
        main = base.main.medium(),
        paragraph = base.paragraph.medium(),
        body1 = base.body1.medium(),
        body2 = base.body2.medium(),
        button = base.button.medium(),
        footnote1 = base.footnote1.medium(),
        footnote2 = base.footnote2.medium(),
        headline1 = base.headline1.medium(),
        headline2 = base.headline2.medium(),
        // subtitle 本来就是 Bold，对应 HyperOS 里再高一档，别动。
        subtitle = base.subtitle,
        title1 = base.title1.medium(),
        title2 = base.title2.medium(),
        title3 = base.title3.medium(),
        title4 = base.title4.medium(),
    )
}

/** 没写字重的补成 Medium；写了的原样保留。 */
private fun TextStyle.medium(): TextStyle =
    if (fontWeight == null) copy(fontWeight = FontWeight.Medium) else this
