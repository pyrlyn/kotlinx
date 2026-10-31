# GC benchmarks

Reproducible comparisons of the `--gc` implementations ([compiler/gc](../../compiler/gc/README.md)).
Run `mise install` in the repo first (bdwgc for `--gc=boehm`). Results go to `bench/results/gc/`.

- `./compare.sh [gc ...]` (default `default boehm`): the whole KotlinX suite (`bench/nkbench.sh`: warm-up +
  5 runs, mean of the middle 3) once per GC; prints a table, best value per row in bold.
- `./micro.sh [gc ...]`: GC-only microbenchmarks in `micro/` (allocation throughput, many short-lived
  objects, cyclic garbage, pauses with a large live cyclic heap): time, peak RSS (`/usr/bin/time -l`) and
  the GC's own statistics (`KX_GC_STATS=1`: collections, total and max pause), one table per metric.
- `./table.py label=file ...`: the table builder used by both (also for before/after runs of `nkbench.sh`).

Close other heavy processes; results within ±3-5% are noise on a loaded machine.

## Shared-edge traversal regression

`compiler/gc/tests/unit/traversal.c` checks live reference-count restoration, exact cycle reclamation,
and bounded traversal scratch space for a ring with 64 references per node to its successor. It also
checks a queued sibling restored by ScanBlack. `compiler/gc/tests/run.sh` runs it for `default` and
`incremental` at `-O1`, `-O3`, and under ASan. From the repository root, benchmark a larger ring with:

```sh
clang -O3 -Icompiler/gc compiler/gc/tests/unit/traversal.c compiler/runtime/runtime.c -o /tmp/gc-traversal
/usr/bin/time -l /tmp/gc-traversal 32768
# Add -DTEST_INCREMENTAL to select the incremental collector.
```

The explicit size enables benchmark mode (all correctness assertions remain, except the scratch-space
limit that the old collector fails). On Apple M3 Max, Apple clang 21.0.0, compared with `09daa04`,
medians of nine interleaved runs on 2026-10-31 were:

| GC | traversal scratch, before → after | peak RSS, MiB | total GC pause, ms | maximum GC pause, ms |
|---|---:|---:|---:|---:|
| default | 16 MiB → 2 KiB | 40.8 → 23.0 | 39.93 → 15.99 | 20.37 → 8.46 |
| incremental | 16 MiB → 2 KiB | 40.5 → 22.9 | 40.37 → 17.11 | 21.50 → 8.60 |

This deliberately stresses shared edges; it is not a general workload speedup or a hard pause bound.
Seven interleaved runs of the existing `cycles`, `lru`, and `trees` Kotlin benchmarks retained their
checksums; median kernel times changed from 49.09/116.79/122.86 ms to 48.45/113.50/122.10 ms, within
the usual 3–5% noise. Other compiler tests were running during these measurements.

## Result files

Every script also writes one file per benchmark, appending a run each time:
`bench/results/nk/<case>.json` (`nkbench.sh`), `bench/results/gc-nk/<case>.json` (`compare.sh`),
`bench/results/gc-micro/<bench>.json` (`micro.sh`). A run records date, commit (+ dirty flag), git ref,
machine (model, CPU, OS, load), toolchain (clang, opt), GC, flags and metrics (mean ms + raw timings;
for micro also peak RSS, total/max pause, collections). Writer: `bench/record.py`. The PR #3 results are
imported there (runs `gc-before`, `gc-after`, `gc-default`, `gc-boehm`, `micro-*`).
