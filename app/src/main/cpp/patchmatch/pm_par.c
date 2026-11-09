/* 极简并行 for：按行切块，每块一条线程，调用线程自己也干一块。
 * 只在「大图」时启用；金字塔粗层图很小，起线程的代价比算的还大。 */
#include "pm.h"

#include <pthread.h>

#define PM_PAR_MIN_PIXELS 120000

typedef struct {
    void (*fn)(void *, int, int, int);
    void *ctx;
    int idx, x0, x1;
} Band;

static void *band_main(void *arg)
{
    Band *b = (Band *)arg;
    b->fn(b->ctx, b->idx, b->x0, b->x1);
    return NULL;
}

void pm_parallel_rows(int nrows, int threads, void (*fn)(void *, int, int, int), void *ctx)
{
    if (threads < 2 || nrows < 32) {
        fn(ctx, 0, 0, nrows);
        return;
    }
    if (threads > 8) threads = 8;

    pthread_t th[8];
    Band bands[8];
    int started = 0;
    int step = (nrows + threads - 1) / threads;

    for (int i = 1; i < threads; i++) {
        int x0 = i * step;
        if (x0 >= nrows) break;
        int x1 = x0 + step;
        if (x1 > nrows) x1 = nrows;
        bands[started].fn = fn;
        bands[started].ctx = ctx;
        bands[started].idx = i;
        bands[started].x0 = x0;
        bands[started].x1 = x1;
        if (pthread_create(&th[started], NULL, band_main, &bands[started]) != 0) {
            /* 起不来就自己算这一块，别丢工作 */
            fn(ctx, i, x0, x1);
        } else {
            started++;
        }
    }
    /* 主线程干第一块 */
    int mainEnd = step < nrows ? step : nrows;
    fn(ctx, 0, 0, mainEnd);
    for (int i = 0; i < started; i++) pthread_join(th[i], NULL);
}
