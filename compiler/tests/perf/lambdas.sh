#!/bin/bash
# Compile time of a main with N distinct lambdas (closure classes discovered during code generation).
# Usage: compiler/tests/perf/lambdas.sh [nkc] [N...]   (default: compiler/build/newkotlinc.kexe, 50 100 200 400)
set -e
cd "$(dirname "$0")/../../.."
NKC=${1:-compiler/build/newkotlinc.kexe}; shift || true
SIZES=${*:-50 100 200 400}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
for n in $SIZES; do
  { echo "fun apply(x: Int, f: (Int) -> Int): Int = f(x)"; echo "fun main() {"; echo "    var s = 0"
    i=0; while [ $i -lt $n ]; do echo "    s += apply($i) { it * $(( i % 7 + 1 )) + $i }"; i=$((i + 1)); done
    echo "    println(s)"; echo "}"; } > "$W/lam_$n.kt"
  s=$(date +%s%N); "$NKC" -S "$W/lam_$n.kt" -o "$W/lam_$n.ll"; e=$(date +%s%N)
  echo "lambdas N=$n $(( (e - s) / 1000000 )) ms"
done
