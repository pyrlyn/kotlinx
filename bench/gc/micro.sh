#!/bin/bash
# GC-only microbenchmarks (bench/gc/micro/*.kt) per GC implementation: time (warm-up + 5 runs, mean of the
# middle 3), peak RSS (/usr/bin/time -l), and the collector's own statistics (KX_GC_STATS=1: collections,
# total and max pause). Writes bench/results/gc/micro-<gc>-<metric>.txt and prints one table per metric
# (best value per row in bold).   bench/gc/micro.sh [gc ...]   (default: default boehm)
# One file per benchmark: bench/results/gc-micro/<bench>.json (one run per GC with all metrics, see record.py).
set -eu
cd "$(dirname "$0")/.."
NKC=${NKC:-../bin/newkotlinc}
gcs=${*:-default boehm}
mkdir -p results/gc ../compiler/build/gcmicro
for g in $gcs; do for m in time rss pause_total pause_max collections; do : > results/gc/micro-$g-$m.txt; done; done
# Interleaved: every timed run goes through all GCs before the next one, so load drift hits all alike.
for src in gc/micro/*.kt; do
  c=$(basename "$src" .kt)
  for g in $gcs; do b=../compiler/build/gcmicro/$g.$c; "$NKC" --gc="$g" "$src" -o "$b"; "$b" >/dev/null; eval "ts_$g=''"; done
  for r in 1 2 3 4 5; do
    for g in $gcs; do eval "ts_$g=\"\$ts_$g \$(../compiler/build/gcmicro/$g.$c | grep -oE 'nanos=[0-9]+' | cut -d= -f2)\""; done
  done
  for g in $gcs; do
    b=../compiler/build/gcmicro/$g.$c; eval "ts=\$ts_$g"
    python3 -c "v=sorted(map(int,'$ts'.split()))[1:4]; print('$c', round(sum(v)/3/1e6, 3))" >> results/gc/micro-$g-time.txt
    rss=$( { /usr/bin/time -l "$b" >/dev/null; } 2>&1 | awk '/maximum resident set size/ {print $1}')
    echo "$c $(python3 -c "print(round($rss/1048576, 1))")" >> results/gc/micro-$g-rss.txt
    st=$(KX_GC_STATS=1 "$b" 2>&1 >/dev/null | grep '^gc-stats:')
    for m in collections pause_total pause_max; do
      echo "$c $(echo "$st" | grep -oE "$m=[0-9.]+" | cut -d= -f2)" >> results/gc/micro-$g-$m.txt
    done
    v() { awk -v c="$c" '$1 == c {print $2}' "results/gc/micro-$g-$1.txt"; }
    python3 record.py gc-micro "$c" --run "micro-$g" --gc "$g" ms="$(v time)" raw_ns="$(echo $ts | tr ' ' ',')" \
      peak_rss_mib="$(v rss)" pause_total_ms="$(v pause_total)" pause_max_ms="$(v pause_max)" collections="$(v collections)"
  done
done
for m in time:"case, ms" rss:"case, peak RSS MiB" pause_total:"case, total GC pause ms" pause_max:"case, max GC pause ms" collections:"case, collections"; do
  k=${m%%:*}; args=""; for g in $gcs; do args="$args $g=results/gc/micro-$g-$k.txt"; done
  python3 gc/table.py --metric "${m#*:}" $args; echo
done | tee results/gc/micro-table.md
