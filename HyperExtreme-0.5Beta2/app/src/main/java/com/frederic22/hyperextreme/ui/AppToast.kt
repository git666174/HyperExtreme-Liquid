package com.frederic22.hyperextreme.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 系统 Toast。
 *
 * 之前用的是应用内自绘的 miuix `Snackbar`：它挂在 Scaffold 的 `snackbarHost` 槽位上，
 * 受本应用窗口、主题、甚至被 hook 的进程状态影响（"替换失败"这种报错恰恰出现在这些都不太正常的时候），
 * 而且必须把 `SnackbarHostState` 一路传进 [MaskUiState]。
 *
 * 换成系统 Toast：由 SystemUI 绘制，独立于本应用窗口，用完即走，报错信息也更"系统"。
 *
 * ⚠️ `Toast.show()` 必须在有 Looper 的线程上调用。调用点很多在 `Dispatchers.IO` 里
 * （root 执行完的回调、文件读写之后），在无 Looper 的线程直接 show 会抛
 * `RuntimeException: Can't toast on a thread that has not called Looper.prepare()`，
 * 所以这里统一 post 到主线程。
 */
object AppToast {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, message: String, long: Boolean = false) {
        if (message.isBlank()) return
        val appContext = context.applicationContext
        val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        val show = Runnable { runCatching { Toast.makeText(appContext, message, duration).show() } }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run()
        } else {
            mainHandler.post(show)
        }
    }
}
