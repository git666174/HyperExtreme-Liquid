package com.frederic22.hyperextreme.aicr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 小米澎湃 AI 引擎（`com.xiaomi.aicr`）的**抠图**客户端 —— 纯手写 AIDL，不依赖它家的 SDK。
 *
 * ## 为什么手写
 *
 * `com.xiaomi.aicr` 的客户端类只存在于系统应用（aod / miwallpaper）里，我们自己的 APK 拿不到。
 * 但 AIDL 的线上协议是稳定的：接口描述符 + transaction code + Parcel 布局，全部从
 * `IImageSegmentService$Stub$Proxy` / `IAiCrCoreService$Stub$Proxy` 的字节码里读出来（2026-11-03）：
 *
 * | 方法 | code | 说明 |
 * |---|---|---|
 * | `IAiCrCoreService.getPluginBinder` | 1 | 参数 (callback, tag, 接口名, 版本)，**Bundle 是 out** |
 * | `IAiCrCoreService.releaseConnect` | 2 | |
 * | `IAiCrCoreService.isSupport` | 3 | |
 * | `IImageSegmentService.segment` | 4 | Bitmap 版（大图会撞 1MB binder 限制，不用） |
 * | `IImageSegmentService.segmentByFd` | 5 | **本类用的就是这个** |
 * | `IImageSegmentService.releaseAll` | 7 | |
 *
 * `getPluginBinder` 的返回 Bundle 里 `Status` 是枚举 ordinal：`OK = 0`、
 * `DOWNLOADING_FIRST_USE = 1`（首次要下模型，约 1MB）、`NEED_UPGRADE = 2` …
 *
 * ## 参数与返回（真机实测）
 *
 * `VisionAttribute`（Parcel 顺序：int, bool, Bundle, bool, bool）：
 * `visionType / isPrivicy / params / isExtractCombinedSubject / isMaxAreaAsSingleSubject`。
 * 后两个才是真正起作用的开关：合并多个主体 / 只取面积最大的一个。
 *
 * `SegmentResult`（Parcel 顺序：`readTypedList(Bitmap)`、`readList(RectF)`、`readList(contour)`）。
 *
 * ⚠️ 服务返回的不是灰度遮罩，而是**已经抠好的 RGBA 主体**：实测 1052×1600 的图里
 * 60.7% 的像素 alpha=0，其余不透明 ⇒ `alpha` 就是遮罩。想看灰度就 [deriveMask]。
 *
 * ⚠️ `depthSegmentMainFunc` 在这版 AICR 上是空壳：服务端只打
 * `AiCrEngine_SegmentPlugin: depthSegmentMainFunc not support!` 然后返回 null（2026-11-03 实测）。
 * 所以系统给不了深度，空间壁纸继续用 DA V2。
 *
 * @author 22Frederic
 */
internal object AicrVision {

    private const val TAG = "HX-AICR"

    /** 展示给用户看的方案名。 */
    const val ROUTE_APP = "免 Root（应用直连）"

    private const val PKG = "com.xiaomi.aicr"
    private const val ACTION = "com.xiaomi.aicr.access.AICR_ENGINE"
    private const val IFACE_CORE = "com.xiaomi.aicr.IAiCrCoreService"
    private const val IFACE_SEGMENT = "com.xiaomi.aicr.plugin.IImageSegmentService"
    private const val PLUGIN_VERSION = "5.0"

    private const val CODE_GET_PLUGIN = 1
    private const val CODE_SEGMENT_BY_FD = 5

    /** 插件状态：首次使用要下模型（服务端会一直返回这个，直到就绪）。 */
    private const val STATUS_DOWNLOADING = 1

    /** 首次下载的等待上限 / 轮询间隔（2026-11-05：等它就绪，而不是一次就判失败）。 */
    private const val PLUGIN_WAIT_MS = 45_000L
    private const val PLUGIN_POLL_MS = 1_500L

    /** 单次调用的结果。`mask` 是**已经归一化成灰度**的那份，`bitmap` 是服务原样返回的抠像。 */
    data class Result(
        val ok: Boolean,
        val status: Int,
        val bitmap: Bitmap?,
        val mask: Bitmap?,
        val elapsedMs: Long,
        val detail: String,
        /** 为「首次下载」等掉的毫秒数（0 = 没等）。界面用它决定要不要提示。 */
        val waitedMs: Long = 0,
    )

    /**
     * 抠图：以**调用方自己的 uid** bind 系统服务。
     *
     * 服务端会主动打印 `调用方 uid: 10335 nameForUID:com.frederic22.hyperextreme`，
     * 说明它是公开接受普通应用的，不是钻空子。
     */
    fun segment(ctx: Context, src: Bitmap): Result {
        val startAt = System.currentTimeMillis()
        val input = File(ctx.cacheDir, "aicr_mask_in.png")
        val scaled = downscale(src, 1600)
        runCatching {
            input.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { return fail(startAt, "写临时文件失败：$it") }
        if (scaled !== src) scaled.recycle()
        // 临时文件用完要删；原图不能删。
        return runFd(ctx, input, startAt, deleteAfter = true)
    }

    /**
     * **直接把原图文件**扔给 AICR（2026-11-04）：不解码、不缩放、不重编码。
     *
     * 服务本来就收 `ParcelFileDescriptor`（`segmentByFd`），它自己会解 ——
     * 我们多做的那次"解码 → 缩到 1600 → PNG"纯属白掉精度。抠图质量直接取决于送进去的像素，
     * 所以景深主体一律走这条。
     */
    fun segmentFile(ctx: Context, file: File): Result =
        runFd(ctx, file, System.currentTimeMillis(), deleteAfter = false)

    /** 真正干活的那段：开 FD → getPluginBinder → segmentByFd → deriveMask。 */
    private fun runFd(ctx: Context, input: File, startAt: Long, deleteAfter: Boolean): Result {
        val conn = connect(ctx, 8000)
            ?: return fail(startAt, "绑不上 $PKG（服务不存在 / 被系统限制）")

        var pfd: ParcelFileDescriptor? = null
        var waited = 0L
        try {
            val core = conn.binder ?: return fail(startAt, "服务连接为空")
            val waitStart = System.currentTimeMillis()
            val (plugin, status) = awaitPlugin(core)
            waited = System.currentTimeMillis() - waitStart
            Log.i(TAG, "plugin: status=$status binder=$plugin waited=${waited}ms")
            if (plugin == null) {
                return fail(startAt, "getPluginBinder 返回空（${statusText(status)}）", waited)
            }
            if (status != 0) {
                return fail(
                    startAt,
                    if (status == STATUS_DOWNLOADING) {
                        "AI 模型首次下载超时（等了 ${waited / 1000} 秒仍未就绪）"
                    } else {
                        "插件未就绪（${statusText(status)}）"
                    },
                    waited,
                )
            }

            pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IFACE_SEGMENT)
                data.writeTypedObject(pfd, 0)
                data.writeTypedObject(
                    VisionAttribute(
                        visionType = 0,
                        privacy = false,
                        // 把多个主体并成一块：光栅壁纸要的是「整个人」，不是「最大那个人」
                        extractCombined = true,
                        maxAreaAsSingle = false,
                    ),
                    0,
                )
                plugin.transact(CODE_SEGMENT_BY_FD, data, reply, 0)
                reply.readException()

                val bitmap = reply.readTypedObject(SegmentResult.CREATOR)?.bitmaps?.firstOrNull()
                val cost = System.currentTimeMillis() - startAt
                if (bitmap == null) {
                    return Result(false, status, null, null, cost, "服务返回空位图", waited)
                }
                val mask = deriveMask(bitmap)
                Log.i(TAG, "ok=${bitmap.width}x${bitmap.height} cost=${cost}ms")
                return Result(
                    true, status, bitmap, mask, cost,
                    "成功：${bitmap.width}×${bitmap.height}", waited,
                )
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "调用失败", t)
            return fail(startAt, "${t.javaClass.simpleName}: ${t.message}", waited)
        } finally {
            runCatching { pfd?.close() }
            runCatching { ctx.unbindService(conn.connection) }
            if (deleteAfter) runCatching { input.delete() }
        }
    }

    // ──────────────────────────────────────────────────────────── 绑定 / 插件

    private class Conn(val connection: ServiceConnection) {
        @Volatile var binder: IBinder? = null
    }

    private fun connect(ctx: Context, timeoutMs: Long): Conn? {
        val latch = CountDownLatch(1)
        lateinit var conn: Conn
        val sc = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                conn.binder = service
                Log.i(TAG, "onServiceConnected: $name")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "onServiceDisconnected: $name")
                conn.binder = null
            }
        }
        conn = Conn(sc)
        val intent = Intent(ACTION).setPackage(PKG)
        val bound = runCatching { ctx.bindService(intent, sc, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.e(TAG, "bindService 抛异常", it) }
            .getOrDefault(false)
        if (!bound) return null
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "bind 超时")
            return null
        }
        return conn
    }

    /** @return (插件 binder, Status) */
    private fun plugin(core: IBinder): Pair<IBinder?, Int> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_CORE)
            data.writeStrongBinder(SilentCallback())
            data.writeString("HyperExtreme")
            data.writeString(IFACE_SEGMENT)
            data.writeString(PLUGIN_VERSION)
            core.transact(CODE_GET_PLUGIN, data, reply, 0)
            reply.readException()
            val binder = reply.readStrongBinder()
            val hasBundle = reply.readInt() != 0
            val bundle = Bundle()
            if (hasBundle) bundle.readFromParcel(reply)
            binder to bundle.getInt("Status", -1)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 取插件，**首次下载时会一直等到就绪**（2026-11-05）。
     *
     * 为什么必须等：真机模型下好之后状态恒为 0，但新设备第一次调用拿到的是
     * `Status=1`（`DOWNLOADING_FIRST_USE`）——原实现直接判失败，用户看到的就是
     * 「景深主体计算失败」，再点一次也还是失败（模型还没下完）。
     *
     * 上限 [PLUGIN_WAIT_MS]，每 [PLUGIN_POLL_MS] 重取一次；超时后把最后一次的
     * `(binder, status)` 原样返回，由上层拼成「下载超时」的文案。
     */
    private fun awaitPlugin(core: IBinder): Pair<IBinder?, Int> {
        var last = plugin(core)
        if (last.second != STATUS_DOWNLOADING) return last
        Log.i(TAG, "AI 模型首次下载中，最多等 ${PLUGIN_WAIT_MS / 1000} 秒")
        val deadline = System.currentTimeMillis() + PLUGIN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(PLUGIN_POLL_MS)
            last = plugin(core)
            Log.i(TAG, "轮询插件：Status=${last.second}")
            if (last.second != STATUS_DOWNLOADING) return last
        }
        return last
    }

    /** Status 枚举译成人话（界面/toast/日志共用一套说法）。 */
    private fun statusText(status: Int): String = when (status) {
        0 -> "Status=0 就绪"
        1 -> "Status=1 AI 模型首次下载中"
        2 -> "Status=2 AI 引擎需要升级"
        // 服务端「一个字节都不写」的拒绝（空 reply）也会落到 -1。
        // AICR 4.13.8 起新增调用方签名白名单（AccessGuard），被拒时就是这种现象。
        -1 -> "Status=-1 服务未响应（新版 AI 引擎可能拒绝调用：需模块 hook 放行，" +
            "并确认 LSPosed 已勾选「小米澎湃 AI 引擎」作用域）"
        else -> "Status=$status"
    }

    /**
     * `ICoreServiceCallback` 的空实现。
     *
     * 服务端只在「模型首次下载」时回调 `onDownload(IBinder)` / `speed(String, long)`；
     * 我们是同步等待，不需要那些进度，**照单全收 return true** 即可。
     */
    private class SilentCallback : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            return true
        }

        companion object {
            private const val DESCRIPTOR = "com.xiaomi.aicr.ICoreServiceCallback"
        }
    }

    // ──────────────────────────────────────────────────────────── Parcelable

    /**
     * 与 `com.xiaomi.aicr.vision.VisionAttribute` **逐字段同序**。
     * 见类注释里的 Parcel 顺序；写错一个字段服务端就会收到垃圾。
     */
    private class VisionAttribute(
        private val visionType: Int,
        private val privacy: Boolean,
        private val extractCombined: Boolean,
        private val maxAreaAsSingle: Boolean,
    ) : Parcelable {

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(visionType)
            dest.writeBoolean(privacy)
            dest.writeBundle(Bundle())
            dest.writeBoolean(extractCombined)
            dest.writeBoolean(maxAreaAsSingle)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<VisionAttribute> {
                override fun createFromParcel(source: Parcel): VisionAttribute {
                    val type = source.readInt()
                    val privacy = source.readBoolean()
                    source.readBundle(javaClass.classLoader)
                    val combined = source.readBoolean()
                    val maxArea = source.readBoolean()
                    return VisionAttribute(type, privacy, combined, maxArea)
                }

                override fun newArray(size: Int) = arrayOfNulls<VisionAttribute>(size)
            }
        }
    }

    /**
     * 只用于**读**服务端返回值。三个字段的顺序与 `SegmentResult.writeToParcel` 一致：
     * `writeTypedList(mBitmaps)` → `writeList(mRects)` → `writeList(mContours)`。
     */
    private class SegmentResult(
        val bitmaps: List<Bitmap>,
    ) : Parcelable {

        override fun writeToParcel(dest: Parcel, flags: Int) = Unit

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SegmentResult> {
                override fun createFromParcel(source: Parcel): SegmentResult {
                    val list = ArrayList<Bitmap>()
                    @Suppress("DEPRECATION")
                    source.readTypedList(list, Bitmap.CREATOR)
                    val rects = ArrayList<Any?>()
                    source.readList(rects, Bitmap::class.java.classLoader)
                    val contours = ArrayList<Any?>()
                    source.readList(contours, Bitmap::class.java.classLoader)
                    return SegmentResult(list)
                }

                override fun newArray(size: Int) = arrayOfNulls<SegmentResult>(size)
            }
        }
    }

    // ──────────────────────────────────────────────────────────── 工具

    private fun fail(startAt: Long, msg: String, waitedMs: Long = 0): Result {
        Log.w(TAG, msg)
        return Result(false, -1, null, null, System.currentTimeMillis() - startAt, msg, waitedMs)
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * 服务返回的可能是**抠好的 RGBA 主体**（有 alpha），也可能是**灰度遮罩**（alpha 全 255）。
     * 这里做一次自检：只要有 1% 以上的像素带透明，就按 alpha 取；否则取亮度。
     */
    internal fun deriveMask(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var transparent = 0
        for (p in px) if ((p ushr 24) < 250) transparent++
        val useAlpha = transparent > px.size / 100
        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            val v = if (useAlpha) {
                a
            } else {
                (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            }
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Log.i(TAG, "deriveMask: ${w}x$h useAlpha=$useAlpha transparent=$transparent/${px.size}")
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
