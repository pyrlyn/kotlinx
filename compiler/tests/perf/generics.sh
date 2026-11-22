#!/bin/bash
# Compile time of N functions each using a distinct generic class instance (discovered during code generation).
# Usage: compiler/tests/perf/generics.sh [nkc] [N...]   (default: compiler/build/newkotlinc.kexe, 25 50 100 200)
set -e
cd "$(dirname "$0")/../../.."
NKC=${1:-compiler/build/newkotlinc.kexe}; shift || true
SIZES=${*:-25 50 100 200}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
for n in $SIZES; do
  { echo "class Box<T>(val v: T)"
    i=0; while [ $i -lt $n ]; do echo "class C$i(val x: Int)"; i=$((i + 1)); done
    i=0; while [ $i -lt $n ]; do echo "fun f$i(): Int = Box<C$i>(C$i($i)).v.x"; i=$((i + 1)); done
    echo "fun main() {"; echo "    var s = 0"
    i=0; while [ $i -lt $n ]; do echo "    s += f$i()"; i=$((i + 1)); done
    echo "    println(s)"; echo "}"; } > "$W/gen_$n.kt"
  s=$(date +%s%N); "$NKC" -S "$W/gen_$n.kt" -o "$W/gen_$n.ll"; e=$(date +%s%N)
  echo "generics N=$n $(( (e - s) / 1000000 )) ms"
done
