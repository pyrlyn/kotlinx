// The three graph passes of Bacon & Rajan synchronous cycle collection ("trial deletion"), shared by
// the default and incremental implementations. Included after object.inc. The including file defines
// the root buffer and drives the passes:
//
//   MarkRoots    mark_gray(root)      subtract every internal reference: walk the subgraph reachable
//                                     from the root, coloring it GRAY and decrementing each child's count.
//   ScanRoots    scan(root)           a GRAY object whose count is still > 0 is referenced from outside
//                                     the subgraph: restore it and everything it reaches (scan_black);
//                                     a GRAY object at 0 becomes WHITE (garbage candidate).
//   CollectRoots collect_white(root)  move every WHITE object to `garbage`; acyclic children get their
//                                     reference released through the normal RC path.
//
// After CollectRoots the caller releases the untraced fields of every garbage object (vtab->relo) and
// only then frees them, because garbage objects may still point at each other.
// Iterative (explicit stacks) so deep structures cannot overflow the C stack.

#ifndef CC_CLAIM_SKIPS_BUFFERED
#error "define CC_CLAIM_SKIPS_BUFFERED (0 or 1) before including trial_deletion.inc"
#endif

static PtrStack cc_work;      /* MarkGray / Scan / CollectWhite work list */
static PtrStack cc_blacken;   /* ScanBlack work list (separate: scan_black runs inside scan) */
static PtrStack cc_garbage;   /* garbage found by CollectWhite, freed by the caller */
static size_t cc_traced;      /* objects visited by mark_gray in the current collection */

/* MarkGray child visitor: trial-delete the internal reference to a cyclic child. */
static void mark_gray_child(void *child) {
    if (child && is_cyclic(child)) { (*header(child))--; stack_push(&cc_work, child); }
}
static void mark_gray(void *root) {
    size_t traced = 0;
    stack_push(&cc_work, root);
    while (cc_work.len) {
        void *obj = stack_pop(&cc_work);
        if (color(obj) == GRAY) continue;
        CC_VISIT(obj);
        set_color(obj, GRAY);
        traced++;
        vtab(obj)->trav(obj, mark_gray_child);
    }
    cc_traced += traced;
}

/* ScanBlack child visitor: undo the trial deletion and restore the child. */
static void scan_black_child(void *child) {
    if (!child || !is_cyclic(child)) return;
    (*header(child))++;
    if (color(child) != BLACK) { set_color(child, BLACK); stack_push(&cc_blacken, child); }
}
static void scan_black(void *obj) {
    set_color(obj, BLACK);
    stack_push(&cc_blacken, obj);
    while (cc_blacken.len) { void *x = stack_pop(&cc_blacken); vtab(x)->trav(x, scan_black_child); }
}

static void scan_child(void *child) { if (child && is_cyclic(child)) stack_push(&cc_work, child); }
static void scan(void *root) {
    stack_push(&cc_work, root);
    while (cc_work.len) {
        void *obj = stack_pop(&cc_work);
        if (color(obj) != GRAY) continue;
        if (ref_count(obj) > 0) scan_black(obj);   /* externally referenced: live */
        else { set_color(obj, WHITE); vtab(obj)->trav(obj, scan_child); }
    }
}

/* Claims a WHITE object for collection by coloring it BLACK when pushed, so each garbage object is
 * collected exactly once. With CC_CLAIM_SKIPS_BUFFERED a buffered WHITE object is left alone (default
 * GC: it is a root of this same collection and will be claimed from there). The incremental GC claims
 * buffered objects too and lets their own slice free them. */
static int collect_white_claim(void *obj) {
    if (color(obj) != WHITE) return 0;
    if (CC_CLAIM_SKIPS_BUFFERED && is_buffered(obj)) return 0;
    set_color(obj, BLACK);
    stack_push(&cc_work, obj);
    return 1;
}
static void collect_white_child(void *child) {
    if (!child) return;
    if (is_cyclic(child)) collect_white_claim(child);
    else vtab(child)->rel(child); /* acyclic child: drop the garbage object's reference normally */
}
static void collect_white(void *root) {
    if (!collect_white_claim(root)) return;
    while (cc_work.len) {
        void *obj = stack_pop(&cc_work);
        vtab(obj)->trav(obj, collect_white_child);
        stack_push(&cc_garbage, obj); /* freed after all roots are processed: other garbage may still point here */
    }
}

/* Adaptive trigger. CC_THRESHOLD buffered roots start a collection. A collection whose trial deletion
 * traced far more objects than it reclaimed (the roots were live, e.g. relinked list nodes in a cache)
 * doubles the threshold, up to CC_MAX_THRESHOLD, so live structures are not re-traced every 10k
 * decrements; a productive collection resets it. "Unproductive" = reclaimed < traced / CC_PRODUCTIVE_RATIO. */
#define CC_THRESHOLD 10000
#define CC_MAX_THRESHOLD (1u << 18)
#define CC_PRODUCTIVE_RATIO 4
static size_t cc_threshold = CC_THRESHOLD;
static void adapt_threshold(size_t reclaimed) {
    if (reclaimed * CC_PRODUCTIVE_RATIO < cc_traced) { if (cc_threshold < CC_MAX_THRESHOLD) cc_threshold *= 2; }
    else cc_threshold = CC_THRESHOLD;
}
