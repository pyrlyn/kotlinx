// Epoch-based GC (newkotlinc --gc=epoch): deferred RC + concurrent cycle collection on a background thread
// (Bacon, Attanasio, Lee, Rajan, Smith: "Java without the Coffee Breaks" / Bacon & Rajan, "Concurrent Cycle
// Collection in Reference Counted Systems", 2001), adapted to KotlinX.
//
// Mutator (the program thread):
//   * Acyclic objects: unchanged inline RC (Perceus), freed immediately.
//   * Traced (possibly cyclic) objects: generated code never touches their header after initialization.
//     Retain/release append (pointer | op) to the current epoch log (rt_gc_log_inc / rt_gc_log_dec); a
//     reference store into a traced object's field is a release store followed by rt_gc_write_barrier,
//     which logs the object as modified.
//   * Epoch boundary (log full, LOG_CAP entries): the log is handed to the collector (one mutex
//     round-trip) and the mutator frees objects the collector found dead, under a time budget
//     (KX_GC_FREE_BUDGET_US, default 200 us). The boundary is the only pause; it blocks only if the
//     collector falls more than KX_GC_MAX_QUEUED (default 16) logs behind (back-pressure).
// Collector (background thread), per log, in order:
//   1. apply all increments, then all decrements (the mutator is single-threaded, so no count drops to
//      zero early); every touched object is marked dirty. Count zero -> dead: decrement its traced children
//      and return it to the mutator (which releases its acyclic fields and frees the memory). Non-zero ->
//      purple candidate root.
//   2. when the root buffer is large: concurrent trial deletion (MarkGray / Scan / CollectWhite) on a
//      separate cyclic count (CRC, header bits 40-63), so real counts are never disturbed while the
//      mutator keeps running; each white component becomes a candidate cycle.
//   3. a candidate cycle is checked after the next log whose hand-off happened after detection ended:
//      Delta test (no member was touched by any RC operation or write barrier since detection) and Sigma
//      test (sum of member counts minus member-to-member edges is zero: no external references). Passing
//      cycles are freed (traced children outside the cycle decremented, members returned to the mutator);
//      failing members go back to the root buffer.
// Traced objects live in 64 KiB-aligned size-class slabs registered in a page bitmap, so the
// collector recognizes traced pointers without dereferencing (acyclic objects may be freed concurrently).
// Generations: objects found externally referenced by a scan become stable (old) until decremented; minor
// detections do not trace them, major detections (amortized against the stable population, with a
// geometric backoff while majors find no garbage) do.
// Trial deletion's MarkGray/Scan run in parallel on KX_GC_THREADS threads for large major detections.
// Bounded slices: a minor detection takes at most KX_GC_SLICE_ROOTS roots, validation at most
// VALIDATE_BUDGET members per log; the leftovers carry over to the next log. Opt-in experiments:
// KX_GC_CARRY_QUEUE=n skips detection while n logs are queued, KX_GC_MAJOR_SLACK=k lets the queue grow
// k-fold during a major (both measured worse, see bench/results/gc/exp/combined).
// Program exit drains the queue only for --leak-check / --alloc malloc builds or KX_GC_EXIT_DRAIN=1.
// See compiler/gc/docs/architecture.md, section 7.
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "kxgc.h"

#if defined(__has_feature)
#if __has_feature(address_sanitizer)
#include <sanitizer/asan_interface.h>
#define POISON(p, n) ASAN_POISON_MEMORY_REGION(p, n)
#define UNPOISON(p, n) ASAN_UNPOISON_MEMORY_REGION(p, n)
#endif
#endif
#ifndef POISON
#define POISON(p, n) ((void)0)
#define UNPOISON(p, n) ((void)0)
#endif

#include "../common/object.inc"

// ---- statistics / leak check ----
static KxGcStats gc_st;
static int64_t ep_epochs, ep_detections;
#ifdef RT_LEAKCHECK
static int64_t rg_alloc[2], rg_free[2], cc_visits;
__attribute__((destructor(200))) static void rt_region_report(void) {
    fprintf(stderr, "region: rc allocated=%lld freed=%lld traced=0\n", (long long)rg_alloc[0], (long long)rg_free[0]);
    fprintf(stderr, "region: trace allocated=%lld freed=%lld\n", (long long)rg_alloc[1], (long long)rg_free[1]);
    fprintf(stderr, "region: collector runs=%lld visits=%lld outside-trace=0\n", (long long)ep_detections, (long long)cc_visits);
}
#define LEAK(d) rt_leak_obj(d)
#define VISIT() __atomic_fetch_add(&cc_visits, 1, __ATOMIC_RELAXED)
#else
#define LEAK(d) ((void)0)
#define VISIT() ((void)0)
#endif

// ---- acyclic objects: plain allocator (mimalloc / malloc) ----
void *rt_alloc(int64_t size) {
    void *p = malloc((size_t)size); if (!p) rt_panic("OutOfMemoryError");
    LEAK(1);
#ifdef RT_LEAKCHECK
    rg_alloc[0]++;
#endif
    return p;
}
void rt_free(void *p, int64_t size) {
    (void)size; LEAK(-1);
#ifdef RT_LEAKCHECK
    rg_free[0]++;
#endif
    free(p);
}
void *rt_gc_mem_alloc(size_t n) { void *p = malloc(n); if (!p && n) rt_panic("OutOfMemoryError"); return p; }
void *rt_gc_mem_calloc(size_t n) { void *p = calloc(1, n); if (!p) rt_panic("OutOfMemoryError"); return p; }
void *rt_gc_mem_realloc(void *p, size_t n) { p = realloc(p, n); if (!p && n) rt_panic("OutOfMemoryError"); return p; }
void rt_gc_mem_free(void *p) { free(p); }

// ---- traced objects: 64 KiB-aligned slabs + page bitmap ----
#define PAGE_SHIFT 16
#define PAGE (1u << PAGE_SHIFT)
/* Page bitmap: one bit per 64 KiB page of the 47-bit address space (256 MiB of virtual memory,
 * reserved lazily; only the pages covering the heap are ever touched). Never dereferences p. */
#define PAGE_BITS_LOG 31
static _Atomic uint64_t *page_bits;
static void reg_add(uintptr_t addr) {
    uintptr_t pg = addr >> PAGE_SHIFT;
    if (pg >> PAGE_BITS_LOG) rt_panic("epoch GC: heap address outside the page bitmap");
    atomic_fetch_or_explicit(&page_bits[pg >> 6], 1ULL << (pg & 63), memory_order_release);
}
static inline int is_traced(void *p) {
    uintptr_t pg = (uintptr_t)p >> PAGE_SHIFT;
    if (!p || (pg >> PAGE_BITS_LOG) || !page_bits) return 0;
    return (atomic_load_explicit(&page_bits[pg >> 6], memory_order_acquire) >> (pg & 63)) & 1;
}
#define CLASSES 32
static void *free_list[CLASSES + 1];
static char *bump, *bump_end;
typedef struct Big { struct Big *next; size_t size; } Big;
static Big *big_free;
static void *page_alloc(size_t bytes) {
    void *p = NULL;
    if (!page_bits) {
        page_bits = mmap(NULL, (1ULL << PAGE_BITS_LOG) / 8, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON | MAP_NORESERVE, -1, 0);
        if (page_bits == MAP_FAILED) rt_panic("OutOfMemoryError");
    }
    if (posix_memalign(&p, PAGE, bytes)) rt_panic("OutOfMemoryError");
    for (size_t o = 0; o < bytes; o += PAGE) reg_add((uintptr_t)p + o);
    return p;
}
// ---- small vector ----
/* Moves every pointer of `from` onto `to`. */
static void stack_move(PtrStack *to, PtrStack *from) { for (size_t i = 0; i < from->len; i++) stack_push(to, from->items[i]); from->len = 0; }

static int64_t dbg_bp_ns, dbg_bp_max, dbg_exit_ns, dbg_bmax;
static PtrStack ret_local; static size_t ret_pos;       /* mutator: dead objects returned by the collector */
static void free_returned(void *p);
void *rt_alloc_trace(int64_t size) {
    /* Lazy freeing: every traced allocation frees up to two returned dead objects, so reclamation keeps
     * pace with allocation without a pause. */
    for (int k = 0; k < 2 && ret_pos < ret_local.len; k++) free_returned(ret_local.items[ret_pos++]);
    LEAK(1);
#ifdef RT_LEAKCHECK
    rg_alloc[1]++;
#endif
    size_t cls = ((size_t)size + 7) >> 3;
    void *p;
    if (cls > CLASSES) {
        size_t bytes = ((size_t)size + PAGE - 1) & ~(size_t)(PAGE - 1);
        for (Big **b = &big_free; *b; b = &(*b)->next) if ((*b)->size == bytes) { p = *b; *b = (*b)->next; UNPOISON(p, bytes); return p; }
        return page_alloc(bytes);
    }
    p = free_list[cls];
    if (p) { UNPOISON(p, cls << 3); free_list[cls] = *(void **)p; return p; }
    if (bump + (cls << 3) > bump_end) { bump = page_alloc(PAGE); bump_end = bump + PAGE; }
    p = bump; bump += cls << 3;
    return p;
}
void rt_free_trace(void *p, int64_t size) {
    LEAK(-1);
#ifdef RT_LEAKCHECK
    rg_free[1]++;
#endif
    size_t cls = ((size_t)size + 7) >> 3;
    if (cls > CLASSES) {
        size_t bytes = ((size_t)size + PAGE - 1) & ~(size_t)(PAGE - 1);
        Big *b = p; b->size = bytes; b->next = big_free; big_free = b; POISON((char *)p + sizeof(Big), bytes - sizeof(Big));
        return;
    }
    *(void **)p = free_list[cls]; free_list[cls] = p;
    POISON((char *)p + 8, (cls << 3) - 8);
}
/* Not used with deferred RC (generated code logs instead). */
void rt_cc_possible_root(void *p) { (void)p; rt_panic("epoch GC: rt_cc_possible_root"); }
void rt_cc_dead(void *p) { (void)p; rt_panic("epoch GC: rt_cc_dead"); }

// ---- epoch logs, hand-off ----
#define LOG_CAP 32768
#define MAX_QUEUED_DEFAULT 16   /* logs (256 KiB each) the collector may fall behind; KX_GC_MAX_QUEUED */
static size_t max_queued;
static int major_slack = -1;          /* KX_GC_MAJOR_SLACK: queue limit multiplier while a major runs (default 1: off; measured: the mutator fills any slack within a major) */
static _Atomic int c_in_major;         /* collector is in a major detection */
enum { OP_INC = 0, OP_DEC = 1, OP_MOD = 2 };
typedef struct Log { struct Log *next; size_t n; uint64_t seq; uintptr_t a[LOG_CAP]; } Log;
static Log *cur;                                   /* mutator */
static Log *q_head, *q_tail, *log_pool;            /* guarded by mu */
static size_t q_len;
static uint64_t handed, applied;                   /* guarded by mu */
static PtrStack ret_shared;                             /* dead objects for the mutator, guarded by mu */
static int full_req, coll_quiet, shutting;         /* guarded by mu */
static uint64_t full_done;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv_work = PTHREAD_COND_INITIALIZER, cv_done = PTHREAD_COND_INITIALIZER;
static pthread_t coll_thread;
static int coll_started;
static int in_boundary;
static int64_t free_budget_ns = -1;

static void *collector_main(void *);
static Log *log_new(void) {
    Log *l = log_pool;
    if (l) log_pool = l->next; else { l = malloc(sizeof *l); if (!l) rt_panic("OutOfMemoryError"); }
    l->n = 0; l->next = NULL; return l;
}
/* Hands the current log to the collector; takes the dead objects it returned. Caller holds no lock. */
static void handoff(void) {
    pthread_mutex_lock(&mu);
    if (!coll_started) {
        coll_started = 1;
        if (pthread_create(&coll_thread, NULL, collector_main, NULL)) rt_panic("epoch GC: cannot start the collector thread");
    }
    if (cur && cur->n) {
        cur->seq = ++handed;
        if (q_tail) q_tail->next = cur; else q_head = cur;
        q_tail = cur; q_len++;
        cur = NULL;
        pthread_cond_signal(&cv_work);
    }
    if (!max_queued) { const char *e = getenv("KX_GC_MAX_QUEUED"); max_queued = e && atoi(e) > 0 ? (size_t)atoi(e) : MAX_QUEUED_DEFAULT; }
    if (major_slack < 0) { const char *e = getenv("KX_GC_MAJOR_SLACK"); major_slack = e && *e ? atoi(e) : 1; if (major_slack < 1) major_slack = 1; }
    /* a major detection is one long collector step: let the queue grow (bounded) instead of stopping the
     * mutator; the backlog is carried over, and the raised limit stays until it is back under max_queued,
     * so the mutator never waits for the whole backlog at once */
#define Q_LIMIT() (atomic_load_explicit(&c_in_major, memory_order_relaxed) ? max_queued * (size_t)major_slack : max_queued)
    if (q_len > Q_LIMIT()) { int64_t w0 = rt_nanos(); while (q_len > Q_LIMIT()) pthread_cond_wait(&cv_done, &mu); int64_t d = rt_nanos() - w0; dbg_bp_ns += d; if (d > dbg_bp_max) dbg_bp_max = d; }
    stack_move(&ret_local, &ret_shared);
    if (!cur) cur = log_new();
    pthread_mutex_unlock(&mu);
}
/* Frees dead traced objects returned by the collector: release acyclic fields, free memory. */
static void mut_release_child(void *t) { if (t && !is_traced(t)) vtab(t)->rel(t); }
static void free_returned(void *p) {
    vtab(p)->trav(p, mut_release_child);
    vtab(p)->relo(p);
    vtab(p)->freemem(p);
}
static void process_returns(int64_t budget_ns, int64_t t0) {
    while (ret_pos < ret_local.len) {
        free_returned(ret_local.items[ret_pos++]);
        if (budget_ns >= 0 && (ret_pos & 31) == 0 && rt_nanos() - t0 > budget_ns) break;
    }
    if (ret_pos == ret_local.len) { ret_local.len = 0; ret_pos = 0; }
}
static void note_pause(int64_t t0) { stats_add_pause(&gc_st, rt_nanos() - t0); }
__attribute__((noinline)) static void boundary(void) {
    if (in_boundary) { /* log filled while freeing returned objects: just hand it over */
        pthread_mutex_lock(&mu);
        cur->seq = ++handed; if (q_tail) q_tail->next = cur; else q_head = cur; q_tail = cur; q_len++; cur = NULL;
        pthread_cond_signal(&cv_work);
        cur = log_new();
        pthread_mutex_unlock(&mu);
        return;
    }
    in_boundary = 1;
    int64_t t0 = rt_nanos();
    if (free_budget_ns < 0) { const char *e = getenv("KX_GC_FREE_BUDGET_US"); free_budget_ns = (e && *e ? atoll(e) : 200) * 1000; }
    ep_epochs++;
    handoff();
    process_returns(free_budget_ns, t0);
    if (rt_nanos() - t0 > dbg_bmax) dbg_bmax = rt_nanos() - t0;
    note_pause(t0);
    in_boundary = 0;
}
static inline void log_op(uintptr_t e) {
    if (__builtin_expect(cur == NULL, 0)) { pthread_mutex_lock(&mu); cur = log_new(); pthread_mutex_unlock(&mu); }
    cur->a[cur->n++] = e;
    if (__builtin_expect(cur->n == LOG_CAP, 0)) boundary();
}
void rt_gc_log_inc(void *p) { if (p) log_op((uintptr_t)p | OP_INC); }
void rt_gc_log_dec(void *p) { if (p) log_op((uintptr_t)p | OP_DEC); }
void rt_gc_write_barrier(void *obj) { log_op((uintptr_t)obj | OP_MOD); }

// ---- collector (background thread): header bits are collector-owned for traced objects ----
// Header word of a traced object under epoch (bits 0-34 as in object.inc; the rest is collector-owned):
//
//   63         40   39     38      37     36     35      34        33  32   31                 0
//   +------------+-------+-------+------+------+-------+----------+-------+-------------------+
//   | CRC / set  | stable| zombie| dead | cand | dirty | buffered | color |  reference count  |
//   +------------+-------+-------+------+------+-------+----------+-------+-------------------+
//
//   dirty:  touched by an RC operation or write barrier since the last MarkGray visit (Delta test)
//   cand:   member of a candidate cycle; the CRC field then holds the cycle's set id
//   dead:   count reached zero while a candidate; freed by the set's validation
//   zombie: dead (children already released) but still listed in the root buffer or deferred list;
//           its memory is returned when the list entry is removed
//   stable: survived a cycle scan as externally referenced and untouched since (old generation);
//           minor detections treat it as an opaque live object, so they never re-trace the live graph
//   bit 63: SEEN by the in-progress incremental major (CRC / set id then use bits 40-62)
#define DIRTY_BIT (1ULL << 35)
#define CAND_BIT (1ULL << 36)
#define DEAD_BIT (1ULL << 37)
#define ZOMBIE_BIT (1ULL << 38)   /* freed cycle member still referenced by the root buffer */
#define STABLE_BIT (1ULL << 39)
#define CRC_SHIFT 40
#define CRC_MAX 0x7fffffULL
#define CRC_MASK (CRC_MAX << CRC_SHIFT)
#define SEEN_BIT (1ULL << 63)   /* visited by the in-progress incremental major (its memory is held until it ends) */
static inline int has(void *p, uint64_t b) { return (*header(p) & b) != 0; }
static inline void set_bit(void *p, uint64_t b) { *header(p) |= b; }
static inline void clr_bit(void *p, uint64_t b) { *header(p) &= ~b; }
static inline uint64_t crc(void *p) { return (*header(p) >> CRC_SHIFT) & CRC_MAX; }
static inline void set_crc(void *p, uint64_t c) { *header(p) = (*header(p) & ~CRC_MASK) | ((c & CRC_MAX) << CRC_SHIFT); }

static PtrStack c_roots, c_work, c_ret;                 /* collector-local */
static size_t c_threshold = 10000;
#define C_MAX_THRESHOLD (1u << 18)
#define MAJOR_RATIO 4   /* major detection once 4x as many roots were deferred as there are stable objects */
#define MAJOR_RATIO_MAX 256
static int64_t major_ratio = MAJOR_RATIO;   /* adaptive: doubles after a major that found (almost) no garbage */
/* Candidate cycles of one detection, validated together: set i has id first_id + i and members
 * members[ends[i-1] .. ends[i]). One allocation per detection; batches are validated in FIFO order. */
typedef struct Batch { struct Batch *next; uint64_t valid_after, first_id; size_t nsets; size_t *ends; void *members[]; } Batch;
static Batch *cands, *cands_tail;
static uint64_t next_set_id = 1;
static int64_t dbg_dem_touch, dbg_dem_other, dbg_prom, dbg_major_visits, dbg_minor_visits, dbg_roots_k, dbg_t_mark, dbg_t_scan, dbg_t_apply, dbg_t_valid, dbg_t_detect, c_stable_n, c_deferred_new, dbg_majors, c_traversed, dbg_members, dbg_sets, dbg_pass, dbg_fail_delta, dbg_fail_sigma, dbg_dead, dbg_logs;

/* An increment or write barrier touched p: it is dirty (Delta test) but stays in the old generation.
 * Neither can make p garbage (an increment adds a reference, a store changes only p's out-edges, whose
 * targets get their own increment / decrement), so a stable object stays opaque to minor detections. */
static inline void touch_keep(void *p) { *header(p) |= DIRTY_BIT; }
/* A decrement touched p: it is dirty and leaves the old generation (it may have become garbage). */
static inline void touch(void *p) {
    uint64_t h = *header(p);
    if (h & STABLE_BIT) { c_stable_n--; dbg_dem_touch++; }
    *header(p) = (h | DIRTY_BIT) & ~STABLE_BIT;
}
static inline void promote(void *p) { if (!has(p, STABLE_BIT)) { set_bit(p, STABLE_BIT); c_stable_n++; } }
static void dec_apply(void *p);
static void release_dead_child(void *t) { if (t && is_traced(t)) dec_apply(t); }
/* Count reached zero (no candidate set or root buffer holds it): decrement traced children, return memory.
 * Iterative (c_dying), so freeing a long dead list cannot overflow the collector thread's stack. */
static PtrStack c_dying;
static int c_releasing;
static void release_children(void *p);
static void ret_push(void *p);
static void release_dead(void *p) {
    ret_push(p);
    release_children(p);
}
/* Count reached zero while p is still listed in the root buffer or the deferred list: release its
 * children now (so dead chains do not keep each other alive until the list is drained) and mark it a
 * zombie; its memory is returned when the list entry is removed (detect, pass 1). */
static void release_zombie(void *p) {
    set_bit(p, ZOMBIE_BIT);
    release_children(p);
}
static void release_children(void *p) {
    dbg_dead++;
    if (has(p, STABLE_BIT)) { clr_bit(p, STABLE_BIT); c_stable_n--; }
    clr_bit(p, DEAD_BIT);
    set_color(p, BLACK);
    stack_push(&c_dying, p);
    if (c_releasing) return;
    c_releasing = 1;
    while (c_dying.len) { void *x = stack_pop(&c_dying); vtab(x)->trav(x, release_dead_child); }
    c_releasing = 0;
}
static void possible_root(void *p) {
    set_color(p, PURPLE);
    if (!has(p, KX_BUF_BIT)) { set_bit(p, KX_BUF_BIT); stack_push(&c_roots, p); }
}
static void dec_apply(void *p) {
    uint64_t h = *header(p);
    if ((h & KX_CNT_MASK) == 0) rt_panic("epoch GC: reference count underflow");
    *header(p) = h - 1; touch(p);
    if (ref_count(p) == 0) {
        if (has(p, CAND_BIT)) set_bit(p, DEAD_BIT);          /* the candidate set's validation frees it */
        else if (has(p, KX_BUF_BIT)) release_zombie(p);
        else release_dead(p);
    }
    else possible_root(p);
}
static void apply_log(Log *l) {
    for (size_t i = 0; i < l->n; i++) {
        uintptr_t e = l->a[i]; void *p = (void *)(e & ~(uintptr_t)3);
        if ((e & 3) == OP_INC) { *header(p) += 1; touch_keep(p); }
        else if ((e & 3) == OP_MOD) touch_keep(p);
    }
    for (size_t i = 0; i < l->n; i++) {
        uintptr_t e = l->a[i];
        if ((e & 3) == OP_DEC) dec_apply((void *)(e & ~(uintptr_t)3));
    }
}

// Concurrent trial deletion, run in parallel by the collector thread and KX_GC_THREADS - 1 helpers.
// MarkGray counts, per object, the references from traversed objects in the CRC field (header bits
// 40-63, zero outside a detection); Scan compares it with the real count: count - CRC > 0 means a
// reference from outside the traversed subgraph. Both phases change headers only with atomic
// operations; CollectWhite and everything else run on the collector thread alone, separated from the
// parallel phases by the phase barrier. Objects in a candidate set are opaque, and in a minor detection
// so are stable (old-generation) objects. A wrong CRC (more than 2^24 references) can only make the
// detector propose a wrong candidate, which the Sigma test rejects.
#define MAX_THREADS 16
#define PAR_MIN_ROOTS 2048          /* smaller detections run on the collector thread alone */
#define SHARE_CHUNK 128
static int c_major;                 /* current detection ignores the stable bit (traces the old generation too) */
static _Atomic int c_hit_stable;    /* current minor detection stopped at a stable object */
static _Atomic int64_t c_stable_delta;
static int n_threads = 1;
typedef struct { PtrStack work, black; int64_t traversed; } Worker;
static Worker workers[MAX_THREADS];
static __thread Worker *tw;
static pthread_mutex_t pmu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t p_go = PTHREAD_COND_INITIALIZER, p_wait = PTHREAD_COND_INITIALIZER;
static uint64_t p_gen;                         /* guarded by pmu: phase generation */
static int p_phase, p_active, p_idle, p_finished, p_done, p_stop;
static PtrStack p_shared;                      /* guarded by pmu: work shared between workers */
enum { PHASE_MARK = 1, PHASE_SCAN = 2 };

static inline uint64_t hload(void *p) { return __atomic_load_n(header(p), __ATOMIC_RELAXED); }
static inline int hcas(void *p, uint64_t *old, uint64_t nw) {
    return __atomic_compare_exchange_n(header(p), old, nw, 1, __ATOMIC_RELAXED, __ATOMIC_RELAXED);
}
static inline uint64_t with_color(uint64_t h, int c) { return (h & ~COLOR_MASK) | ((uint64_t)c << KX_COLOR_SHIFT); }
static inline void promote_atomic(void *p) {
    if (!(__atomic_fetch_or(header(p), STABLE_BIT, __ATOMIC_RELAXED) & STABLE_BIT)) atomic_fetch_add_explicit(&c_stable_delta, 1, memory_order_relaxed);
}
static int c_im;                    /* an incremental major is tracing (single-threaded) */
static PtrStack im_seen, c_hold;     /* objects it visited; freed objects it visited (held until it ends) */
static void mg_child(void *t) {
    if (!t || !is_traced(t)) return;
    uint64_t h = hload(t);
    if (h & (CAND_BIT | DEAD_BIT)) return;
    if (!c_major && (h & STABLE_BIT)) { atomic_store_explicit(&c_hit_stable, 1, memory_order_relaxed); return; }
    tw->traversed++;
    h = __atomic_add_fetch(header(t), 1ULL << CRC_SHIFT, __ATOMIC_RELAXED);
    while (((h >> KX_COLOR_SHIFT) & 3) != GRAY)
        /* clear the Delta bit only on the first visit of this detection (CRC 1): an incremental major may
         * see an object re-colored by a log applied between its steps, and must keep that touch visible */
        if (hcas(t, &h, (((h >> CRC_SHIFT) & CRC_MAX) == 1 ? with_color(h, GRAY) & ~DIRTY_BIT : with_color(h, GRAY)) | (c_im ? SEEN_BIT : 0))) {
            if (c_im && !(h & SEEN_BIT)) stack_push(&im_seen, t);
            stack_push(&tw->work, t); break;
        }
}
static void sb_child(void *t) {
    if (!t || !is_traced(t)) return;
    uint64_t h = hload(t);
    if (h & (CAND_BIT | DEAD_BIT)) return;
    while (((h >> KX_COLOR_SHIFT) & 3) != BLACK)
        if (hcas(t, &h, with_color(h, BLACK) & ~CRC_MASK)) { promote_atomic(t); stack_push(&tw->black, t); break; }
}
static void scan_child(void *t) { if (t && is_traced(t) && !(hload(t) & CAND_BIT)) stack_push(&tw->work, t); }
static void scan_one(void *x) {
    uint64_t h = hload(x);
    while (((h >> KX_COLOR_SHIFT) & 3) == GRAY) {
        int64_t ext = (int64_t)(h & KX_CNT_MASK) - (int64_t)((h >> CRC_SHIFT) & CRC_MAX);
        if (ext > 0) {
            if (hcas(x, &h, with_color(h, BLACK) & ~CRC_MASK)) {
                promote_atomic(x); stack_push(&tw->black, x);
                while (tw->black.len) { void *y = stack_pop(&tw->black); vtab(y)->trav(y, sb_child); }
                return;
            }
        } else if (hcas(x, &h, with_color(h, WHITE))) { vtab(x)->trav(x, scan_child); return; }
    }
}
/* Runs the current phase on this worker until all workers are out of work. */
static void phase_loop(Worker *w) {
    tw = w;
    for (;;) {
        while (w->work.len) {
            void *x = stack_pop(&w->work);
            if (p_phase == PHASE_MARK) { VISIT(); vtab(x)->trav(x, mg_child); } else scan_one(x);
            if (p_active > 1 && w->work.len > 2 * SHARE_CHUNK && __atomic_load_n(&p_idle, __ATOMIC_RELAXED)) {
                pthread_mutex_lock(&pmu);
                if (p_shared.len < SHARE_CHUNK) {
                    for (int i = 0; i < SHARE_CHUNK; i++) stack_push(&p_shared, stack_pop(&w->work));
                    if (__atomic_load_n(&p_idle, __ATOMIC_RELAXED)) pthread_cond_broadcast(&p_wait);
                }
                pthread_mutex_unlock(&pmu);
            }
        }
        if (p_active == 1) return;
        pthread_mutex_lock(&pmu);
        for (;;) {
            if (p_finished) { pthread_mutex_unlock(&pmu); return; }
            if (p_shared.len) {
                for (int i = 0; i < SHARE_CHUNK && p_shared.len; i++) stack_push(&w->work, stack_pop(&p_shared));
                break;
            }
            if (__atomic_add_fetch(&p_idle, 1, __ATOMIC_RELAXED) == p_active) { p_finished = 1; pthread_cond_broadcast(&p_wait); pthread_mutex_unlock(&pmu); return; }
            pthread_cond_wait(&p_wait, &pmu);
            if (!p_finished) __atomic_sub_fetch(&p_idle, 1, __ATOMIC_RELAXED);
        }
        pthread_mutex_unlock(&pmu);
    }
}
static void *helper_main(void *arg) {
    Worker *w = arg;
    uint64_t seen = 0;
    pthread_mutex_lock(&pmu);
    for (;;) {
        while (p_gen == seen && !p_stop) pthread_cond_wait(&p_go, &pmu);
        if (p_stop) break;
        seen = p_gen;
        if (w - workers >= p_active) continue;
        pthread_mutex_unlock(&pmu);
        phase_loop(w);
        pthread_mutex_lock(&pmu);
        if (++p_done == p_active - 1) pthread_cond_broadcast(&p_wait);
    }
    pthread_mutex_unlock(&pmu);
    return NULL;
}
static void start_helpers(void) {
    const char *e = getenv("KX_GC_THREADS");
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    int n = e && *e ? atoi(e) : (int)(cores < 4 ? cores : 4);
    if (n < 1) n = 1;
    if (n > MAX_THREADS) n = MAX_THREADS;
    n_threads = n;
    for (int i = 1; i < n; i++) {
        pthread_t t;
        if (pthread_create(&t, NULL, helper_main, &workers[i])) { n_threads = i; break; }
        pthread_detach(t);
    }
}
/* Runs one phase over `items` on `active` workers (the collector thread is worker 0). */
static void run_phase(int phase, void **items, size_t n, int active) {
    /* contiguous slices: neighbouring roots usually share cache lines and subgraphs */
    for (int w = 0; w < active; w++)
        for (size_t i = n * (size_t)w / (size_t)active; i < n * (size_t)(w + 1) / (size_t)active; i++) stack_push(&workers[w].work, items[i]);
    p_phase = phase;
    if (active > 1) {
        pthread_mutex_lock(&pmu);
        p_active = active; __atomic_store_n(&p_idle, 0, __ATOMIC_RELAXED); p_finished = 0; p_done = 0; p_gen++;
        pthread_cond_broadcast(&p_go);
        pthread_mutex_unlock(&pmu);
        phase_loop(&workers[0]);
        pthread_mutex_lock(&pmu);
        while (p_done < active - 1) pthread_cond_wait(&p_wait, &pmu);
        pthread_mutex_unlock(&pmu);
    } else {
        p_active = 1;
        phase_loop(&workers[0]);
    }
}
static void collect_phases_stats(void) {
    for (int i = 0; i < n_threads; i++) { c_traversed += workers[i].traversed; workers[i].traversed = 0; }
    int64_t dd = atomic_exchange_explicit(&c_stable_delta, 0, memory_order_relaxed); c_stable_n += dd; dbg_prom += dd;
}
static PtrStack cw_m; static uint64_t cw_id;
static void cw_child(void *t) {
    if (t && is_traced(t) && !has(t, CAND_BIT) && color(t) == WHITE && ref_count(t) > 0 && !has(t, DEAD_BIT)) {
        if (has(t, STABLE_BIT)) { clr_bit(t, STABLE_BIT); c_stable_n--; }
        set_bit(t, CAND_BIT); set_crc(t, cw_id & CRC_MAX); stack_push(&cw_m, t); stack_push(&c_work, t);
    }
}
static PtrStack cw_ends;    /* end index in cw_m of every set of the current detection */
static uint64_t cw_first_id;
static void collect_white(void *s) {
    if (color(s) != WHITE || has(s, CAND_BIT | DEAD_BIT) || ref_count(s) == 0) return;   /* count 0: freed meanwhile */
    if (!cw_ends.len) cw_first_id = next_set_id;
    cw_id = next_set_id++;
    if (has(s, STABLE_BIT)) { clr_bit(s, STABLE_BIT); c_stable_n--; }
    set_bit(s, CAND_BIT); set_crc(s, cw_id & CRC_MAX); stack_push(&cw_m, s); stack_push(&c_work, s);
    while (c_work.len) { void *x = c_work.items[--c_work.len]; vtab(x)->trav(x, cw_child); }
    stack_push(&cw_ends, (void *)(uintptr_t)cw_m.len);
    dbg_sets++;
}
static void batch_close(uint64_t valid_after) {
    if (!cw_ends.len) return;
    size_t n = cw_m.len, k = cw_ends.len;
    Batch *b = malloc(sizeof *b + n * sizeof(void *) + k * sizeof(size_t)); if (!b) rt_panic("OutOfMemoryError");
    b->next = NULL; b->valid_after = valid_after; b->first_id = cw_first_id; b->nsets = k;
    memcpy(b->members, cw_m.items, n * sizeof(void *));
    b->ends = (size_t *)(b->members + n);
    for (size_t i = 0; i < k; i++) b->ends[i] = (size_t)(uintptr_t)cw_ends.items[i];
    if (cands_tail) cands_tail->next = b; else cands = b;
    cands_tail = b;
    dbg_members += (int64_t)n; cw_m.len = 0; cw_ends.len = 0;
}
#define SLICE_ROOTS 32768   /* KX_GC_SLICE_ROOTS; 0 = unbounded */
static size_t slice_roots = SLICE_ROOTS;
static size_t q_seen;          /* queue length when the collector took the current log */
static int64_t carry_q = -1;   /* KX_GC_CARRY_QUEUE: skip detection at this queue length (default 0 = never) */
static uint64_t c_valid_after;                      /* hand-off count when the current detection ended */
static PtrStack c_deferred;   /* buffered roots whose minor detection stopped at the old generation */
/* Minor (major = 0): stable objects are opaque. Roots left non-white after a minor detection that hit a
 * stable object stay buffered in c_deferred; a major detection (every stable object traced) handles them
 * once MAJOR_RATIO times as many roots were deferred as there are stable objects (amortized O(1) per root). */
static size_t detect_prepare(int major, PtrStack *rootsp);
static void detect_finish(int major, PtrStack roots, size_t k, int hit_stable);
static void detect(int major) {
    PtrStack roots; size_t k = detect_prepare(major, &roots);
    int active = major && k >= PAR_MIN_ROOTS ? n_threads : 1;
    atomic_store_explicit(&c_hit_stable, 0, memory_order_relaxed);
    int64_t t0 = rt_nanos();
    run_phase(PHASE_MARK, roots.items, k, active);
    int64_t t1 = rt_nanos();
    run_phase(PHASE_SCAN, roots.items, k, active);
    dbg_t_mark += t1 - t0; dbg_t_scan += rt_nanos() - t1;
    detect_finish(major, roots, k, atomic_load_explicit(&c_hit_stable, memory_order_relaxed));
}
static size_t detect_prepare(int major, PtrStack *rootsp) {
    ep_detections++;
    c_major = major;
    if (major) { dbg_majors++; for (size_t i = 0; i < c_deferred.len; i++) { void *d = c_deferred.items[i]; if (color(d) == BLACK) set_color(d, PURPLE); stack_push(&c_roots, d); } c_deferred.len = 0; c_deferred_new = 0; }
    PtrStack roots = c_roots; c_roots = (PtrStack){0};
    /* bounded slice: a minor takes at most slice_roots roots, the rest stays buffered for the next one */
    if (!major && slice_roots && roots.len > slice_roots) {
        for (size_t i = slice_roots; i < roots.len; i++) stack_push(&c_roots, roots.items[i]);
        roots.len = slice_roots;
    }
    size_t k = 0;
    /* Pass 1: unbuffer, and free roots that died while buffered. Freeing cascades through dec_apply and
     * may free roots already unbuffered here (count 0, BLACK), so the roots to trace are chosen only
     * afterwards (pass 2); otherwise a root could be freed while it is being traced. */
    for (size_t i = 0; i < roots.len; i++) {
        void *s = roots.items[i];
        clr_bit(s, KX_BUF_BIT);
        if (has(s, ZOMBIE_BIT)) { clr_bit(s, ZOMBIE_BIT); ret_push(s); roots.items[i] = NULL; continue; }
        if (has(s, DEAD_BIT) && !has(s, CAND_BIT)) { clr_bit(s, DEAD_BIT); release_dead(s); roots.items[i] = NULL; }
    }
    for (size_t i = 0; i < roots.len; i++) {
        void *s = roots.items[i];
        if (!s || has(s, CAND_BIT | DEAD_BIT)) continue;
        if (color(s) == PURPLE && ref_count(s) > 0) { set_color(s, GRAY); clr_bit(s, DIRTY_BIT); roots.items[k++] = s; }
    }
    *rootsp = roots;
    return k;
}
static void detect_finish(int major, PtrStack roots, size_t k, int hit_stable) {
    int64_t tv0 = c_traversed; collect_phases_stats();
    if (major) dbg_major_visits += c_traversed - tv0; else dbg_minor_visits += c_traversed - tv0;
    dbg_roots_k += (int64_t)k;
    for (size_t i = 0; i < k; i++) collect_white(roots.items[i]);
    if (!major && hit_stable)
        for (size_t i = 0; i < k; i++) {
            void *s = roots.items[i];
            if (!has(s, CAND_BIT) && !has(s, KX_BUF_BIT)) { set_bit(s, KX_BUF_BIT); stack_push(&c_deferred, s); c_deferred_new++; }
        }
    free(roots.items);
    pthread_mutex_lock(&mu); uint64_t h = handed; pthread_mutex_unlock(&mu);
    batch_close(h);
}
// Incremental major: the old-generation pass (MarkGray, then Scan) runs in bounded steps (see below),
// one step after every applied log and more while the collector is
// idle, so a major never stalls log application (and through back-pressure the mutator) for long.
// Logs applied between steps change counts and colors exactly as a concurrently running mutator would in
// Bacon & Rajan's concurrent collector: every touched object is dirty (the Delta bit is cleared only on an
// object's first visit), and candidates are still checked by Delta + Sigma with current counts. While a
// major is in progress there is no minor detection (they share the CRC field and the worker stacks;
// roots stay buffered), but logs, validation and freeing go on: every object the major visits is marked
// SEEN (header bit 63) and listed; a SEEN object that dies or whose cycle is freed is held (DEAD bit set,
// memory not returned) until the major ends, since the trace's stacks may still point to it, and the trace
// never follows the fields of a dead or held object. Objects the major never visited are returned at once:
// the trace can only reach them through a live object, and a live object cannot point to freed memory.
// Step size: KX_GC_MAJOR_STEP_US (default 1000) of tracing per applied log, in 512-visit pieces; with
// KX_GC_MAJOR_STEP_US=0, a fixed KX_GC_MAJOR_STEP visits (default 2048).
#define MAJOR_STEP 2048       /* visits per step when KX_GC_MAJOR_STEP_US=0 */
#define MAJOR_STEP_US 1000    /* default: steps of 512 visits until 1 ms per log has passed */
static int im_state;                 /* 0 none, 1 MarkGray, 2 Scan */
static PtrStack im_roots; static size_t im_k;
static int64_t im_step_budget = -1, im_m0, im_w0, im_steps;
static int incremental_major = -1;   /* KX_GC_INC_MAJOR (default 1) */
static void major_adapt(void);
static int64_t im_step_ns = -1, dbg_held, dbg_held_max;
static inline int im_dead(void *x) { return ref_count(x) == 0 || has(x, DEAD_BIT); }
static void ret_push(void *p) {
    if (c_im && has(p, SEEN_BIT)) { set_bit(p, DEAD_BIT); stack_push(&c_hold, p); dbg_held++; }
    else stack_push(&c_ret, p);
}
static void im_begin(void) {
    im_k = detect_prepare(1, &im_roots);
    c_im = 1;
    for (size_t i = 0; i < im_k; i++) { set_bit(im_roots.items[i], SEEN_BIT); stack_push(&im_seen, im_roots.items[i]); }
    im_m0 = dbg_members + dbg_dead; im_w0 = c_traversed;
    tw = &workers[0]; p_active = 1;
    for (size_t i = 0; i < im_k; i++) stack_push(&workers[0].work, im_roots.items[i]);
    im_state = 1;
}
/* One bounded step; returns 1 when the major finished. */
static int im_step(int64_t budget) {
    Worker *w = &workers[0]; tw = w; c_major = 1;
    int64_t n = 0, t0 = rt_nanos();
    if (im_state == 1) {
        while (w->work.len && n < budget) { void *x = stack_pop(&w->work); if (im_dead(x)) continue; VISIT(); vtab(x)->trav(x, mg_child); n++; }
        dbg_t_mark += rt_nanos() - t0;
        if (w->work.len) return 0;
        for (size_t i = 0; i < im_k; i++) stack_push(&w->work, im_roots.items[i]);
        im_state = 2; t0 = rt_nanos();
    }
    while (n < budget) {
        if (w->black.len) { void *y = stack_pop(&w->black); if (!im_dead(y)) vtab(y)->trav(y, sb_child); }
        else if (w->work.len) {
            void *x = stack_pop(&w->work); uint64_t h = hload(x);
            if (((h >> KX_COLOR_SHIFT) & 3) == GRAY) {
                int64_t ext = (int64_t)(h & KX_CNT_MASK) - (int64_t)((h >> CRC_SHIFT) & CRC_MAX);
                if (ext > 0) { set_color(x, BLACK); set_crc(x, 0); promote_atomic(x); stack_push(&w->black, x); }
                else { set_color(x, WHITE); if (!im_dead(x)) vtab(x)->trav(x, scan_child); }
            }
        } else break;
        n++;
    }
    dbg_t_scan += rt_nanos() - t0; im_steps++;
    if (w->work.len || w->black.len) return 0;
    detect_finish(1, im_roots, im_k, 0);
    im_roots = (PtrStack){0}; im_state = 0; c_im = 0;
    for (size_t i = 0; i < im_seen.len; i++) clr_bit(im_seen.items[i], SEEN_BIT);
    im_seen.len = 0;
    if ((int64_t)c_hold.len > dbg_held_max) dbg_held_max = (int64_t)c_hold.len;
    stack_move(&c_ret, &c_hold);
    major_adapt();
    return 1;
}
static uint64_t v_sid; static int64_t v_internal;
static inline int in_set(void *t) { return has(t, CAND_BIT) && crc(t) == v_sid; }
static void count_child(void *t) { if (t && is_traced(t) && in_set(t)) v_internal++; }
static void free_cycle_child(void *t) { if (t && is_traced(t) && !in_set(t)) dec_apply(t); }
/* Delta + Sigma tests; frees a passing cycle, re-roots a failing one. Returns objects freed. */
typedef struct { size_t n; void **a; } Members;
static size_t validate(uint64_t id, Members m) {
    int ok = 1;
    v_sid = id & CRC_MAX;
    for (size_t i = 0; i < m.n && ok; i++) if (has(m.a[i], DIRTY_BIT | DEAD_BIT)) ok = 0;   /* Delta */
    if (!ok) dbg_fail_delta++;
    if (ok) {                                                                                        /* Sigma */
        int64_t ext = 0; v_internal = 0;
        for (size_t i = 0; i < m.n; i++) { void *x = m.a[i]; ext += (int64_t)ref_count(x); vtab(x)->trav(x, count_child); }
        if (ext - v_internal != 0) { ok = 0; dbg_fail_sigma++; }
    }
    size_t freed = 0;
    if (ok) {
        for (size_t i = 0; i < m.n; i++) { void *x = m.a[i]; vtab(x)->trav(x, free_cycle_child); }
        for (size_t i = 0; i < m.n; i++) { void *x = m.a[i]; clr_bit(x, CAND_BIT); set_color(x, BLACK); set_crc(x, 0); if (has(x, KX_BUF_BIT)) set_bit(x, ZOMBIE_BIT); else ret_push(x); }
        freed = m.n; dbg_pass++;
    } else {
        for (size_t i = 0; i < m.n; i++) {
            void *x = m.a[i];
            clr_bit(x, CAND_BIT); set_color(x, BLACK); set_crc(x, 0);
            if (has(x, DEAD_BIT)) { if (has(x, KX_BUF_BIT)) release_zombie(x); else release_dead(x); }
            else if (ref_count(x) > 0) possible_root(x);
        }
    }
    return freed;
}
/* Validates every candidate set detected before hand-off `upto` (all sets when force). */
#define VALIDATE_BUDGET 65536   /* candidate members validated per log; the rest waits for the next log */
static size_t validate_ready(uint64_t upto, int force) {
    size_t freed = 0, seen = 0;
    while (cands && (force || cands->valid_after < upto) && (force || seen < VALIDATE_BUDGET)) {
        seen += cands->nsets ? cands->ends[cands->nsets - 1] : 0;
        Batch *b = cands; cands = b->next; if (!cands) cands_tail = NULL;
        size_t start = 0;
        for (size_t i = 0; i < b->nsets; i++) {
            freed += validate(b->first_id + i, (Members){ b->ends[i] - start, b->members + start });
            start = b->ends[i];
        }
        free(b);
    }
    return freed;
}
static void publish_returns(void) {
    if (!c_ret.len) return;
    pthread_mutex_lock(&mu); stack_move(&ret_shared, &c_ret); pthread_mutex_unlock(&mu);
}
/* Stop-the-world drain (mutator waits): detect and validate until no garbage is left. */
static void full_cycle(void) {
    while (im_state && !im_step(INT64_MAX)) {}
    for (;;) {
        size_t freed = validate_ready(0, 1);
        if (c_roots.len || c_deferred.len) { detect(1); freed += validate_ready(0, 1); }
        gc_st.reclaimed += (int64_t)freed;
        if (!freed && !c_roots.len && !c_deferred.len && !cands) break;
    }
}
/* Geometric major backoff for the incremental major (same rule as in collector_main). */
static void major_adapt(void) {
    int64_t found = dbg_members + dbg_dead - im_m0, work = c_traversed - im_w0;
    if (work > 64 * found) { if (major_ratio < MAJOR_RATIO_MAX) major_ratio *= 2; }
    else major_ratio = MAJOR_RATIO;
}
static void *collector_main(void *arg) {
    (void)arg;
    { const char *e = getenv("KX_GC_SLICE_ROOTS"); if (e && *e) slice_roots = (size_t)atoll(e); }
    start_helpers();
    pthread_mutex_lock(&mu);
    for (;;) {
        while (!q_head && !full_req && !shutting) {
            if (im_state) {   /* idle collector: advance the incremental major */
                pthread_mutex_unlock(&mu);
                int64_t td = rt_nanos(); int done = im_step(im_step_budget); dbg_t_detect += rt_nanos() - td;
                (void)done; publish_returns();
                pthread_mutex_lock(&mu); continue;
            }
            coll_quiet = 1; pthread_cond_wait(&cv_work, &mu);
        }
        coll_quiet = 0;
        if (q_head) {
            Log *l = q_head; q_head = l->next; if (!q_head) q_tail = NULL; q_len--; q_seen = q_len;
            pthread_cond_broadcast(&cv_done);
            pthread_mutex_unlock(&mu);
            int64_t ta = rt_nanos(); apply_log(l); dbg_logs++;
            int64_t tb = rt_nanos(); size_t freed = validate_ready(l->seq, 0);
            int64_t tc = rt_nanos(); dbg_t_apply += tb - ta; dbg_t_valid += tc - tb;
            int64_t major_at = major_ratio * (c_stable_n > (int64_t)c_threshold ? c_stable_n : (int64_t)c_threshold);
            /* Carry-over (opt-in, KX_GC_CARRY_QUEUE): while the queue holds that many logs, only apply logs and
             * leave detection (roots stay buffered) for a later log, unless the buffer grew 8x past the
             * threshold. Detection work then never stacks up behind a full queue. */
            if (carry_q < 0) { const char *e = getenv("KX_GC_CARRY_QUEUE"); carry_q = e && *e ? atoll(e) : 0; }
            int behind = carry_q > 0 && (int64_t)q_seen >= carry_q && c_roots.len < 8 * c_threshold;
            if (incremental_major < 0) {
                const char *e = getenv("KX_GC_INC_MAJOR"); incremental_major = e && *e ? *e != '0' : 1;
                e = getenv("KX_GC_MAJOR_STEP"); im_step_budget = e && *e ? atoll(e) : MAJOR_STEP;
                e = getenv("KX_GC_MAJOR_STEP_US"); im_step_ns = (e && *e ? atoll(e) : MAJOR_STEP_US) * 1000;
            }
            if (im_state) {
                int64_t td = rt_nanos();
                if (im_step_ns > 0) { while (!im_step(512) && rt_nanos() - td < im_step_ns) {} }
                else im_step(im_step_budget);
                dbg_t_detect += rt_nanos() - td;
            }
            else if (incremental_major && !behind && c_deferred_new >= major_at) {
                dbg_majors++; gc_st.collections++; im_begin();
            }
            else if (!behind && (c_roots.len >= c_threshold || c_deferred_new >= major_at)) {
                size_t nroots = c_roots.len;
                int64_t m0 = dbg_members + dbg_dead, w0 = c_traversed;
                int major = c_deferred_new >= major_at;
                if (major) { pthread_mutex_lock(&mu); atomic_store(&c_in_major, 1); pthread_cond_broadcast(&cv_done); pthread_mutex_unlock(&mu); }
                int64_t td = rt_nanos(); detect(major);
                dbg_t_detect += rt_nanos() - td;
                gc_st.collections++;
                /* adaptive: traversal work mostly spent on live data -> detect less often */
                int64_t found = dbg_members + dbg_dead - m0, work = c_traversed - w0;
                if (major) {
                    /* old generation only when needed: a major that mostly re-traced live data makes the
                     * next one rarer (geometric backoff); one that found garbage resets the ratio */
                    if (work > 64 * found) { if (major_ratio < MAJOR_RATIO_MAX) major_ratio *= 2; }
                    else major_ratio = MAJOR_RATIO;
                } else if (work > 4 * found + 2 * (int64_t)nroots) { if (c_threshold < C_MAX_THRESHOLD) c_threshold *= 2; }
                else if (c_threshold > 10000) c_threshold /= 2;
            }
            gc_st.reclaimed += (int64_t)freed;
            publish_returns();
            pthread_mutex_lock(&mu);
            applied = l->seq; l->next = log_pool; log_pool = l;
            if (q_len <= max_queued && atomic_load_explicit(&c_in_major, memory_order_relaxed)) atomic_store(&c_in_major, 0);
            pthread_cond_broadcast(&cv_done);
        } else if (full_req) {
            pthread_mutex_unlock(&mu);
            full_cycle();
            publish_returns();
            pthread_mutex_lock(&mu);
            full_req = 0; full_done++;
            pthread_cond_broadcast(&cv_done);
        } else break;
    }
    pthread_mutex_unlock(&mu);
    return NULL;
}

static void epoch_debug(void) {
    const char *e = getenv("KX_GC_DEBUG");
    if (e && *e) fprintf(stderr, "epoch: epochs=%lld logs=%lld detections=%lld sets=%lld pass=%lld fail-delta=%lld fail-sigma=%lld dead=%lld majors=%lld stable=%lld deferred=%zu\n",
        (long long)ep_epochs, (long long)dbg_logs, (long long)ep_detections, (long long)dbg_sets, (long long)dbg_pass, (long long)dbg_fail_delta, (long long)dbg_fail_sigma, (long long)dbg_dead, (long long)dbg_majors, (long long)c_stable_n, c_deferred.len);
    if (e && *e) fprintf(stderr, "epoch: collector apply=%.1fms validate=%.1fms detect=%.1fms (mark %.1f scan %.1f) threads=%d\n", dbg_t_apply/1e6, dbg_t_valid/1e6, dbg_t_detect/1e6, dbg_t_mark/1e6, dbg_t_scan/1e6, n_threads);
    if (e && *e) fprintf(stderr, "epoch: roots=%lld minor-visits=%lld major-visits=%lld members=%lld\n", (long long)dbg_roots_k, (long long)dbg_minor_visits, (long long)dbg_major_visits, (long long)dbg_members);
    if (e && *e) fprintf(stderr, "epoch: prom=%lld dem-touch=%lld held=%lld held-max=%lld major-steps=%lld\n", (long long)dbg_prom, (long long)dbg_dem_touch, (long long)dbg_held, (long long)dbg_held_max, (long long)im_steps);
    if (e && *e) fprintf(stderr, "epoch: backpressure total=%.3fms max=%.3fms boundary max=%.3fms last collect=%.3fms\n", dbg_bp_ns/1e6, dbg_bp_max/1e6, dbg_bmax/1e6, dbg_exit_ns/1e6);
}
/* Program exit drains the queue only when someone looks at the heap afterwards: --leak-check
 * (RT_LEAKCHECK) and --alloc malloc (ASan / macOS leaks builds), or KX_GC_EXIT_DRAIN=1. Otherwise the
 * process exits with the queue as it is, and the OS reclaims the memory. */
#if defined(RT_LEAKCHECK) || defined(RT_PLAIN_MALLOC)
#define EXIT_DRAIN_DEFAULT 1
#else
#define EXIT_DRAIN_DEFAULT 0
#endif
static int exit_drain(void) {
    const char *e = getenv("KX_GC_EXIT_DRAIN");
    return e && *e ? *e != '0' : EXIT_DRAIN_DEFAULT;
}
/* Stop-the-world: hand off the log, wait for the collector, drain all garbage. Called only at exit. */
void rt_gc_collect(void) {
    if (in_boundary) return;
    if (!exit_drain()) { epoch_debug(); return; }
    in_boundary = 1;
    int64_t t0 = rt_nanos();
    for (int round = 0;; round++) {
        int had_ops = cur && cur->n;
        handoff();
        pthread_mutex_lock(&mu);
        while (q_head) pthread_cond_wait(&cv_done, &mu);
        uint64_t want = full_done + 1; full_req = 1; pthread_cond_signal(&cv_work);
        while (full_done < want) pthread_cond_wait(&cv_done, &mu);
        stack_move(&ret_local, &ret_shared);
        pthread_mutex_unlock(&mu);
        size_t returned = ret_local.len - ret_pos;
        process_returns(-1, t0);
        if (!had_ops && !returned && !(cur && cur->n)) break;
    }
    dbg_exit_ns = rt_nanos() - t0;
    note_pause(t0);
    in_boundary = 0;
    epoch_debug();
}
const char *rt_gc_name(void) { return "epoch"; }
void rt_gc_stats(KxGcStats *out) {
    pthread_mutex_lock(&mu);   /* collector-side counters are updated under no lock; read them quiescent */
    *out = gc_st;
    pthread_mutex_unlock(&mu);
}
