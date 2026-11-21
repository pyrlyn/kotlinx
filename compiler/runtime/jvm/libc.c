/* libc functions of the jvm target that take callbacks (qsort, bsearch, atexit). They are compiled to LLVM IR with the
 * runtime and the program, so their indirect calls are translated like any other; everything else comes from
 * KxRt.java. Built with -fno-builtin so clang does not turn these bodies back into libc calls. */
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

__attribute__((noreturn)) void _exit(int code); /* KxRt.java: flushes stdout and halts the JVM */

/* atexit handlers: atexit(), and __cxa_atexit(), which clang uses for __attribute__((destructor)) on Darwin. */
void *__dso_handle = 0;
static void (*exit_fns[64])(void *);
static void *exit_args[64];
static int exit_n;

int __cxa_atexit(void (*fn)(void *), void *arg, void *dso) {
    (void)dso;
    if (exit_n == 64) return -1;
    exit_fns[exit_n] = fn; exit_args[exit_n] = arg; exit_n++;
    return 0;
}

int atexit(void (*fn)(void)) { return __cxa_atexit((void (*)(void *))fn, 0, 0); }

void exit(int code) {
    while (exit_n > 0) { exit_n--; exit_fns[exit_n](exit_args[exit_n]); }
    _exit(code);
}

static void swap_bytes(char *a, char *b, size_t n) {
    while (n--) { char t = *a; *a++ = *b; *b++ = t; }
}

/* Merge sort (stable): ties keep their input order. Falls back to insertion sort if no scratch memory. */
static void msort(char *base, char *tmp, size_t n, size_t size, int (*cmp)(const void *, const void *)) {
    if (n < 2) return;
    if (n <= 8) {
        for (size_t i = 1; i < n; i++)
            for (size_t j = i; j > 0 && cmp(base + (j - 1) * size, base + j * size) > 0; j--)
                swap_bytes(base + (j - 1) * size, base + j * size, size);
        return;
    }
    size_t h = n / 2;
    msort(base, tmp, h, size, cmp);
    msort(base + h * size, tmp, n - h, size, cmp);
    if (cmp(base + (h - 1) * size, base + h * size) <= 0) return;
    memcpy(tmp, base, h * size);
    size_t i = 0, j = h, k = 0;
    while (i < h && j < n) {
        if (cmp(base + j * size, tmp + i * size) < 0) { memcpy(base + k * size, base + j * size, size); j++; }
        else { memcpy(base + k * size, tmp + i * size, size); i++; }
        k++;
    }
    if (i < h) memcpy(base + k * size, tmp + i * size, (h - i) * size);
}

void qsort(void *base, size_t n, size_t size, int (*cmp)(const void *, const void *)) {
    if (n < 2 || size == 0) return;
    char *tmp = malloc((n / 2 + 1) * size);
    if (!tmp) {
        for (size_t i = 1; i < n; i++)
            for (size_t j = i; j > 0 && cmp((char *)base + (j - 1) * size, (char *)base + j * size) > 0; j--)
                swap_bytes((char *)base + (j - 1) * size, (char *)base + j * size, size);
        return;
    }
    msort(base, tmp, n, size, cmp);
    free(tmp);
}

void *bsearch(const void *key, const void *base, size_t n, size_t size, int (*cmp)(const void *, const void *)) {
    size_t lo = 0, hi = n;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        const char *p = (const char *)base + mid * size;
        int c = cmp(key, p);
        if (c == 0) return (void *)p;
        if (c < 0) hi = mid; else lo = mid + 1;
    }
    return NULL;
}
