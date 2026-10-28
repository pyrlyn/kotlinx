#!/bin/bash
# Usage: ./run.sh            -> runs only the new Kotlin->LLVM compiler row (KLLVM_BIN)
#        ALL=1 ./run.sh      -> also re-runs Rust, Zig, Kotlin Native
# KLLVM_BIN: path to kotlin/Main.kt compiled by our Kotlin->LLVM compiler.
KLLVM_BIN=${KLLVM_BIN:-kllvm/bench_kllvm}
set -- "KotlinLLVM:$KLLVM_BIN"
[ -n "$ALL" ] && set -- "Rust:rust/target/release/mini_bench" "Zig:zig/bench_zig" "KotlinNative:kotlin/bench_kn.kexe" "$@"
for p in "$@"; do
 name=${p%%:*}; bin=${p#*:}
 [ -x "$bin" ] || { echo "$name: binary $bin not found, skipped" >&2; continue; }
 for r in 1 2 3 4 5; do
  out=$( { /usr/bin/time -lp ./$bin; } 2>&1 )
  sum=$(echo "$out"|grep -o 'sum=[-0-9]*'); nan=$(echo "$out"|grep -o 'nanos=[0-9]*'|cut -d= -f2)
  real=$(echo "$out"|awk '/^real/{print $2}'); user=$(echo "$out"|awk '/^user/{print $2}'); sys=$(echo "$out"|awk '/^sys/{print $2}')
  rss=$(echo "$out"|awk '/maximum resident/{print $1}')
  echo "$name $r $sum $nan $rss $real $user $sys"
 done
done
