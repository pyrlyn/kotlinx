// Input header for the kxbindgen test (compiler/tests/ffi/gen.sh).
#include <stdint.h>
#include <stdbool.h>
#define DEMO_VERSION 3
#define DEMO_BIG 0x100000000LL
enum demo_mode { DEMO_FAST = 1, DEMO_SLOW = 4 };
typedef struct { int64_t a, b; } demo_pair;
int32_t demo_add(int32_t a, int32_t b);
double demo_mix(double x, int64_t k, bool neg);
void *demo_pair_new(int64_t a, int64_t b);
int64_t demo_pair_sum(const void *p);
demo_pair demo_by_value(void);
int demo_printf(const char *fmt, ...);
