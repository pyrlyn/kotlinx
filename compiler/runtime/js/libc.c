// libc subset for newkotlinc --target=js, compiled to wasm32 LLVM IR (wasi-libc headers; driver/JsDriver.kt) and
// translated to JavaScript together with the program, compiler/runtime/runtime.c and the GC. The rest of libc (stdio,
// strings, time, exit) lives in compiler/runtime/js/runtime.js. Memory comes from js_sbrk (the end of the JS
// ArrayBuffer heap).
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

extern char *js_sbrk(size_t n);

// FILE* handles understood by runtime.js: 1 stdout, 2 stderr, 3 stdin; fopen returns >= 4.
FILE *const stdin = (FILE *)3, *const stdout = (FILE *)1, *const stderr = (FILE *)2;

// wasi-libc's clock ids are addresses (CLOCK_MONOTONIC is &_CLOCK_MONOTONIC); runtime.js ignores the id.
struct __clockid { int id; };
const struct __clockid _CLOCK_MONOTONIC = {1};

// ---- allocator ---------------------------------------------------------------------------------------------
// Chunks are multiples of 16 bytes: a size_t header (the chunk size) right before a 16-byte aligned payload.
// Small chunks (<= SMALL_MAX) are carved from 1 MB arenas and recycled through exact-size free lists;
// large chunks come straight from js_sbrk and are recycled through power-of-two bins (first fit).
#define SMALL_MAX 4096
#define ARENA (1u << 20)
#define HDR sizeof(size_t)
static void *free_small[SMALL_MAX / 16 + 1];
static void *free_large[32];
static char *cur, *lim;

static inline size_t chunk_of(size_t n) { return (n + HDR + 15) & ~(size_t)15; }
static inline size_t *hdr(void *p) { return (size_t *)p - 1; }
static inline int log2_floor(size_t c) { return 31 - __builtin_clz(c); }

static void *alloc_large(size_t c) {
    int b = log2_floor(c);
    for (int k = b; k < 32 && k <= b + 1; k++) {
        void **prev = &free_large[k];
        for (void *p = *prev; p; prev = (void **)p, p = *prev) {
            if (*hdr(p) >= c) { *prev = *(void **)p; return p; }
        }
    }
    char *base = js_sbrk(c + 16);
    if (!base) return NULL;
    char *p = base + 16;
    *hdr(p) = c;
    return p;
}

static void *kx_alloc(size_t n) {
    size_t c = chunk_of(n);
    if (c > SMALL_MAX) return alloc_large(c);
    void **l = &free_small[c >> 4];
    void *p = *l;
    if (p) { *l = *(void **)p; return p; }
    if (!cur || cur - HDR + c > lim) {
        char *b = js_sbrk(ARENA);
        if (!b) return NULL;
        if (b == lim && cur) lim = b + ARENA; // contiguous: extend the arena
        else { cur = b + 16; lim = b + ARENA; }
    }
    p = cur;
    *hdr(p) = c;
    cur += c;
    return p;
}

void *malloc(size_t n) { return kx_alloc(n); }

void free(void *p) {
    if (!p) return;
    size_t c = *hdr(p);
    void **l = c <= SMALL_MAX ? &free_small[c >> 4] : &free_large[log2_floor(c)];
    *(void **)p = *l;
    *l = p;
}

void *calloc(size_t n, size_t m) {
    size_t t;
    if (__builtin_mul_overflow(n, m, &t)) return NULL;
    void *p = kx_alloc(t);
    if (p) __builtin_memset(p, 0, t);
    return p;
}

void *realloc(void *p, size_t n) {
    if (!p) return kx_alloc(n);
    size_t c = *hdr(p);
    if (chunk_of(n) <= c) return p;
    void *q = kx_alloc(n);
    if (!q) return NULL;
    __builtin_memcpy(q, p, c - HDR);
    free(p);
    return q;
}

// ---- qsort (heapsort: no recursion, no extra memory) --------------------------------------------------------
static void swap_bytes(char *a, char *b, size_t n) { for (size_t i = 0; i < n; i++) { char t = a[i]; a[i] = b[i]; b[i] = t; } }

static void sift(char *base, size_t start, size_t end, size_t w, int (*cmp)(const void *, const void *)) {
    size_t root = start;
    while (2 * root + 1 < end) {
        size_t child = 2 * root + 1;
        if (child + 1 < end && cmp(base + child * w, base + (child + 1) * w) < 0) child++;
        if (cmp(base + root * w, base + child * w) >= 0) return;
        swap_bytes(base + root * w, base + child * w, w);
        root = child;
    }
}

void qsort(void *b, size_t n, size_t w, int (*cmp)(const void *, const void *)) {
    char *base = b;
    if (n < 2) return;
    for (size_t i = n / 2; i-- > 0;) sift(base, i, n, w, cmp);
    for (size_t end = n - 1; end > 0; end--) {
        swap_bytes(base, base + end * w, w);
        sift(base, 0, end, w, cmp);
    }
}
