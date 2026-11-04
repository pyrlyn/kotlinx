#include "gen_demo.h"
#include <stdlib.h>
int32_t demo_add(int32_t a, int32_t b) { return a + b; }
double demo_mix(double x, int64_t k, bool neg) { double r = x * (double)k; return neg ? -r : r; }
void *demo_pair_new(int64_t a, int64_t b) { demo_pair *p = malloc(sizeof *p); p->a = a; p->b = b; return p; }
int64_t demo_pair_sum(const void *p) { const demo_pair *q = p; return q->a + q->b; }
demo_pair demo_by_value(void) { demo_pair p = {1, 2}; return p; }
int demo_printf(const char *fmt, ...) { (void)fmt; return 0; }
