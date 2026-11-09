/* NNF：初始化 / 距离 / 传播+随机搜索最小化。
 * 距离度量与上游一致：patch 内每像素取 颜色 / Gx / Gy 三项 SSD，按 9·255² 归一化。
 * 改动见 pm.h 顶部说明（float、指针直达、修 t_value 读错源）。 */
#include "pm.h"

#define FLD(n, a, b, k) ((n)->field[(((size_t)(a) * (n)->fw + (size_t)(b)) * 3) + (k)])

/* 线程私有 LCG：上游用全局 rand()，并行化后会互相踩 */
/* 随机数状态跟着「band」走，不用 __thread（DLL 里的 TLS 可能走 emutls，很慢） */
static unsigned int g_rng_seed = 0x9e3779b9u;

void pm_seed_rng(unsigned int seed)
{
    g_rng_seed = seed ? seed : 1u;
}

static inline unsigned int rnext(unsigned int *st)
{
    *st = *st * 1664525u + 1013904223u;
    return (*st >> 8);
}

/* 相似度查表：把 patch 距离（0..DSCALE）映射成投票权重。
 * 上游是 base[11] 折线插值（t=0 → 1.0，t≈0.04 → 0.38，t≥0.07 → 0）；
 * 这里保持一致，只是存成 float。 */
float *pm_sim_table = NULL;

static void init_sim(void)
{
    if (pm_sim_table) return;
    static const double base[11] = {1.0, 0.99, 0.96, 0.83, 0.38, 0.11,
                                    0.02, 0.005, 0.0006, 0.0001, 0};
    int len = PM_DSCALE + 1;
    pm_sim_table = (float *)calloc((size_t)len, sizeof(float));
    if (!pm_sim_table) return;
    for (int i = 0; i < len; i++) {
        double t = (double)i / len;
        int j = (int)(100 * t);
        int k = j + 1;
        double vj = (j < 11) ? base[j] : 0;
        double vk = (k < 11) ? base[k] : 0;
        pm_sim_table[i] = (float)(vj + (100 * t - j) * (vk - vj));
    }
}

static inline float sim_of(int dp)
{
    if (!pm_sim_table) return 0.f;
    if (dp < 0) dp = 0;
    if (dp > PM_DSCALE) dp = PM_DSCALE;
    return pm_sim_table[dp];
}

/* ---- patch 距离 ---- */
static inline int dist_patch(const PmImage *src, int xs, int ys, const PmImage *tgt,
                             int xt, int yt, int S)
{
    const int sw = src->w, sh = src->h;
    const int tw = tgt->w, th = tgt->h;
    const int ss = sw * 3, ts = tw * 3;
    const float ssdmax = 9.0f * 255.0f * 255.0f;
    float distance = 0.f, wsum = 0.f;

    for (int dy = -S; dy <= S; ++dy) {
        for (int dx = -S; dx <= S; ++dx) {
            int xks = xs + dx, yks = ys + dy;
            int xkt = xt + dx, ykt = yt + dy;
            wsum++;
            /* 越界或含洞都不许当样本 —— 与上游一致，罚 1 */
            if (xks < 1 || xks >= sh - 1) { distance++; continue; }
            if (yks < 1 || yks >= sw - 1) { distance++; continue; }
            if (src->mask[xks * sw + yks]) { distance++; continue; }
            if (xkt < 1 || xkt >= th - 1) { distance++; continue; }
            if (ykt < 1 || ykt >= tw - 1) { distance++; continue; }
            if (tgt->mask[xkt * tw + ykt]) { distance++; continue; }

            const uint8_t *sp = src->rgb + (size_t)xks * ss + (size_t)yks * 3;
            const uint8_t *tp = tgt->rgb + (size_t)xkt * ts + (size_t)ykt * 3;
            float ssd = 0.f;
            for (int band = 0; band < 3; ++band) {
                float dv = (float)sp[band] - (float)tp[band];
                /* 梯度（x=行方向、y=列方向），与上游 128+(a-b)/2 等价 */
                float dgx = ((float)(sp[ss + band] - sp[-ss + band]) -
                             (float)(tp[ts + band] - tp[-ts + band])) * 0.5f;
                float dgy = ((float)(sp[3 + band] - sp[-3 + band]) -
                             (float)(tp[3 + band] - tp[-3 + band])) * 0.5f;
                ssd += dv * dv + dgx * dgx + dgy * dgy;
            }
            distance += ssd / ssdmax;
        }
    }
    int res = (int)(PM_DSCALE * distance / wsum);
    if (res < 0) return 0;
    if (res > PM_DSCALE) return PM_DSCALE;
    return res;
}

/* ---- 构造 / 释放 ---- */
PmNNF *pm_nnf_new(PmImage *in, PmImage *out, int S)
{
    PmNNF *n = (PmNNF *)calloc(1, sizeof(PmNNF));
    if (!n) return NULL;
    n->input = in;
    n->output = out;
    n->S = S;
    n->fh = in->h;
    n->fw = in->w;
    n->field = (int *)calloc((size_t)n->fh * n->fw * 3 + 8, sizeof(int));
    if (!n->field) {
        free(n);
        return NULL;
    }
    return n;
}

void pm_nnf_free(PmNNF *n)
{
    if (!n) return;
    free(n->field);
    free(n);
}

/* 计算整个场的初始距离；全洞 patch 会得到 DSCALE，重试若干次找一个有效候选 */
static void nnf_init_dist(PmNNF *n)
{
    unsigned int st = g_rng_seed;
    for (int x = 0; x < n->fh; ++x) {
        for (int y = 0; y < n->fw; ++y) {
            int xp = FLD(n, x, y, 0), yp = FLD(n, x, y, 1);
            int d = dist_patch(n->input, x, y, n->output, xp, yp, n->S);
            int retry = 0;
            while (d == PM_DSCALE && retry < 20) {
                xp = (int)(rnext(&st) % (unsigned)n->output->h) + 1;
                yp = (int)(rnext(&st) % (unsigned)n->output->w) + 1;
                d = dist_patch(n->input, x, y, n->output, xp, yp, n->S);
                retry++;
            }
            FLD(n, x, y, 0) = xp;
            FLD(n, x, y, 1) = yp;
            FLD(n, x, y, 2) = d;
        }
    }
}

void pm_nnf_randomize(PmNNF *n)
{
    init_sim();
    unsigned int st = g_rng_seed;
    for (int x = 0; x < n->fh; ++x) {
        for (int y = 0; y < n->fw; ++y) {
            FLD(n, x, y, 0) = (int)(rnext(&st) % (unsigned)n->output->h) + 1;
            FLD(n, x, y, 1) = (int)(rnext(&st) % (unsigned)n->output->w) + 1;
            FLD(n, x, y, 2) = PM_DSCALE;
        }
    }
    nnf_init_dist(n);
}

void pm_nnf_from(PmNNF *n, const PmNNF *o)
{
    init_sim();
    int fx = n->fh / o->fh;
    int fy = n->fw / o->fw;
    if (fx < 1) fx = 1;
    if (fy < 1) fy = 1;
    for (int x = 0; x < n->fh; ++x) {
        int xlow = x / fx;
        if (xlow > o->fh - 1) xlow = o->fh - 1;
        for (int y = 0; y < n->fw; ++y) {
            int ylow = y / fy;
            if (ylow > o->fw - 1) ylow = o->fw - 1;
            FLD(n, x, y, 0) = FLD(o, xlow, ylow, 0) * fx;
            FLD(n, x, y, 1) = FLD(o, xlow, ylow, 1) * fy;
            FLD(n, x, y, 2) = PM_DSCALE;
        }
    }
    nnf_init_dist(n);
}

/* ---- 最小化 ---- */
static inline void minimize_link(PmNNF *n, int x, int y, int dir, unsigned int *st)
{
    int xp, yp, dp;
    int xpi, ypi;

    /* 向上/向下传播 */
    int xd = x - dir;
    if (xd >= 0 && xd < n->fh) {
        xp = FLD(n, xd, y, 0) + dir;
        yp = FLD(n, xd, y, 1);
        if (xp >= 0 && xp < n->output->h) {
            dp = dist_patch(n->input, x, y, n->output, xp, yp, n->S);
            if (dp < FLD(n, x, y, 2)) {
                FLD(n, x, y, 0) = xp;
                FLD(n, x, y, 1) = yp;
                FLD(n, x, y, 2) = dp;
            }
        }
    }
    /* 向左/向右传播 */
    int yd = y - dir;
    if (yd >= 0 && yd < n->fw) {
        xp = FLD(n, x, yd, 0);
        yp = FLD(n, x, yd, 1) + dir;
        if (yp >= 0 && yp < n->output->w) {
            dp = dist_patch(n->input, x, y, n->output, xp, yp, n->S);
            if (dp < FLD(n, x, y, 2)) {
                FLD(n, x, y, 0) = xp;
                FLD(n, x, y, 1) = yp;
                FLD(n, x, y, 2) = dp;
            }
        }
    }
    /* 随机搜索：半径逐次减半 */
    int wi = n->output->w;
    xpi = FLD(n, x, y, 0);
    ypi = FLD(n, x, y, 1);
    while (wi > 0) {
        int r1 = (int)(rnext(st) % (unsigned)(2 * wi)) - wi;
        int r2 = (int)(rnext(st) % (unsigned)(2 * wi)) - wi;
        xp = xpi + r1;
        yp = ypi + r2;
        if (xp < 0) xp = 0;
        if (xp > n->output->h - 1) xp = n->output->h - 1;
        if (yp < 0) yp = 0;
        if (yp > n->output->w - 1) yp = n->output->w - 1;
        dp = dist_patch(n->input, x, y, n->output, xp, yp, n->S);
        if (dp < FLD(n, x, y, 2)) {
            FLD(n, x, y, 0) = xp;
            FLD(n, x, y, 1) = yp;
            FLD(n, x, y, 2) = dp;
        }
        wi /= 2;
    }
}

typedef struct {
    PmNNF *n;
    int dir;
    const uint8_t *active;
    int W;
    unsigned int rng[8];
} ScanCtx;

static void scan_band(void *vp, int idx, int x0, int x1)
{
    ScanCtx *c = (ScanCtx *)vp;
    PmNNF *n = c->n;
    const int activeW = c->W;
    unsigned int st = c->rng[idx];
    if (c->dir > 0) {
        for (int x = x0; x < x1; ++x) {
            for (int y = 0; y < n->fw; ++y) {
                /* 已知像素 field 恒 identity、dist=0，本来就会被跳过；只跑洞⊕R */
                if (!c->active[(size_t)x * activeW + y]) continue;
                if (FLD(n, x, y, 2) > 0) minimize_link(n, x, y, +1, &st);
            }
        }
    } else {
        /* 反向扫描必须连 y 一起倒着走，否则传播拿到的都是上一趟的陈旧值（退化成 Jacobi） */
        for (int x = x1 - 1; x >= x0; --x) {
            for (int y = n->fw - 1; y >= 0; --y) {
                if (!c->active[(size_t)x * activeW + y]) continue;
                if (FLD(n, x, y, 2) > 0) minimize_link(n, x, y, -1, &st);
            }
        }
    }
    c->rng[idx] = st;
}

void pm_nnf_minimize(PmNNF *n, int pass, int threads, PmImage *maskRef)
{
    if (!maskRef) maskRef = n->input;
    pm_image_build_active(maskRef, n->S);
    ScanCtx ctx;
    ctx.n = n;
    ctx.active = maskRef->active;
    ctx.W = maskRef->w;
    for (int i = 0; i < 8; i++) ctx.rng[i] = g_rng_seed + (unsigned)i * 2654435761u;
    if (!ctx.active) return; /* 内存不够就放弃这一趟，别崩 */

    int par = (threads > 1 && (long)n->fw * n->fh >= 120000) ? threads : 1;
    for (int i = 0; i < pass; i++) {
        ctx.dir = +1;
        pm_parallel_rows(n->fh, par, scan_band, &ctx);
        ctx.dir = -1;
        pm_parallel_rows(n->fh, par, scan_band, &ctx);
    }
}
