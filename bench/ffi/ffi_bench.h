// Shared C side of the FFI benchmark: the same object file is linked into KotlinX and Kotlin/Native.
#include <stdint.h>
int64_t fb_add(int64_t acc, int64_t x);
double fb_scale(double acc, double k);
int64_t fb_now_ns(void);
// Large struct (256 bytes), passed by pointer.
typedef struct { int64_t v[32]; } fb_big;
void *fb_big_new(void);
int64_t fb_big_sum(void *p, int64_t salt);
