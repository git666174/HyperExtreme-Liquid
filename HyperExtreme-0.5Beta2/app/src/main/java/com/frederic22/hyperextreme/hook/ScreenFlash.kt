package com.frederic22.hyperextreme.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import com.frederic22.hyperextreme.data.MaskProvider

/**
 * 「替换后自动灭亮屏一次」的 **hook 主路**（2026-10-04 用户选 C：hook 优先 + 脚本兜底）。
 *
 * 为什么放在 SystemUI：`goToSleep()` / `wakeUp()` 需要 `DEVICE_POWER`，
 * 而 `com.android.systemui` 的清单里正好有这条权限（aod **没有**，反编译清单核对过）。
 *
 * 通道：模块 App 发一条**显式广播**（显式到 `com.android.systemui` 包，避免隐式广播限制），
 * 我们在 SystemUI 里动态注册的 receiver 收到后：灭屏 → 350ms → 亮屏，并回执
 * `METHOD_FLASH_ACK`；App 等不到回执就退到一条 `su -c "input keyevent 26 …"` 兜底。
 */
internal object ScreenFlash {

    const val ACTION = "com.frederic22.hyperextreme.action.FLASH"
    const val EXTRA_TOKEN = "token"

    @Volatile
    private var installed = false

    /** 幂等：第一次拿到 SystemUI 的 Context 时装上（由 `HookEntry` 的视图 hook 顺手调用）。 */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    val token = intent.getLongExtra(EXTRA_TOKEN, 0L)
                    val app = receiverContext.applicationContext ?: return
                    Thread { flash(app, token) }.also { it.isDaemon = true }.start()
                }
            }
            // ⚠️ Android 14（targetSdk ≥ 34）起，注册非系统广播**必须**带 RECEIVER_EXPORTED /
            // RECEIVER_NOT_EXPORTED 标志，否则直接抛 SecurityException —— 之前挂不上就是吃了这个。
            val app = context.applicationContext
            val filter = IntentFilter(ACTION)
            val ok = runCatching {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                true
            }.getOrElse { error ->
                HookRuntime.log("灭亮屏 hook：带标志注册失败（$error），退回两参注册")
                runCatching {
                    app.registerReceiver(receiver, filter)
                    true
                }.getOrDefault(false)
            }
            installed = ok
            HookRuntime.log("灭亮屏 hook：注册${if (ok) "成功" else "失败"}")
            if (!ok) {
                runCatching {
                    app.contentResolver.call(
                        MaskProvider.configUri(),
                        MaskProvider.METHOD_NOTE,
                        "灭亮屏 hook：注册失败（见 LSPosed 日志）",
                        null,
                    )
                }
            }
        }
    }

    /** 灭屏 → 350ms → 亮屏；`goToSleep`/`wakeUp` 是 @hide，用反射调（编译期 SDK stub 里没有）。 */
    private fun flash(context: Context, token: Long) {
        val ok = runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val now = SystemClock.uptimeMillis()
            power.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(power, now)
            Thread.sleep(350L)
            power.javaClass.getMethod("wakeUp", Long::class.javaPrimitiveType)
                .invoke(power, SystemClock.uptimeMillis())
            true
        }.getOrElse { error ->
            HookRuntime.log("灭亮屏 hook 失败：$error")
            runCatching {
                context.contentResolver.call(
                    MaskProvider.configUri(),
                    MaskProvider.METHOD_NOTE,
                    "灭亮屏 hook 失败：${error.javaClass.simpleName}: ${error.message}",
                    null,
                )
            }
            false
        }
        if (ok) HookRuntime.log("灭亮屏 hook：已完成（请求 $token）")
        runCatching {
            context.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_FLASH_ACK,
                if (ok) token.toString() else "",
                null,
            )
        }
    }
}
