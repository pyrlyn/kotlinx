#!/bin/bash
# Usage: ./run.sh                 -> KotlinLLVM rows only (kllvm/<case>; sum uses $KLLVM_BIN)
#        ALL=1 ./run.sh           -> also Rust, Zig, Kotlin Native
#        CASES="alloc file" ...   -> subset (default: sum alloc recursion file)
#        NOCPU=1                  -> skip the CPU (multi-launch) pass
# Output (appended): results/runs.txt  "case row run checksum nanos rss_bytes real user sys"
#                    results/cpu.txt   "case row rep launches real user sys"
# Then: ./summarize.sh > results/summary.md
cd "$(dirname "$0")"
KLLVM_BIN=${KLLVM_BIN:-kllvm/bench_kllvm}
CASES=${CASES:-sum alloc recursion file}
mkdir -p results
launches() { case $1 in alloc) echo 20;; *) echo 200;; esac; }  # alloc ~0.2 s/run -> fewer launches
for c in $CASES; do
  if [ "$c" = sum ]; then
    rows=("KotlinLLVM:$KLLVM_BIN")
    [ -n "$ALL" ] && rows=("Rust:rust/target/release/mini_bench" "Zig:zig/bench_zig" "KotlinNative:kotlin/bench_kn.kexe" "${rows[@]}")
  else
    d=cases/$c
    rows=("KotlinLLVM:kllvm/$c")
    [ -n "$ALL" ] && rows=("Rust:$d/rust/bench_rust" "Zig:$d/zig/bench_zig" "KotlinNative:$d/kotlin/bench_kn.kexe" "${rows[@]}")
  fi
  for p in "${rows[@]}"; do
    name=${p%%:*}; bin=${p#*:}
    [ -x "$bin" ] || { echo "$c $name: $bin not found, skipped" >&2; continue; }
    for r in 1 2 3 4 5; do
      out=$( { /usr/bin/time -lp ./$bin; } 2>&1 )
      cs=$(echo "$out"|grep -oE '(sum|checksum)=[-0-9]+'|head -1|cut -d= -f2)
      ns=$(echo "$out"|grep -oE '(^| )nanos=[0-9]+'|head -1|cut -d= -f2)
      real=$(echo "$out"|awk '/^real/{print $2}'); user=$(echo "$out"|awk '/^user/{print $2}'); sys=$(echo "$out"|awk '/^sys/{print $2}')
      rss=$(echo "$out"|awk '/maximum resident/{print $1}')
      echo "$c $name $r $cs $ns $rss $real $user $sys" | tee -a results/runs.txt
    done
    [ -n "$NOCPU" ] && continue
    n=$(launches $c)
    for r in 1 2 3 4 5; do
      t=$( { /usr/bin/time -p bash -c "for i in \$(seq $n); do ./$bin >/dev/null 2>&1; done"; } 2>&1 )
      echo "$c $name $r $n $(echo "$t"|awk '/^real/{r=$2}/^user/{u=$2}/^sys/{s=$2}END{print r,u,s}')" | tee -a results/cpu.txt
    done
  done
done
