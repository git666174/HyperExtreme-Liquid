/*
 * PatchMatch 图像修复（C 移植版）
 *
 * 来源：younesse-cv/PatchMatch（PatchMatch 论文的 inpainting 实现，作者 Younesse ANDAM）。
 * 本移植保持算法结构不变（金字塔 + 双向 NNF + EM 投票），只做工程化改造：
 *
 *   1. 去掉 OpenCV/IplImage，改成扁平 RGB 缓冲 + 扁平 mask；
 *   2. patch 距离用 float、直接指针寻址（上游用 long double + pow(x,2)，慢两个数量级）；
 *   3. 修上游 bug：target 侧像素颜色原本误读 source；
 *   4. NNF field 用扁平数组（去掉每像素三跳指针）；
 *   5. 线程私有 LCG 替代全局 rand()；
 *   6. 删掉原仓库为 cvShowImage 实时显示进度而做的整幅 resize（每次 EM 迭代都跑，纯浪费）；
 *   7. **NNF 最小化只跑「洞 ⊕ patch 半径」**：已知像素 field 恒为 identity、且被 dp>0 跳过，
 *      传播经过它们只会得到自身（必然被拒），所以限制到洞⊕R 是精确等价，不是近似。
 *
 * 参数口径（2026-10-24 PC 端实测结论）：
 *   - patch 半径 **1**（3×3）比上游默认的 2 更好也更快；半径越大越会把结构抹平；
 *   - 投票取平均（EM）而不是 winner-take-all：后者细节更高但整片是水彩涂块。
 */
#ifndef PM_H
#define PM_H

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* 距离量化上限：patch 距离归一化后乘这个值变成整数，供相似度查表 */
#define PM_DSCALE 65535

/* 金字塔最多几层 */
#define PM_MAX_PYR 24

/* 投票权重查表（pm_nnf.c 里建），estep 直接下标取，省一次函数调用 */
extern float *pm_sim_table;

typedef struct {
    int w, h;
    uint8_t *rgb;    /* w*h*3，交错 RGB */
    uint8_t *mask;   /* w*h，1 = 洞 */
    uint8_t *active; /* w*h，mask 膨胀 radius 后的「活跃区」，惰性生成 */
} PmImage;

typedef struct {
    PmImage *input;
    PmImage *output;
    int S;       /* patch 半径 */
    int *field;  /* ((x*fw) + y)*3 -> {xp, yp, dist} */
    int fh, fw;
} PmNNF;

/* ---- image ---- */
PmImage *pm_image_new(int w, int h);
PmImage *pm_image_clone(const PmImage *s);
void pm_image_free(PmImage *im);
PmImage *pm_image_downsample(const PmImage *s);
PmImage *pm_image_upscale(const PmImage *s, int nw, int nh);
void pm_image_build_active(PmImage *im, int r);

/* ---- nnf ---- */
PmNNF *pm_nnf_new(PmImage *in, PmImage *out, int S);
void pm_nnf_free(PmNNF *n);
void pm_nnf_randomize(PmNNF *n);
void pm_nnf_from(PmNNF *n, const PmNNF *o);
/* maskRef：用哪张图的 mask 决定「洞⊕R」活跃区。nnfB 的 input 是 target（mask 全 0），
 * 必须显式传 source，否则活跃区为空、最小化整个被跳过。 */
void pm_nnf_minimize(PmNNF *n, int pass, int threads, PmImage *maskRef);
void pm_seed_rng(unsigned int seed);

/* ---- parallel ---- */
void pm_parallel_rows(int nrows, int threads, void (*fn)(void *, int, int, int), void *ctx);

/** 阶段计时（诊断用，见 pm_inpaint.c） */
typedef struct { double minimize, estep, mstep, cons, upscale; } PmProf;
extern PmProf pm_prof;

/* ---- 主入口：rgb 原地填洞，hole 1=洞 ---- */
int pm_inpaint(uint8_t *rgb, int w, int h, uint8_t *hole, int radius, int threads);

#endif /* PM_H */
