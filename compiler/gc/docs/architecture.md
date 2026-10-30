# KotlinX memory management: architecture

This document explains how memory is managed in programs compiled by the KotlinX compiler
(`newkotlinc`). For the list of implementations and how to select one, see [`../README.md`](../README.md).

## 1. Layers

```
generated code (NativeBackend.kt)   inlined retain/release fast paths, Perceus drop/reuse, stack allocation
        |  calls only kxgc.h
runtime (compiler/runtime/runtime.c) strings, ArrayList, HashMap, arrays, exceptions, exit hook
        |  calls only kxgc.h
memory manager (compiler/gc/<name>/gc.c)   allocation regions, slow paths, cycle collection, statistics
```

The driver compiles the runtime, the selected `gc.c` and the program in one `clang -O3 -flto`
invocation, so slow paths can be inlined across the boundary and the abstraction costs nothing.

## 2. Object layout

Every heap object of a Kotlin class starts with two words:

| word | contents |
|---|---|
| 0 | header (below) |
| 1 | pointer to the class vtable (`KxVTab` prefix, then interface method slots) |
| 2.. | fields |

Header word:

| bits | meaning | owner |
|---|---|---|
| 0-31 | reference count (`KX_CNT_MASK`) | generated code (and the collector during trial deletion) |
| 32-33 | color (`KX_COLOR_SHIFT`): BLACK 0, GRAY 1, WHITE 2, PURPLE 3 | collector; generated code sets PURPLE / BLACK |
| 34 | buffered (`KX_BUF_BIT`): the object is in the root buffer | implementation only |
| 35-63 | unused, must stay 0 | - |

Color and buffered bits are meaningful only for *traced* objects (possibly cyclic classes).

`KxVTab` (all implementations rely on it):

| slot | meaning |
|---|---|
| `rel` | drop one reference: decrement, and at zero release the fields and free |
| `trav(obj, visit)` | call `visit` on every reference field (children may be NULL) |
| `relo` | release the fields the collector does not trace (acyclic objects, lists, strings, ...) |
| `freemem` | return the memory; fields must already be released |
| `cyclic` | non-NULL iff the class is possibly cyclic |

## 3. Reference counting and Perceus

The compiler does precise RC with the Perceus optimizations:

- **Drop at last use**: a reference is released right after its last use, not at scope end.
- **Reuse (FBIP)**: if an object dies just before an allocation of the same size, its memory is reused
  in place (`@relf.C` releases only its fields).
- **No RC** for stack-allocated (non-escaping) objects and for immutable locals that are borrowed.

The retain/release fast paths are inlined: null check, increment or decrement, and the zero test.
Only two cases leave generated code:

1. count reaches zero: release the fields, then `rt_free` (acyclic) or `rt_free_trace` (traced). For a
   traced object that is buffered, generated code (an inlined copy of `rt_cc_dead`) only colors it BLACK
   and leaves it to the implementation, which frees it when it takes it out of the buffer.
2. a traced object is decremented to a non-zero count: it may be the only external reference into a
   now-unreachable cycle, so generated code colors it PURPLE and, unless already buffered, calls
   `rt_cc_possible_root`.

An implementation that never sets the buffered bit gets plain RC (`rc`, `boehm`).

## 4. Regions

The compiler classifies each class (`NativeBackend.cyclic`): a class that lies on a cycle of
class-typed fields containing at least one `var` field is *possibly cyclic*; subclasses inherit that.
Everything else is acyclic and can never be part of a reference cycle.

- **RC region** (`rt_alloc` / `rt_free`): acyclic objects. Freed the instant the count is zero, never
  buffered, never traced. Backed by the object allocator (mimalloc by default, malloc, or the pool with
  `RT_POOL`).
- **Tracing region** (`rt_alloc_trace` / `rt_free_trace`): possibly cyclic objects. The cycle collector
  only ever visits this region. Backed by size-class slabs (8-byte classes up to 256 bytes, 64 KiB slabs).

With `--alloc malloc` (ASan and macOS `leaks` builds) both regions use malloc so the tools see every
object. With `--leak-check` (`RT_LEAKCHECK`) each object carries a 16-byte region tag, frees into the
wrong region abort, and any collector visit outside the tracing region fails the run (exit 4).
Implementation: [`common/regions.inc`](../common/regions.inc).

Runtime containers that store references (ArrayList storage, HashMap values, arrays) are allocated with
`rt_gc_mem_*`, so a tracing collector (bdwgc) can scan them.

## 5. Cycle collector (default)

Bacon & Rajan 2001, synchronous trial deletion
([`common/trial_deletion.inc`](../common/trial_deletion.inc)):

1. **Buffering.** `rt_cc_possible_root` sets the buffered bit and appends the object to the root buffer.
2. **Trigger.** When the buffer holds `cc_threshold` roots (10 000, adaptive up to 2^18) or at exit.
3. **MarkRoots.** Roots that are no longer PURPLE or have count 0 leave the buffer (and are freed if they
   died while buffered). For each remaining root, `mark_gray` walks the traced subgraph, colors it GRAY
   and decrements every internal reference.
4. **ScanRoots.** `scan`: a GRAY object with count > 0 is referenced from outside, so it and everything it
   reaches are restored (`scan_black`, re-incrementing counts). GRAY objects at 0 become WHITE.
5. **CollectRoots.** `collect_white` moves WHITE objects to the garbage list; references to acyclic
   children are released through the normal RC path.
6. **Free.** First `relo` on every garbage object, then `freemem` on all of them (garbage may still point
   to other garbage until everything is released).

Ownership rules:
- A buffered object is owned by the root buffer: nobody else may free it. Whoever removes it from the
  buffer clears the bit and takes over freeing it.
- A collection is never re-entered (`cc_running`); roots buffered while freeing garbage go to a fresh
  buffer and may trigger another collection right after.
- All passes use explicit stacks; deep structures cannot overflow the C stack.

Adaptive threshold: if a collection reclaimed less than 1/4 of what it traced (the roots were live, e.g.
relinked LRU nodes), the threshold doubles, up to 2^18; a productive collection resets it to 10 000.

Limitation: cycles through an ArrayList/HashMap are not traced by the RC implementations.

## 6. Incremental collector

`incremental` processes the root buffer in slices of 64 roots; trial deletion is correct for any subset
of candidate roots. After each slice it checks the time budget (`KX_GC_BUDGET_US`, default 500 us). Left
over roots stay buffered and the next step starts after 2000 new roots. A garbage object that is a root of
a later slice is released but left BLACK with count 0 (like `rt_cc_dead`); its own slice frees it. If the
very first slice already exceeds the budget, the step finishes in one pass, since every further slice
would re-trace the same large graph. `rt_gc_collect` and program exit run without a budget.

## 7. Epoch collector (deferred RC, background cycle collection)

`epoch` (caps `cycles precise asan deferred`) moves all reference counting of possibly cyclic objects and
all cycle collection to a background thread (Bacon & Rajan, "Concurrent Cycle Collection in Reference
Counted Systems", 2001). The driver reads the `deferred` cap and sets `NativeBackend.deferredRc`, which
changes the generated code for traced (cyclic) classes only:

- retain/release of a traced object append `pointer | op` to the current epoch log
  (`rt_gc_log_inc` / `rt_gc_log_dec`); acyclic objects keep the inline Perceus fast paths;
- a reference store into a traced object is a release store followed by `rt_gc_write_barrier(obj)`
  (logs "modified"); the collector reads fields with acquire loads (`trav.C`);
- values of unknown dynamic class go through `rt_retain_dyn`, which checks `vtable->cyclic`;
- FBIP reuse is disabled for traced classes (their count is not known to the mutator).

`rt_gc_write_barrier` is an empty function in every other implementation and is never emitted for them.

Epoch boundary (log full, 32768 entries): the mutator hands the log over (one mutex round-trip) and frees
dead objects returned by the collector under `KX_GC_FREE_BUDGET_US` (default half the target pause, 1.5 ms); allocation also frees
two returned objects per call. If the collector is more than `KX_GC_MAX_QUEUED` logs behind (default 16,
256 KiB each), the boundary waits (back-pressure); that wait is counted as a pause.

The collector, per log: applies all increments, then all decrements (single mutator, so no count drops to
zero early), marking each touched object dirty; count zero frees the object (its traced children are
decremented, the memory goes back to the mutator), non-zero buffers it as a possible root. When the buffer
is large it runs trial deletion; each white component becomes a candidate set. All sets of one detection
form one batch (one allocation), validated once a log handed over after the detection has been applied:
Delta test (no member dirty) and Sigma test (sum of member counts equals the member-to-member edges).
Passing sets are freed, failing members are re-buffered.

Trial deletion counts in the CRC field (header bits 40-63) the references coming from traversed objects
and compares it with the real count, so real counts are never disturbed. MarkGray and Scan run in parallel
on the collector thread plus `KX_GC_THREADS - 1` helpers (default `min(cores, 4)`), with atomic header
updates (CAS on the color, atomic add on the CRC) and shared work stealing; CollectWhite, validation and
log application stay on the collector thread, separated from the parallel phases by a barrier.
Parallel phases are used for major detections of at least 2048 roots; small and minor detections run on
the collector thread alone (measured: more threads only add synchronization there).

Generations: an object that Scan finds externally referenced (black) is promoted to the old generation
(header bit 39, `stable`). Any RC operation or write barrier on it demotes it. A minor detection treats
stable objects as opaque live objects, so it never re-traces the live graph: its cost is proportional to
the objects touched since the last scan. Garbage cycles that run through stable objects are still found:
a root whose minor detection stopped at a stable object and did not turn white stays buffered in a
deferred list, and a major detection (stable objects traced like any other) processes the deferred roots
once four times as many roots have been deferred as there are stable objects, so its cost is amortized
O(1) per root. An object whose count drops to zero while it is listed (root buffer or deferred list)
releases its children at once and stays as a zombie until its list entry is removed, so dead chains do
not keep each other alive until the next major detection.

Program exit does not drain the queue: the process exits and the OS reclaims the memory. Builds that
inspect the heap at exit drain it stop-the-world first: `--leak-check`, `--alloc malloc` (ASan and
macOS `leaks` builds), or any build with `KX_GC_EXIT_DRAIN=1`.

Traced objects use 64 KiB-aligned size-class slabs registered in a page bitmap (one bit per 64 KiB page,
256 MiB of lazily touched virtual memory), so the collector recognizes traced pointers without
dereferencing them (acyclic objects may be freed concurrently). The unit test `gc_unit.c`
(immediate-RC protocol) is skipped for `deferred` implementations.

## 8. bdwgc backend

`boehm` keeps RC (objects are freed with `GC_FREE` at count zero) and lets bdwgc reclaim unreachable
cycles. Acyclic objects and `rt_gc_mem_*` storage are uncollectable but scanned; traced objects are
collectable with a finalizer that releases their acyclic fields. Pauses are measured through bdwgc's
collection event callback.

## 9. Statistics and environment

| variable | effect |
|---|---|
| `NEWKOTLIN_GC` | compile time: default for `--gc` |
| `NEWKOTLIN_GC_DIR` | compile time: directory with implementations (default `<runtime>/../gc`) |
| `NEWKOTLIN_BDWGC_DIR` | compile time: bdwgc install prefix for `--gc=boehm` |
| `KX_GC_STATS=1` | run time: print `gc-stats: gc=... collections=... reclaimed=... pause_total=... pause_max=...` (ms) at exit |
| `KX_GC_BUDGET_US` | run time, `incremental` only: per-step budget in microseconds (default 500) |
| `KX_GC_FREE_BUDGET_US` | run time, `epoch` only: time per epoch boundary for freeing returned objects (default half of `KX_GC_TARGET_PAUSE_US`: 1500) |
| `KX_GC_THREADS` | run time, `epoch` only: collector threads for parallel trial deletion (default min(cores, 4)) |
| `KX_GC_MAX_QUEUED` | run time, `epoch` only: logs the collector may fall behind before the program waits (default 16) |
| `KX_GC_EXIT_DRAIN=1` | run time, `epoch` only: drain the queue at exit even without `--leak-check` / `--alloc malloc` |
| `KX_GC_DEBUG=1` | run time, `epoch` only: print epoch/detection/validation counters at each full collection |

## 10. Adding an implementation

1. Create `compiler/gc/<name>/gc.c` implementing every function of `kxgc.h`. RC implementations include
   `common/regions.inc`; cycle collectors add `common/object.inc` and `common/trial_deletion.inc`.
2. Add `compiler/gc/<name>/caps` listing what tests may expect: `cycles` (reclaims cycles), `precise`
   (zero live objects under `--leak-check`), `asan` (works under ASan), `deferred` (generated code logs RC
   operations on traced objects through `rt_gc_log_inc`/`rt_gc_log_dec`/`rt_gc_write_barrier`).
3. If it needs extra clang flags, add an executable `config.sh` that prints them.
4. Run `compiler/gc/tests/run.sh` (picks it up automatically) and the benchmarks below.

## 11. Testing and benchmarking

- `compiler/test.sh`: everything, including leak tests and `compiler/gc/tests/run.sh`.
- `GCS="default incremental" compiler/gc/tests/run.sh`: GC unit tests (-O1/-O3), Kotlin tests, leak-check
  and ASan per implementation. Tests marked `// gc-needs: cycles` are skipped without the `cycles` cap.
- `bench/gc/compare.sh default incremental`: full KotlinX suite per GC, interleaved over `ROUNDS` rounds (median).
- `bench/gc/micro.sh default incremental`: GC microbenchmarks (time, peak RSS, pauses).
- Results are appended, one file per benchmark, under `bench/results/` (see
  [`bench/gc/README.md`](../../../bench/gc/README.md)).
