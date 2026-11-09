/* 图像与金字塔基本操作：全部扁平缓冲，无 OpenCV。 */
#include "pm.h"

PmImage *pm_image_new(int w, int h)
{
    PmImage *im = (PmImage *)calloc(1, sizeof(PmImage));
    if (!im) return NULL;
    im->w = w;
    im->h = h;
    im->rgb = (uint8_t *)calloc((size_t)w * h * 3 + 8, 1);
    im->mask = (uint8_t *)calloc((size_t)w * h + 8, 1);
    if (!im->rgb || !im->mask) {
        pm_image_free(im);
        return NULL;
    }
    return im;
}

PmImage *pm_image_clone(const PmImage *s)
{
    if (!s) return NULL;
    PmImage *d = pm_image_new(s->w, s->h);
    if (!d) return NULL;
    memcpy(d->rgb, s->rgb, (size_t)s->w * s->h * 3);
    memcpy(d->mask, s->mask, (size_t)s->w * s->h);
    return d;
}

void pm_image_free(PmImage *im)
{
    if (!im) return;
    free(im->rgb);
    free(im->mask);
    free(im->active);
    free(im);
}

/* 1/2 下采样：6 抽头 {1,5,10,10,5,1} 可分离，洞像素不参与（权重归零后归一）。
 * 整块全洞 ⇒ 输出标记为洞、颜色 0。与上游一致。 */
PmImage *pm_image_downsample(const PmImage *s)
{
    static const int K[6] = {1, 5, 10, 10, 5, 1};
    int W = s->w, H = s->h;
    int nw = W / 2, nh = H / 2;
    if (nw < 1) nw = 1;
    if (nh < 1) nh = 1;
    PmImage *d = pm_image_new(nw, nh);
    if (!d) return NULL;

    for (int x = 0; x < H - 1; x += 2) {
        for (int y = 0; y < W - 1; y += 2) {
            int r = 0, g = 0, b = 0, m = 0, ksum = 0;
            for (int dy = -2; dy <= 3; ++dy) {
                int yk = y + dy;
                if (yk < 0 || yk >= W) continue;
                int ky = K[2 + dy];
                for (int dx = -2; dx <= 3; ++dx) {
                    int xk = x + dx;
                    if (xk < 0 || xk >= H) continue;
                    if (s->mask[xk * W + yk]) continue;
                    int k = K[2 + dx] * ky;
                    const uint8_t *p = s->rgb + ((size_t)xk * W + yk) * 3;
                    r += k * p[0];
                    g += k * p[1];
                    b += k * p[2];
                    ksum += k;
                    m++;
                }
            }
            int di = (x / 2) * nw + (y / 2);
            if (m != 0) {
                if (ksum > 0) { r /= ksum; g /= ksum; b /= ksum; }
                uint8_t *q = d->rgb + (size_t)di * 3;
                q[0] = (uint8_t)r;
                q[1] = (uint8_t)g;
                q[2] = (uint8_t)b;
                d->mask[di] = 0;
            } else {
                d->mask[di] = 1;
            }
        }
    }
    return d;
}

/* 最近邻放大（结构来自粗层，像素由上层原始源提供，见 pm_inpaint.c 的 upscaled 分支） */
PmImage *pm_image_upscale(const PmImage *s, int nw, int nh)
{
    PmImage *d = pm_image_new(nw, nh);
    if (!d) return NULL;
    int W = s->w, H = s->h;
    for (int x = 0; x < nh; x++) {
        int xs = (int)((long)x * H / nh);
        if (xs >= H) xs = H - 1;
        for (int y = 0; y < nw; y++) {
            int ys = (int)((long)y * W / nw);
            if (ys >= W) ys = W - 1;
            int di = x * nw + y;
            int si = xs * W + ys;
            if (!s->mask[si]) {
                memcpy(d->rgb + (size_t)di * 3, s->rgb + (size_t)si * 3, 3);
                d->mask[di] = 0;
            } else {
                d->mask[di] = 1;
            }
        }
    }
    return d;
}

/* active = mask 用 r 半径膨胀（可分离最大滤波，两趟 O(n)） */
void pm_image_build_active(PmImage *im, int r)
{
    if (im->active || r < 0) return;
    int W = im->w, H = im->h;
    uint8_t *tmp = (uint8_t *)calloc((size_t)W * H + 8, 1);
    im->active = (uint8_t *)calloc((size_t)W * H + 8, 1);
    if (!tmp || !im->active) {
        free(tmp);
        return;
    }
    /* 横向 */
    for (int x = 0; x < H; x++) {
        const uint8_t *row = im->mask + (size_t)x * W;
        uint8_t *out = tmp + (size_t)x * W;
        for (int y = 0; y < W; y++) {
            if (!row[y]) continue;
            int a = y - r < 0 ? 0 : y - r;
            int b = y + r >= W ? W - 1 : y + r;
            memset(out + a, 1, (size_t)(b - a + 1));
        }
    }
    /* 纵向 */
    for (int x = 0; x < H; x++) {
        uint8_t *out = im->active + (size_t)x * W;
        int a = x - r < 0 ? 0 : x - r;
        int b = x + r >= H ? H - 1 : x + r;
        for (int k = a; k <= b; k++)
            for (int y = 0; y < W; y++)
                if (tmp[(size_t)k * W + y]) out[y] = 1;
    }
    free(tmp);
}
