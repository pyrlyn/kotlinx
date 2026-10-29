#!/bin/bash
# GC-only microbenchmarks (bench/gc/micro/*.kt) per GC implementation: time (warm-up + 5 runs, mean of the
# middle 3), peak RSS (/usr/bin/time -l), and the collector's own statistics (KX_GC_STATS=1: collections,
# total and max pause). Writes bench/results/gc/micro-<gc>-<metric>.txt and prints one table per metric
# (best value per row in bold).   bench/gc/micro.sh [gc ...]   (default: default boehm)
set -eu
cd "$(dirname "$0")/.."
NKC=${NKC:-../bin/newkotlinc}
gcs=${*:-default boehm}
mkdir -p results/gc ../compiler/build/gcmicro
for g in $gcs; do
  for m in time rss pause_total pause_max collections; do : > results/gc/micro-$g-$m.txt; done
  for src in gc/micro/*.kt; do
    c=$(basename "$src" .kt); b=../compiler/build/gcmicro/$g.$c
    "$NKC" --gc="$g" "$src" -o "$b"
    "$b" >/dev/null
    ts=""; for r in 1 2 3 4 5; do ts="$ts $("$b" | grep -oE 'nanos=[0-9]+' | cut -d= -f2)"; done
    python3 -c "v=sorted(map(int,'$ts'.split()))[1:4]; print('$c', round(sum(v)/3/1e6, 3))" >> results/gc/micro-$g-time.txt
    rss=$( { /usr/bin/time -l "$b" >/dev/null; } 2>&1 | awk '/maximum resident set size/ {print $1}')
    echo "$c $(python3 -c "print(round($rss/1048576, 1))")" >> results/gc/micro-$g-rss.txt
    st=$(KX_GC_STATS=1 "$b" 2>&1 >/dev/null | grep '^gc-stats:')
    for m in collections pause_total pause_max; do
      echo "$c $(echo "$st" | grep -oE "$m=[0-9.]+" | cut -d= -f2)" >> results/gc/micro-$g-$m.txt
    done
  done
done
for m in time:"case, ms" rss:"case, peak RSS MiB" pause_total:"case, total GC pause ms" pause_max:"case, max GC pause ms" collections:"case, collections"; do
  k=${m%%:*}; args=""; for g in $gcs; do args="$args $g=results/gc/micro-$g-$k.txt"; done
  python3 gc/table.py --metric "${m#*:}" $args; echo
done | tee results/gc/micro-table.md
