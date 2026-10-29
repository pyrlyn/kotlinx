// Incremental cycle collector (newkotlinc --gc=incremental): the default GC (Perceus-style RC + Bacon & Rajan
// trial deletion) with the root buffer processed in slices under a per-step time budget.
//
// A step takes buffered roots in slices of CC_SLICE and runs MarkRoots / ScanRoots / CollectRoots on each
// slice (trial deletion is correct for any subset of the candidate roots). After each slice it checks the
// budget (KX_GC_BUDGET_US, default 500 us); unprocessed roots stay buffered for the next step, which runs
// after CC_STEP_ROOTS more candidate roots. A garbage object that is still in the buffer (a later slice's
// root) is released but not freed: it is left black with count zero, exactly like rt_cc_dead, and its
// own slice frees it. Program exit and rt_gc_collect run all slices without a budget.
// Bound: one slice is atomic, so a slice whose roots reach a huge live graph can still exceed the budget;
// when the first slice of a step already does, the step finishes in one pass (as the default GC would),
// because every further slice would re-trace the same graph.
#include <stdint.h>
#include <stdlib.h>
#include "../common/regions.inc"

// ---- cycle collector (Bacon & Rajan 2001, synchronous trial deletion) -----
// Only objects of "cyclic" classes (on a field cycle with a `var` field; vtable[4] != 0) take part.
// Their 64-bit header word: low 32 bits = reference count, bits 32-33 = color, bit 34 = buffered.
// A decrement to a non-zero count makes the object a possible root (purple, buffered). When the
// buffer reaches CC_THRESHOLD, and at program exit, the collector runs MarkRoots / ScanRoots /
// CollectRoots. Other objects (acyclic classes, lists, maps, builders) are released by plain RC.
// Limitation: cycles that pass through an ArrayList/HashMap are not traced.

typedef kx_visit_fn visit_fn;
typedef KxVTab VTab;

#define CC_THRESHOLD 10000
/* Adaptive trigger: a collection whose trial deletion traced far more objects than it reclaimed as
 * cyclic garbage (the buffered roots were live, e.g. relinked list nodes in a cache) doubles the threshold, up to CC_MAX_THRESHOLD, so live
 * structures are not re-traced every 10k decrements; a productive collection resets it. */
#define CC_MAX_THRESHOLD (1u << 18)
static size_t cc_threshold = CC_THRESHOLD, cc_traced, cc_trigger = CC_THRESHOLD;
#define CNT_MASK KX_CNT_MASK
#define COLOR_SHIFT KX_COLOR_SHIFT
#define BUF_BIT KX_BUF_BIT
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
static KxGcStats gc_st;

static void rt_cc_collect(void);
void rt_cc_possible_root(void *p) {
    set_color(p, PURPLE);
    if (!buffered(p)) {
        *hdr(p) |= BUF_BIT;
        vpush(&cc_roots, p);
        if (cc_roots.n >= cc_trigger && !cc_running) rt_cc_collect();
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
/* Buffered white objects (roots of later slices) are claimed too; the free phase leaves them to their slice. */
static int cw_claim(void *t) {
    if (color(t) != WHITE) return 0;
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

#define CC_SLICE 64
#define CC_STEP_ROOTS 2000
static int64_t cc_budget_ns = -1;

/* Trial deletion over roots a[0..n), which are buffered and purple/black. Returns objects reclaimed. */
static size_t collect_slice(void **a, size_t n) {
    size_t k = 0;
    for (size_t i = 0; i < n; i++) {                 /* MarkRoots */
        void *s = a[i];
        if (color(s) == PURPLE && cnt(s) > 0) { mark_gray(s); a[k++] = s; }
        else { *hdr(s) &= ~BUF_BIT; if (color(s) == BLACK && cnt(s) == 0) vtab(s)->freemem(s); }
    }
    for (size_t i = 0; i < k; i++) scan(a[i]);         /* ScanRoots */
    for (size_t i = 0; i < k; i++) *hdr(a[i]) &= ~BUF_BIT;
    for (size_t i = 0; i < k; i++) collect_white(a[i]);  /* CollectRoots */
    for (size_t i = 0; i < cc_garbage.n; i++) vtab(cc_garbage.a[i])->relo(cc_garbage.a[i]);
    size_t freed = 0;
    for (size_t i = 0; i < cc_garbage.n; i++) {
        void *x = cc_garbage.a[i];
        if (buffered(x)) continue;                     /* black, count 0: freed by its own slice */
        vtab(x)->freemem(x); freed++;
    }
    cc_garbage.n = 0;
    return freed;
}

static void cc_step(int full) {
    if (cc_running || cc_roots.n == 0) return;
    cc_running = 1;
    if (cc_budget_ns < 0) { const char *e = getenv("KX_GC_BUDGET_US"); cc_budget_ns = (e && *e ? atoll(e) : 500) * 1000; }
    int64_t t0 = rt_nanos();
#ifdef RT_LEAKCHECK
    cc_runs++;
#endif
    Vec roots = cc_roots; cc_roots = (Vec){0};
    size_t i = 0, reclaimed = 0;
    cc_traced = 0;
    while (i < roots.n) {
        size_t n = roots.n - i < CC_SLICE ? roots.n - i : CC_SLICE;
        reclaimed += collect_slice(roots.a + i, n);
        i += n;
        if (full || rt_nanos() - t0 <= cc_budget_ns) continue;
        /* Over budget. If the first slice alone blew it, the cost is a large graph shared by the roots
         * (re-traced by every slice), so slicing would multiply work without shortening the pause:
         * finish this step in one pass instead. */
        if (i == n) full = 1; else break;
    }
    size_t left = roots.n - i;
    for (; i < roots.n; i++) vpush(&cc_roots, roots.a[i]);   /* still buffered */
    free(roots.a);
    if (reclaimed * 4 < cc_traced) { if (cc_threshold < CC_MAX_THRESHOLD) cc_threshold *= 2; }
    else cc_threshold = CC_THRESHOLD;
    cc_trigger = left ? cc_roots.n + CC_STEP_ROOTS : cc_threshold;
    gc_st.collections++; gc_st.reclaimed += (int64_t)reclaimed;
    int64_t dt = rt_nanos() - t0; gc_st.pause_ns_total += dt; if (dt > gc_st.pause_ns_max) gc_st.pause_ns_max = dt;
    cc_running = 0;
}
static void rt_cc_collect(void) { cc_step(0); }

void rt_gc_collect(void) { while (cc_roots.n && !cc_running) cc_step(1); }
const char *rt_gc_name(void) { return "incremental"; }
void rt_gc_stats(KxGcStats *out) { *out = gc_st; }
