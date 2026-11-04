#include "ffi_bench.h"
#include <stdlib.h>
#include <time.h>
int64_t fb_add(int64_t acc, int64_t x) { return acc + (x ^ 3); }
double fb_scale(double acc, double k) { return acc * k + 1.0; }
int64_t fb_now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (int64_t)t.tv_sec * 1000000000 + t.tv_nsec; }
void *fb_big_new(void) { fb_big *b = malloc(sizeof *b); for (int i = 0; i < 32; i++) b->v[i] = i * 7; return b; }
int64_t fb_big_sum(void *p, int64_t salt) { fb_big *b = p; int64_t s = salt; for (int i = 0; i < 32; i++) s += b->v[i]; return s; }
