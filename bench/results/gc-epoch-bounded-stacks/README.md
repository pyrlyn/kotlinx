# Lean collector stacks in the incremental major (KX_GC_LEAN_STACKS)

Measured with `bench/gc/epoch-compare.sh` (epoch GC, 3 interleaved rotated rounds, median; peak RSS from `/usr/bin/time -l`). Apple Silicon Mac, load 3-15 during the run (see each file's header): treat ±10% as noise.

| case | 2c9e4a9 ms | ded919f ms | lean ms | lean-off ms | lean/2c9e4a9 | 2c9e4a9 MiB | ded919f MiB | lean MiB | lean-off MiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| huge_live_churn | 1793 | 1719 | 1854 | 1680 | 1.03 | 1005 | 983 | 768 | 994 |
| wide_graph_churn | 3074 | 2593 | 2617 | 3114 | 0.85 | 851 | 842 | 840 | 843 |
| big_live_churn | 778 | 851 | 773 | 802 | 0.99 | 215 | 210 | 184 | 214 |
| lru | 220 | 236 | 226 | 220 | 1.03 | 54 | 56 | 52 | 53 |
| cycles | 169 | 170 | 169 | 170 | 1.00 | 9 | 9 | 9 | 9 |
| bigheap | 120 | 117 | 110 | 118 | 0.91 | 214 | 214 | 214 | 217 |

Tests: gc-tests.txt, test-sh-default.txt, test-sh-epoch.txt (ALL PASSED); upstream.txt (PASS 2589 in epoch and default).
