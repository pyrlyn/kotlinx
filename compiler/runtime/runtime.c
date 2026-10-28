// Minimal runtime for New Kotlin (newkotlinc). Linked into every program by clang.
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

int64_t rt_nanos(void) { return (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW); }

void rt_exit_flush(void) { fflush(stdout); }

void rt_panic(const char *msg) {
    fflush(stdout);
    fprintf(stderr, "Exception in thread \"main\" java.lang.IllegalStateException: %s\n", msg);
    exit(1);
}

// ---- printing -------------------------------------------------------------

/* Kotlin-style Double formatting: shortest round-trip digits, always with a '.'. */
static void fmt_double(char *buf, size_t n, double d) {
    if (d != d) { snprintf(buf, n, "NaN"); return; }
    if (d == 1.0 / 0.0) { snprintf(buf, n, "Infinity"); return; }
    if (d == -1.0 / 0.0) { snprintf(buf, n, "-Infinity"); return; }
    for (int p = 1; p <= 17; p++) {
        snprintf(buf, n, "%.*g", p, d);
        if (strtod(buf, NULL) == d) break;
    }
    if (!strpbrk(buf, ".eEn")) strncat(buf, ".0", n - strlen(buf) - 1);
}

void rt_print_str(const char *s) { fputs(s ? s : "null", stdout); }
void rt_print_i64(int64_t v) { printf("%" PRId64, v); }
void rt_print_f64(double d) { char b[64]; fmt_double(b, sizeof b, d); fputs(b, stdout); }
void rt_print_bool(_Bool b) { fputs(b ? "true" : "false", stdout); }
void rt_newline(void) { putchar('\n'); }

// ---- string builder (templates); strings are never freed ------------------

typedef struct { char *buf; size_t len, cap; } SB;

SB *rt_sb_new(void) { SB *s = malloc(sizeof *s); s->cap = 32; s->len = 0; s->buf = malloc(s->cap); s->buf[0] = 0; return s; }
void rt_sb_str(SB *s, const char *x) {
    if (!x) x = "null";
    size_t n = strlen(x);
    if (s->len + n + 1 > s->cap) { while (s->len + n + 1 > s->cap) s->cap *= 2; s->buf = realloc(s->buf, s->cap); }
    memcpy(s->buf + s->len, x, n + 1); s->len += n;
}
void rt_sb_i64(SB *s, int64_t v) { char b[32]; snprintf(b, sizeof b, "%" PRId64, v); rt_sb_str(s, b); }
void rt_sb_f64(SB *s, double d) { char b[64]; fmt_double(b, sizeof b, d); rt_sb_str(s, b); }
void rt_sb_bool(SB *s, _Bool v) { rt_sb_str(s, v ? "true" : "false"); }
char *rt_sb_done(SB *s) { char *r = s->buf; free(s); return r; }

// ---- ArrayList (reference counted; elements stored as 64-bit slots) -------

typedef void (*release_fn)(void *);
typedef struct { int64_t rc; int64_t len, cap; int64_t *data; release_fn rel; } List;

List *rt_list_new(release_fn rel) {
    List *l = malloc(sizeof *l);
    l->rc = 1; l->len = 0; l->cap = 0; l->data = NULL; l->rel = rel;
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
void rt_list_release(List *l) {
    if (!l || --l->rc > 0) return;
    if (l->rel) for (int64_t i = 0; i < l->len; i++) l->rel((void *)(intptr_t)l->data[i]);
    free(l->data); free(l);
}

// ---- ByteArray: { int64 length; bytes... }; not freed ----------------------

void *rt_bytes_new(int64_t n) {
    int64_t *p = calloc(1, 8 + (size_t)n);
    if (!p) rt_panic("OutOfMemoryError");
    *p = n;
    return p;
}

// ---- small-object allocator: per-size free lists over 64 KiB slabs ---------
// Objects of classes are <= 128 bytes in practice; larger sizes fall back to malloc.

#define RT_CLASSES 16
static void *rt_free_lists[RT_CLASSES + 1];
static char *rt_slab_ptr, *rt_slab_end;

void *rt_alloc(int64_t size) {
    size_t cls = ((size_t)size + 7) >> 3;
    if (cls > RT_CLASSES) return malloc((size_t)size);
    void *p = rt_free_lists[cls];
    if (p) { rt_free_lists[cls] = *(void **)p; return p; }
    size_t bytes = cls << 3;
    if (rt_slab_ptr + bytes > rt_slab_end) {
        rt_slab_ptr = malloc(1 << 16);
        if (!rt_slab_ptr) rt_panic("OutOfMemoryError");
        rt_slab_end = rt_slab_ptr + (1 << 16);
    }
    p = rt_slab_ptr; rt_slab_ptr += bytes;
    return p;
}

void rt_free(void *p, int64_t size) {
    size_t cls = ((size_t)size + 7) >> 3;
    if (cls > RT_CLASSES) { free(p); return; }
    *(void **)p = rt_free_lists[cls];
    rt_free_lists[cls] = p;
}
