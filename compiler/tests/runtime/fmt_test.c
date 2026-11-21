/* kxfmt.h (runtime.c's number formatting without printf) against the snprintf/strtod reference it replaced:
 * special values, powers of two and ten, subnormals and format boundaries, short decimals and random bit patterns,
 * for doubles and floats; and the integer digits against printf. Run by compiler/tests/runtime/run.sh. */
#include <float.h>
#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../runtime/kxfmt.h"

/* The formatting kxfmt.h replaced (runtime.c's fmt_fp before it), verbatim apart from the name. */
static void ref_fmt_fp(char *buf, size_t n, double d, int f32) {
    if (d != d) { snprintf(buf, n, "NaN"); return; }
    if (d == 1.0 / 0.0) { snprintf(buf, n, "Infinity"); return; }
    if (d == -1.0 / 0.0) { snprintf(buf, n, "-Infinity"); return; }
    if (d == 0) { snprintf(buf, n, (1.0 / d < 0) ? "-0.0" : "0.0"); return; }
    char e[40];
    for (int p = 0; p <= 16; p++) { snprintf(e, sizeof e, "%.*e", p, d); if (f32 ? (double)strtof(e, NULL) == d : strtod(e, NULL) == d) break; }
    char digits[24]; int nd = 0, neg = 0; const char *q = e;
    if (*q == '-') { neg = 1; q++; }
    for (; *q && *q != 'e'; q++) if (*q != '.') digits[nd++] = *q;
    int exp = atoi(q + 1);
    while (nd > 1 && digits[nd - 1] == '0') nd--;
    digits[nd] = 0;
    char *o = buf; size_t left = n;
#define PUT(...) do { int w = snprintf(o, left, __VA_ARGS__); o += w; left -= (size_t)w; } while (0)
    if (neg) PUT("-");
    double a = d < 0 ? -d : d;
    if (a >= 1e-3 && a < 1e7) {
        if (exp >= 0) {
            for (int i = 0; i <= exp; i++) PUT("%c", i < nd ? digits[i] : '0');
            PUT(".");
            if (nd > exp + 1) PUT("%s", digits + exp + 1); else PUT("0");
        } else {
            PUT("0.");
            for (int i = 0; i < -exp - 1; i++) PUT("0");
            PUT("%s", digits);
        }
    } else {
        PUT("%c.", digits[0]);
        if (nd > 1) PUT("%s", digits + 1); else PUT("0");
        PUT("E%d", exp);
    }
#undef PUT
}

static long checked, failed;
static void check(double d, int f32) {
    if (f32) d = (float)d;
    char want[64], got[64];
    ref_fmt_fp(want, sizeof want, d, f32);
    int n = kx_fmt_fp(got, d, f32);
    checked++;
    if (strcmp(want, got) != 0 || n != (int)strlen(got)) {
        if (failed++ < 20) printf("FAIL %s %.17g (bits %016" PRIx64 "): want %s got %s\n", f32 ? "float" : "double", d, *(uint64_t *)&d, want, got);
    }
}
static void both(double d) { check(d, 0); check(-d, 0); check(d, 1); check(-d, 1); }
static uint64_t rng = 0x9E3779B97F4A7C15ULL;
static uint64_t next(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return rng; }

int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 1000000;
    double specials[] = {0.0, 1.0 / 0.0, NAN, DBL_MIN, DBL_MAX, DBL_TRUE_MIN, FLT_MIN, FLT_MAX, FLT_TRUE_MIN, 1e-3, 1e7,
                         0.1, 0.2, 0.3, 1.0 / 3, 2.0 / 3, 5e-324, 1e23, 9007199254740993.0, 123456789.0, 9999999.999999998};
    for (size_t i = 0; i < sizeof specials / sizeof *specials; i++) {
        double d = specials[i]; both(d); both(nextafter(d, 0)); both(nextafter(d, 1.0 / 0.0));
    }
    for (int e = -1074; e <= 1023; e++) { double d = ldexp(1.0, e); both(d); both(nextafter(d, 0)); both(nextafter(d, 1.0 / 0.0)); }
    for (int e = -325; e <= 308; e++) { char s[16]; snprintf(s, sizeof s, "1e%d", e); double d = strtod(s, NULL); both(d); both(nextafter(d, 0)); both(nextafter(d, 1.0 / 0.0)); }
    for (long i = 0; i < 100000; i++) { /* short decimals: k / 10^j */
        char s[40]; snprintf(s, sizeof s, "%" PRIu64 "e%d", next() % 100000000, (int)(next() % 40) - 20); both(strtod(s, NULL));
    }
    for (long i = 0; i < 100000; i++) both((double)(int64_t)(next() >> (next() % 64)));
    for (long i = 0; i < n; i++) {
        uint64_t b = next(); double d; memcpy(&d, &b, 8); if (d == d) check(d, 0);
        uint32_t fb = (uint32_t)next(); float f; memcpy(&f, &fb, 4); if (f == f) check(f, 1);
    }
    for (long i = 0; i < 200000; i++) { /* integers */
        int64_t v = (int64_t)next() >> (next() % 64); char want[32], got[32];
        snprintf(want, sizeof want, "%" PRId64, v); int k = kx_i64_dec(got, v); checked++;
        if (strcmp(want, got) || k != (int)strlen(got)) { if (failed++ < 20) printf("FAIL i64 want %s got %s\n", want, got); }
        snprintf(want, sizeof want, "%" PRIu64, (uint64_t)v); k = kx_u64_dec(got, (uint64_t)v); checked++;
        if (strcmp(want, got) || k != (int)strlen(got)) { if (failed++ < 20) printf("FAIL u64 want %s got %s\n", want, got); }
    }
    { char got[32]; kx_i64_dec(got, INT64_MIN); checked++; if (strcmp(got, "-9223372036854775808")) { failed++; printf("FAIL INT64_MIN %s\n", got); } }
    printf("%s fmt_test: %ld checked, %ld failed\n", failed ? "FAIL" : "PASS", checked, failed);
    return failed != 0;
}
