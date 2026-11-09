package com.frederic22.hyperextreme.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「灭亮屏走完一轮 → 结束 hook 保护」的观察者（**兜底**：2026-10-04 起保护主路收尾改为
 * 「立即替换」完成时由 App 直接结束，本机制只兜"收尾没跑到"的残留）。
 *
 * ## 为什么这是个比"判指纹"更干净的终点
 *
 * hook 保护唯一的作用是：在**替换还没落地**的那段时间里，守住系统文件不被编辑器/SystemUI 写回去。
 * 而"落地"有一个确定的标志 —— 灭亮屏之后 Keyguard 会重新跑一次
 * `KeyguardDepthInteractor.updateDeductedImageView()`，也就是重新解码
 * `crop_subject`、把替换后的画面真正画上去。从那一刻起，系统文件写不写回都不影响已经显示出来的结果，
 * 保护就没有必要再守着了。
 *
 * 上一版靠"换壁纸判据"来结束，那是在**猜**用户什么时候不再需要保护；这是**观测**一个确定的事件，
 * 简单得多，也不会因为判据算不出来而卡在"永久保护"。
 *
 * ## 实现要点
 *
 * * `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` 是**只能动态注册**接收的系统广播，所以每边进程各自注册；
 * * 收到亮屏后**延迟 [DelayMs] 再解除**：亮屏瞬间编辑器和 SystemUI 可能还有收尾写入，让它先写完；
 * * 判断"现在该不该解除"由调用方喂进来的 [shouldRelease] 决定 —— 因为模块 app 和 hook 进程
 *   读的不是同一份 SharedPreferences（hook 跑在 com.miui.aod 里），各进程只能用自己的视野判断；
 * * 真正的解除动作统一走 provider 的 `METHOD_RELEASE`（provider 在模块 app 进程里，读得到正确的那份），
 *   这样"没在保护却把 app 进程唤醒"这种事不会发生。
 */
object ScreenDisarm {

    /**
     * 亮屏后等这么久再解除。
     *
     * 需求是"几十毫秒也够"，但这里没省到那么抠：亮屏后 SystemUI 会连着重建一堆 view，
     * 顺带给编辑器留一点收尾写入的余地。400ms 用户完全感知不到，却能把"解除得太早、被下一秒的写入覆盖"
     * 这种边角情况挡住。
     */
    const val DelayMs = 400L

    private val started = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var receiver: BroadcastReceiver? = null

    /** 幂等：同一个进程里多次调用只会注册一次。 */
    fun start(context: Context, shouldRelease: () -> Boolean) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val target = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_ON) return
                // 诊断：真机上必须能看出"广播到底有没有到" —— 否则分不清是接收器没注册，
                // 还是判据没过。写进 prefs，界面在设置页显示它。
                val armed = runCatching { shouldRelease() }.getOrDefault(false)
                runCatching {
                    val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    MaskPrefs(app).setDiagNote(
                        "亮屏检测：$at → " +
                            if (armed) "判据通过，即将结束保护" else "判据未通过（保护已结束）",
                    )
                }
                if (!armed) return
                main.postDelayed({ release(app, shouldRelease) }, DelayMs)
            }
        }
        val ok = runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 这里**必须**用 RECEIVER_EXPORTED：Android 14 起，"监听系统广播"的动态接收器
                // 如果用 RECEIVER_NOT_EXPORTED 注册，就收不到 ACTION_SCREEN_ON/OFF 了
                //（表现为"灭屏后结束保护"整个失效）。我们只处理 SCREEN_ON、且是幂等动作，
                // 允许别的应用给我们发同款广播没有实际风险。
                app.registerReceiver(target, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(target, filter)
            }
        }.isSuccess
        if (ok) receiver = target
    }

    private fun release(app: Context, shouldRelease: () -> Boolean) {
        // 延迟期间状态可能已经变了（比如用户手动点了"立即解除保护"），再确认一次。
        if (!runCatching { shouldRelease() }.getOrDefault(false)) return
        runCatching {
            app.contentResolver.call(
                MaskProvider.configUri(),
                MaskProvider.METHOD_RELEASE,
                MaskProvider.RELEASE_BY_SCREEN_ON,
                null,
            )
            MaskPrefs(app).setDiagNote("已按「灭亮屏」结束保护")
        }
    }
}
