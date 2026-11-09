package com.frederic22.hyperextreme.ui

import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** 界面配色模式。这里刻意不用 Monet 取色，只做普通的深浅色切换。 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    fun toColorSchemeMode(): ColorSchemeMode = when (this) {
        SYSTEM -> ColorSchemeMode.System
        LIGHT -> ColorSchemeMode.Light
        DARK -> ColorSchemeMode.Dark
    }

    companion object {
        fun of(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
