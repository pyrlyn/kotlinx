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
#include "../common/object.inc"
#define CC_CLAIM_SKIPS_BUFFERED 0   /* later slices' roots are claimed too; their own slice frees them */
#include "../common/trial_deletion.inc"

#define CC_SLICE 64                       /* roots per atomic slice */
#define CC_STEP_ROOTS 2000                /* new roots before an unfinished step resumes */
#define CC_DEFAULT_BUDGET_US 500          /* KX_GC_BUDGET_US default */

static PtrStack cc_roots;                 /* buffered possible roots (each has the buffered bit set) */
static size_t cc_trigger = CC_THRESHOLD;  /* buffer length that starts the next step */
static int cc_running;                    /* re-entrancy guard */
static int64_t cc_budget_ns = -1;         /* per-step budget, read lazily from KX_GC_BUDGET_US */
static KxGcStats gc_stats;

static void collect_step(int full);

void rt_cc_possible_root(void *obj) {
    set_color(obj, PURPLE);
    if (is_buffered(obj)) return;
    set_buffered(obj);
    stack_push(&cc_roots, obj);
    if (cc_roots.len >= cc_trigger && !cc_running) collect_step(0);
}

/* Count reached zero and the children were released: free now, unless the root buffer still holds it. */
void rt_cc_dead(void *obj) {
    set_color(obj, BLACK);
    if (!is_buffered(obj)) vtab(obj)->freemem(obj);
}

/* Trial deletion over roots[0..n), which are buffered and PURPLE or BLACK. Returns objects reclaimed. */
static size_t collect_slice(void **roots, size_t n) {
    size_t kept = 0;
    for (size_t i = 0; i < n; i++) {                                 /* MarkRoots */
        void *root = roots[i];
        if (color(root) == PURPLE && ref_count(root) > 0) { mark_gray(root); roots[kept++] = root; }
        else {
            clear_buffered(root);
            if (color(root) == BLACK && ref_count(root) == 0) vtab(root)->freemem(root);
        }
    }
    for (size_t i = 0; i < kept; i++) scan(roots[i]);                /* ScanRoots */
    for (size_t i = 0; i < kept; i++) clear_buffered(roots[i]);
    for (size_t i = 0; i < kept; i++) collect_white(roots[i]);       /* CollectRoots */
    for (size_t i = 0; i < cc_garbage.len; i++) vtab(cc_garbage.items[i])->relo(cc_garbage.items[i]);
    size_t freed = 0;
    for (size_t i = 0; i < cc_garbage.len; i++) {
        void *obj = cc_garbage.items[i];
        if (is_buffered(obj)) continue;              /* BLACK, count 0: freed by its own slice */
        vtab(obj)->freemem(obj);
        freed++;
    }
    cc_garbage.len = 0;
    return freed;
}

static int64_t budget_ns(void) {
    if (cc_budget_ns < 0) {
        const char *env = getenv("KX_GC_BUDGET_US");
        cc_budget_ns = (env && *env ? atoll(env) : CC_DEFAULT_BUDGET_US) * 1000;
    }
    return cc_budget_ns;
}

/* One collector step: slices until the buffer is empty or (unless `full`) the budget is spent. */
static void collect_step(int full) {
    if (cc_running || cc_roots.len == 0) return;
    cc_running = 1;
    int64_t budget = budget_ns();
    int64_t start = rt_nanos();
#ifdef RT_LEAKCHECK
    cc_runs++;
#endif
    PtrStack roots = cc_roots; cc_roots = (PtrStack){0};
    size_t done = 0, reclaimed = 0;
    cc_traced = 0;
    while (done < roots.len) {
        size_t n = roots.len - done < CC_SLICE ? roots.len - done : CC_SLICE;
        reclaimed += collect_slice(roots.items + done, n);
        done += n;
        if (full || rt_nanos() - start <= budget) continue;
        /* Over budget. If the first slice alone blew it, the cost is a large graph shared by the roots
         * (re-traced by every slice), so slicing would multiply work without shortening the pause:
         * finish this step in one pass instead. */
        if (done == n) full = 1; else break;
    }
    size_t left = roots.len - done;
    for (; done < roots.len; done++) stack_push(&cc_roots, roots.items[done]);   /* still buffered */
    free(roots.items);
    adapt_threshold(reclaimed);
    cc_trigger = left ? cc_roots.len + CC_STEP_ROOTS : cc_threshold;
    gc_stats.collections++;
    gc_stats.reclaimed += (int64_t)reclaimed;
    stats_add_pause(&gc_stats, rt_nanos() - start);
    cc_running = 0;
}

void rt_gc_collect(void) { while (cc_roots.len && !cc_running) collect_step(1); }
const char *rt_gc_name(void) { return "incremental"; }
void rt_gc_stats(KxGcStats *out) { *out = gc_stats; }
