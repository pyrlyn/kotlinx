#!/bin/bash
# KotlinX benchmark suite (bench/nkbench.sh: warm-up + 5 runs, mean of the middle 3) once per GC
# implementation, then a table with the best value per row in bold.
#   bench/gc/compare.sh [gc ...]      (default: default boehm)  -> bench/results/gc/nk-<gc>.txt, table on stdout
set -eu
cd "$(dirname "$0")/.."
gcs=${*:-default boehm}
mkdir -p results/gc; args=""
for g in $gcs; do
  NKFLAGS="--gc=$g ${NKFLAGS:-}" ./nkbench.sh "gc-$g" >/dev/null
  mv results/nk/gc-$g.txt results/gc/nk-$g.txt
  args="$args $g=results/gc/nk-$g.txt"
done
python3 gc/table.py $args | tee results/gc/nk-table.md
