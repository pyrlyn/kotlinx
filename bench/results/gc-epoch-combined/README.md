# #77 slab release + #78 lean stacks combined (KX_GC_SLAB_RELEASE=1, KX_GC_LEAN_STACKS=1, both default)

Measured with `bench/gc/epoch-compare.sh`, ROUNDS=5, run twice (each file holds both runs). Values: median of the 10 rounds; spread = min-max of the two per-run medians in %. Apple Silicon Mac, load 9-16 with other compiler work running: treat ±10% as noise.

| case | main ms | #77 ms | #78 ms | comb ms | comb/main | comb/#78 | main MiB | #77 MiB | #78 MiB | comb MiB | off MiB | run spread (comb ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| huge_live_churn | 1871 | 1453 | 1771 | 1424 | 0.76 | 0.80 | 974 | 968 | 734 | 708 | 952 | 14% |
| wide_graph_churn | 3797 | 3733 | 4004 | 3842 | 1.01 | 0.96 | 843 | 847 | 841 | 846 | 844 | 11% |
| big_live_churn | 836 | 781 | 814 | 752 | 0.90 | 0.92 | 211 | 208 | 185 | 187 | 208 | 3% |
| lru | 445 | 386 | 399 | 364 | 0.82 | 0.91 | 44 | 54 | 53 | 53 | 50 | 14% |
| cycles | 173 | 180 | 174 | 179 | 1.04 | 1.03 | 10 | 10 | 10 | 10 | 10 | 3% |
| bigheap | 90 | 88 | 88 | 90 | 1.00 | 1.02 | 217 | 215 | 217 | 215 | 217 | 1% |

off = combined tree with KX_GC_SLAB_RELEASE=0,KX_GC_LEAN_STACKS=0.

Tests: gc-tests.txt (137 PASS, 8 SKIP), test-sh-default.txt (422 PASS) and test-sh-epoch.txt (423 PASS), all ALL PASSED; upstream.txt (PASS 2589 in epoch and default).
