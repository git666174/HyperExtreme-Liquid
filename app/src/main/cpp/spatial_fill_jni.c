/* JNI 入口：把 Kotlin 的 IntArray(ARGB) + ByteArray(mask) 喂给 PatchMatch 填充。
 *
 * 为什么走 C：算法是随机 gather 密集的，瓶颈在内存带宽（PC 实测：扁平化只快 5%、
 * 8 线程只到 1.5×）。GL 加速在手机上抢同一根 LPDDR5X，加上 PatchMatch 传播天生顺序扫描
 * （GPU 得改成 tile 内共享内存传播），收益不到 2 倍 —— 不值得。
 * 真正的杠杆：patch 半径 1、去掉上游为 GUI 做的整幅 resize、只跑洞⊕R、按行多线程。
 */
#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "patchmatch/pm.h"

#define TAG "HX-SpatialFill"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define PM_DEFAULT_RADIUS 1
#define PM_DEFAULT_THREADS 4

static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}

/* 返回耗时（ms）；失败返回 -1。px 原地修改。 */
JNIEXPORT jlong JNICALL
Java_com_frederic22_hyperextreme_spatial_SpatialInpaint_nativeFill(
        JNIEnv *env, jobject thiz, jintArray jPx, jbyteArray jHole, jint w, jint h,
        jint radius, jint threads)
{
    (void)thiz;
    if (!jPx || !jHole || w <= 0 || h <= 0) return -1;
    jsize n = (jsize)((int64_t)w * h);
    if ((*env)->GetArrayLength(env, jPx) < n) return -1;
    if ((*env)->GetArrayLength(env, jHole) < n) return -1;
    if (radius <= 0) radius = PM_DEFAULT_RADIUS;
    if (threads <= 0) threads = PM_DEFAULT_THREADS;

    jint *px = (*env)->GetIntArrayElements(env, jPx, NULL);
    jbyte *hole = (*env)->GetByteArrayElements(env, jHole, NULL);
    if (!px || !hole) {
        if (px) (*env)->ReleaseIntArrayElements(env, jPx, px, JNI_ABORT);
        if (hole) (*env)->ReleaseByteArrayElements(env, jHole, hole, JNI_ABORT);
        LOGE("GetArrayElements failed");
        return -1;
    }

    size_t bytes = (size_t)n * 3;
    uint8_t *rgb = (uint8_t *)malloc(bytes);
    uint8_t *mask = (uint8_t *)malloc((size_t)n);
    if (!rgb || !mask) {
        free(rgb);
        free(mask);
        (*env)->ReleaseIntArrayElements(env, jPx, px, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, jHole, hole, JNI_ABORT);
        LOGE("OOM %zu + %d", bytes, (int)n);
        return -1;
    }

    long holes = 0;
    for (jsize i = 0; i < n; i++) {
        jint v = px[i];
        rgb[(size_t)i * 3 + 0] = (uint8_t)((v >> 16) & 0xFF);
        rgb[(size_t)i * 3 + 1] = (uint8_t)((v >> 8) & 0xFF);
        rgb[(size_t)i * 3 + 2] = (uint8_t)(v & 0xFF);
        uint8_t m = ((hole[i] & 0xFF) >= 128) ? 1 : 0;
        mask[i] = m;
        holes += m;
    }

    double t0 = now_ms();
    int rc = pm_inpaint(rgb, w, h, mask, radius, threads);
    double cost = now_ms() - t0;

    if (rc == 0) {
        for (jsize i = 0; i < n; i++) {
            jint a = px[i] & 0xFF000000;
            px[i] = a | ((jint)rgb[(size_t)i * 3 + 0] << 16) |
                    ((jint)rgb[(size_t)i * 3 + 1] << 8) | (jint)rgb[(size_t)i * 3 + 2];
        }
    }
    (*env)->ReleaseIntArrayElements(env, jPx, px, 0);
    (*env)->ReleaseByteArrayElements(env, jHole, hole, JNI_ABORT);

    LOGI("fill %dx%d hole=%ld(%.1f%%) r=%d th=%d -> %s  %.0fms", w, h, holes,
         100.0 * (double)holes / (double)n, radius, threads, rc == 0 ? "ok" : "FAIL", cost);

    free(rgb);
    free(mask);
    return rc == 0 ? (jlong)(cost + 0.5) : -1;
}
