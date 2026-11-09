package com.frederic22.hyperextreme.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/**
 * 液态壁纸的**触摸桥**（2026-11-09）。
 *
 * ## 为什么锁屏必须单独桥
 *
 * 桌面壁纸可以用 `WallpaperService.Engine#setTouchEventsEnabled(true)` 直接收事件；
 * **锁屏收不到** —— 事件被 Keyguard 窗口吃掉，根本到不了壁纸层。所以我们的 GL 层
 * 挂在壁纸 leash 上（在窗口之下），触摸只能从 SystemUI 的视图树里「旁听」。
 *
 * ## 怎么旁听而不影响解锁
 *
 * 挂在 `ViewGroup#dispatchTouchEvent` 上，命中宿主的**那一次调用**只抄坐标，
 * 然后照常 `chain.proceed()` —— **不消费、不拦截、不改变返回值**。
 * 解锁手势、通知下拉、时钟点击全部原样走，我们只是顺路看一眼。
 *
 * 为什么必须先找到宿主再比对：`ViewGroup#dispatchTouchEvent` 在 SystemUI 里
 * 每次触摸会走整条视图链（几十次），所以第一行就是引用比对，不命中立刻返回。
 *
 * ## 宿主是谁
 *
 * `keyguard_root_view`（与 `LockScreenButton.ROOT_ID_NAME` 同一个 id 名）的**窗口根**
 * —— 直接用 `view.rootView`，它是 DecorView，整窗事件的必经之路，而且不依赖
 * SystemUI 内部类名（那些类每个版本都在改名）。
 *
 * ## 已知边界
 *
 * * 只认锁屏。桌面态事件也会被抄到，但渲染器自己按 `isKeyguardLocked` 过滤；
 * * 如果 HyperOS 换掉了 `keyguard_root_view` 这个 id 名，桥会静默不生效 ——
 *   日志里会有一条「等 keyguard_root_view 进窗口」，不会抛异常、不会影响系统；
 * * 宿主是弱引用：锁屏窗口重建后 `onViewAttached` 会把新的根换进来。
 */
internal object LiquidTouch {

    /** 锁屏根视图的 id 名（`R.id.keyguard_root_view`，与 LockScreenButton 一致）。 */
    private const val ROOT_ID_NAME = "keyguard_root_view"

    private var host = WeakReference<ViewGroup>(null)

    private var installed = false
    private var rejected = false

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        if (installed) return true
        if (rejected) return false
        val cls = runCatching { Class.forName("android.view.ViewGroup", false, loader) }
            .getOrNull() ?: runCatching { ViewGroup::class.java }.getOrNull()
        if (cls == null) {
            HookRuntime.log("LIQUID 触摸桥：拿不到 ViewGroup")
            rejected = true
            return false
        }
        val method = runCatching {
            cls.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull()
        if (method == null) {
            HookRuntime.log("LIQUID 触摸桥：ViewGroup#dispatchTouchEvent 不存在")
            rejected = true
            return false
        }
        // 热点方法：LSPosed 会把整个 ViewGroup 的触摸派发接到桥上，
        // 所以拦截器第一行必须是**引用比对**，命中才取参数。
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val vg = chain.getThisObject() as? ViewGroup
                    if (vg != null && vg === host.get()) {
                        // 参数位置以 getArgs() 的**内容**为准：不同版本的 libxposed API
                        // 对「接收者是否占第 0 位」处理不一致（AicrHook 里踩过）。
                        val e = chain.getArgs().firstOrNull { it is MotionEvent } as? MotionEvent
                        if (e != null) runCatching { forward(e) }
                    }
                    return chain.proceed()
                }
            })
            installed = true
            HookRuntime.log("LIQUID 触摸桥已挂：ViewGroup#dispatchTouchEvent（等 $ROOT_ID_NAME 进窗口）")
            true
        }.getOrElse {
            HookRuntime.log("LIQUID 触摸桥安装失败：${it.javaClass.simpleName}: ${it.message}")
            rejected = true
            false
        }
    }

    /**
     * 由 `HookEntry` 的 `View#onAttachedToWindow` 转发（那一处已经在为
     * `LockScreenButton` 服务，顺手多看一眼，不额外装 hook）。
     *
     * 高频路径，**第一行必须是「已经找到就返回」**。
     */
    fun onViewAttached(view: View) {
        if (host.get()?.isAttachedToWindow == true) return
        val id = view.id
        if (id == 0) return
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull() ?: return
        if (name != ROOT_ID_NAME) return
        val root = (view.rootView as? ViewGroup) ?: (view as? ViewGroup) ?: return
        host = WeakReference(root)
        HookRuntime.log("LIQUID 触摸宿主已锁定：$name -> ${root.javaClass.name}")
    }

    /** 宿主是否已经拿到（诊断用）。 */
    fun hasHost(): Boolean = host.get() != null

    // ───────────────────────────── 事件转发 ─────────────────────────────

    private fun forward(e: MotionEvent) {
        if (!LiquidRenderer.isRunning) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> LiquidRenderer.onTouch(e.rawX, e.rawY, down = true)
            MotionEvent.ACTION_MOVE -> LiquidRenderer.onTouch(e.rawX, e.rawY, down = false)
        }
    }
}
