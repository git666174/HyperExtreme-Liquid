/* 金字塔 + 双向 NNF + EM 投票。
 * 与上游 younesse-cv/PatchMatch 的 inpaint.c 同构：
 *   - 每个 level: iterEM = 1+2*level 次 EM，iterNNF = min(7, 1+level) 趟最小化；
 *   - 两张 NNF：source→target（完整性）、target→source（一致性），投票求和；
 *   - 最后一级 EM 直接放大到上一层尺寸，投票的**像素**取自全分辨率源图（因此细节不掉）。
 *
 * 内存所有权（上游整条链都在泄漏，手机上会 OOM，这里定死规则）：
 *   run_em() 拥有进来时的 target，替换时立即释放；返回最后一次 newtarget。
 *   调用方只保留返回值，最后统一释放。 */
#include "pm.h"
#include <time.h>
PmProf pm_prof;
static inline double pnow(void){ struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return ts.tv_sec*1000.0 + ts.tv_nsec/1e6; }

typedef struct {
    PmImage **pyr;
    int npyr;
    int radius;
    int threads;
} PmCtx;

/* patch 内是否含洞（含洞的 patch 不许当样本） */
static int contains_masked(const PmImage *im, int x, int y, int S)
{
    int W = im->w, H = im->h;
    for (int dy = -S; dy <= S; dy++) {
        int ys = y + dy;
        if (ys < 0 || ys >= W) continue;
        for (int dx = -S; dx <= S; dx++) {
            int xs = x + dx;
            if (xs < 0 || xs >= H) continue;
            if (im->mask[xs * W + ys]) return 1;
        }
    }
    return 0;
}

static void weighted_copy(const PmImage *src, int xs, int ys, float *vote, int VW,
                          int xd, int yd, float w)
{
    int W = src->w, H = src->h;
    if (xs < 0 || xs >= H || ys < 0 || ys >= W) return;
    if (src->mask[xs * W + ys]) return;
    float *v = vote + ((size_t)xd * VW + yd) * 4;
    const uint8_t *p = src->rgb + ((size_t)xs * W + ys) * 3;
    v[0] += w * p[0];
    v[1] += w * p[1];
    v[2] += w * p[2];
    v[3] += w;
}

static void estep(PmNNF *nnf, int srcToTgt, float *vote, int VW, const PmImage *source, int upscale)
{
    int H = nnf->input->h, W = nnf->input->w;
    int Ho = nnf->output->h, Wo = nnf->output->w;
    int R = nnf->S;
    for (int x = 0; x < H; ++x) {
        for (int y = 0; y < W; ++y) {
            const int *fp = nnf->field + ((size_t)x * nnf->fw + y) * 3;
            int xp = fp[0], yp = fp[1], dp = fp[2];
            if (dp < 0) dp = 0;
            if (dp > PM_DSCALE) dp = PM_DSCALE;
            float w = pm_sim_table ? pm_sim_table[dp] : 0.f;
            for (int dy = -R; dy <= R; ++dy) {
                for (int dx = -R; dx <= R; ++dx) {
                    int xs, ys, xt, yt;
                    if (srcToTgt) { xs = x + dx; ys = y + dy; xt = xp + dx; yt = yp + dy; }
                    else          { xs = xp + dx; ys = yp + dy; xt = x + dx;  yt = y + dy; }
                    if (xs < 0 || xs >= H) continue;
                    if (ys < 0 || ys >= W) continue;
                    if (xt < 0 || xt >= Ho) continue;
                    if (yt < 0 || yt >= Wo) continue;
                    if (upscale) {
                        weighted_copy(source, 2 * xs,     2 * ys,     vote, VW, 2 * xt,     2 * yt,     w);
                        weighted_copy(source, 2 * xs + 1, 2 * ys,     vote, VW, 2 * xt + 1, 2 * yt,     w);
                        weighted_copy(source, 2 * xs,     2 * ys + 1, vote, VW, 2 * xt,     2 * yt + 1, w);
                        weighted_copy(source, 2 * xs + 1, 2 * ys + 1, vote, VW, 2 * xt + 1, 2 * yt + 1, w);
                    } else {
                        weighted_copy(source, xs, ys, vote, VW, xt, yt, w);
                    }
                }
            }
        }
    }
}

static void mstep(PmImage *target, const float *vote)
{
    int W = target->w, H = target->h;
    for (int x = 0; x < H; ++x) {
        for (int y = 0; y < W; ++y) {
            const float *v = vote + ((size_t)x * W + y) * 4;
            if (v[3] <= 0.f) continue;
            uint8_t *p = target->rgb + ((size_t)x * W + y) * 3;
            p[0] = (uint8_t)(v[0] / v[3] + 0.5f);
            p[1] = (uint8_t)(v[1] / v[3] + 0.5f);
            p[2] = (uint8_t)(v[2] / v[3] + 0.5f);
            target->mask[x * W + y] = 0;
        }
    }
}

/* 拥有进来时的 target；返回最后一次重建的 newtarget（调用方接手） */
static PmImage *run_em(PmCtx *imp, PmNNF *nnfA, PmNNF *nnfB, int level)
{
    int iterEM = 1 + 2 * level;
    int iterNNF = (1 + level > 7) ? 7 : (1 + level);

    PmImage *source = imp->pyr[level];
    PmImage *target = nnfA->output;
    PmImage *newtarget = NULL;

    for (int emloop = 1; emloop <= iterEM; emloop++) {
        double _t;
        if (newtarget) {
            nnfA->output = newtarget;
            nnfB->input = newtarget;
            pm_image_free(target); /* 上一个 target 归本函数所有 */
            target = newtarget;
            newtarget = NULL;
        }

        /* 把「不含洞的 patch」钉成 identity（dp=0 ⇒ 最小化跳过它，投票也只投自己） */
        _t = pnow();
        int H = source->h, W = source->w;
        for (int x = 0; x < H; ++x) {
            for (int y = 0; y < W; ++y) {
                if (contains_masked(source, x, y, imp->radius)) continue;
                int *fa = nnfA->field + ((size_t)x * nnfA->fw + y) * 3;
                fa[0] = x; fa[1] = y; fa[2] = 0;
                int *fb = nnfB->field + ((size_t)x * nnfB->fw + y) * 3;
                fb[0] = x; fb[1] = y; fb[2] = 0;
            }
        }

        pm_prof.cons += pnow() - _t;
        _t = pnow();
        pm_nnf_minimize(nnfA, iterNNF, imp->threads, source);
        pm_nnf_minimize(nnfB, iterNNF, imp->threads, source);
        pm_prof.minimize += pnow() - _t;

        _t = pnow();
        int upscaled;
        PmImage *newsource;
        if (level >= 1 && emloop == iterEM) {
            /* 最后一轮直接搭上一层的尺寸：像素用全分辨率源，细节不被粗层糊掉 */
            newsource = imp->pyr[level - 1];
            newtarget = pm_image_upscale(target, newsource->w, newsource->h);
            upscaled = 1;
        } else {
            newsource = imp->pyr[level];
            newtarget = pm_image_clone(target);
            upscaled = 0;
        }
        pm_prof.upscale += pnow() - _t;
        if (!newtarget) { pm_image_free(target); return NULL; }

        int VW = newtarget->w;
        float *vote = (float *)calloc((size_t)newtarget->w * newtarget->h * 4 + 8, sizeof(float));
        if (!vote) { pm_image_free(newtarget); pm_image_free(target); return NULL; }

        _t = pnow();
        estep(nnfA, 1, vote, VW, newsource, upscaled);
        estep(nnfB, 0, vote, VW, newsource, upscaled);
        pm_prof.estep += pnow() - _t;
        _t = pnow();
        mstep(newtarget, vote);
        pm_prof.mstep += pnow() - _t;
        free(vote);
    }
    if (!newtarget) return NULL;
    pm_image_free(target);
    /* 旧 target 一释放，nnfB->input / nnfA->output 就成了野指针，必须同步指向新对象 */
    nnfA->output = newtarget;
    nnfB->input = newtarget;
    return newtarget;
}

int pm_inpaint(uint8_t *rgb, int w, int h, uint8_t *hole, int radius, int threads)
{
    if (!rgb || !hole || w < 8 || h < 8) return -1;
    if (radius < 1) radius = 1;
    if (threads < 1) threads = 1;

    PmCtx ctx;
    ctx.radius = radius;
    ctx.threads = threads;
    ctx.npyr = 0;
    ctx.pyr = (PmImage **)calloc(PM_MAX_PYR, sizeof(PmImage *));
    if (!ctx.pyr) return -1;

    PmImage *init = pm_image_new(w, h);
    if (!init) { free(ctx.pyr); return -1; }
    memcpy(init->rgb, rgb, (size_t)w * h * 3);
    memcpy(init->mask, hole, (size_t)w * h);
    ctx.pyr[ctx.npyr++] = init;

    /* 与上游一致：降到边长不大于 patch 半径为止 */
    while (ctx.npyr < PM_MAX_PYR &&
           ctx.pyr[ctx.npyr - 1]->w > radius && ctx.pyr[ctx.npyr - 1]->h > radius) {
        PmImage *d = pm_image_downsample(ctx.pyr[ctx.npyr - 1]);
        if (!d) break;
        ctx.pyr[ctx.npyr++] = d;
    }

    int maxlevel = ctx.npyr;
    PmNNF *nnfA = NULL, *nnfB = NULL;
    PmImage *cur = NULL;    /* 上一层交上来的 target（本层 run_em 接手所有权） */
    PmImage *result = NULL;

    for (int level = maxlevel - 1; level > 0; level--) {
        PmImage *source = ctx.pyr[level];
        if (level == maxlevel - 1) {
            /* 最粗层：target = source 副本，且视为"全已知"，像素靠投票长出来 */
            PmImage *target = pm_image_clone(source);
            if (!target) break;
            memset(target->mask, 0, (size_t)target->w * target->h);
            nnfA = pm_nnf_new(source, target, radius);
            nnfB = pm_nnf_new(target, source, radius);
            if (!nnfA || !nnfB) { pm_image_free(target); break; }
            pm_seed_rng(0x1234567u);
            pm_nnf_randomize(nnfA);
            pm_seed_rng(0x7654321u);
            pm_nnf_randomize(nnfB);
            cur = target;
        } else {
            if (!cur) break;
            PmNNF *na = pm_nnf_new(source, cur, radius);
            PmNNF *nb = pm_nnf_new(cur, source, radius);
            if (!na || !nb) { pm_nnf_free(na); pm_nnf_free(nb); break; }
            pm_nnf_from(na, nnfA);
            pm_nnf_from(nb, nnfB);
            pm_nnf_free(nnfA);
            pm_nnf_free(nnfB);
            nnfA = na;
            nnfB = nb;
        }
        PmImage *out = run_em(&ctx, nnfA, nnfB, level);
        if (!out) { cur = NULL; break; }
        cur = out;
        result = out;
    }

    if (result) memcpy(rgb, result->rgb, (size_t)w * h * 3);
    int rc = result ? 0 : -1;

    for (int i = 0; i < ctx.npyr; i++) pm_image_free(ctx.pyr[i]);
    free(ctx.pyr);
    pm_nnf_free(nnfA);
    pm_nnf_free(nnfB);
    pm_image_free(result);
    return rc;
}
