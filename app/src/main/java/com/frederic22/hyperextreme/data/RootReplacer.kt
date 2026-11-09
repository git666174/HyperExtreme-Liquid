package com.frederic22.hyperextreme.data

/**
 * 与 root 相关的少量遗留工具（2026-10-15 大幅瘦身）。
 *
 * 原来这里是一整条"用 root 直接把模板文件替换掉"的通道（`replace` / `restore` / `clearBackups`
 * 及配套脚本、壁纸探测、主体合成等）。随着"立即替换"全部改走 hook 通道（App 写快照 →
 * 直连 aod 的 provider → hook 在宿主进程内落盘），这条 root 替换通道早已没有调用者；
 * 再往后"替换前备份 / 还原"整组逻辑也按用户要求删除。
 *
 * 于是本文件只保留 [isAvailable]：root 探测（重启目标应用、唤醒兜底等仍会用到）。
 * 脚本时代的缓存常量（`CACHED_WALLPAPER`，写方早已不存在）与候选行解析
 * （`parseCandidates`，随对应自检项）已于 2026-10-15 第二次瘦身删除。
 */
object RootReplacer {

    fun isAvailable(): Boolean = RootShell.isAvailable()
}
