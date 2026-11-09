package com.frederic22.hyperextreme.hook

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AICR（小米澎湃 AI 引擎）**服务端兜底** —— 测试版（2026-11-05）。
 *
 * ## 为什么要有它
 *
 * 光栅壁纸的「景深主体」走 `AicrVision.segmentFile()` 直连 `com.xiaomi.aicr`。
 * 真机反馈里有设备返回 `getPluginBinder 返回空（Status=-1）`，而服务端反编译
 * （`re-glass/out/aiengine`）里**每条失败分支都会 `ret.putInt("Status", …)`**
 * ⇒ 只可能是三种情况之一：
 *
 * 1. 返回的 Bundle 根本没写回来（`onTransact` 顺序/版本不一致）；
 * 2. `mPlugInManager == null`（服务没就绪）；
 * 3. 插件没注册 / 版本对不上（`f27.b(desc)` / `f27.e(desc)`）。
 *
 * 本 hook 挂在服务端方法上，**只做两件事**：
 *
 * * **诊断**：把 `ret` 的 keySet/值、`mPlugInManager` 是否为 null、插件注册与否、
 *   插件版本全打进 LSPosed 日志 —— 一看就知道断在哪一条；
 * * **兜底**：`proceed` 后如果 binder 为 null 或 Status != 0，反射插件管理器
 *   （`AiCrCoreService#mPlugInManager`，类型 `f27`）直接 `a(desc, address)` 取 binder，
 *   写回 `Status = 0` 并返回。
 *
 * ## 挂点为什么有两个
 *
 * AIDL 的实现是 `AiCrCoreService` 里的**匿名内部类**（`new IAiCrCoreService.Stub() {…}`）。
 * 真机第一次装上来时 `declaredClasses` 里**没找到**它（2026-11-05 20:38 日志），
 * 所以再加一条兜底：hook `Service#onCreate`，从 `mBinder` 字段拿实例、
 * 再 hook 它的 `getPluginBinder`。两条路谁先成功用谁，方法级去重。
 *
 * ## v1.17.6（2026-11-05 深夜）：AICR 4.13.8 加了调用方签名白名单
 *
 * 新版本在 `onTransact` 门口加了 `AccessGuardUtils`：第三方应用直接 `return false`，
 * reply 全空 ⇒ 客户端读到 `binder=null / Status=-1`，而 `getPluginBinder` **根本不被调用**
 * （所以本 hook 的诊断日志一条都不会有）。绕法见 [hookOnTransact]：自实现 case 1 分发。
 */
internal object AicrHook {

    /** 服务实现类（匿名 Stub 的宿主）。 */
    private const val SERVICE = "com.xiaomi.aicr.access.AiCrCoreService"

    private const val METHOD = "getPluginBinder"

    /** `IAiCrCoreService` 的事务码 / 描述符（与客户端 `AicrVision` 同一份口径）。 */
    private const val IFACE_CORE = "com.xiaomi.aicr.IAiCrCoreService"
    private const val CODE_GET_PLUGIN = 1
    private const val CODE_INTERFACE = 1598968902

    /** 方法级去重：同一个 Method 被 hook 两次会抛「already hooked」。 */
    private val hooked = ConcurrentHashMap<String, Boolean>()

    /** `onTransact` 放行日志只打一次（每条 binder 事务都会过它，全打会刷屏）。 */
    private val firstPass = AtomicBoolean(false)

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val service = runCatching { Class.forName(SERVICE, false, loader) }.getOrNull()
        if (service == null) {
            log("未找到 $SERVICE（本进程没有这个类，跳过）")
            return false
        }

        var ok = false
        // ① 直接按内部类找（正常情况：匿名 Stub 会出现在 declaredClasses 里）
        val classes = runCatching { service.declaredClasses }.getOrDefault(emptyArray())
        log("declaredClasses(${classes.size}) = ${classes.joinToString { it.simpleName }}")
        for (c in classes) {
            val m = runCatching {
                c.declaredMethods.firstOrNull { it.name == METHOD && it.parameterCount == 5 }
            }.getOrNull() ?: continue
            ok = hookMethod(module, m) || ok
            hookOnTransact(module, c)
        }

        // ② 兜底：hook Service#onCreate，构造完实例后从 mBinder 字段拿实现对象再 hook
        if (!ok) {
            val onCreate = runCatching { service.getDeclaredMethod("onCreate") }.getOrNull()
            if (onCreate == null) {
                log("兜底失败：$SERVICE 没有 onCreate")
                return false
            }
            onCreate.isAccessible = true
            runCatching {
                module.hook(onCreate).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        val svc = chain.getThisObject()
                        val binder = runCatching {
                            svc?.javaClass?.getDeclaredField("mBinder")
                                ?.apply { isAccessible = true }
                                ?.get(svc)
                        }.getOrNull()
                        if (binder == null) {
                            log("兜底：onCreate 后拿不到 mBinder（字段名变了？）")
                        } else {
                            val m = binder.javaClass.declaredMethods.firstOrNull {
                                it.name == METHOD && it.parameterCount == 5
                            }
                            if (m == null) {
                                log("兜底：mBinder 里没有 $METHOD(5 参数)，类=${binder.javaClass.name}")
                            } else {
                                hookMethod(module, m)
                            }
                            // 访问校验在 onTransact 里（新版 4.13+），必须单独挂
                            hookOnTransact(module, binder.javaClass)
                        }
                        return result
                    }
                })
                log("已挂上 $SERVICE#onCreate（等实例后 hook $METHOD）")
                ok = true
            }.onFailure { log("挂 onCreate 失败：${it.javaClass.simpleName}: ${it.message}") }
        }
        return ok
    }

    // ──────────────────────────────────────────────────────────── hook

    private fun hookMethod(module: XposedModule, method: java.lang.reflect.Method): Boolean {
        val key = "${method.declaringClass.name}#${method.name}"
        if (hooked.putIfAbsent(key, true) != null) return true
        return runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // (callback, address, desc, minVersion, ret)
                    val args = chain.getArgs()
                    val address = args.getOrNull(1) as? String
                    val desc = args.getOrNull(2) as? String
                    val minVersion = args.getOrNull(3) as? String
                    val ret = args.getOrNull(4) as? Bundle

                    val result = chain.proceed() as? IBinder
                    val status = ret?.getInt("Status", Int.MIN_VALUE) ?: Int.MIN_VALUE
                    log(
                        "调用：address=$address desc=$desc minVersion=$minVersion " +
                            "→ 常规 binder=${result != null} Status=$status keys=${ret?.keySet()}",
                    )

                    // **hook 侧优先**（2026-11-05 用户定）：proceed 只为让 reply 合法（客户端要 readException），
                    // 返回值一律以 `mPlugInManager` 直取为准 —— 它绕过了版本校验、客户端限流和
                    // Status 分支；拿不到才回落到常规结果。
                    val pm = pluginManager(chain.getThisObject())
                    if (pm == null) {
                        log("mPlugInManager 为 null（服务未就绪）⇒ 回落常规结果")
                        return result
                    }
                    val direct = runCatching { callBinder(pm, desc, address) }.getOrNull()
                    if (direct != null) {
                        runCatching { ret?.putInt("Status", 0) }
                        log(
                            "hook 优先：从 mPlugInManager 取到 binder" +
                                "（常规 binder=${result != null} Status=$status）",
                        )
                        return direct
                    }
                    val registered = runCatching { callRegistered(pm, desc) }.getOrNull()
                    val version = runCatching { callVersion(pm, desc) }.getOrNull()
                    log(
                        "mPlugInManager 拿不到 binder（registered=$registered version=$version）" +
                            "⇒ 回落常规结果",
                    )
                    return result
                }
            })
            log("已挂上 ${method.declaringClass.name}#${method.name}（诊断 + 插件管理兜底）")
            true
        }.getOrElse {
            hooked.remove(key)
            log("挂 ${method.declaringClass.name}#${method.name} 失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * **绕过新版 AICR 的调用方签名校验**（2026-11-05，AICR 4.13.8 实测）。
     *
     * ## 新版本加了什么
     *
     * `AiCrCoreService$2` 重写了 `onTransact`，所有事务进门先过 `AccessGuardUtils`：
     *
     * ```
     * if (v8.a(this)) return super.onTransact(...)      // 放行
     * Log("onTransact denied for uid: ..."); return false   // 拒绝：reply 一个字节都不写
     * ```
     *
     * 放行条件只有四种：debug/root build、调用方==自己 / uid 1000、包名==com.xiaomi.aicr、
     * 或 **APK 签名 SHA-256 命中内置的 11 条白名单**。我们是第三方签名 ⇒ 必然被拒。
     *
     * 被拒时客户端读到的是**空 reply**：`readException()=0`（不抛）、`readStrongBinder()=null`、
     * `readInt()=0` ⇒ `Status=-1`，正是「getPluginBinder 返回空（Status=-1）」的来源。
     * 而 `getPluginBinder` 根本没被调用，所以挂在它上面的 hook 一条日志也不会有。
     *
     * ## 怎么绕
     *
     * **不能**反射调父类 `Stub#onTransact` —— `Method.invoke` 是**虚分派**，
     * 会重新落回子类的重写 ⇒ 自己调自己，栈溢出（v1.17.6 第一版就这么炸的，
     * 日志里是一串 `onTransact → Method.invoke → intercept → proceed → onTransact`）。
     *
     * 正解：**自己复刻 AIDL 存根的 case 1 分发**。闸门只在外层 `onTransact`，
     * 真正的 `getPluginBinder` 上没有 —— 所以直接读 Parcel、调实现、按原格式写回：
     *
     * ```
     * readStrongBinder → readString ×3 → new Bundle
     * getPluginBinder(cb, address, desc, minVersion, ret)     ← 虚分派，落到实现类
     * writeNoException → writeStrongBinder → writeInt(1) → ret.writeToParcel(reply, 1)
     * ```
     *
     * 其余事务（`isSupport` / `releaseConnect` 等）照常 `proceed()`：我们用不到，
     * 系统应用本来就在白名单里。旧版本没有这个重写 ⇒ `getDeclaredMethod` 找不到 ⇒ 直接跳过。
     */
    private fun hookOnTransact(module: XposedModule, implCls: Class<*>): Boolean {
        val key = "${implCls.name}#onTransact"
        if (hooked.putIfAbsent(key, true) != null) return true
        val params = arrayOf(
            Int::class.javaPrimitiveType!!,
            Parcel::class.java,
            Parcel::class.java,
            Int::class.javaPrimitiveType!!,
        )
        val implMethod = runCatching { implCls.getDeclaredMethod("onTransact", *params) }.getOrNull()
        if (implMethod == null) {
            hooked.remove(key)
            log("${implCls.simpleName} 没重写 onTransact（旧版无访问校验，无需绕过）")
            return false
        }
        // 真正的业务实现：`AiCrCoreService$2#getPluginBinder`（它自己**没有**闸门，闸门只在外层 onTransact）
        val pluginMethod = implCls.declaredMethods.firstOrNull {
            it.name == METHOD && it.parameterCount == 5
        }
        val cbStub = pluginMethod?.let { m ->
            runCatching {
                Class.forName(m.parameterTypes[0].name + "\$Stub", false, implCls.classLoader)
            }.getOrNull()
        }
        val asInterface = cbStub?.let {
            runCatching { it.getMethod("asInterface", IBinder::class.java) }.getOrNull()
        }
        if (pluginMethod == null || asInterface == null) {
            hooked.remove(key)
            log("找不到 $METHOD / ICoreServiceCallback.Stub，跳过 onTransact 绕过")
            return false
        }
        return runCatching {
            implMethod.isAccessible = true
            pluginMethod.isAccessible = true
            module.hook(implMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 取最后 4 个：签名为 (int code, Parcel data, Parcel reply, int flags)，
                    // 万一 getArgs() 把接收者也带进来，末尾四位仍然是参数。
                    val args = chain.getArgs().takeLast(4)
                    val code = args.getOrNull(0) as? Int ?: -1
                    val data = args.getOrNull(1) as? Parcel
                    val reply = args.getOrNull(2) as? Parcel
                    if (data == null || reply == null) return chain.proceed()

                    // `Binder.transact(INTERFACE_TRANSACTION)` —— 原实现也不查闸门，照抄
                    if (code == CODE_INTERFACE) {
                        reply.writeString(IFACE_CORE)
                        return true
                    }
                    // 其余事务（isSupport / releaseConnect 等）照常走原逻辑；
                    // 只有我们自己要用的 getPluginBinder 走下面这段自实现分发。
                    if (code != CODE_GET_PLUGIN) return chain.proceed()

                    data.enforceInterface(IFACE_CORE)
                    val cbRaw = data.readStrongBinder()
                    val address = data.readString()
                    val desc = data.readString()
                    val minVersion = data.readString()
                    val ret = Bundle()
                    val cb = try {
                        asInterface.invoke(null, cbRaw)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException ?: e
                    }
                    val binder = try {
                        // 虚分派落到 AiCrCoreService$2 的实现，再经我们的 getPluginBinder hook
                        // （hook 侧优先：直接找 mPlugInManager 要 binder）
                        pluginMethod.invoke(chain.getThisObject(), cb, address, desc, minVersion, ret)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException ?: e
                    }
                    // 与 AIDL 存根 `Stub.onTransact` 的 case 1 逐字一致：
                    // writeNoException → writeStrongBinder → writeInt(1) → Bundle.writeToParcel(_, 1)
                    reply.writeNoException()
                    reply.writeStrongBinder(binder as? IBinder)
                    reply.writeInt(1)
                    ret.writeToParcel(reply, 1)
                    if (firstPass.compareAndSet(false, true)) {
                        log(
                            "onTransact 自行分发 case 1：跳过 ${implCls.simpleName} 的访问校验" +
                                "（address=$address desc=$desc）",
                        )
                    }
                    return true
                }
            })
            log("已挂上 ${implCls.name}#onTransact（自实现 case 1 分发，绕过访问校验）")
            true
        }.getOrElse {
            hooked.remove(key)
            log("挂 ${implCls.name}#onTransact 失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }


    /**
     * 从服务实现对象里摸出 `mPlugInManager`。
     *
     * 实现类是**匿名内部类**，字段在外部类 `AiCrCoreService` 上 ⇒ 先取 `this$0`。
     * 字段名在系统 app 里通常保留（反编译可见 `mPlugInManager`）；拿不到就按
     * "类型名像插件管理器"兜底，最后再不行返回 null。
     */
    private fun pluginManager(impl: Any?): Any? = runCatching {
        val self = impl ?: return null
        val outer = self.javaClass.declaredFields
            .firstOrNull { it.name == "this\$0" }
            ?.apply { isAccessible = true }
            ?.get(self) ?: self
        val fields = outer.javaClass.declaredFields
        val f = fields.firstOrNull { it.name == "mPlugInManager" }
            ?: fields.firstOrNull { !it.type.isPrimitive && it.type.name.startsWith("com.xiaomi.aicr") }
            ?: return null
        f.isAccessible = true
        f.get(outer)
    }.getOrNull()

    /** `f27.b(String): boolean` —— 插件是否注册。 */
    private fun callRegistered(pm: Any, desc: String?): Boolean? {
        if (desc == null) return null
        val m = pm.javaClass.methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                it.returnType == Boolean::class.javaPrimitiveType
        } ?: return null
        return m.invoke(pm, desc) as? Boolean
    }

    /** `f27.e(String): String` —— 插件版本。 */
    private fun callVersion(pm: Any, desc: String?): String? {
        if (desc == null) return null
        val m = pm.javaClass.methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        } ?: return null
        return m.invoke(pm, desc) as? String
    }

    /** `f27.a(String, String): IBinder` —— 取插件 binder（第二个参数是 address）。 */
    private fun callBinder(pm: Any, desc: String?, address: String?): IBinder? {
        if (desc == null || address == null) return null
        val m = pm.javaClass.methods.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                IBinder::class.java.isAssignableFrom(it.returnType)
        } ?: return null
        return m.invoke(pm, desc, address) as? IBinder
    }

    private fun log(msg: String) = HookRuntime.log("AICR $msg")
}
