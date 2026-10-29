// Minimal runtime for Kotlin-X compiler (newkotlinc). Linked into every program by clang.
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

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
#else
#define LK(k, d) ((void)0)
#endif

int64_t rt_nanos(void) { return (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW); }

void rt_cc_collect(void);
void rt_exit_flush(void) { rt_cc_collect(); fflush(stdout); }

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
static void fmt_double(char *buf, size_t n, double d) {
    if (d != d) { snprintf(buf, n, "NaN"); return; }
    if (d == 1.0 / 0.0) { snprintf(buf, n, "Infinity"); return; }
    if (d == -1.0 / 0.0) { snprintf(buf, n, "-Infinity"); return; }
    if (d == 0) { snprintf(buf, n, (1.0 / d < 0) ? "-0.0" : "0.0"); return; }
    char e[40];
    for (int p = 0; p <= 16; p++) { snprintf(e, sizeof e, "%.*e", p, d); if (strtod(e, NULL) == d) break; }
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
void rt_print_f64(double d) { char b[64]; fmt_double(b, sizeof b, d); fputs(b, stdout); }
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
    if (l->len == l->cap) { l->cap = l->cap ? l->cap * 2 : 10; l->data = realloc(l->data, (size_t)l->cap * 8); }
    l->data[l->len++] = v;
}
static void bounds(List *l, int64_t i) {
    if (i < 0 || i >= l->len) {
        fflush(stdout);
        fprintf(stderr, "Exception in thread \"main\" java.lang.IndexOutOfBoundsException: Index %" PRId64 " out of bounds for length %" PRId64 "\n", i, l->len);
        exit(1);
    }
}
int64_t rt_list_get(List *l, int64_t i) { bounds(l, i); return l->data[i]; }
int64_t rt_list_set(List *l, int64_t i, int64_t v) { bounds(l, i); int64_t o = l->data[i]; l->data[i] = v; return o; }
int64_t rt_list_size(List *l) { return l->len; }
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
    free(l->data); free(l);
}

// ---- primitive arrays: pointer to { int64 length; elements... }, refcount at -8 ----

void *rt_arr_new(int64_t n, int64_t elem) {
    if (n < 0) rt_panic("NegativeArraySizeException");
    int64_t *p = calloc(1, 16 + (size_t)n * (size_t)elem); /* { rc, length, elements } */
    if (!p) rt_panic("OutOfMemoryError");
    p[0] = 1; p[1] = n; LK(LK_ARR, 1);
    return p + 1;
}
void rt_arr_release(void *a) {
    if (!a) return;
    int64_t *h = (int64_t *)a - 1;
    if (--*h == 0) { LK(LK_ARR, -1); free(h); }
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
    m->keys = malloc(16 * 8); m->vals = malloc(16 * 8); m->used = calloc(16, 1); LK(LK_MAP, 1);
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
    m->keys = malloc((size_t)m->cap * 8); m->vals = malloc((size_t)m->cap * 8); m->used = calloc((size_t)m->cap, 1);
    for (int64_t i = 0; i < old.cap; i++) if (old.used[i]) {
        int64_t j = map_find(m, old.keys[i]);
        m->used[j] = 1; m->keys[j] = old.keys[i]; m->vals[j] = old.vals[i]; m->size++;
    }
    free(old.keys); free(old.vals); free(old.used);
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
void rt_map_release(Map *m) { if (!m || --m->rc > 0) return; LK(LK_MAP, -1); free(m->keys); free(m->vals); free(m->used); free(m); }

// ---- object allocation: two regions ---------------------------------------
// The compiler classifies every class (NativeBackend.cyclic): a class whose instances can lie on a
// reference cycle (a field cycle containing a `var` field) is "possibly cyclic"; all others are acyclic.
//   * RC region (rt_alloc / rt_free): acyclic objects. Freed the instant their count hits zero,
//     never buffered, never traced (escape analysis puts non-escaping ones on the stack instead).
//   * Tracing region (rt_alloc_trace / rt_free_trace): possibly cyclic objects only. The cycle
//     collector below runs over this region alone; it never visits an RC-region object.
// Backing memory: RC region = the object allocator (mimalloc by default, malloc, or the pool);
// tracing region = its own size-class slabs (64 KiB), so traced objects stay dense and apart from
// short-lived acyclic ones. With --alloc malloc (ASan / macOS leaks builds) both regions use malloc
// so the tools see every object.
// --leak-check (RT_LEAKCHECK) prefixes each object with a region tag and counts, per region,
// allocations and collector visits; any collector visit outside the tracing region fails the run.

#ifdef RT_LEAKCHECK
enum { RG_RC = 0x5243, RG_TRACE = 0x5452 };
static int64_t rg_alloc[2], rg_free[2], cc_visits, cc_visits_outside, cc_runs;
#define RG_PREFIX 16
static void *rg_tag(void *raw, int64_t tag) { if (!raw) rt_panic("OutOfMemoryError"); *(int64_t *)raw = tag; return (char *)raw + RG_PREFIX; }
static void *rg_raw(void *p, int64_t tag) {
    char *raw = (char *)p - RG_PREFIX;
    if (*(int64_t *)raw != tag) { fprintf(stderr, "region: object %p freed into the wrong region\n", p); abort(); }
    *(int64_t *)raw = 0; return raw;
}
static int rg_is_trace(void *p) { return *(int64_t *)((char *)p - RG_PREFIX) == RG_TRACE; }
__attribute__((destructor(200))) static void rt_region_report(void) {
    fprintf(stderr, "region: rc allocated=%lld freed=%lld traced=0\n", (long long)rg_alloc[0], (long long)rg_free[0]);
    fprintf(stderr, "region: trace allocated=%lld freed=%lld\n", (long long)rg_alloc[1], (long long)rg_free[1]);
    fprintf(stderr, "region: collector runs=%lld visits=%lld outside-trace=%lld\n", (long long)cc_runs, (long long)cc_visits, (long long)cc_visits_outside);
    if (cc_visits_outside) { fprintf(stderr, "region: FAIL collector touched RC-region objects\n"); fflush(stderr); _exit(4); }
}
void *rt_alloc(int64_t size) { LK(LK_OBJ, 1); rg_alloc[0]++; return rg_tag(malloc((size_t)size + RG_PREFIX), RG_RC); }
void rt_free(void *p, int64_t size) { (void)size; LK(LK_OBJ, -1); rg_free[0]++; free(rg_raw(p, RG_RC)); }
void *rt_alloc_trace(int64_t size) { LK(LK_OBJ, 1); rg_alloc[1]++; return rg_tag(malloc((size_t)size + RG_PREFIX), RG_TRACE); }
void rt_free_trace(void *p, int64_t size) { (void)size; LK(LK_OBJ, -1); rg_free[1]++; free(rg_raw(p, RG_TRACE)); }
#define CC_VISIT(p) do { cc_visits++; if (!rg_is_trace(p)) cc_visits_outside++; } while (0)
#else
#define CC_VISIT(p) ((void)0)

/* Size-class slab pool (8-byte classes up to RT_CLASSES*8 bytes, 64 KiB slabs, larger -> malloc). */
#define RT_CLASSES 32
typedef struct { void *free_list[RT_CLASSES + 1]; char *ptr, *end; } Pool;
static inline void *pool_alloc(Pool *pl, int64_t size) {
    size_t cls = ((size_t)size + 7) >> 3;
    if (cls > RT_CLASSES) { void *q = malloc((size_t)size); if (!q) rt_panic("OutOfMemoryError"); return q; }
    void *p = pl->free_list[cls];
    if (p) { pl->free_list[cls] = *(void **)p; return p; }
    size_t bytes = cls << 3;
    if (pl->ptr + bytes > pl->end) {
        pl->ptr = malloc(1 << 16);
        if (!pl->ptr) rt_panic("OutOfMemoryError");
        pl->end = pl->ptr + (1 << 16);
    }
    p = pl->ptr; pl->ptr += bytes;
    return p;
}
static inline void pool_free(Pool *pl, void *p, int64_t size) {
    size_t cls = ((size_t)size + 7) >> 3;
    if (cls > RT_CLASSES) { free(p); return; }
    *(void **)p = pl->free_list[cls];
    pl->free_list[cls] = p;
}

#ifdef RT_POOL
static Pool rc_pool;
void *rt_alloc(int64_t size) { return pool_alloc(&rc_pool, size); }
void rt_free(void *p, int64_t size) { pool_free(&rc_pool, p, size); }
#else
void *rt_alloc(int64_t size) { void *p = malloc((size_t)size); if (!p) rt_panic("OutOfMemoryError"); return p; }
void rt_free(void *p, int64_t size) { (void)size; free(p); }
#endif

#ifdef RT_PLAIN_MALLOC
void *rt_alloc_trace(int64_t size) { return rt_alloc(size); }
void rt_free_trace(void *p, int64_t size) { rt_free(p, size); }
#else
static Pool trace_pool;
void *rt_alloc_trace(int64_t size) { return pool_alloc(&trace_pool, size); }
void rt_free_trace(void *p, int64_t size) { pool_free(&trace_pool, p, size); }
#endif
#endif

// ---- cycle collector (Bacon & Rajan 2001, synchronous trial deletion) -----
// Only objects of "cyclic" classes (on a field cycle with a `var` field; vtable[4] != 0) take part.
// Their 64-bit header word: low 32 bits = reference count, bits 32-33 = color, bit 34 = buffered.
// A decrement to a non-zero count makes the object a possible root (purple, buffered). When the
// buffer reaches CC_THRESHOLD, and at program exit, the collector runs MarkRoots / ScanRoots /
// CollectRoots. Other objects (acyclic classes, lists, maps, builders) are released by plain RC.
// Limitation: cycles that pass through an ArrayList/HashMap are not traced.

typedef void (*visit_fn)(void *);
typedef struct { void (*rel)(void *); void (*trav)(void *, visit_fn); void (*relo)(void *); void (*freemem)(void *); void *cyclic; } VTab;

#define CC_THRESHOLD 10000
/* Adaptive trigger: a collection whose trial deletion traced far more objects than it reclaimed as
 * cyclic garbage (the buffered roots were live, e.g. relinked list nodes in a cache) doubles the threshold, up to CC_MAX_THRESHOLD, so live
 * structures are not re-traced every 10k decrements; a productive collection resets it. */
#define CC_MAX_THRESHOLD (1u << 18)
static size_t cc_threshold = CC_THRESHOLD, cc_traced;
#define CNT_MASK 0xffffffffULL
#define COLOR_SHIFT 32
#define BUF_BIT (1ULL << 34)
enum { BLACK = 0, GRAY = 1, WHITE = 2, PURPLE = 3 };

static inline VTab *vtab(void *p) { return *(VTab **)((char *)p + 8); }
static inline uint64_t *hdr(void *p) { return (uint64_t *)p; }
static inline int is_cyc(void *p) { return vtab(p)->cyclic != NULL; }
static inline uint64_t cnt(void *p) { return *hdr(p) & CNT_MASK; }
static inline int color(void *p) { return (int)((*hdr(p) >> COLOR_SHIFT) & 3); }
static inline void set_color(void *p, int c) { *hdr(p) = (*hdr(p) & ~(3ULL << COLOR_SHIFT)) | ((uint64_t)c << COLOR_SHIFT); }
static inline int buffered(void *p) { return (*hdr(p) & BUF_BIT) != 0; }

typedef struct { void **a; size_t n, cap; } Vec;
static void vpush(Vec *v, void *p) {
    if (v->n == v->cap) { v->cap = v->cap ? v->cap * 2 : 256; v->a = realloc(v->a, v->cap * sizeof *v->a); if (!v->a) rt_panic("OutOfMemoryError"); }
    v->a[v->n++] = p;
}
static Vec cc_roots, cc_work, cc_black, cc_garbage;
static int cc_running;

void rt_cc_collect(void);
void rt_cc_possible_root(void *p) {
    set_color(p, PURPLE);
    if (!buffered(p)) {
        *hdr(p) |= BUF_BIT;
        vpush(&cc_roots, p);
        if (cc_roots.n >= cc_threshold && !cc_running) rt_cc_collect();
    }
}
/* Count reached zero and the children were released: free now, unless the root buffer still holds it. */
void rt_cc_dead(void *p) {
    set_color(p, BLACK);
    if (!buffered(p)) vtab(p)->freemem(p);
}

static void mg_child(void *t) { if (t && is_cyc(t)) { (*hdr(t))--; vpush(&cc_work, t); } }
static void mark_gray(void *s) {
    size_t traced = 0;
    vpush(&cc_work, s);
    while (cc_work.n) {
        void *x = cc_work.a[--cc_work.n];
        if (color(x) != GRAY) { CC_VISIT(x); set_color(x, GRAY); traced++; vtab(x)->trav(x, mg_child); }
    }
    cc_traced += traced;
}
static void sb_child(void *t) {
    if (t && is_cyc(t)) { (*hdr(t))++; if (color(t) != BLACK) { set_color(t, BLACK); vpush(&cc_black, t); } }
}
static void scan_black(void *s) {
    set_color(s, BLACK); vpush(&cc_black, s);
    while (cc_black.n) { void *x = cc_black.a[--cc_black.n]; vtab(x)->trav(x, sb_child); }
}
static void scan_child(void *t) { if (t && is_cyc(t)) vpush(&cc_work, t); }
static void scan(void *s) {
    vpush(&cc_work, s);
    while (cc_work.n) {
        void *x = cc_work.a[--cc_work.n];
        if (color(x) != GRAY) continue;
        if (cnt(x) > 0) scan_black(x);
        else { set_color(x, WHITE); vtab(x)->trav(x, scan_child); }
    }
}
/* Garbage objects are claimed (colored black) when pushed, so each one is freed exactly once. */
static int cw_claim(void *t) {
    if (color(t) != WHITE || buffered(t)) return 0;
    set_color(t, BLACK); vpush(&cc_work, t); return 1;
}
static void cw_child(void *t) {
    if (!t) return;
    if (is_cyc(t)) cw_claim(t);
    else vtab(t)->rel(t); /* acyclic child: drop the garbage object's reference normally */
}
static void collect_white(void *s) {
    if (!cw_claim(s)) return;
    while (cc_work.n) {
        void *x = cc_work.a[--cc_work.n];
        vtab(x)->trav(x, cw_child);
        vpush(&cc_garbage, x); /* freed after all roots are processed: other garbage may still point here */
    }
}

void rt_cc_collect(void) {
    if (cc_running || cc_roots.n == 0) return;
    cc_running = 1;
#ifdef RT_LEAKCHECK
    cc_runs++;
#endif
    Vec roots = cc_roots; cc_roots = (Vec){0};
    size_t k = 0;
    cc_traced = 0;
    for (size_t i = 0; i < roots.n; i++) {           /* MarkRoots */
        void *s = roots.a[i];
        if (color(s) == PURPLE && cnt(s) > 0) { mark_gray(s); roots.a[k++] = s; }
        else { *hdr(s) &= ~BUF_BIT; if (color(s) == BLACK && cnt(s) == 0) vtab(s)->freemem(s); }
    }
    roots.n = k;
    for (size_t i = 0; i < roots.n; i++) scan(roots.a[i]);  /* ScanRoots */
    for (size_t i = 0; i < roots.n; i++) {           /* CollectRoots */
        void *s = roots.a[i];
        *hdr(s) &= ~BUF_BIT;
        collect_white(s);
    }
    for (size_t i = 0; i < cc_garbage.n; i++) vtab(cc_garbage.a[i])->relo(cc_garbage.a[i]);
    for (size_t i = 0; i < cc_garbage.n; i++) vtab(cc_garbage.a[i])->freemem(cc_garbage.a[i]);
    size_t reclaimed = cc_garbage.n;
    cc_garbage.n = 0;
    free(roots.a);
    if (reclaimed * 4 < cc_traced) { if (cc_threshold < CC_MAX_THRESHOLD) cc_threshold *= 2; }
    else cc_threshold = CC_THRESHOLD;
    cc_running = 0;
    if (cc_roots.n >= cc_threshold) rt_cc_collect();
}
