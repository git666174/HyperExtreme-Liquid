package com.frederic22.hyperextreme.data

import android.content.Context

/**
 * 模块"是否被框架激活"的判定。
 *
 * 依据的是 libxposed 官方 service 通道：模块在自己的 manifest 里声明
 * `io.github.libxposed.service.XposedProvider`，框架（LSPosed）在模块被启用时会把
 * 服务 binder 通过 `call("SendBinder", ...)` 送进来 —— **收到 binder 就等于已激活**，
 * 既不需要注入目标应用，也不需要勾选模块自身。
 */
object ActivationState {

    @Volatile
    var frameworkConnected: Boolean = false
        private set

    fun recordFrameworkConnected(context: Context) {
        frameworkConnected = true
        runCatching { MaskPrefs(context).recordFrameworkConnected(System.currentTimeMillis()) }
    }

    /**
     * 框架 binder 已经失效（用户在 LSPosed 里取消了勾选 / 停用了模块）。
     *
     * **注意**：判定"是否激活"只看这个进程内标记（外加 [com.frederic22.hyperextreme.hook.HookRuntime.selfInjected]），
     * **不要**再用持久化的 `frameworkConnectedAt` 时间戳 —— 那是历史值、永不清零，
     * 会出现"取消了勾选界面还一直显示已激活"（实测踩过）。
     */
    fun markDisconnected() {
        frameworkConnected = false
    }
}
