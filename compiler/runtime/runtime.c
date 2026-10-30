// Minimal runtime for KotlinX compiler (newkotlinc). Linked into every program by clang.
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "kxgc.h" /* memory manager interface; implementation in compiler/gc/<name>/gc.c (newkotlinc --gc) */

// ---- leak check (newkotlinc --leak-check => -DRT_LEAKCHECK) ----------------
// Counts live runtime objects per kind; at exit prints a report to stderr and exits with 3
// if any object (class instance, StringBuilder, ArrayList, HashMap, array, string) is still live.
#ifdef RT_LEAKCHECK
enum { LK_OBJ, LK_SB, LK_LIST, LK_MAP, LK_ARR, LK_STR, LK_N };
static int64_t rt_live[LK_N], rt_total[LK_N], rt_peak[LK_N];
#define LK(k, d) do { rt_live[k] += (d); if ((d) > 0) { rt_total[k] += (d); if (rt_live[k] > rt_peak[k]) rt_peak[k] = rt_live[k]; } } while (0)
static const char *lk_names[LK_N] = {"object", "StringBuilder", "ArrayList", "HashMap", "array", "string"};
__attribute__((destructor)) static void rt_leak_report(void) {
    fflush(stdout);
    int64_t bad = 0;
    for (int k = 0; k < LK_N; k++) {
        fprintf(stderr, "leak-check: %s allocated=%lld peak=%lld live=%lld\n", lk_names[k], (long long)rt_total[k], (long long)rt_peak[k], (long long)rt_live[k]);
        bad += rt_live[k];
    }
    fprintf(stderr, "leak-check: %s\n", bad ? "FAIL" : "OK");
    if (bad) _exit(3);
}
void rt_leak_obj(int64_t d) { LK(LK_OBJ, d); }
#else
#define LK(k, d) ((void)0)
#endif

int64_t rt_nanos(void) { return (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW); }

/* At exit: final collection; KX_GC_STATS=1 prints the GC's statistics to stderr (bench/gc/micro.sh). */
void rt_exit_flush(void) {
    rt_gc_collect(); fflush(stdout);
    const char *e = getenv("KX_GC_STATS");
    if (e && *e && *e != '0') {
        KxGcStats st; rt_gc_stats(&st);
        fprintf(stderr, "gc-stats: gc=%s collections=%lld reclaimed=%lld pause_total=%.3f pause_max=%.3f\n", rt_gc_name(),
                (long long)st.collections, (long long)st.reclaimed, st.pause_ns_total / 1e6, st.pause_ns_max / 1e6);
    }
}

#ifdef RT_LEAKCHECK
int rt_leakcheck = 1;
#else
int rt_leakcheck = 0;
#endif
void rt_throw(const char *cls, const char *msg) {
    fflush(stdout);
    if (msg) fprintf(stderr, "Exception in thread \"main\" %s: %s\n", cls, msg);
    else fprintf(stderr, "Exception in thread \"main\" %s\n", cls);
    exit(1);
}
void rt_bad_step(int64_t step) {
    fflush(stdout);
    fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalArgumentException: Step must be positive, was: %" PRId64 ".\n", step);
    exit(1);
}
void rt_panic(const char *msg) {
    fflush(stdout);
    fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalStateException: %s\n", msg);
    exit(1);
}

// ---- printing -------------------------------------------------------------

/* Kotlin-style Double formatting: shortest round-trip digits; plain notation for 1e-3 <= |d| < 1e7,
   otherwise computerized scientific notation (e.g. 3.3358775E8, 1.0E-4), always with a '.'. */
static void fmt_fp(char *buf, size_t n, double d, int f32) {
    if (d != d) { snprintf(buf, n, "NaN"); return; }
    if (d == 1.0 / 0.0) { snprintf(buf, n, "Infinity"); return; }
    if (d == -1.0 / 0.0) { snprintf(buf, n, "-Infinity"); return; }
    if (d == 0) { snprintf(buf, n, (1.0 / d < 0) ? "-0.0" : "0.0"); return; }
    char e[40];
    for (int p = 0; p <= 16; p++) { snprintf(e, sizeof e, "%.*e", p, d); if (f32 ? (double)strtof(e, NULL) == d : strtod(e, NULL) == d) break; }
    /* e = [-]D[.DDD]e[+-]XX */
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

void rt_print_str(const char *s) { fputs(s ? s : "null", stdout); }
void rt_print_i64(int64_t v) { printf("%" PRId64, v); }
static void fmt_double(char *buf, size_t n, double d) { fmt_fp(buf, n, d, 0); }
void rt_print_f64(double d) { char b[64]; fmt_double(b, sizeof b, d); fputs(b, stdout); }
void rt_print_f32(float d) { char b[64]; fmt_fp(b, sizeof b, d, 1); fputs(b, stdout); }
void rt_print_u64(uint64_t v) { printf("%" PRIu64, v); }
/* Kotlin String.hashCode over the UTF-16 code units of a UTF-8 string. */
int32_t rt_str_hash(const char *s) {
    uint32_t h = 0;
    if (!s) return 0;
    const unsigned char *p = (const unsigned char *)s;
    while (*p) {
        uint32_t c = *p++;
        if (c >= 0xF0 && p[0] && p[1] && p[2]) { c = ((c & 7) << 18) | ((p[0] & 63) << 12) | ((p[1] & 63) << 6) | (p[2] & 63); p += 3; }
        else if (c >= 0xE0 && p[0] && p[1]) { c = ((c & 15) << 12) | ((p[0] & 63) << 6) | (p[1] & 63); p += 2; }
        else if (c >= 0xC0 && p[0]) { c = ((c & 31) << 6) | (p[0] & 63); p += 1; }
        if (c >= 0x10000) { c -= 0x10000; h = h * 31 + (0xD800 + (c >> 10)); h = h * 31 + (0xDC00 + (c & 0x3FF)); }
        else h = h * 31 + c;
    }
    return (int32_t)h;
}
int32_t rt_double_hash(double d) {
    uint64_t b;
    if (d != d) b = 0x7ff8000000000000ULL; else { if (d == 0.0 && !__builtin_signbit(d)) d = 0.0; memcpy(&b, &d, 8); }
    return (int32_t)(b ^ (b >> 32));
}
/* Kotlin String.compareTo: difference of the first differing chars, else of the lengths (bytewise for non-ASCII). */
int32_t rt_str_cmp(const char *a, const char *b) {
    const unsigned char *x = (const unsigned char *)(a ? a : ""), *y = (const unsigned char *)(b ? b : "");
    while (*x && *x == *y) { x++; y++; }
    if (*x && *y) return (int32_t)*x - (int32_t)*y;
    return (int32_t)strlen((const char *)x) - (int32_t)strlen((const char *)y);
}
int32_t rt_str_eq(const char *a, const char *b) { return a == b || (a && b && strcmp(a, b) == 0); }
void rt_print_bool(_Bool b) { fputs(b ? "true" : "false", stdout); }
void rt_newline(void) { putchar('\n'); }
static int utf8(char *b, int32_t c) {
    if (c < 0x80) { b[0] = (char)c; return 1; }
    if (c < 0x800) { b[0] = (char)(0xC0 | c >> 6); b[1] = (char)(0x80 | (c & 63)); return 2; }
    b[0] = (char)(0xE0 | c >> 12); b[1] = (char)(0x80 | ((c >> 6) & 63)); b[2] = (char)(0x80 | (c & 63)); return 3;
}
void rt_print_char(int32_t c) { char b[4]; int n = utf8(b, c); fwrite(b, 1, (size_t)n, stdout); }

// ---- StringBuilder (reference counted; also used for templates) ----------
// Strings are char* with an 8-byte refcount just before the chars (literals: immortal, 2^62).

typedef struct { int64_t rc; char *buf; size_t len, cap; } SB;

/* The buffer keeps 8 spare bytes in front, so rt_sb_done can hand it over as a string without copying. */
SB *rt_sb_new(void) { SB *s = malloc(sizeof *s); s->rc = 1; s->cap = 32; s->len = 0; s->buf = (char *)malloc(s->cap + 8) + 8; s->buf[0] = 0; LK(LK_SB, 1); return s; }
static inline void sb_reserve(SB *s, size_t n) {
    if (s->len + n + 1 > s->cap) { while (s->len + n + 1 > s->cap) s->cap *= 2; s->buf = (char *)realloc(s->buf - 8, s->cap + 8) + 8; }
}
static void sb_bytes(SB *s, const char *x, size_t n) { sb_reserve(s, n); memcpy(s->buf + s->len, x, n); s->len += n; s->buf[s->len] = 0; }
void rt_sb_str(SB *s, const char *x) { if (!x) x = "null"; sb_bytes(s, x, strlen(x)); }
void rt_sb_char(SB *s, int32_t c) { char b[4]; sb_bytes(s, b, (size_t)utf8(b, c)); }
void rt_sb_i64(SB *s, int64_t v) {
    char b[24]; int i = 24; uint64_t u = v < 0 ? 0 - (uint64_t)v : (uint64_t)v;
    do { b[--i] = (char)('0' + u % 10); u /= 10; } while (u);
    if (v < 0) b[--i] = '-';
    sb_bytes(s, b + i, (size_t)(24 - i));
}
void rt_sb_u64(SB *s, uint64_t v) { char b[24]; int i = 24; do { b[--i] = (char)('0' + v % 10); v /= 10; } while (v); sb_bytes(s, b + i, (size_t)(24 - i)); }
static char *str_alloc(size_t len) {
    int64_t *h = malloc(8 + len + 1);
    if (!h) rt_panic("OutOfMemoryError");
    *h = 1; LK(LK_STR, 1);
    return (char *)(h + 1);
}
char *rt_sb_tostring(SB *s) { char *r = str_alloc(s->len); memcpy(r, s->buf, s->len + 1); return r; }
char *rt_str_from_c(const char *c) { if (!c) return NULL; size_t n = strlen(c); char *r = str_alloc(n); memcpy(r, c, n + 1); return r; }
/* Strings and primitive arrays: refcount at p - 8. */
void rt_hdr_retain(void *p) { if (p) ((int64_t *)p)[-1]++; }
void rt_str_release(char *p) {
    if (!p) return;
    int64_t *h = (int64_t *)p - 1;
    if (--*h == 0) { LK(LK_STR, -1); free(h); }
}
int64_t rt_sb_len(SB *s) { return (int64_t)s->len; }
void rt_sb_release(SB *s) { if (!s || --s->rc > 0) return; LK(LK_SB, -1); free(s->buf - 8); free(s); }
void rt_sb_f64(SB *s, double d) { char b[64]; fmt_double(b, sizeof b, d); rt_sb_str(s, b); }
void rt_sb_f32(SB *s, float d) { char b[64]; fmt_fp(b, sizeof b, d, 1); rt_sb_str(s, b); }
void rt_sb_bool(SB *s, _Bool v) { rt_sb_str(s, v ? "true" : "false"); }
char *rt_sb_done(SB *s) { char *r = s->buf; ((int64_t *)r)[-1] = 1; LK(LK_SB, -1); LK(LK_STR, 1); free(s); return r; }

// ---- ArrayList (reference counted; elements stored as 64-bit slots) -------

typedef void (*release_fn)(void *);
typedef struct { int64_t rc; int64_t len, cap; int64_t *data; release_fn rel; } List;

List *rt_list_new(release_fn rel) {
    List *l = malloc(sizeof *l);
    l->rc = 1; l->len = 0; l->cap = 0; l->data = NULL; l->rel = rel; LK(LK_LIST, 1);
    return l;
}
void rt_list_add(List *l, int64_t v) {
    if (l->len == l->cap) { l->cap = l->cap ? l->cap * 2 : 10; l->data = rt_gc_mem_realloc(l->data, (size_t)l->cap * 8); }
    l->data[l->len++] = v;
}
/* Out-of-line, cold error path of the bounds check: keeps the hot accessors below tiny. */
__attribute__((noinline, cold, noreturn)) static void bounds_fail(List *l, int64_t i) {
    fflush(stdout);
    fprintf(stderr, "Exception in thread \"main\" java.lang.IndexOutOfBoundsException: Index %" PRId64 " out of bounds for length %" PRId64 "\n", i, l->len);
    exit(1);
}
static inline void bounds(List *l, int64_t i) {
    /* One unsigned compare covers i < 0 and i >= len. */
    if (__builtin_expect((uint64_t)i >= (uint64_t)l->len, 0)) bounds_fail(l, i);
}
/* Hot accessors (called from inner loops, e.g. bench refpass): aligned to a 64-byte cache line so that
 * their placement does not depend on the size of unrelated runtime/GC code in front of them
 * (PR #3 shifted rt_list_get across a cache-line boundary: refpass 131.6 -> 145 ms). */
#define RT_HOT __attribute__((hot, aligned(64)))
RT_HOT int64_t rt_list_get(List *l, int64_t i) { bounds(l, i); return l->data[i]; }
RT_HOT int64_t rt_list_set(List *l, int64_t i, int64_t v) { bounds(l, i); int64_t o = l->data[i]; l->data[i] = v; return o; }
RT_HOT int64_t rt_list_size(List *l) { return l->len; }
/* Removes and returns element i; ownership of a reference element moves to the caller. */
int64_t rt_list_remove_at(List *l, int64_t i) {
    bounds(l, i); int64_t o = l->data[i];
    memmove(l->data + i, l->data + i + 1, (size_t)(l->len - i - 1) * 8); l->len--;
    return o;
}
void rt_list_release(List *l) {
    if (!l || --l->rc > 0) return;
    LK(LK_LIST, -1);
    if (l->rel) for (int64_t i = 0; i < l->len; i++) l->rel((void *)(intptr_t)l->data[i]);
    rt_gc_mem_free(l->data); free(l);
}

// ---- primitive arrays: pointer to { int64 length; elements... }, refcount at -8 ----

void *rt_arr_new(int64_t n, int64_t elem) {
    if (n < 0) rt_panic("NegativeArraySizeException");
    int64_t *p = rt_gc_mem_calloc(16 + (size_t)n * (size_t)elem); /* { rc, length, elements } */
    if (!p) rt_panic("OutOfMemoryError");
    p[0] = 1; p[1] = n; LK(LK_ARR, 1);
    return p + 1;
}
void rt_arr_release(void *a) {
    if (!a) return;
    int64_t *h = (int64_t *)a - 1;
    if (--*h == 0) { LK(LK_ARR, -1); rt_gc_mem_free(h); }
}

void rt_oob(int64_t i, int64_t len) {
    fflush(stdout);
    fprintf(stderr, "Exception in thread \"main\" kotlin.ArrayIndexOutOfBoundsException: Index %" PRId64 " out of bounds for length %" PRId64 "\n", i, len);
    exit(1);
}

// ---- HashMap<primitive, primitive>: open addressing, linear probing --------

typedef struct { int64_t rc; int64_t size, cap; int64_t *keys, *vals; uint8_t *used; } Map;

static inline uint64_t map_hash(int64_t k) {
    uint64_t x = (uint64_t)k;
    x ^= x >> 33; x *= 0xff51afd7ed558ccdULL; x ^= x >> 33;
    return x;
}
Map *rt_map_new(void) {
    Map *m = malloc(sizeof *m);
    m->rc = 1; m->size = 0; m->cap = 16;
    m->keys = malloc(16 * 8); m->vals = rt_gc_mem_alloc(16 * 8); m->used = calloc(16, 1); LK(LK_MAP, 1);
    return m;
}
static inline int64_t map_find(Map *m, int64_t k) {
    uint64_t mask = (uint64_t)m->cap - 1, i = map_hash(k) & mask;
    while (m->used[i] && m->keys[i] != k) i = (i + 1) & mask;
    return (int64_t)i;
}
static void map_grow(Map *m) {
    Map old = *m;
    m->cap *= 2; m->size = 0;
    m->keys = malloc((size_t)m->cap * 8); m->vals = rt_gc_mem_alloc((size_t)m->cap * 8); m->used = calloc((size_t)m->cap, 1);
    for (int64_t i = 0; i < old.cap; i++) if (old.used[i]) {
        int64_t j = map_find(m, old.keys[i]);
        m->used[j] = 1; m->keys[j] = old.keys[i]; m->vals[j] = old.vals[i]; m->size++;
    }
    free(old.keys); rt_gc_mem_free(old.vals); free(old.used);
}
void rt_map_put(Map *m, int64_t k, int64_t v) {
    if ((m->size + 1) * 4 > m->cap * 3) map_grow(m);
    int64_t i = map_find(m, k);
    if (!m->used[i]) { m->used[i] = 1; m->keys[i] = k; m->size++; }
    m->vals[i] = v;
}
int32_t rt_map_contains(Map *m, int64_t k) { return m->used[map_find(m, k)]; }
int64_t rt_map_get_or(Map *m, int64_t k, int64_t d) { int64_t i = map_find(m, k); return m->used[i] ? m->vals[i] : d; }
int64_t rt_map_get(Map *m, int64_t k) {
    int64_t i = map_find(m, k);
    if (!m->used[i]) { fflush(stdout); fprintf(stderr, "Exception in thread \"main\" java.lang.NullPointerException: key %" PRId64 " not in map\n", k); exit(1); }
    return m->vals[i];
}
int64_t rt_map_size(Map *m) { return m->size; }
void rt_map_release(Map *m) { if (!m || --m->rc > 0) return; LK(LK_MAP, -1); free(m->keys); rt_gc_mem_free(m->vals); free(m->used); free(m); }

// Object allocation, reference counting slow paths and the cycle collector live in compiler/gc/ (kxgc.h).
