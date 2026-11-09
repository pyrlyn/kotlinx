// Minimal runtime for KotlinX compiler (newkotlinc). Linked into every program by clang.
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef __wasi__
#include <sys/resource.h>
#endif
#include <time.h>
#include <unistd.h>

#include "kxgc.h" /* memory manager interface; implementation in compiler/gc/<name>/gc.c (newkotlinc --gc) */
#if !defined(KXGC_API_VERSION) || KXGC_API_VERSION != 1
#error "compiler/gc (pyrlyn/gc) has an incompatible kxgc.h; update the submodule pin: git submodule update --init"
#endif
#include "common/object.inc"

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

#ifdef __wasi__ /* --target=wasm (wasm32-wasi): no CLOCK_UPTIME_RAW */
int64_t rt_nanos(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (int64_t)t.tv_sec * 1000000000 + t.tv_nsec; }
#else
int64_t rt_nanos(void) { return (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW); }
#endif

// ---- bounded release ---------------------------------------------------------
// Below rt_rel_limit on the C stack @relz.C leaves its object here instead of nesting another frame. The
// first call empties the stack itself, with the limit moved just below its own frame, so the release goes
// no deeper than rel_drain_room past that call, and every object is released before it returns. Recursion
// would have released the deferred objects before their parents are freed; the difference is visible only
// to the collector, which does not start meanwhile (kxgc.h).
// Only the mutator thread releases objects (the epoch collector returns dead ones to it), so one limit is enough.
#define REL_STACK (4 * 1024 * 1024)
char *rt_rel_limit;
int rt_rel_draining;
_Thread_local int rt_trav_mode;
static size_t rel_drain_room;
static PtrStack rel_pending;

__attribute__((constructor)) static void rel_limit_init(void) {
    size_t room = REL_STACK;
#ifndef __wasi__ /* wasm: the linker sets the shadow stack size (newkotlinc links -z stack-size) */
    struct rlimit r;
    if (getrlimit(RLIMIT_STACK, &r) == 0 && r.rlim_cur / 2 < room) room = r.rlim_cur / 2;
#endif
    rt_rel_limit = (char *)__builtin_frame_address(0) - room;
    rel_drain_room = room / 8; /* limit + drain must fit a small stack too */
}

void rt_rel_defer(void *obj) {
    stack_push(&rel_pending, obj);
    if (rt_rel_draining) return;
    rt_rel_draining = 1;
    char *limit = rt_rel_limit;
    rt_rel_limit = (char *)__builtin_frame_address(0) - rel_drain_room;
    while (rel_pending.len) {
        void *o = stack_pop(&rel_pending);
        /* rel takes a count of 1 to zero, the path into @relz.C. A dying acyclic object still reads 1 here,
         * since generated code publishes the zero only for cyclic ones, so this cannot be an increment. */
        *header(o) = (*header(o) & ~KX_CNT_MASK) | 1;
        vtab(o)->rel(o);
    }
    rt_rel_limit = limit;
    rt_rel_draining = 0;
}

/* Every collector pause, kept only to report percentiles at exit (KX_GC_STATS=1). */
static int64_t *gc_pauses; static size_t gc_pauses_len, gc_pauses_cap;
void rt_gc_note_pause(int64_t pause_ns) {
    if (gc_pauses_len == gc_pauses_cap) {
        size_t cap = gc_pauses_cap ? gc_pauses_cap * 2 : 1024;
        int64_t *p = realloc(gc_pauses, cap * sizeof *p);
        if (!p) return; /* statistics only: drop the sample rather than fail */
        gc_pauses = p; gc_pauses_cap = cap;
    }
    gc_pauses[gc_pauses_len++] = pause_ns;
}
static int cmp_i64(const void *a, const void *b) { int64_t x = *(const int64_t *)a, y = *(const int64_t *)b; return (x > y) - (x < y); }
/* Nearest-rank percentile in ms; 0 with no pauses. */
static double gc_pause_pct(double q) {
    if (!gc_pauses_len) return 0;
    size_t i = (size_t)(q * gc_pauses_len + 0.999999); if (i < 1) i = 1; if (i > gc_pauses_len) i = gc_pauses_len;
    return gc_pauses[i - 1] / 1e6;
}

/* At exit: final collection; KX_GC_STATS=1 prints the GC's statistics to stderr (bench/gc/micro.sh). */
void rt_exit_flush(void) {
    rt_gc_collect(); fflush(stdout);
    const char *e = getenv("KX_GC_STATS");
    if (e && *e && *e != '0') {
        KxGcStats st; rt_gc_stats(&st);
        fprintf(stderr, "gc-stats: gc=%s collections=%lld reclaimed=%lld pause_total=%.3f pause_max=%.3f pause_p50=%.3f pause_p99=%.3f\n",
                rt_gc_name(), (long long)st.collections, (long long)st.reclaimed, st.pause_ns_total / 1e6, st.pause_ns_max / 1e6,
                (qsort(gc_pauses, gc_pauses_len, sizeof *gc_pauses, cmp_i64), gc_pause_pct(0.50)), gc_pause_pct(0.99));
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
/* StringBuilder.setLength: truncates, or pads with '\0' (Kotlin pads with \u0000). */
void rt_sb_set_length(SB *s, int64_t n) {
    if (n < 0) { fflush(stdout); fprintf(stderr, "Exception in thread \"main\" java.lang.IndexOutOfBoundsException: length %" PRId64 "\n", n); exit(1); }
    if ((size_t)n <= s->len) { s->len = (size_t)n; if (s->buf) s->buf[s->len] = 0; return; }
    char z = 0;
    while (s->len < (size_t)n) sb_bytes(s, &z, 1);
}
void rt_sb_release(SB *s) { if (!s || --s->rc > 0) return; LK(LK_SB, -1); free(s->buf - 8); free(s); }
void rt_sb_f64(SB *s, double d) { char b[64]; fmt_double(b, sizeof b, d); rt_sb_str(s, b); }
void rt_sb_f32(SB *s, float d) { char b[64]; fmt_fp(b, sizeof b, d, 1); rt_sb_str(s, b); }
void rt_sb_bool(SB *s, _Bool v) { rt_sb_str(s, v ? "true" : "false"); }
char *rt_sb_done(SB *s) { char *r = s->buf; ((int64_t *)r)[-1] = 1; LK(LK_SB, -1); LK(LK_STR, 1); free(s); return r; }

// ---- ArrayList (reference counted; elements stored as 64-bit slots) -------

typedef void (*release_fn)(void *);
/* The traced objects whose fields hold a collection of traced elements (an ArrayList or HashMap field of a
 * possibly cyclic class; NativeBackend stores call rt_list_hold / rt_list_own, overwrites and deaths
 * rt_list_disown). `owner` is one of them, the one the collector traverses the collection from (deferred RC)
 * and the one a release that leaves a single reference makes a possible root: with a count of 1 that owner
 * holds the only reference, and the release may have been the last way into a cycle through the collection
 * (an alias `val l = g.kids; l.add(g)` moves g into the list without decrementing it). When the owner drops
 * the collection, another holder takes over, so a collection shared by two objects is still collected once
 * one of them drops it. Invariant: no owner, no other holders. */
typedef struct { void *owner; void **more; int32_t n, cap; } Holders;
/* Deferred RC (epoch) only: `gc` is set once the list is stored in a field of a traced object, where the
 * concurrent collector can read it; from then on its storage is published with release stores and freed
 * with rt_gc_free_later, and every change the collector could see logs the owner as modified
 * (rt_gc_write_barrier), as a field store would. `ctrav`: the collector took over the element references of
 * the dead object it traversed the list from. */
typedef struct { int64_t rc; int64_t len, cap; int64_t *data; release_fn rel; Holders h; int32_t gc, ctrav; } List;
#define LD(x) __atomic_load_n(&(x), __ATOMIC_RELAXED)
#define LDA(x) __atomic_load_n(&(x), __ATOMIC_ACQUIRE)
#define ST(x, v) __atomic_store_n(&(x), (v), __ATOMIC_RELAXED)
#define STR(x, v) __atomic_store_n(&(x), (v), __ATOMIC_RELEASE)
#define OWNER_MOD(c) do { if (__builtin_expect((c)->gc, 0) && (c)->h.owner) rt_gc_write_barrier((c)->h.owner); } while (0)
/* A release left one reference: the owner's (if any). Deferred RC also logs it as modified (the hint does). */
#define OWNER_ONE(c) do { if ((c)->h.owner) rt_gc_root_hint((c)->h.owner); } while (0)

static void hold_add(Holders *h, void *obj) {
    if (!h->owner) { ST(h->owner, obj); return; }
    if (h->n == h->cap) {
        h->cap = h->cap ? h->cap * 2 : 2;
        h->more = realloc(h->more, (size_t)h->cap * sizeof *h->more);
        if (!h->more) rt_panic("OutOfMemoryError");
    }
    h->more[h->n++] = obj;
}
/* Removes one hold of obj; returns 1 if obj was the owner (then another holder, if any, takes over). */
static int hold_remove(Holders *h, void *obj) {
    if (h->owner == obj) { ST(h->owner, h->n ? h->more[--h->n] : NULL); return 1; }
    for (int32_t i = 0; i < h->n; i++) if (h->more[i] == obj) { h->more[i] = h->more[--h->n]; break; }
    return 0;
}
/* Deferred RC: set when a relo dropped a collection its object owned. Log entries may still name that object
 * (OWNER_MOD, rt_gc_root_hint), so the implementation keeps its memory until those logs are applied. */
int rt_relo_owner;

List *rt_list_new(release_fn rel) {
    List *l = malloc(sizeof *l);
    l->rc = 1; l->len = 0; l->cap = 0; l->data = NULL; l->rel = rel; l->h = (Holders){0}; l->gc = 0; l->ctrav = 0; LK(LK_LIST, 1);
    return l;
}
/* Grows a collector-visible list without realloc: the collector may still read the old storage. */
static __attribute__((noinline)) void list_grow_gc(List *l) {
    int64_t cap = l->cap ? l->cap * 2 : 10;
    int64_t *d = rt_gc_mem_alloc((size_t)cap * 8), *old = l->data;
    if (l->len) memcpy(d, old, (size_t)l->len * 8);
    STR(l->data, d); l->cap = cap;
    if (old) rt_gc_free_later(old);
}
void rt_list_add(List *l, int64_t v) {
    if (__builtin_expect(l->gc, 0)) {
        if (l->len == l->cap) list_grow_gc(l);
        ST(l->data[l->len], v); STR(l->len, l->len + 1);   /* element before length: the collector reads len first */
        OWNER_MOD(l);
        return;
    }
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
RT_HOT int64_t rt_list_set(List *l, int64_t i, int64_t v) { bounds(l, i); int64_t o = l->data[i]; ST(l->data[i], v); OWNER_MOD(l); return o; }
RT_HOT int64_t rt_list_size(List *l) { return l->len; }
/* Removes and returns element i; ownership of a reference element moves to the caller. */
int64_t rt_list_remove_at(List *l, int64_t i) {
    bounds(l, i); int64_t o = l->data[i];
    if (__builtin_expect(l->gc, 0)) {
        /* word by word (memmove may tear a slot the collector reads), length last */
        for (int64_t j = i; j + 1 < l->len; j++) ST(l->data[j], l->data[j + 1]);
        STR(l->len, l->len - 1); OWNER_MOD(l);
        return o;
    }
    memmove(l->data + i, l->data + i + 1, (size_t)(l->len - i - 1) * 8); l->len--;
    return o;
}
static void list_free(List *l) {
    LK(LK_LIST, -1);
    free(l->h.more);   /* the collector reads only h.owner */
    if (l->gc) { rt_gc_free_later(l->data); rt_gc_free_later(l); } else { rt_gc_mem_free(l->data); free(l); }
}
void rt_list_release(List *l) {
    if (!l) return;
    if (l->rc > 1) {
        ST(l->rc, l->rc - 1);
        if (l->rc == 1) OWNER_ONE(l); else OWNER_MOD(l);   /* with one reference left the list becomes traversable */
        return;
    }
    if (l->rel) for (int64_t i = 0; i < l->len; i++) l->rel((void *)(intptr_t)l->data[i]);
    list_free(l);
}
/* Deferred RC: a retain of a list the collector may traverse (it then holds more than one reference). */
void rt_list_retain_gc(List *l) { if (l) { ST(l->rc, l->rc + 1); OWNER_MOD(l); } }
/* l is about to be stored in a field of traced object obj. */
void rt_list_hold(List *l, void *obj) { if (l) hold_add(&l->h, obj); }
/* Deferred RC: the same; obj logs itself as modified. */
void rt_list_own(List *l, void *obj) { if (l) { l->gc = 1; hold_add(&l->h, obj); } }
/* The field of obj that held l was overwritten or released (before the release of l). */
void rt_list_disown(List *l, void *obj) { if (l) hold_remove(&l->h, obj); }

/* Cycle collection through lists: a list held only by its object (count 1) is traversed as part of it, its
 * elements being that object's children (NativeBackend @trav). CollectWhite traverses garbage with
 * RT_TRAV_RELEASE: the collector then takes over the elements, so the dead object's relo frees only the memory
 * (rt_list_release_untraced). A list that was shared then is released normally, even if the relo of another
 * garbage object has meanwhile brought its count down to 1. */
void rt_list_trav(List *l, void (*visit)(void *)) {
    if (!l || l->rc != 1) return;
    if (rt_trav_mode == RT_TRAV_RELEASE) l->ctrav = 1;
    for (int64_t i = 0; i < l->len; i++) visit((void *)(intptr_t)l->data[i]);
}
void rt_list_release_untraced(List *l) {
    if (!l) return;
    if (!l->ctrav) { rt_list_release(l); return; }
    list_free(l);
}
/* Deferred RC (the collector runs next to the mutator): traversed only from its owner and while that holds
 * the only reference; both can change concurrently, but every such change logs the owner as modified, so a
 * detection that saw the old state fails its Delta test. Length before storage before elements (acquire),
 * the order list_grow_gc / rt_list_add publish them in; old storage stays valid (rt_gc_free_later). */
void rt_list_trav_owned(List *l, void *owner, void (*visit)(void *)) {
    if (!l) return;
    int ok;
    if (rt_trav_mode == RT_TRAV_FREE) ok = l->ctrav;
    else {
        ok = LD(l->h.owner) == owner && LD(l->rc) == 1;
        if (rt_trav_mode == RT_TRAV_RELEASE) ST(l->ctrav, ok);
    }
    if (!ok) return;
    int64_t n = LDA(l->len); int64_t *d = LDA(l->data);
    for (int64_t i = 0; i < n; i++) visit((void *)(intptr_t)LD(d[i]));
}
/* Deferred RC: relo of the dead owner (or holder) of l. */
void rt_list_release_owned(List *l, void *obj) {
    if (!l) return;
    if (hold_remove(&l->h, obj)) rt_relo_owner = 1;
    if (l->ctrav) list_free(l); else rt_list_release(l);
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

// ---- HashMap<primitive|String, primitive>: open addressing, linear probing --------
// String keys (`skeys`): the slot holds the string pointer (retained by the map), hashed and compared by content.

/* Insertion-ordered hash map (iteration follows insertion, as LinkedHashMap / mutableMapOf).
 * Entries live in dense arrays keys/vals/used (n entries, room for dcap; a removed entry keeps its place with
 * used = 0 until the next rehash compacts them). idx is an open-addressing table of cap slots holding an entry
 * index, EMPTY or TOMB. */
typedef struct { int64_t rc; int64_t size, cap; int64_t *keys, *vals; uint8_t *used; int32_t skeys; release_fn vrel;
                 int64_t n, dcap, tombs; int64_t *idx; Holders h; int32_t gc, ctrav; } Map;
// Reference values (`vrel` set): the map owns one reference per value; overwrites and the map release drop it.
// `h`, `gc`, `ctrav`: as for List. The concurrent collector reads n, used and vals, never
// keys or idx: an entry's value is stored before its used flag and the flag before n (release), so a collector
// that sees a flag set sees its value; a gc map copies vals and used when they grow instead of reallocating them,
// and frees the old blocks with rt_gc_free_later.
#define MAP_EMPTY (-1)
#define MAP_TOMB (-2)

static inline uint64_t map_hash(int64_t k) {
    uint64_t x = (uint64_t)k;
    x ^= x >> 33; x *= 0xff51afd7ed558ccdULL; x ^= x >> 33;
    return x;
}
static inline uint64_t map_hash_str(const char *s) {
    uint64_t h = 1469598103934665603ULL;
    for (; *s; s++) { h ^= (uint8_t)*s; h *= 1099511628211ULL; }
    return h ^ (h >> 32);
}
static inline uint64_t map_hash_of(Map *m, int64_t k) { return m->skeys ? map_hash_str((const char *)(intptr_t)k) : map_hash(k); }
static inline int map_eq(Map *m, int64_t a, int64_t b) { return m->skeys ? strcmp((const char *)(intptr_t)a, (const char *)(intptr_t)b) == 0 : a == b; }
Map *rt_map_new(void) {
    Map *m = malloc(sizeof *m);
    m->skeys = 0; m->vrel = NULL; m->h = (Holders){0}; m->gc = 0; m->ctrav = 0;
    m->rc = 1; m->size = 0; m->cap = 16; m->n = 0; m->dcap = 12; m->tombs = 0;
    m->keys = malloc(12 * 8); m->vals = rt_gc_mem_alloc(12 * 8); m->used = malloc(12);
    m->idx = malloc(16 * 8); for (int i = 0; i < 16; i++) m->idx[i] = MAP_EMPTY;
    LK(LK_MAP, 1);
    return m;
}
Map *rt_smap_new(void) { Map *m = rt_map_new(); m->skeys = 1; return m; }
Map *rt_map_with_vrel(Map *m, release_fn rel) { m->vrel = rel; return m; }
/* Slot of key k in idx, or -1 - (first free slot for inserting it). */
static int64_t map_slot(Map *m, int64_t k) {
    uint64_t mask = (uint64_t)m->cap - 1, i = map_hash_of(m, k) & mask;
    int64_t freeSlot = -1;
    for (;;) {
        int64_t e = m->idx[i];
        if (e == MAP_EMPTY) return -1 - (freeSlot >= 0 ? freeSlot : (int64_t)i);
        if (e == MAP_TOMB) { if (freeSlot < 0) freeSlot = (int64_t)i; }
        else if (map_eq(m, m->keys[e], k)) return (int64_t)i;
        i = (i + 1) & mask;
    }
}
/* Rebuilds idx (cap slots) over the live entries, compacting the dense arrays. */
static void map_rehash(Map *m, int64_t cap) {
    int64_t w = 0;
    for (int64_t r = 0; r < m->n; r++) if (m->used[r]) { m->keys[w] = m->keys[r]; ST(m->vals[w], m->vals[r]); STR(m->used[w], 1); w++; }
    STR(m->n, w); m->tombs = 0;
    free(m->idx); m->cap = cap; m->idx = malloc((size_t)cap * 8);
    for (int64_t i = 0; i < cap; i++) m->idx[i] = MAP_EMPTY;
    uint64_t mask = (uint64_t)cap - 1;
    for (int64_t e = 0; e < m->n; e++) { uint64_t i = map_hash_of(m, m->keys[e]) & mask; while (m->idx[i] != MAP_EMPTY) i = (i + 1) & mask; m->idx[i] = e; }
}
/* Doubles the dense arrays. A gc map copies all of the old ones (also the entries past n a collector with a stale n
 * may still read) into zeroed blocks, then publishes values before flags. */
static void map_grow_dense(Map *m) {
    int64_t old = m->dcap;
    m->dcap *= 2;
    m->keys = realloc(m->keys, (size_t)m->dcap * 8);
    if (!m->gc) { m->used = realloc(m->used, (size_t)m->dcap); m->vals = rt_gc_mem_realloc(m->vals, (size_t)m->dcap * 8); return; }
    int64_t *v = rt_gc_mem_calloc((size_t)m->dcap * 8); uint8_t *u = calloc((size_t)m->dcap, 1);
    if (!u) rt_panic("OutOfMemoryError");
    memcpy(v, m->vals, (size_t)old * 8); memcpy(u, m->used, (size_t)old);
    int64_t *ov = m->vals; uint8_t *ou = m->used;
    STR(m->vals, v); STR(m->used, u);
    rt_gc_free_later(ov); rt_gc_free_later(ou);
}
void rt_map_put(Map *m, int64_t k, int64_t v) {
    int64_t s = map_slot(m, k);
    if (s >= 0) {
        int64_t e = m->idx[s], o = m->vals[e];
        ST(m->vals[e], v); OWNER_MOD(m);
        if (m->vrel) m->vrel((void *)(intptr_t)o);
        return;
    }
    if ((m->size + m->tombs + 1) * 4 > m->cap * 3 || m->n == m->dcap) {
        if (m->n == m->dcap && m->size * 2 < m->n) map_rehash(m, m->cap); /* many removed entries: compact */
        if (m->n == m->dcap) map_grow_dense(m);
        if ((m->size + m->tombs + 1) * 4 > m->cap * 3) map_rehash(m, (m->size + 1) * 4 > m->cap * 3 ? m->cap * 2 : m->cap);
        s = map_slot(m, k);
    }
    int64_t e = m->n;
    m->keys[e] = k; ST(m->vals[e], v); STR(m->used[e], 1); STR(m->n, e + 1); m->size++;
    if (m->idx[-1 - s] == MAP_TOMB) m->tombs--;
    m->idx[-1 - s] = e;
    if (m->skeys) rt_hdr_retain((void *)(intptr_t)k);
    OWNER_MOD(m);
}
int32_t rt_map_contains(Map *m, int64_t k) { return map_slot(m, k) >= 0; }
int64_t rt_map_get_or(Map *m, int64_t k, int64_t d) { int64_t s = map_slot(m, k); return s >= 0 ? m->vals[m->idx[s]] : d; }
int64_t rt_map_get(Map *m, int64_t k) {
    int64_t s = map_slot(m, k);
    if (s < 0) {
        fflush(stdout);
        if (m->skeys) fprintf(stderr, "Exception in thread \"main\" java.lang.NullPointerException: key %s not in map\n", (const char *)(intptr_t)k);
        else fprintf(stderr, "Exception in thread \"main\" java.lang.NullPointerException: key %" PRId64 " not in map\n", k);
        exit(1);
    }
    return m->vals[m->idx[s]];
}
/* Removes k; returns its value (the map's reference passes to the caller) or d when absent. */
int64_t rt_map_remove(Map *m, int64_t k, int64_t d) {
    int64_t s = map_slot(m, k);
    if (s < 0) return d;
    int64_t e = m->idx[s], v = m->vals[e];
    m->idx[s] = MAP_TOMB; m->tombs++; ST(m->used[e], 0); m->size--;
    if (m->skeys) rt_str_release((char *)(intptr_t)m->keys[e]);
    OWNER_MOD(m);
    return v;
}
void rt_map_clear(Map *m) {
    int64_t n = m->n;
    STR(m->n, 0); m->size = 0; m->tombs = 0;
    for (int64_t i = 0; i < m->cap; i++) m->idx[i] = MAP_EMPTY;
    OWNER_MOD(m);
    for (int64_t e = 0; e < n; e++) if (m->used[e]) {
        if (m->skeys) rt_str_release((char *)(intptr_t)m->keys[e]);
        if (m->vrel) m->vrel((void *)(intptr_t)m->vals[e]);
    }
}
int64_t rt_map_size(Map *m) { return m->size; }
/* Iteration over entry positions 0 until rt_map_end, skipping dead ones (insertion order). */
int64_t rt_map_end(Map *m) { return m->n; }
int32_t rt_map_live(Map *m, int64_t e) { return m->used[e]; }
int64_t rt_map_key_at(Map *m, int64_t e) { return m->keys[e]; }
int64_t rt_map_val_at(Map *m, int64_t e) { return m->vals[e]; }
/* Memory only: keys are released here, values by the caller (or by the collector). */
static void map_free(Map *m) {
    LK(LK_MAP, -1);
    free(m->h.more);
    if (m->skeys) for (int64_t e = 0; e < m->n; e++) if (m->used[e]) rt_str_release((char *)(intptr_t)m->keys[e]);
    free(m->keys); free(m->idx);
    if (m->gc) { rt_gc_free_later(m->vals); rt_gc_free_later(m->used); rt_gc_free_later(m); }
    else { rt_gc_mem_free(m->vals); free(m->used); free(m); }
}
void rt_map_release(Map *m) {
    if (!m) return;
    if (m->rc > 1) {
        ST(m->rc, m->rc - 1);
        if (m->rc == 1) OWNER_ONE(m); else OWNER_MOD(m);
        return;
    }
    if (m->vrel) for (int64_t e = 0; e < m->n; e++) if (m->used[e]) m->vrel((void *)(intptr_t)m->vals[e]);
    map_free(m);
}
/* Cycle collection through map values: like rt_list_trav / rt_list_release_untraced. */
void rt_map_trav(Map *m, void (*visit)(void *)) {
    if (!m || m->rc != 1 || !m->vrel) return;
    if (rt_trav_mode == RT_TRAV_RELEASE) m->ctrav = 1;
    for (int64_t e = 0; e < m->n; e++) if (m->used[e]) visit((void *)(intptr_t)m->vals[e]);
}
void rt_map_release_untraced(Map *m) {
    if (!m) return;
    if (!m->ctrav) { rt_map_release(m); return; }
    map_free(m);
}
/* Deferred RC: like rt_list_trav_owned. n, then used flags, then values: the reverse of their publication. */
void rt_map_trav_owned(Map *m, void *owner, void (*visit)(void *)) {
    if (!m || !m->vrel) return;
    int ok;
    if (rt_trav_mode == RT_TRAV_FREE) ok = m->ctrav;
    else {
        ok = LD(m->h.owner) == owner && LD(m->rc) == 1;
        if (rt_trav_mode == RT_TRAV_RELEASE) ST(m->ctrav, ok);
    }
    if (!ok) return;
    int64_t n = LDA(m->n); uint8_t *used = LDA(m->used); int64_t *vals = LDA(m->vals);
    for (int64_t e = 0; e < n; e++) if (LDA(used[e])) visit((void *)(intptr_t)LD(vals[e]));
}
void rt_map_retain_gc(Map *m) { if (m) { ST(m->rc, m->rc + 1); OWNER_MOD(m); } }
void rt_map_hold(Map *m, void *obj) { if (m) hold_add(&m->h, obj); }
void rt_map_own(Map *m, void *obj) { if (m) { m->gc = 1; hold_add(&m->h, obj); } }
void rt_map_disown(Map *m, void *obj) { if (m) hold_remove(&m->h, obj); }
void rt_map_release_owned(Map *m, void *obj) {
    if (!m) return;
    if (hold_remove(&m->h, obj)) rt_relo_owner = 1;
    if (m->ctrav) map_free(m); else rt_map_release(m);
}

// Object allocation, reference counting slow paths and the cycle collector live in compiler/gc/ (kxgc.h).


