# Young-object probe (KX_GC_YOUNG_PROBE=1), gc-epoch-nursery, Apple M3 Max, 2026-10-31

Share of logged retains/releases (rt_gc_log_inc/dec) whose target was allocated in the current epoch:
the entries a per-epoch nursery could apply without a log (direct-mapped table, slight undercount).

| case | logged ops | young | share |
|---|---:|---:|---:|
| nk cycles | 16,080,000 | 15,902,330 | 98.9% |
| nk bigheap | 9,352,000 | 9,171,797 | 98.1% |
| nk lru | 45,836,074 | 7,803,812 | 17.0% |
| micro cyclic_garbage | 5,000,000 | 4,999,848 | 100.0% |
| micro huge_live_churn | 69,999,997 | 51,994,629 | 74.3% |
| micro big_live_churn | 29,999,997 | 19,998,046 | 66.7% |
| micro live_heap_churn | 20,999,997 | 12,798,816 | 60.9% |
| micro wide_graph_churn | 65,999,996 | 8,078,480 | 12.2% |

The other 13 nkbench cases log nothing (logs=0: no cyclic classes, plain RC), so a nursery cannot change them.

## Upper bound vs main 2c9e4a9 (epoch, nkbench means from bench/results/gc-main-vs-branch/main-nk-epoch.txt)

Optimistic: cycles drops to the default GC's 47 ms (0.27x), lru loses 17% of its 263 ms gap to default
(0.875x), bigheap and the 13 untouched cases stay at 1.0x. Geomean = (0.27 * 0.875)^(1/16) = 0.914x > 0.9x.

Peak RSS of huge_live_churn (934 MiB after gc-epoch-smooth) is the live heap (10M live objects, ~386 MiB
slabs) plus the major's stacks over old objects; a nursery changes neither, so <= 800 MiB is not reachable
through it.
