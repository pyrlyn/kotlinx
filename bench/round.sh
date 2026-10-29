#!/bin/bash
# Equal-conditions round: all rows of a case run back-to-back, interleaved round-robin per run,
# after one warm-up run each, with the data file cache warmed; load average recorded.
#   ./round.sh [case...]   (default: all cases)   ->  results/round/{runs,cpu,conditions}.txt
# CPU % = (user+sys)/wall over N back-to-back launches per row (N chosen per case so a batch takes
# ~2 s, 5 <= N <= 200), repeated 5x, also round-robin.
cd "$(dirname "$0")"
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$PWD/../compiler/runtime/runtime.c}
OUT=${OUT:-results/round}
mkdir -p "$OUT"
CASES=${*:-sum $(ls cases | tr '\n' ' ')}
rows_for() {
  local c=$1 d=cases/$1 kl=kllvm/$1
  if [ "$c" = sum ]; then d=.; kl=kllvm/bench_kllvm; fi
  echo "Rust:$d/rust/bench_rust Zig:$d/zig/bench_zig KotlinNative:$d/kotlin/bench_kn.kexe NewKotlin:$kl NewKotlinPool:$kl.pool"
}
load() { sysctl -n vm.loadavg | tr -d '{}' | awk '{print $1, $2, $3}'; }
{
  echo "date: $(date '+%Y-%m-%d %H:%M %Z')"; echo "machine: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cpus, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
  echo "os: $(sw_vers -productName) $(sw_vers -productVersion)"; echo "load at start: $(load)"
} > "$OUT/conditions.txt"
for c in $CASES; do
  rows=($(rows_for $c))
  for p in "${rows[@]}"; do [ -x "${p#*:}" ] || { echo "missing ${p#*:}; run ./build.sh" >&2; exit 1; }; done
  cat data/words.txt > /dev/null
  echo "case $c load before: $(load)" >> "$OUT/conditions.txt"
  for p in "${rows[@]}"; do   # warm-up (also sizes the CPU batches)
    s=$(python3 -c 'import time;print(time.time())'); "./${p#*:}" >/dev/null 2>&1; e=$(python3 -c 'import time;print(time.time())')
    eval "N_${p%%:*}=$(python3 -c "print(max(5, min(200, int(2.0 / max($e - $s, 1e-3)))))")"
  done
  for r in 1 2 3 4 5; do
    for p in "${rows[@]}"; do
      name=${p%%:*}; bin=${p#*:}
      out=$( { /usr/bin/time -lp ./$bin; } 2>&1 )
      cs=$(echo "$out"|grep -oE '(sum|checksum)=[-0-9]+'|head -1|cut -d= -f2)
      ns=$(echo "$out"|grep -oE '(^| )nanos=[0-9]+'|head -1|cut -d= -f2)
      real=$(echo "$out"|awk '/^real/{print $2}'); user=$(echo "$out"|awk '/^user/{print $2}'); sys=$(echo "$out"|awk '/^sys/{print $2}')
      rss=$(echo "$out"|awk '/maximum resident/{print $1}')
      echo "$c $name $r $cs $ns $rss $real $user $sys" >> "$OUT/runs.txt"
    done
  done
  for r in 1 2 3 4 5; do
    for p in "${rows[@]}"; do
      name=${p%%:*}; bin=${p#*:}; eval "n=\$N_$name"
      t=$( { /usr/bin/time -p bash -c "for i in \$(seq $n); do ./$bin >/dev/null 2>&1; done"; } 2>&1 )
      echo "$c $name $r $n $(echo "$t"|awk '/^real/{r=$2}/^user/{u=$2}/^sys/{s=$2}END{print r,u,s}')" >> "$OUT/cpu.txt"
    done
  done
  echo "case $c load after: $(load)" >> "$OUT/conditions.txt"
done
echo "load at end: $(load)" >> "$OUT/conditions.txt"
