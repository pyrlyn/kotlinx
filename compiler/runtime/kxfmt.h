/* Number formatting for runtime.c without printf or strtod (included by runtime.c; not --target=js, whose
 * runtime.js formats with JS numbers). printf would link musl's printf_core and, on wasm32, 128-bit soft-float for
 * long double: about 17 KB of every wasm module. Tested against snprintf/strtod by compiler/tests/runtime/fmt_test.c.
 *
 * kx_fmt_fp produces the digits the old snprintf("%.*e", p) / strtod loop did: the first precision p = 0..16 whose
 * correctly rounded (half to even) decimal reads back as the same double (or float), found with exact big-number
 * arithmetic on the value and the two midpoints to its neighbours. */
#ifndef KXFMT_H
#define KXFMT_H
#include <stdint.h>
#include <string.h>

/* Decimal digits of v and a NUL into b (>= 21 bytes); returns the count. */
static int kx_u64_dec(char *b, uint64_t v) {
    char t[20]; int n = 0;
    do { t[n++] = (char)('0' + v % 10); v /= 10; } while (v);
    for (int i = 0; i < n; i++) b[i] = t[n - 1 - i];
    b[n] = 0; return n;
}
static int kx_i64_dec(char *b, int64_t v) {
    if (v >= 0) return kx_u64_dec(b, (uint64_t)v);
    b[0] = '-'; return 1 + kx_u64_dec(b + 1, 0 - (uint64_t)v);
}

/* Exact decimal of m * 2^e: digits (no leading zeros, m > 0) in d, returns the count; *x = decimal exponent of the
 * first digit. Base-1e9 limbs; m < 2^55 and -1077 <= e <= 971 need at most 86 limbs (774 digits). */
#define KXF_LIMBS 90
static int kxf_exact(uint64_t m, int e, char *d, int *x) {
    uint32_t l[KXF_LIMBS]; int n = 0;
    while (m) { l[n++] = (uint32_t)(m % 1000000000u); m /= 1000000000u; }
    int k = e >= 0 ? e : -e; /* multiply by 2^e, or by 5^-e and divide by 10^-e (shift of the decimal point) */
    while (k > 0) {
        int s = k > 13 ? 13 : k; k -= s;
        uint64_t f = 1, carry = 0;
        for (int i = 0; i < s; i++) f *= e >= 0 ? 2 : 5;
        for (int i = 0; i < n; i++) { uint64_t t = (uint64_t)l[i] * f + carry; l[i] = (uint32_t)(t % 1000000000u); carry = t / 1000000000u; }
        while (carry) { l[n++] = (uint32_t)(carry % 1000000000u); carry /= 1000000000u; }
    }
    int nd = kx_u64_dec(d, l[n - 1]);
    for (int i = n - 2; i >= 0; i--) { uint32_t v = l[i]; for (int j = 8; j >= 0; j--) { d[nd + j] = (char)('0' + v % 10); v /= 10; } nd += 9; }
    *x = nd - 1 - (e < 0 ? -e : 0);
    return nd;
}
/* Compares a = 0.a[0..na) * 10^(xa+1) with b likewise (no leading zeros): <0, 0, >0. */
static int kxf_cmp(const char *a, int na, int xa, const char *b, int nb, int xb) {
    if (xa != xb) return xa < xb ? -1 : 1;
    for (int i = 0; i < na || i < nb; i++) {
        char p = i < na ? a[i] : '0', q = i < nb ? b[i] : '0';
        if (p != q) return p < q ? -1 : 1;
    }
    return 0;
}
/* Kotlin-style Double formatting: shortest round-trip digits; plain notation for 1e-3 <= |d| < 1e7, otherwise
 * computerized scientific notation (e.g. 3.3358775E8, 1.0E-4), always with a '.'. f32: d is a float's value and
 * the digits must read back as that float. Writes at most 26 bytes with the NUL; returns the length. */
static int kx_fmt_fp(char *buf, double d, int f32) {
    char *o = buf;
    if (d != d) { memcpy(buf, "NaN", 4); return 3; }
    uint64_t bits; memcpy(&bits, &d, 8);
    int neg = (int)(bits >> 63);
    if (d == 1.0 / 0.0 || d == -1.0 / 0.0) { const char *s = neg ? "-Infinity" : "Infinity"; size_t n = strlen(s); memcpy(buf, s, n + 1); return (int)n; }
    if (d == 0) { const char *s = neg ? "-0.0" : "0.0"; memcpy(buf, s, strlen(s) + 1); return (int)strlen(s); }
    /* d = m * 2^e with the neighbours of the target format (double: 53-bit, float: 24-bit significand). */
    uint64_t m; int e, lowerTight;
    if (f32) {
        float f = (float)d; uint32_t fb; memcpy(&fb, &f, 4);
        int be = (int)((fb >> 23) & 0xff); m = fb & 0x7fffff;
        if (be) { m |= 0x800000; e = be - 150; } else e = -149;
        lowerTight = m == 0x800000 && be > 1;
    } else {
        int be = (int)((bits >> 52) & 0x7ff); m = bits & 0xfffffffffffffULL;
        if (be) { m |= 1ULL << 52; e = be - 1075; } else e = -1074;
        lowerTight = m == (1ULL << 52) && be > 1;
    }
    /* exact value, and the midpoints to the neighbours below (closer at a power of two) and above */
    char v[800], lo[800], hi[800];
    int xv, xl, xh;
    int nv = kxf_exact(m, e, v, &xv);
    int nl = lowerTight ? kxf_exact(4 * m - 1, e - 2, lo, &xl) : kxf_exact(2 * m - 1, e - 1, lo, &xl);
    int nh = kxf_exact(2 * m + 1, e - 1, hi, &xh);
    int even = (m & 1) == 0; /* round-to-nearest-even: a midpoint reads back as d iff d's significand is even */
    char digits[24]; int nd = 0, exp = xv;
    for (int p = 0; p <= 16; p++) {
        int keep = p + 1; exp = xv;
        if (keep >= nv) { memcpy(digits, v, (size_t)nv); for (nd = nv; nd < keep; nd++) digits[nd] = '0'; }
        else {
            memcpy(digits, v, (size_t)keep); nd = keep;
            int up = v[keep] > '5';
            if (v[keep] == '5') { up = 0; for (int i = keep + 1; i < nv; i++) if (v[i] != '0') { up = 1; break; }
                                  if (!up) up = (digits[keep - 1] - '0') & 1; }
            if (up) {
                int i = keep - 1;
                while (i >= 0 && digits[i] == '9') digits[i--] = '0';
                if (i >= 0) digits[i]++; else { digits[0] = '1'; for (int j = 1; j < keep; j++) digits[j] = '0'; exp++; }
            }
        }
        int cl = kxf_cmp(digits, nd, exp, lo, nl, xl), ch = kxf_cmp(digits, nd, exp, hi, nh, xh);
        if ((cl > 0 || (cl == 0 && even)) && (ch < 0 || (ch == 0 && even))) break;
    }
    while (nd > 1 && digits[nd - 1] == '0') nd--;
    if (neg) *o++ = '-';
    double a = d < 0 ? -d : d;
    if (a >= 1e-3 && a < 1e7) {
        if (exp >= 0) {
            for (int i = 0; i <= exp; i++) *o++ = i < nd ? digits[i] : '0';
            *o++ = '.';
            if (nd > exp + 1) { memcpy(o, digits + exp + 1, (size_t)(nd - exp - 1)); o += nd - exp - 1; } else *o++ = '0';
        } else {
            *o++ = '0'; *o++ = '.';
            for (int i = 0; i < -exp - 1; i++) *o++ = '0';
            memcpy(o, digits, (size_t)nd); o += nd;
        }
    } else {
        *o++ = digits[0]; *o++ = '.';
        if (nd > 1) { memcpy(o, digits + 1, (size_t)(nd - 1)); o += nd - 1; } else *o++ = '0';
        *o++ = 'E'; o += kx_i64_dec(o, exp);
    }
    *o = 0;
    return (int)(o - buf);
}
#endif
