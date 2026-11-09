package com.frederic22.hyperextreme.spatial

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 空间壁纸：深度图生成（2026-10-22 恢复，v1.3.44）。
 *
 * ## 用的什么
 *
 * Depth Anything V2 **ViT-S int8**，跑在 **ONNX Runtime 完整版 1.31.0**。
 * 模型是 `assets/models/dav2_small_int8.onnx`（全量动态量化，26MB）。
 *
 * ⚠️ **2026-10-22 试过 mobile（minimal build，7.2MB AAR）失败了**：mobile 的 so 里
 * 没有 `ConvInteger` kernel（`quantize_dynamic` 会把 `patch_embed` 的 Conv 量化成它），
 * 报 `ORT_NOT_IMPLEMENTED: Could not find an implementation for ConvInteger(10)`。
 * 只量化 MatMul 重新出一版、绕开 ConvInteger 之后仍然失败 ⇒ 用户决定**退回完整版**。
 * 别再把依赖换回 `onnxruntime-mobile`，除非换模型（见《07-坑点台账.md》§7.13）。
 *
 * ## 定死的参数（见《04-功能设计存档.md》需求四）
 *
 * * 推理短边从 `SHORT_SIDES = [768, 896, 1024, 1152, 1280]` 里选，**默认 768**（518 边界毛糙有断线）；
 *   对齐到 14 的倍数（768 → 756）。高两档是否出现由 `shortSidesFor()` 按物理内存过滤；
 * * **输出尺寸 = 所选档位对齐后的尺寸，不再放大到长边 1920** —— 深度是低频图，
 *   放大会插值出假细节（2026-10-22 用户定案「短边就是所选值」）；
 * * 单张 **10 秒内**，超时/质量不达标 = 直接失败，**不做 2 层降级**。
 *
 * ## 缓存
 *
 * `files/spatial/<sha256前16位>/depth.png`（灰度，**越亮 = 越近**，min-max 归一化到 0~255）
 * + 同目录 `meta.json`。缓存键 = **图片内容 sha256 前 16 位** ⇒ 换图必重算、同图直接命中。
 *
 * ## 三个必须照做的坑（见《07-坑点台账.md》§7.13）
 *
 * 1. **模型不能 `assets.open().readBytes()` 建 session**：ORT 内部再复制一份 ⇒ 峰值 52MB+，
 *    低内存设备上 Activity 被回收（现象是「点按钮后自动退回功能页」，不是崩溃）。
 *    这里先把资产**流式拷到 `files/models/`**，再用路径 `createSession(path)`。
 * 2. **`ai.onnxruntime.**` 必须整包 keep**（`proguard-rules.pro`），native 层按类名反射，
 *    改名就 `SIGABRT`。
 * 3. **线程数不要压低**：之前为排查刷新率下调过，2026-10-22 用户明确「可以升回去」⇒
 *    现在按 CPU 核数给满（`availableProcessors`）。
 */
internal object SpatialDepth {

    private const val TAG = "SPATIAL_DEPTH"

    private const val MODEL_ASSET = "models/dav2_small_int8.onnx"
    private const val MODEL_NAME = "dav2_small_int8.onnx"

    /**
     * 可选档位（推理短边，像素）。**必须与 `MaskPrefs.SPATIAL_SHORT_CHOICES` 一致**。
     *
     * 下限 768：518 那档发丝会断线、低于训练尺寸 518 更没意义；
     * 1024（像素量约 180 万，真机 ~9.3s）是**稳妥档**；1152 / 1280 是 2026-11-09 按用户要求
     * 新增的「更高档」，像素量分别是 1024 的 ~1.6 / ~2.4 倍，耗时约 15s / 22s，**峰值内存也按同一
     * 比例涨**，所以低内存机不展示，见 [shortSidesFor]。
     */
    val SHORT_SIDES = intArrayOf(768, 896, 1024, 1152, 1280)

    /**
     * 这台机器**实际可用**的档位：低内存机隐藏 1152 / 1280，防止推理时 OOM。
     *
     * 判据用 `MemoryInfo.totalMem`（物理内存）而不是堆上限 —— 深度推理的峰值主要在
     * bitmap / ORT 的 native 临时缓冲里，物理内存更能反映余量。阈值按真机常见规格取整：
     *
     * * `>= 11 GB`（12G / 16G 机型实测 11.x GB）：768 / 896 / 1024 / 1152 / 1280
     * * `>= 7 GB`（8G 机型）：768 / 896 / 1024 / 1152
     * * 其余：768 / 896 / 1024
     */
    fun shortSidesFor(ctx: Context): IntArray {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return intArrayOf(768, 896, 1024)
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }
        val gb = info.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb >= 11.0 -> intArrayOf(768, 896, 1024, 1152, 1280)
            gb >= 7.0 -> intArrayOf(768, 896, 1024, 1152)
            else -> intArrayOf(768, 896, 1024)
        }
    }

    /** ViT 的 patch 尺寸：宽高必须是它的整数倍。 */
    private const val MULT = 14

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * 缓存目录里的**原图副本**（用户选中图的原始字节，不重编码、不缩放）。
     *
     * 2026-10-29 起取代原来的 `preview.webp`（半尺寸 WEBP 88）：二级页的 1:1 预览框
     * 直接解码它 ⇒ 看到的就是用户选的那张图；页面重建后也能靠它把「选中」状态恢复回来，
     * 不会再出现「预览显示着、实际没选中」。选图时写一份，`generate` 里补写一份。
     */
    const val SOURCE_NAME = "source"

    /** 生成结果。[hitCache] = true 时 [costMs] 为 0。 */
    data class Result(val dir: File, val costMs: Long, val hitCache: Boolean)

    private val sessionLock = Any()
    private var session: OrtSession? = null

    /** 上一次建会话用的后端（仅诊断：每次生成都会打印，别再靠"首次创建"那行日志）。 */
    private var sessionNnapi = false
    private var sessionIntra = 0

    /** 深度缓存根目录。 */
    fun cacheRoot(ctx: Context): File = File(ctx.filesDir, "spatial")

    /**
     * 一张**候选图**（2026-11-04，二级页改卡片式后引入）。
     *
     * 候选就是 `files/spatial/<key>/` 这个目录本身 —— **不需要新的数据结构**：
     * 深度、层图、原图副本全在里面，key 就是身份。
     */
    internal data class SpatialCandidate(
        val key: String,
        /** 卡片封面：优先原图副本 `source`，退化到层图 `layers/layer0.png`。都没有 = null（只显示底色）。 */
        val cover: File?,
        /** 有没有原图副本。**没有的不能重算深度**（`generate` 需要原图 uri）。 */
        val hasSource: Boolean,
        /** 深度 + 层图是否齐（决定「应用」能不能用、按钮三态）。 */
        val hasLayers: Boolean,
        /** 这张图的「显示区域」参数（`crop.json`，没有就是默认 = 不调整）。 */
        val crop: SpatialCrop.Crop = SpatialCrop.DEFAULT,
        /**
         * 封面图的**宽高比**（w/h）。
         *
         * 卡片要把 crop 反映到封面上，需要用它 + 屏幕比例算出 `Geometry.visibleHalf`。
         * 读的是文件头（`inJustDecodeBounds`），几乎零成本；读不出来回 1f。
         */
        val aspect: Float = 1f,
    )

    /**
     * 枚举所有候选（2026-11-04）。
     *
     * ## 为什么要 `source → layer0` 退路
     *
     * 真机实测 5 个缓存目录里，**3 个只有深度和层图、没有 `source`**（早期版本不写原图副本）。
     * 这些图仍然可用、可替换，只是不能重算深度 —— 封面必须能从 `layer0.png` 拿到，
     * 否则卡片一片空白。
     *
     * @param order 用户排定的顺序（`MaskPrefs.spatialCandidateOrder`）。不在里面的
     *   （新导入 / 老数据 / 手动拷入）按**目录修改时间倒序**追加到末尾。
     */
    fun listCandidates(ctx: Context, order: List<String> = emptyList()): List<SpatialCandidate> {
        val found = runCatching {
            cacheRoot(ctx).listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.length == 16 }
                ?.mapNotNull { dir ->
                    val source = File(dir, SOURCE_NAME).takeIf { it.isFile && it.length() > 0L }
                    val layers = File(dir, "layers")
                    val layer0 = File(layers, "layer0.png").takeIf { it.isFile }
                    val cover = source ?: layer0
                    // 三样都没有 ⇒ 空壳目录（删了一半 / 生成失败），不算候选
                    if (cover == null && !File(dir, "depth.png").isFile) return@mapNotNull null
                    SpatialCandidate(
                        key = dir.name,
                        cover = cover,
                        hasSource = source != null,
                        hasLayers = SpatialLayers.hasLayers(dir),
                        crop = SpatialCrop.read(ctx, dir.name),
                        aspect = aspectOf(cover),
                    ) to dir.lastModified()
                }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

        if (order.isEmpty()) return found.sortedByDescending { it.second }.map { it.first }
        val rank = order.withIndex().associate { (i, k) -> k to i }
        // `order` 里有的按 rank；没有的排到末尾，彼此之间按 mtime 倒序（新的在前）。
        return found
            .sortedWith(
                compareBy({ rank[it.first.key] ?: Int.MAX_VALUE }, { -it.second }),
            )
            .map { it.first }
    }


    /** 读文件头拿宽高比（`inJustDecodeBounds`，不解码像素）。读不出来回 1f。 */
    private fun aspectOf(file: File?): Float = runCatching {
        if (file == null || !file.isFile) return 1f
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1f
    }.getOrDefault(1f)

    /** 图片内容哈希（缓存键）。UI 用它判断「这张图有没有缓存」。 */
    fun cacheKey(ctx: Context, uri: Uri): String = sha16(ctx, uri)

    /**
     * 该图是否已有深度缓存：有则返回缓存键，没有返回 null。
     *
     * ⚠️ 会**完整读一遍图片算 sha256**（几十到几百毫秒），调用方放 IO 线程。
     */
    fun hasCache(ctx: Context, uri: Uri): String? {
        val key = sha16(ctx, uri)
        val dir = File(cacheRoot(ctx), key)
        return if (File(dir, "depth.png").isFile && File(dir, "meta.json").isFile) key else null
    }

    /** 缓存目录里的原图文件（可能不存在）。 */
    fun sourceFile(depthDir: File): File = File(depthDir, SOURCE_NAME)

    /**
     * 确保 `<dir>/source` 存在：把 [uri] 的**原始字节**拷进去（不重编码、不缩放）。
     *
     * 两个调用点：
     * * 用户选完图 —— 立刻写一份，这样即使还没生成深度，重进页面预览框也能恢复；
     * * [generate] 开头 —— 补写（旧缓存 / 上次复制失败）。
     *
     * 失败只记日志、返回 null（不影响深度生成）。
     */
    fun ensureSource(ctx: Context, uri: Uri, dir: File): File? {
        val out = File(dir, SOURCE_NAME)
        if (out.isFile && out.length() > 0L) return out
        return runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$SOURCE_NAME.tmp")
            (ctx.contentResolver.openInputStream(uri)
                ?: error("openInputStream 返回 null（uri=$uri）")).use { ins ->
                FileOutputStream(tmp).use { fos -> ins.copyTo(fos, 1 shl 16) }
            }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            out
        }.onFailure {
            Log.w(TAG, "原图副本写入失败：${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /**
     * 解码预览图：按短边 [maxShort] 做 `inSampleSize` 采样。
     *
     * 不采样的话 3000×4000 的图一解码就是 48MB，Compose 再上传一次纹理 —— 二级页会卡死
     * （`SpatialWallpaperPage` 里那条「必须 remember(preview)」的注释说的就是同一个坑）。
     */
    fun decodePreview(file: File, maxShort: Int = 1200): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        var sample = 1
        while (min(sw, sh) / (sample * 2) >= maxShort) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.onFailure {
        Log.w(TAG, "预览解码失败：${it.message}")
    }.getOrNull()

    /** 从 uri 直接解码预览（`source` 还没复制好时的兜底；同样按短边采样）。 */
    fun decodePreview(ctx: Context, uri: Uri, maxShort: Int = 1200): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (ctx.contentResolver.openInputStream(uri) ?: return null).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        var sample = 1
        while (min(sw, sh) / (sample * 2) >= maxShort) sample *= 2
        (ctx.contentResolver.openInputStream(uri) ?: return null).use {
            BitmapFactory.decodeStream(
                it, null, BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }.onFailure {
        Log.w(TAG, "预览解码失败（uri）：${it.message}")
    }.getOrNull()

    /** 清空全部深度缓存，返回删掉的条目数。 */    fun clearAll(ctx: Context): Int =
        cacheRoot(ctx).listFiles()?.count { it.deleteRecursively() } ?: 0

    /**
     * 生成（或命中）深度缓存。**同步阻塞**，调用方负责放到 IO 线程。
     *
     * @param shortSide 推理短边（取 [SHORT_SIDES] 里的一档）。**深度图的短边就是这个值**
     *   （对齐到 14 的倍数，例如 768 → 756），**不再统一放大到长边 1920**。
     * @param force 为 true 时**忽略已有缓存**、重算并覆盖（UI 的「10 秒内再点一次」走这条）。
     *
     * 任何失败都抛异常 —— 上层统一按「处理失败」处理，不做降级。
     */
    fun generate(ctx: Context, uri: Uri, shortSide: Int = 768, force: Boolean = false): Result {
        val target = shortSide.takeIf { it in SHORT_SIDES } ?: 768
        Log.i(TAG, "generate 开始：uri=$uri 短边=$target force=$force  mem=${memStat()}")
        val key = sha16(ctx, uri)
        val dir = File(cacheRoot(ctx), key)
        val depthFile = File(dir, "depth.png")
        val metaFile = File(dir, "meta.json")
        // 原图副本：缓存命中也补写一次（旧缓存只有 preview.webp，用户要求换成原图）
        ensureSource(ctx, uri, dir)
        if (!force && depthFile.isFile && metaFile.isFile) {
            // 命中条件里必须核对**档位**：meta.json 记着生成时用的 shortSide，
            // 换了「缓存推理分辨率」再应用同一张图，得按新档位重算，否则用户会以为新档位没生效。
            val cachedShort = runCatching { JSONObject(metaFile.readText()).optInt("shortSide", -1) }
                .getOrDefault(-1)
            if (cachedShort == target) {
                Log.i(TAG, "缓存命中 $key（短边 $target）")
                return Result(dir, 0L, true)
            }
            Log.i(TAG, "缓存档位不符（缓存 $cachedShort ≠ 当前 $target），重算 $key")
        }

        val t0 = SystemClock.elapsedRealtime()

        // 一、解码（先按 inSampleSize 粗降采样，避免 3000×4000 原图直接占 48MB）
        val src = decodeScaled(ctx, uri, target)
        val srcW = src.width
        val srcH = src.height

        // 二、算推理尺寸：短边 = 档位值，宽高对齐 14（768 → 756、896 → 882、1024 → 1008）
        val scale = target.toFloat() / min(srcW, srcH)
        var iw = max(MULT, (srcW * scale).roundToInt() / MULT * MULT)
        var ih = max(MULT, (srcH * scale).roundToInt() / MULT * MULT)

        val infer = if (iw == srcW && ih == srcH) src else {
            // 双立方 + 抗混叠（PIL 口径），替代无低通的 `createScaledBitmap`。
            // DA 对锐度不敏感（patch14 化后三种插值的差异只剩 16%），这里跟着换只是
            // 为了和层图共用一套算法；实测深度 RMSE 2.97→1.15、bodyMask IoU 0.9904→0.9976。
            SpatialResample.bitmap(src, iw, ih, cubic = true)
        }

        // 三、转 NCHW（ImageNet 归一化）
        val n = iw * ih
        val buf = obtainBuf(n)
        val pixels = buf.px
        infer.getPixels(pixels, 0, iw, 0, 0, iw, ih)
        infer.recycle()
        val chw = buf.chw
        if (chw.size != 3 * n) {  // 尺寸变了（池未重建的极端情况）
            error("NCHW 缓冲尺寸不符：${chw.size} != ${3 * n}")
        }
        for (i in 0 until n) {
            val c = pixels[i]
            chw[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            chw[n + i] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            chw[2 * n + i] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
        }

        // 四、推理
        //
        // ⚠️ `[N, C, H, W]` —— **第 3 维是 H、第 4 维是 W**，而 `iw` 是宽、`ih` 是高，
        // 曾经这里写成 `[1, 3, iw, ih]`（宽高颠倒），数据按 `width=iw` 行优先排布却被当成
        // 另一个宽高去解释 ⇒ 像素错位重排，深度图整幅糊掉（2026-10-22 用户实拍发现）。
        val env = OrtEnvironment.getEnvironment()
        val sess = ensureSession(ctx)
        // **每次生成都打**：不要靠「首次建会话」那一行（进程复用时会话已存在就直接跳过了）
        Log.i(
            TAG,
            "session: nnapi=$sessionNnapi intra=$sessionIntra cores=${Runtime.getRuntime().availableProcessors()}",
        )
        val inputName = sess.inputNames.first()
        val depth: FloatArray
        var ow: Int
        var oh: Int
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, ih.toLong(), iw.toLong()))
            .use { input ->
                sess.run(mapOf(inputName to input)).use { out ->
                    val tensor = out[0] as OnnxTensor
                    val info = tensor.info as TensorInfo
                    oh = info.shape[1].toInt()
                    ow = info.shape[2].toInt()
                    check(ow > 0 && oh > 0) { "推理输出尺寸异常：${ow}×$oh" }
                    Log.i(TAG, "推理输出 ${ow}×$oh（输入 ${iw}×$ih）")
                    val fb = tensor.floatBuffer
                    depth = if (ow * oh == buf.n) buf.depth else FloatArray(ow * oh)
                    fb.get(depth)
                }
            }

        // 五、min-max 归一化 → 灰度（越亮 = 越近）
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in depth) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        // 灰度复用 px（NCHW 已经转完，那块内存不再需要）
        val gray = if (ow * oh == buf.n) buf.px else IntArray(ow * oh)
        for (i in gray.indices) {
            val g = ((depth[i] - lo) / range * 255f).roundToInt().coerceIn(0, 255)
            gray[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(gray, 0, ow, 0, 0, ow, oh)

        // 六、直接存盘：**深度图短边就是所选档位对齐后的值，不再放大到 1920**
        // （2026-10-22 用户定案「短边就是所选值」，避免 512 档被插值成 1920 的假细节）。
        val outW = ow
        val outH = oh
        dir.mkdirs()
        FileOutputStream(depthFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val costMs = SystemClock.elapsedRealtime() - t0
        val meta = JSONObject()
            .put("srcW", srcW)
            .put("srcH", srcH)
            .put("shortSide", target)
            .put("inferW", iw)
            .put("inferH", ih)
            .put("outW", outW)
            .put("outH", outH)
            .put("costMs", costMs)
            .put("depthMin", lo.toDouble())
            .put("depthMax", hi.toDouble())
            .put("model", "dav2_small_int8.onnx (full int8, ort-android 1.31.0)")
            .put("createdAt", System.currentTimeMillis())
        metaFile.writeText(meta.toString())

        Log.i(
            TAG,
            "生成完成 key=$key 源 ${srcW}×$srcH 推理 ${iw}×$ih 输出 ${outW}×$outH " +
                "耗时 ${costMs}ms  mem=${memStat()}",
        )
        return Result(dir, costMs, false)
    }

    /**
     * **大数组复用池**（和 `SpatialLayers.Buf` 同一个思路）。
     *
     * 这里的三块是 Java 堆里最大的：`chw` 是 3n 个 float（1024 档 n≈1.59M ⇒ **19MB**），
     * `px`/`depth` 各 6.4MB。ART 把它们放进 **Large Object Space**（单独 mmap、
     * 不压缩、空页不还给系统），每次生成重新分配 ⇒ 碎片 + full GC。
     *
     * `px` 在转成 `chw` 之后就没用了，所以 `gray` 直接复用 `px`（省一块 6.4MB）。
     */
    private class DepthBuf(val n: Int) {
        /** 解码后的 ARGB → 转完 NCHW 后复用为【灰度 PNG 的 IntArray】。 */
        val px = IntArray(n)

        /** NCHW 输入（3n 个 float）。 */
        val chw = FloatArray(n * 3)

        /** 模型输出（与输入同尺寸时直接复用）。 */
        val depth = FloatArray(n)
    }

    private var pool: DepthBuf? = null

    /**
     * 丢掉复用池（2026-10-31）。
     *
     * 深度图尺寸 = 层图尺寸 ⇒ 这 20n（1022×2268 ⇒ 44.2MB）和 `SpatialLayers.Buf`
     * 是**同一张图的两份工作集**。深度跑完就还回去，分层阶段才腾得出空间
     * （原来两份常驻 168MB，分层再要一块就撞 256MB 上限，见 run_log 的 OOM）。
     */
    fun dropPool() {
        synchronized(this) { pool = null }
    }

    private fun obtainBuf(n: Int): DepthBuf = synchronized(this) {
        var cur: DepthBuf? = pool
        if (cur != null && cur.n == n) {
            cur
        } else {
            // 同 `SpatialLayers.obtainBuf`：先断引用（含局部变量）再建，避免两份叠加。
            cur = null
            pool = null
            System.gc()
            DepthBuf(n).also { pool = it }
        }
    }

    /**
     * 内存快照（诊断用）：java=已用/上限、native=已分配/堆大小、pool=复用池常驻。
     *
     * 判读方法：
     * - **native 涨、java 平** ⇒ Bitmap 像素 + PatchMatch（两者都在 native heap）；
     * - **java 每次都涨到同一高度再回落** ⇒ 还有没池化的大数组；
     * - **java 的「已用」在一次生成后不回落** ⇒ 就是复用池本身在常驻（预期行为）。
     */
    private fun memStat(): String {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) shr 20
        val javaMax = rt.maxMemory() shr 20
        val nativeUsed = android.os.Debug.getNativeHeapAllocatedSize() shr 20
        val nativeSize = android.os.Debug.getNativeHeapSize() shr 20
        val poolMb = (pool?.let { it.n * 4L * 5 } ?: 0L) shr 20
        return "java=${javaUsed}/${javaMax}MB native=${nativeUsed}/${nativeSize}MB pool=${poolMb}MB"
    }

    /** 懒加载 session：模型加载 ~1s，重复生成时复用。 */
    private fun ensureSession(ctx: Context): OrtSession = synchronized(sessionLock) {
        session ?: run {
            val model = ensureModelFile(ctx)
            val env = OrtEnvironment.getEnvironment()
            val cores = Runtime.getRuntime().availableProcessors()
            // 8 Gen 3 是 1+3+4。开满 8 线程时调度器会把一部分线程丢到小核，抖动大、总体更慢；
            // 甜点通常在 4~6（2026-10-23 用户提出调调度，先按 6 试）。
            val intraCpu = minOf(6, cores)

            fun build(nnapi: Boolean, intra: Int): OrtSession {
                val opts = OrtSession.SessionOptions().apply {
                    if (nnapi) addNnapi()
                    setIntraOpNumThreads(intra)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // **关掉 CPU Arena**（2026-10-26 实测定位）。
                    //
                    // DA V2 的 DPT 解码器最后几层在**全分辨率**上融合特征：
                    //   1022×1554 × 256ch × 4B ≈ 1.6GB，× 128ch ≈ 0.8GB
                    // 实测 `dumpsys meminfo` 的 Native Heap 从 32MB 涨到 **2016MB**（8 秒推理），
                    // 而 ORT 的 CPU Arena **在推理结束后不把内存还给系统**（session 常驻，
                    // arena 就常驻）⇒ 2GB 一直占着 ⇒ 系统换页 ⇒ 卡顿（用户反馈「生成完不缓解」）。
                    //
                    // 关掉 arena 后大块分配直接走 mmap，free 时 munmap 归还。
                    // 代价：每次推理多几次 malloc（实测影响很小，见日志耗时）。
                    setCPUArenaAllocator(false)
                }
                return env.createSession(model.absolutePath, opts)
            }

            // NNAPI 已关闭（2026-10-23 实测：nnapi=true 但耗时没有收益，白绕一层）
            val nnapi = false
            val sess = build(false, intraCpu)
            sessionNnapi = nnapi
            sessionIntra = intraCpu
            sess.also {
                session = it
                Log.i(
                    TAG,
                    "session 就绪：nnapi=$nnapi intra=${if (nnapi) 4 else intraCpu}/$cores "
                        + "opt=${OrtSession.SessionOptions.OptLevel.ALL_OPT}",
                )
            }
        }
    }

    /**
     * 把模型流式拷到 `files/models/`（**不要 readBytes**，会双份内存）。
     *
     * 用 `<name>.ok` 记录上次拷贝的字节数：长度对不上就重拷，避免模型换版本后读到旧文件。
     */
    private fun ensureModelFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val dst = File(dir, MODEL_NAME)
        val okMark = File(dir, "$MODEL_NAME.ok")
        if (dst.isFile && okMark.isFile && okMark.readText().trim().toLongOrNull() == dst.length()) {
            return dst
        }
        val tmp = File(dir, "$MODEL_NAME.tmp")
        ctx.assets.open(MODEL_ASSET).use { ins ->
            FileOutputStream(tmp).use { outs -> ins.copyTo(outs) }
        }
        if (dst.exists()) dst.delete()
        check(tmp.renameTo(dst)) { "模型落盘失败：${dst.absolutePath}" }
        okMark.writeText(dst.length().toString())
        Log.i(TAG, "模型已拷到 ${dst.absolutePath}（${dst.length()} 字节）")
        return dst
    }

    /** 图片内容 sha256 前 16 位（16 个 hex 字符）。 */
    private fun sha16(ctx: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = ctx.contentResolver.openInputStream(uri)
            ?: error("无法打开图片（openInputStream 返回 null，uri=$uri）")
        input.use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val read = ins.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        val hex = StringBuilder(64)
        for (b in md.digest()) hex.append("%02x".format(b))
        return hex.substring(0, 16)
    }

    /** 解码并按 [wantShort] 做整数倍降采样（`inSampleSize` 只认 2 的幂）。 */
    private fun decodeScaled(ctx: Context, uri: Uri, wantShort: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = ctx.contentResolver.openInputStream(uri)
            ?: error("无法打开图片（bounds openInputStream 返回 null，uri=$uri）")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        Log.i(TAG, "decode: uri=$uri 原图 ${bounds.outWidth}×${bounds.outHeight} 目标短边=$wantShort")
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片解码失败" }
        // 同 `SpatialLayers.decodeToSize`：跳采样只控内存（不做低通，本身就是混叠源），
        // 精确缩放交给 `SpatialResample`（有抗混叠）。`wantShort` 只用于日志对照。
        val sample = SpatialResample.decodeSample(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("图片解码失败（openInputStream 返回 null，uri=$uri）")
    }
}
