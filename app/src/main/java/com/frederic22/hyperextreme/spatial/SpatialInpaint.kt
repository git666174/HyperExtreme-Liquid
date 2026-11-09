package com.frederic22.hyperextreme.spatial

import android.util.Log

/**
 * 空间壁纸底图填充 —— PatchMatch（C 实现，`libspatial_fill.so`）。
 *
 * ## 为什么是 C 不是 Kotlin
 * 这套算法是**随机 gather 密集**的：每个像素每趟要算十几次 5×5/3×3 patch 距离，
 * 每次都要跳到图像的随机位置取十几个字节。PC 端实测（2026-10-24）：
 *
 * | 改动                       | 效果 |
 * |---------------------------|------|
 * | `long double` + `pow(x,2)` → float 乘法 | 2 个数量级 |
 * | NNF 字段拍平（去三跳指针）  | 只有 5% ⇒ **瓶颈不是指针，是访存** |
 * | 8 线程                     | 1.5× ⇒ 内存子系统已饱和 |
 * | `__thread` 换 band 自带状态 | 单线程 3.3s → 2.1s（TLS 在 DLL 里走 emutls，极慢） |
 *
 * 也就是说：**这是带宽瓶颈，不是算力瓶颈**。手机 GPU 与 CPU 抢同一根 LPDDR5X
 * （Adreno 750 无独立显存），加上 PatchMatch 的传播天生是顺序扫描（上 GPU 得改成
 * tile 内共享内存传播），收益不到 2 倍 —— 所以走 CPU，不做 GL 加速。
 *
 * 实测（1024×1706，洞 24.7%，桌面 Arrow Lake）：1 线程 2.1s / 4 线程 1.6s。
 *
 * ## 参数口径
 * - [DEFAULT_RADIUS] = 1（3×3）。上游默认 2 是给小洞调的：半径越大越会把结构抹平，
 *   而且慢（r=2 是 1.8 倍、r=4 是 5 倍）。PC 实测 r=1 又快又好。
 * - 投票**取平均**（EM），不要改 winner-take-all：后者细节更高，但整片是水彩涂块。
 */
object SpatialInpaint {

    private const val TAG = "HX-SpatialFill"
    private const val LIB = "spatial_fill"

    /** patch 半径：1 ⇒ 3×3。见类注释。 */
    const val DEFAULT_RADIUS = 1

    private val loaded: Boolean = runCatching {
        System.loadLibrary(LIB)
        true
    }.getOrElse { e ->
        Log.e(TAG, "loadLibrary($LIB) 失败：${e.message}")
        false
    }

    /** so 是否装载成功（失败时 [fill] 返回 -1，调用方自己决定怎么兜底）。 */
    val available: Boolean get() = loaded

    /**
     * 原地填充 [px]。
     *
     * @param px  ARGB_8888 像素数组（长度 ≥ w*h），**原地改写**，alpha 字节保留
     * @param hole 遮罩（长度 ≥ w*h），>=128 视为洞；不会被改写
     * @return 耗时 ms；-1 = 失败（so 未装载 / 尺寸不符 / native 返回错误）
     */
    fun fill(
        px: IntArray,
        hole: ByteArray,
        w: Int,
        h: Int,
        radius: Int = DEFAULT_RADIUS,
        threads: Int = defaultThreads(),
    ): Long {
        if (!loaded) return -1L
        if (w <= 0 || h <= 0) return -1L
        val n = w * h
        if (px.size < n || hole.size < n) {
            Log.e(TAG, "尺寸不符：px=${px.size} hole=${hole.size} 需要 $n")
            return -1L
        }
        return try {
            nativeFill(px, hole, w, h, radius, threads)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeFill 异常：${t.message}")
            -1L
        }
    }

    /** 大核数折半：手机上真正的带宽来自 2~4 个核，再多只增加抢总线。 */
    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    private external fun nativeFill(
        px: IntArray,
        hole: ByteArray,
        w: Int,
        h: Int,
        radius: Int,
        threads: Int,
    ): Long
}
