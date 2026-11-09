package com.frederic22.hyperextreme.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * **WMShell 侧「多任务状态」只读探针**（v1.2.2，2026-10-17）—— 纯诊断，**不改任何行为**。
 *
 * ## 为什么要它
 *
 * 「打开多任务时隐藏状态栏」现在的判据链有两处不满意（见《04》N-3 / N-4）：
 * 1. 进入用系统广播 `com.miui.fullscreen_state_change`（`toRecents`），已实测可用；
 * 2. 但「退出」仍依赖桌面 Rust 侧的 T3 边沿，而 **T3 会被 shade 操作污染**
 *    （下拉/收起 shade 会误发 `toHome` + edge=0）—— 这正是 N-4 想上 Dart 的原因，
 *    而 Dart AOT 因 **x15 帧指针与 AAPCS64 冲突已定案不可 hook**（见《07》§7.2）。
 *
 * ⇒ 改从 **SystemUI / WMShell 进程内**找判据：这里全是纯 Java/Kotlin，**没有任何 ABI 约定问题**，
 * 且 WMShell 提供了**正规公开注册入口**（不必猜私有字段）。
 *
 * ## 本探针挂什么（只读）
 *
 * ### 第一轮（v1.2.2，13:19 实测）：**类都在、hook 都挂上了，但一个事件都没来**
 * | 挂点 | 签名 | 结果 |
 * | --- | --- | --- |
 * | `PerDisplayRecentsTransitionStateListener#onTransitionStateChanged` | `(I I)V` | 挂上 ✅，**零事件** |
 * | `RecentTasksController$RecentTasksImpl$1#onTransitionStateChanged` | `(I I)V` | 挂上 ✅，**零事件** |
 * | `RecentTasksImpl#addAnimationStateListener` | `(Executor, Consumer)V` | 挂上 ✅，开机被调 1 次（注册链活着） |
 *
 * 同期场景矩阵 M1–M7 全跑过，`toRecents` / `code=7` / 设位清位都正常触发 ⇒ **不是输入被吞、不是没装上**。
 * ⇒ 推论：这两个 `onTransitionStateChanged` 在本 ROM 上**不是多任务状态的主派发路径**
 * （`PerDisplayRecentsTransitionStateListener` 只在它自己 `animatingDisplays` 有变化时才回调）。
 *
 * ### 第二轮（v1.2.3）：改挂 `RecentsTransitionHandler` 自己的转换生命周期
 * | 挂点 | 签名 | 为什么 |
 * | --- | --- | --- |
 * | `onTransitionReady` | `(IBinder, TransitionInfo, SCT, SCT, FinishCallback)V` | **每次进/退多任务必过**；读 `TransitionInfo#getTransitionType()` 即可分辨进/退/是否 recents |
 * | `onTransitionConsumed` | 同上 | 转换被消费 |
 * | `canHandleTransition` | `(IBinder, TransitionInfo)Z` | WMShell 每次问"这转换归我管吗" |
 * | `addTransitionStateListener` | `(RecentsTransitionStateListener)V` | 确认注册链是否被走 |
 *
 * 前两个都只读 `getTransitionType()` 打日志、其余参数打类型名，**不改任何行为**。
 *
 * ## 这轮要验的三件事
 * 1. **转换类型能否区分进/退多任务** —— `transitionType` 是 AOSP 标准 `ShellTransition` 常量；
 * 2. ★ **shade 展开/收起会不会被算成 recents 转换**（桌面侧 T3 就是被 shade 误发的，
 *    这是选用它之前**最该先排除**的一条）；
 * 3. 进文件夹 / HOME 退出能否与「进多任务」区分开。
 *
 * ## 探针自身的克制
 * - **零行为改动**：`proceed()` 之前只读 int，之后什么都不做；
 * - 与 `RecentsStatusBar` 等既有链**零耦合**，任一挂点装不上只记日志；
 * - 日志特征串统一 `WMProbe`（ASCII，便于 grep）。
 */
internal object RecentsStateProbe {

    // ---------------------------------------------------------------- 挂点常量

    /**
     * WMShell 自己的按-display 监听器实现（`onInit` 里注册进 `RecentsTransitionHandler`）。
     * ⚠ `Class.forName` 里 `$` **不要转义** —— dex 中的内部类分隔符就是 `$` 本身。
     */
    private const val CLS_PER_DISPLAY =
        "com.android.wm.shell.recents.PerDisplayRecentsTransitionStateListener"

    /** `addAnimationStateListener(Executor, Consumer)` 落地的那一环（持有 `Consumer` 字段）。 */
    private const val CLS_TASKS_IMPL_LISTENER =
        "com.android.wm.shell.recents.RecentTasksController\$RecentTasksImpl\$1"

    /** 注册入口本体。 */
    private const val CLS_TASKS_IMPL =
        "com.android.wm.shell.recents.RecentTasksController\$RecentTasksImpl"

    /** 转换状态机本体：`mStateListeners` 的持有者，也是真正派发 `onTransitionStateChanged` 的地方。 */
    private const val CLS_HANDLER = "com.android.wm.shell.recents.RecentsTransitionHandler"

    // ---------------------------------------------------------------- 状态

    private val installed = AtomicBoolean(false)

    /** 累计事件数，仅用于确认"确实收到了信号"。 */
    private val eventCount = AtomicInteger(0)

    // ---------------------------------------------------------------- 安装

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        if (installed.get()) return true

        val perDisplay = hookOnTransitionStateChanged(module, loader, CLS_PER_DISPLAY, "perDisplay")
        val tasksImpl = hookOnTransitionStateChanged(module, loader, CLS_TASKS_IMPL_LISTENER, "tasksImpl")
        val addListener = hookAddAnimationStateListener(module, loader)

        // ★ 第一轮实测：上面三个挂点**装上了但一个事件都没收到**（2026-10-17 13:19，
        //   场景矩阵 M1–M7 全跑过，同期 toRecents / code7 都正常触发）。
        //   ⇒ 真正派发状态的是 `RecentsTransitionHandler` 自己的转换生命周期，
        //   这才是要探的地方（下面四个挂点）。
        val ready = hookHandlerLifecycle(module, loader, "onTransitionReady")
        val consumed = hookHandlerLifecycle(module, loader, "onTransitionConsumed")
        val canHandle = hookCanHandleTransition(module, loader)
        val stateListeners = hookAddTransitionStateListener(module, loader)

        val anyOk = perDisplay || tasksImpl || addListener || ready || consumed || canHandle || stateListeners
        if (anyOk) {
            installed.set(true)
            HookRuntime.log(
                "WMProbe：已挂（perDisplay=$perDisplay，tasksImpl=$tasksImpl，" +
                    "addAnimListener=$addListener，onTransitionReady=$ready，" +
                    "onTransitionConsumed=$consumed，canHandle=$canHandle，" +
                    "addStateListener=$stateListeners）—— 只读探针，不改任何行为",
            )
        } else {
            HookRuntime.log("WMProbe：所有挂点都没找到（WMShell 版本变了？）")
        }
        return anyOk
    }

    /**
     * hook `RecentsTransitionHandler#onTransitionReady` / `onTransitionConsumed`。
     *
     * 这两个是**每次进入/退出多任务必过**的转换生命周期回调（`TransitionRunner` 驱动）。
     * ⚠ **`TransitionInfo` 在下标 1**（实测 4 参：`(IBinder, TransitionInfo, SCT, SCT)`）——
     *   第一版按 5 参去取下标 5，日志里恒为 `info=null`，据此修正。
     * 只读该对象里 type/flag/state 类字段并打日志，其余参数打类型名，**不改任何行为**。
     */
    private fun hookHandlerLifecycle(
        module: XposedModule,
        loader: ClassLoader,
        methodName: String,
    ): Boolean {
        val clazz = runCatching { Class.forName(CLS_HANDLER, false, loader) }.getOrNull() ?: run {
            HookRuntime.log("WMProbe：未找到 $CLS_HANDLER（$methodName 跳过）")
            return false
        }
        val method = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterCount >= 2 &&
                    it.parameterTypes[1].simpleName.contains("TransitionInfo")
            }
        }.getOrNull() ?: run {
            HookRuntime.log("WMProbe：$CLS_HANDLER#$methodName(第2参为TransitionInfo) 找不到（跳过）")
            return false
        }
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val n = eventCount.incrementAndGet()
                    val info = runCatching { chain.getArg(1) }.getOrNull()
                    val desc = describeTransitionInfo(info)
                    val shape = (0 until method.parameterCount).joinToString(",") { i ->
                        runCatching { chain.getArg(i)?.javaClass?.simpleName ?: "null" }.getOrDefault("?")
                    }
                    HookRuntime.log("WMProbe[handler] $methodName $desc args=($shape) n=$n")
                    return chain.proceed()
                }
            })
            HookRuntime.log("WMProbe：$CLS_HANDLER#$methodName(${method.parameterCount}参) 已挂（只读）")
            true
        }.getOrElse {
            HookRuntime.log("WMProbe：挂 $CLS_HANDLER#$methodName 失败：$it")
            false
        }
    }

    /** hook `canHandleTransition(IBinder, TransitionInfo)Z`：每次 WMShell 询问"这转换归我管吗"都会过。 */
    private fun hookCanHandleTransition(module: XposedModule, loader: ClassLoader): Boolean {
        val clazz = runCatching { Class.forName(CLS_HANDLER, false, loader) }.getOrNull() ?: return false
        val method = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == "canHandleTransition" && it.parameterCount == 2
            }
        }.getOrNull() ?: return false
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val n = eventCount.incrementAndGet()
                    val info = runCatching { chain.getArg(1) }.getOrNull()
                    val desc = describeTransitionInfo(info)
                    val result = chain.proceed()
                    HookRuntime.log("WMProbe[handler] canHandleTransition $desc -> $result n=$n")
                    return result
                }
            })
            HookRuntime.log("WMProbe：$CLS_HANDLER#canHandleTransition 已挂（只读）")
            true
        }.getOrElse {
            HookRuntime.log("WMProbe：挂 canHandleTransition 失败：$it")
            false
        }
    }

    /** hook `addTransitionStateListener`：确认注册链是否被走过（不改变入参）。 */
    private fun hookAddTransitionStateListener(module: XposedModule, loader: ClassLoader): Boolean {
        val clazz = runCatching { Class.forName(CLS_HANDLER, false, loader) }.getOrNull() ?: return false
        val method = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == "addTransitionStateListener" && it.parameterCount == 1
            }
        }.getOrNull() ?: return false
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    HookRuntime.log("WMProbe：addTransitionStateListener 被调用（注册链活着）")
                    return chain.proceed()
                }
            })
            HookRuntime.log("WMProbe：$CLS_HANDLER#addTransitionStateListener 已挂")
            true
        }.getOrElse {
            HookRuntime.log("WMProbe：挂 addTransitionStateListener 失败：$it")
            false
        }
    }

    /** hook `onTransitionStateChanged(int, int)`；失败只记日志、不抛出。 */
    private fun hookOnTransitionStateChanged(
        module: XposedModule,
        loader: ClassLoader,
        className: String,
        tag: String,
    ): Boolean {
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: run {
            HookRuntime.log("WMProbe：未找到 $className（$tag 跳过）")
            return false
        }
        val method = runCatching {
            clazz.getDeclaredMethod(
                "onTransitionStateChanged",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: run {
            HookRuntime.log("WMProbe：$className 里没有 onTransitionStateChanged(int,int)（$tag 跳过）")
            return false
        }
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val a1 = chain.getArg(0) as? Int
                    val a2 = chain.getArg(1) as? Int
                    val n = eventCount.incrementAndGet()
                    HookRuntime.log("WMProbe[$tag] onTransitionStateChanged a1=$a1 a2=$a2 n=$n")
                    return chain.proceed()
                }
            })
            HookRuntime.log("WMProbe：$className#onTransitionStateChanged(int,int) 已挂（只读）")
            true
        }.getOrElse {
            HookRuntime.log("WMProbe：挂 $className#onTransitionStateChanged 失败：$it")
            false
        }
    }

    /** hook `addAnimationStateListener(Executor, Consumer)`：只打"有人注册了"，不改参数。 */
    private fun hookAddAnimationStateListener(module: XposedModule, loader: ClassLoader): Boolean {
        val clazz = runCatching { Class.forName(CLS_TASKS_IMPL, false, loader) }.getOrNull() ?: run {
            HookRuntime.log("WMProbe：未找到 $CLS_TASKS_IMPL（addAnimationStateListener 跳过）")
            return false
        }
        val method = runCatching {
            clazz.getDeclaredMethod(
                "addAnimationStateListener",
                Class.forName("java.util.concurrent.Executor", false, loader),
                Class.forName("java.util.function.Consumer", false, loader),
            )
        }.getOrNull() ?: run {
            HookRuntime.log("WMProbe：RecentTasksImpl 无 addAnimationStateListener(Executor,Consumer)（跳过）")
            return false
        }
        return runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    HookRuntime.log("WMProbe：addAnimationStateListener 被调用（注册链活着）")
                    return chain.proceed()
                }
            })
            HookRuntime.log("WMProbe：已挂 RecentTasksImpl#addAnimationStateListener")
            true
        }.getOrElse {
            HookRuntime.log("WMProbe：挂 addAnimationStateListener 失败：$it")
            false
        }
    }

    /** 累计收到的状态变更次数（诊断用）。 */
    fun events(): Int = eventCount.get()

    // ---------------------------------------------------------------- 诊断辅助

    /**
     * 打印 `TransitionInfo` 的关键字段 —— **自发现**，不猜方法名。
     *
     * 第一轮探针写死了 `getTransitionType()`，实测返回 `null`（这个 getter 不存在），
     * 所以改成：把该对象所有 `get*Xxx`/`is*Xxx` 零参方法扫一遍，
     * **只调用名字里带 type / flag / state / kind 语义的那几个**并打印。
     * 这样既不会误调有副作用的方法，也能一次问出"这次转换到底是什么"。
     *
     * 命中集合刻意收窄（含 type|flag|state|kind/transit），且全部包在 runCatching 里。
     */
    private fun describeTransitionInfo(info: Any?): String {
        if (info == null) return "info=null"
        val c = info.javaClass
        val parts = ArrayList<String>()
        for (m in runCatching { c.methods }.getOrDefault(emptyArray())) {
            val name = m.name
            if (m.parameterCount != 0) continue
            if (m.declaringClass == Any::class.java) continue
            val lower = name.lowercase()
            val interesting = lower.contains("type") || lower.contains("flag") ||
                lower.contains("state") || lower.contains("kind") ||
                lower.contains("transit") || lower.contains("launch")
            if (!interesting) continue
            val v = runCatching { m.invoke(info) }.getOrNull() ?: continue
            parts += "$name=$v"
            if (parts.size >= 8) break
        }
        val head = if (parts.isEmpty()) "无可读字段(${c.simpleName})" else parts.joinToString(" ")
        return "[${c.simpleName}] $head"
    }
}