# Immix-style allocator prototype (step 1), epoch GC

Branch `gc-epoch-immix`, opt-in with `KX_GC_IMMIX=1` (`KX_GC_IMMIX_RELEASE=0` keeps freed pages resident).
Measured with `bench/gc/immix-compare.sh` (3 interleaved rotated rounds; per round warm-up + 5 runs, mean of
the middle 3, one `/usr/bin/time -l` peak-RSS run; median over rounds), Apple Silicon Mac, load 6-10
(other jobs running): treat ±10% as noise; `lru` runs varied 270-540 ms within one variant.

```
bench/gc/immix-compare.sh bench/results/gc-epoch-immix \
  main-2c9e4a9=../kotlinx-ix-2c9e4a9 main-ded919f=../kotlinx-ix-ded919f immix=.:KX_GC_IMMIX=1
```

| case | main 2c9e4a9 ms | main ded919f ms | immix ms | immix / 2c9e4a9 | 2c9e4a9 RSS MiB | ded919f RSS MiB | immix RSS MiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| huge_live_churn | 1863.3 | 1712.8 | 1210.7 | 0.65 | 973.5 | 979.0 | 937.2 |
| lru | 439.8 | 487.4 | 306.0 | 0.70 | 37.8 | 54.3 | 61.3 |
| cycles | 171.8 | 175.0 | 158.3 | 0.92 | 8.9 | 8.9 | 9.1 |
| bigheap | 87.5 | 90.6 | 105.1 | 1.20 | 214.7 | 216.8 | 216.0 |

Tests (`test-sh-*.txt`): `compiler/test.sh` ALL PASSED with the default GC and with
`NEWKOTLIN_GC=epoch KX_GC_IMMIX=1`; `compiler/gc/tests/run.sh` (GCS=epoch) ALL PASSED with and without
`KX_GC_IMMIX=1`. Upstream tests unmodified.

Takeaways: the line allocator is faster on allocation-heavy churn (bump allocation, no free-list pops),
but the huge_live_churn peak only drops ~4%: `KX_GC_DEBUG=1` shows ~390 MiB of collector stacks
(deferred, im-seen, workers) at the peak, which the allocator does not touch. lru's peak grows (partly
used lines are not reusable until fully free). bigheap is slower (one noisy round; still >1.0 in the others).
Next steps: bound the collector's mark stacks (the larger share of the peak) and size-class-aware hole
filling for the lru fragmentation.
