// Boehm-Demers-Weiser conservative collector (bdwgc) as a KotlinX GC backend (newkotlinc --gc=boehm).
// Reference counting stays on (generated fast paths free objects at count zero with GC_FREE); bdwgc
// reclaims what RC cannot: unreachable cycles of possibly cyclic objects. Acyclic objects and runtime
// storage that may hold references are uncollectable but scanned, so every reference to a traced object
// is visible to bdwgc. A finalizer on each traced object releases its untraced (acyclic) fields when
// bdwgc reclaims it. bdwgc comes from mise (conda:bdw-gc, see mise.toml); gc/boehm/config.sh finds it.
#include <gc.h>
#include "kxgc.h"

static KxGcStats gc_st;

__attribute__((constructor(101))) static void boehm_init(void) { GC_INIT(); }

static void *chk(void *p) { if (!p) rt_panic("OutOfMemoryError"); return p; }
static inline KxVTab *vt(void *p) { return *(KxVTab **)((char *)p + 8); }

void *rt_alloc(int64_t size) {
#ifdef RT_LEAKCHECK
    rt_leak_obj(1);
#endif
    return chk(GC_MALLOC_UNCOLLECTABLE((size_t)size));
}
void rt_free(void *p, int64_t size) {
    (void)size;
#ifdef RT_LEAKCHECK
    rt_leak_obj(-1);
#endif
    GC_FREE(p);
}
/* Acyclic child of an unreachable object: drop that object's reference (traced children die with it). */
static void release_acyclic(void *t) { if (t && !vt(t)->cyclic) vt(t)->rel(t); }
/* Unreachable cycle member: release the acyclic objects and other fields it still owns; bdwgc frees the memory. */
static void on_unreachable(void *p, void *cd) {
    (void)cd;
    vt(p)->trav(p, release_acyclic);
    vt(p)->relo(p);
    gc_st.reclaimed++;
#ifdef RT_LEAKCHECK
    rt_leak_obj(-1);
#endif
}
void *rt_alloc_trace(int64_t size) {
#ifdef RT_LEAKCHECK
    rt_leak_obj(1);
#endif
    void *p = chk(GC_MALLOC((size_t)size));
    GC_REGISTER_FINALIZER_NO_ORDER(p, on_unreachable, NULL, NULL, NULL);
    return p;
}
void rt_free_trace(void *p, int64_t size) {
    (void)size;
#ifdef RT_LEAKCHECK
    rt_leak_obj(-1);
#endif
    GC_REGISTER_FINALIZER_NO_ORDER(p, NULL, NULL, NULL, NULL);
    GC_FREE(p);
}
void rt_cc_possible_root(void *p) { (void)p; }
void rt_cc_dead(void *p) { vt(p)->freemem(p); }

void *rt_gc_mem_alloc(size_t n) { return chk(GC_MALLOC_UNCOLLECTABLE(n ? n : 1)); }
void *rt_gc_mem_calloc(size_t n) { return chk(GC_MALLOC_UNCOLLECTABLE(n ? n : 1)); } /* bdwgc zeroes */
void *rt_gc_mem_realloc(void *p, size_t n) {
    if (!p) return rt_gc_mem_alloc(n);
    return chk(GC_REALLOC(p, n ? n : 1)); /* keeps the uncollectable kind */
}
void rt_gc_mem_free(void *p) { if (p) GC_FREE(p); }

void rt_gc_collect(void) {
    int64_t t0 = rt_nanos();
    GC_gcollect();
    GC_invoke_finalizers();
    int64_t dt = rt_nanos() - t0;
    gc_st.collections++; gc_st.pause_ns_total += dt; if (dt > gc_st.pause_ns_max) gc_st.pause_ns_max = dt;
}
const char *rt_gc_name(void) { return "boehm"; }
void rt_gc_stats(KxGcStats *out) { *out = gc_st; out->collections = (int64_t)GC_get_gc_no(); }
