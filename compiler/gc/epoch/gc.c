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
//     collector falls more than MAX_QUEUED logs behind (back-pressure).
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
// Traced objects live in 64 KiB-aligned size-class slabs registered in a lock-free page table, so the
// collector recognizes traced pointers without dereferencing (acyclic objects may be freed concurrently).
// Program exit / rt_gc_collect: stop-the-world drain until nothing is left (all garbage reclaimed).
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
#define VISIT() (cc_visits++)
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

// ---- traced objects: 64 KiB-aligned slabs + lock-free page registry ----
#define PAGE_SHIFT 16
#define PAGE (1u << PAGE_SHIFT)
#define REG_BITS 20
static _Atomic uintptr_t reg[1u << REG_BITS];
static inline size_t reg_slot(uintptr_t pg) { return (size_t)((pg * 0x9E3779B97F4A7C15ULL) >> (64 - REG_BITS)); }
static void reg_add(uintptr_t pg) {
    for (size_t i = reg_slot(pg);; i = (i + 1) & ((1u << REG_BITS) - 1)) {
        uintptr_t z = 0;
        if (atomic_compare_exchange_strong_explicit(&reg[i], &z, pg, memory_order_release, memory_order_relaxed) || z == pg) return;
    }
}
/* Pointer into a traced slab? Never dereferences p. */
static inline int is_traced(void *p) {
    if (!p) return 0;
    uintptr_t pg = ((uintptr_t)p >> PAGE_SHIFT) + 1;
    for (size_t i = reg_slot(pg);; i = (i + 1) & ((1u << REG_BITS) - 1)) {
        uintptr_t v = atomic_load_explicit(&reg[i], memory_order_acquire);
        if (v == pg) return 1;
        if (v == 0) return 0;
    }
}
#define CLASSES 32
static void *free_list[CLASSES + 1];
static char *bump, *bump_end;
typedef struct Big { struct Big *next; size_t size; } Big;
static Big *big_free;
static void *page_alloc(size_t bytes) {
    void *p = NULL;
    if (posix_memalign(&p, PAGE, bytes)) rt_panic("OutOfMemoryError");
    for (size_t o = 0; o < bytes; o += PAGE) reg_add((((uintptr_t)p + o) >> PAGE_SHIFT) + 1);
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
#define MAX_QUEUED 4
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
    if (q_len > MAX_QUEUED) { int64_t w0 = rt_nanos(); while (q_len > MAX_QUEUED) pthread_cond_wait(&cv_done, &mu); int64_t d = rt_nanos() - w0; dbg_bp_ns += d; if (d > dbg_bp_max) dbg_bp_max = d; }
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
//   63         40 39   38      37     36     35      34        33  32   31                 0
//   +------------+----+-------+------+------+-------+----------+-------+-------------------+
//   | CRC / set  | -  | zombie| dead | cand | dirty | buffered | color |  reference count  |
//   +------------+----+-------+------+------+-------+----------+-------+-------------------+
//
//   dirty:  touched by an RC operation or write barrier since the last MarkGray visit (Delta test)
//   cand:   member of a candidate cycle; the CRC field then holds the cycle's set id
//   dead:   count reached zero while buffered or a candidate; freed by whoever removes it from there
//   zombie: freed cycle member still in the root buffer; returned when the buffer is next drained
#define DIRTY_BIT (1ULL << 35)
#define CAND_BIT (1ULL << 36)
#define DEAD_BIT (1ULL << 37)
#define ZOMBIE_BIT (1ULL << 38)   /* freed cycle member still referenced by the root buffer */
#define CRC_SHIFT 40
#define CRC_MAX 0xffffffULL
static inline int has(void *p, uint64_t b) { return (*header(p) & b) != 0; }
static inline void set_bit(void *p, uint64_t b) { *header(p) |= b; }
static inline void clr_bit(void *p, uint64_t b) { *header(p) &= ~b; }
static inline uint64_t crc(void *p) { return *header(p) >> CRC_SHIFT; }
static inline void set_crc(void *p, uint64_t c) { *header(p) = (*header(p) & ((1ULL << CRC_SHIFT) - 1)) | (c << CRC_SHIFT); }

static PtrStack c_roots, c_work, c_ret;                 /* collector-local */
static size_t c_threshold = 10000;
#define C_MAX_THRESHOLD (1u << 18)
typedef struct Cand { struct Cand *next; uint64_t id, valid_after; struct { size_t n; void *a[]; } m; } Cand;
static Cand *cands;
static uint64_t next_set_id = 1;
static int64_t c_traversed, dbg_members, dbg_sets, dbg_pass, dbg_fail_delta, dbg_fail_sigma, dbg_dead, dbg_logs;

static void dec_apply(void *p);
static void release_dead_child(void *t) { if (t && is_traced(t)) dec_apply(t); }
/* Count reached zero (no candidate set or root buffer holds it): decrement traced children, return memory. */
static void release_dead(void *p) {
    dbg_dead++;
    set_color(p, BLACK);
    stack_push(&c_ret, p);
    vtab(p)->trav(p, release_dead_child);
}
static void possible_root(void *p) {
    set_color(p, PURPLE);
    if (!has(p, KX_BUF_BIT)) { set_bit(p, KX_BUF_BIT); stack_push(&c_roots, p); }
}
static void dec_apply(void *p) {
    uint64_t h = *header(p);
    if ((h & KX_CNT_MASK) == 0) rt_panic("epoch GC: reference count underflow");
    *header(p) = (h - 1) | DIRTY_BIT;
    if (ref_count(p) == 0) { if (has(p, KX_BUF_BIT | CAND_BIT)) set_bit(p, DEAD_BIT); else release_dead(p); }
    else possible_root(p);
}
static void apply_log(Log *l) {
    for (size_t i = 0; i < l->n; i++) {
        uintptr_t e = l->a[i]; void *p = (void *)(e & ~(uintptr_t)3);
        if ((e & 3) == OP_INC) *header(p) = (*header(p) + 1) | DIRTY_BIT;
        else if ((e & 3) == OP_MOD) set_bit(p, DIRTY_BIT);
    }
    for (size_t i = 0; i < l->n; i++) {
        uintptr_t e = l->a[i];
        if ((e & 3) == OP_DEC) dec_apply((void *)(e & ~(uintptr_t)3));
    }
}

// Concurrent trial deletion on CRC (header bits 40-63). Objects already in a candidate set are opaque.
static void mg_child(void *t) {
    if (!t || !is_traced(t) || has(t, CAND_BIT)) return;
    VISIT(); c_traversed++;
    if (color(t) != GRAY) {
        set_color(t, GRAY); clr_bit(t, DIRTY_BIT);
        uint64_t c = ref_count(t); set_crc(t, c > CRC_MAX ? CRC_MAX : c);
        stack_push(&c_work, t);
    }
    if (crc(t) > 0 && crc(t) < CRC_MAX) set_crc(t, crc(t) - 1);
}
static void mark_gray(void *s) {
    if (color(s) == GRAY) return;
    set_color(s, GRAY); clr_bit(s, DIRTY_BIT);
    uint64_t c = ref_count(s); set_crc(s, c > CRC_MAX ? CRC_MAX : c);
    stack_push(&c_work, s);
    while (c_work.len) { void *x = c_work.items[--c_work.len]; vtab(x)->trav(x, mg_child); }
}
static PtrStack c_black;
static void sb_child(void *t) {
    if (t && is_traced(t) && !has(t, CAND_BIT) && color(t) != BLACK) { set_color(t, BLACK); stack_push(&c_black, t); }
}
static void scan_child(void *t) { if (t && is_traced(t) && !has(t, CAND_BIT)) stack_push(&c_work, t); }
static void scan(void *s) {
    stack_push(&c_work, s);
    while (c_work.len) {
        void *x = c_work.items[--c_work.len];
        if (color(x) != GRAY) continue;
        if (crc(x) > 0) {
            set_color(x, BLACK); stack_push(&c_black, x);
            while (c_black.len) { void *y = c_black.items[--c_black.len]; vtab(y)->trav(y, sb_child); }
        } else { set_color(x, WHITE); vtab(x)->trav(x, scan_child); }
    }
}
static PtrStack cw_m; static uint64_t cw_id;
static void cw_child(void *t) {
    if (t && is_traced(t) && !has(t, CAND_BIT) && color(t) == WHITE) {
        set_bit(t, CAND_BIT); set_crc(t, cw_id & CRC_MAX); stack_push(&cw_m, t); stack_push(&c_work, t);
    }
}
static void collect_white(void *s) {
    if (color(s) != WHITE || has(s, CAND_BIT)) return;
    cw_id = next_set_id++; if ((cw_id & CRC_MAX) == 0) cw_id = next_set_id++;
    cw_m.len = 0;
    set_bit(s, CAND_BIT); set_crc(s, cw_id & CRC_MAX); stack_push(&cw_m, s); stack_push(&c_work, s);
    while (c_work.len) { void *x = c_work.items[--c_work.len]; vtab(x)->trav(x, cw_child); }
    Cand *c = malloc(sizeof *c + cw_m.len * sizeof(void *)); if (!c) rt_panic("OutOfMemoryError");
    c->id = cw_id; c->valid_after = 0; c->m.n = cw_m.len; memcpy(c->m.a, cw_m.items, cw_m.len * sizeof(void *));
    c->next = cands; cands = c; dbg_sets++; dbg_members += (int64_t)c->m.n;
}
static uint64_t c_valid_after;                      /* hand-off count when the current detection ended */
static void detect(void) {
    ep_detections++;
    PtrStack roots = c_roots; c_roots = (PtrStack){0};
    size_t k = 0;
    for (size_t i = 0; i < roots.len; i++) {
        void *s = roots.items[i];
        clr_bit(s, KX_BUF_BIT);
        if (has(s, ZOMBIE_BIT)) { stack_push(&c_ret, s); continue; }
        if (has(s, DEAD_BIT)) { if (!has(s, CAND_BIT)) { clr_bit(s, DEAD_BIT); release_dead(s); } continue; }
        if (has(s, CAND_BIT)) continue;
        if (color(s) == PURPLE && ref_count(s) > 0) { mark_gray(s); roots.items[k++] = s; }
        else if (color(s) == PURPLE) set_color(s, BLACK);   /* already gray: reached from an earlier root */
    }
    for (size_t i = 0; i < k; i++) scan(roots.items[i]);
    Cand *old = cands;
    for (size_t i = 0; i < k; i++) collect_white(roots.items[i]);
    free(roots.items);
    pthread_mutex_lock(&mu); uint64_t h = handed; pthread_mutex_unlock(&mu);
    for (Cand *c = cands; c != old; c = c->next) c->valid_after = h;
}
static uint64_t v_sid; static int64_t v_internal;
static inline int in_set(void *t) { return has(t, CAND_BIT) && crc(t) == v_sid; }
static void count_child(void *t) { if (t && is_traced(t) && in_set(t)) v_internal++; }
static void free_cycle_child(void *t) { if (t && is_traced(t) && !in_set(t)) dec_apply(t); }
/* Delta + Sigma tests; frees a passing cycle, re-roots a failing one. Returns objects freed. */
static size_t validate(Cand *c) {
    int ok = 1, sig_fail = 0;
    v_sid = c->id & CRC_MAX;
    for (size_t i = 0; i < c->m.n && ok; i++) if (has(c->m.a[i], DIRTY_BIT | DEAD_BIT)) ok = 0;   /* Delta */
    if (!ok) dbg_fail_delta++;
    if (ok) {                                                                                        /* Sigma */
        int64_t ext = 0; v_internal = 0;
        for (size_t i = 0; i < c->m.n; i++) { void *x = c->m.a[i]; ext += (int64_t)ref_count(x); vtab(x)->trav(x, count_child); }
        if (ext - v_internal != 0) { ok = 0; sig_fail = 1; dbg_fail_sigma++; }
    }
    size_t freed = 0;
    if (ok) {
        for (size_t i = 0; i < c->m.n; i++) { void *x = c->m.a[i]; vtab(x)->trav(x, free_cycle_child); }
        for (size_t i = 0; i < c->m.n; i++) { void *x = c->m.a[i]; clr_bit(x, CAND_BIT); set_color(x, BLACK); if (has(x, KX_BUF_BIT)) set_bit(x, ZOMBIE_BIT); else stack_push(&c_ret, x); }
        freed = c->m.n; dbg_pass++;
    } else {
        for (size_t i = 0; i < c->m.n; i++) {
            void *x = c->m.a[i];
            clr_bit(x, CAND_BIT); set_color(x, BLACK);
            if (has(x, DEAD_BIT)) { if (!has(x, KX_BUF_BIT)) { clr_bit(x, DEAD_BIT); release_dead(x); } }
            else if (ref_count(x) > 0) possible_root(x);
        }
    }
    free(c);
    return freed;
}
/* Validates every candidate set detected before hand-off `upto` (all sets when force). */
static size_t validate_ready(uint64_t upto, int force) {
    size_t freed = 0;
    Cand **pp = &cands;
    while (*pp) {
        Cand *c = *pp;
        if (force || c->valid_after < upto) { *pp = c->next; freed += validate(c); } else pp = &c->next;
    }
    return freed;
}
static void publish_returns(void) {
    if (!c_ret.len) return;
    pthread_mutex_lock(&mu); stack_move(&ret_shared, &c_ret); pthread_mutex_unlock(&mu);
}
/* Stop-the-world drain (mutator waits): detect and validate until no garbage is left. */
static void full_cycle(void) {
    for (;;) {
        size_t freed = validate_ready(0, 1);
        if (c_roots.len) { detect(); freed += validate_ready(0, 1); }
        gc_st.reclaimed += (int64_t)freed;
        if (!freed && !c_roots.len && !cands) break;
    }
}
static void *collector_main(void *arg) {
    (void)arg;
    pthread_mutex_lock(&mu);
    for (;;) {
        while (!q_head && !full_req && !shutting) { coll_quiet = 1; pthread_cond_wait(&cv_work, &mu); }
        coll_quiet = 0;
        if (q_head) {
            Log *l = q_head; q_head = l->next; if (!q_head) q_tail = NULL; q_len--;
            pthread_cond_broadcast(&cv_done);
            pthread_mutex_unlock(&mu);
            apply_log(l); dbg_logs++;
            size_t freed = validate_ready(l->seq, 0);
            if (c_roots.len >= c_threshold) {
                size_t nroots = c_roots.len;
                int64_t m0 = dbg_members, w0 = c_traversed;
                detect();
                gc_st.collections++;
                /* adaptive: traversal work mostly spent on live data -> detect less often */
                int64_t found = dbg_members - m0, work = c_traversed - w0;
                if (work > 4 * found + 2 * (int64_t)nroots) { if (c_threshold < C_MAX_THRESHOLD) c_threshold *= 2; }
                else if (c_threshold > 10000) c_threshold /= 2;
            }
            gc_st.reclaimed += (int64_t)freed;
            publish_returns();
            pthread_mutex_lock(&mu);
            applied = l->seq; l->next = log_pool; log_pool = l;
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
    if (e && *e) fprintf(stderr, "epoch: epochs=%lld logs=%lld detections=%lld sets=%lld pass=%lld fail-delta=%lld fail-sigma=%lld dead=%lld\n",
        (long long)ep_epochs, (long long)dbg_logs, (long long)ep_detections, (long long)dbg_sets, (long long)dbg_pass, (long long)dbg_fail_delta, (long long)dbg_fail_sigma, (long long)dbg_dead);
    if (e && *e) fprintf(stderr, "epoch: backpressure total=%.3fms max=%.3fms boundary max=%.3fms last collect=%.3fms\n", dbg_bp_ns/1e6, dbg_bp_max/1e6, dbg_bmax/1e6, dbg_exit_ns/1e6);
}
/* Stop-the-world: hand off the log, wait for the collector, drain all garbage (also run at exit). */
void rt_gc_collect(void) {
    if (in_boundary) return;
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
