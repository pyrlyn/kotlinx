# Empty small-object slabs returned to the OS (KX_GC_SLAB_RELEASE)

Measured with `bench/gc/epoch-compare.sh` (epoch GC, 3 interleaved rotated rounds, median; peak RSS from `/usr/bin/time -l`). Apple Silicon Mac, load 3-15 during the run (see each file's header): treat ±10% as noise.

| case | 2c9e4a9 ms | ded919f ms | slab ms | slab-off ms | slab/2c9e4a9 | 2c9e4a9 MiB | ded919f MiB | slab MiB | slab-off MiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| huge_live_churn | 1836 | 1878 | 1501 | 1699 | 0.82 | 973 | 979 | 961 | 996 |
| wide_graph_churn | 2676 | 2607 | 2583 | 2610 | 0.97 | 848 | 841 | 854 | 850 |
| big_live_churn | 736 | 768 | 708 | 746 | 0.96 | 207 | 212 | 213 | 210 |
| lru | 212 | 215 | 202 | 206 | 0.95 | 52 | 56 | 53 | 53 |
| cycles | 166 | 168 | 175 | 165 | 1.05 | 9 | 9 | 9 | 9 |
| bigheap | 119 | 119 | 121 | 120 | 1.01 | 214 | 214 | 214 | 214 |

Tests: gc-tests.txt, test-sh-default.txt, test-sh-epoch.txt (ALL PASSED); upstream.txt (PASS 2589 in epoch and default).
