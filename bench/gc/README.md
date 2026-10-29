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
