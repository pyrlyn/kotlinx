#!/bin/bash
# Builds every case for all rows. Outputs are git-ignored.
#   ./build.sh [case...]      default: all cases (sum + cases/*)
#   ONLY=newkotlin ./build.sh  only the KotlinX compiler rows
# Flag levels (equal-conditions policy: -O3-equivalent everywhere, no -march/-mcpu=native for anyone):
#   Rust          rustc -C opt-level=3 -C codegen-units=1
#   Zig           -OReleaseFast
#   Kotlin Native kotlinc-native -opt
#   KotlinX compiler    newkotlinc (clang -O3 -flto), system malloc; kllvm/<case>.pool = --alloc pool
set -e
cd "$(dirname "$0")"
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$PWD/../compiler/runtime/runtime.c}
KN=${KONANC:-$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21/bin/kotlinc-native}
ALL_CASES="sum $(ls cases | tr '\n' ' ')"
CASES=${*:-$ALL_CASES}
KLLVM_COMPILER=${KLLVM_COMPILER:-../compiler/build/newkotlinc.kexe}
if [ "$KLLVM_COMPILER" = ../compiler/build/newkotlinc.kexe ] && [ ! -x "$KLLVM_COMPILER" ]; then ../compiler/build.sh; fi
mkdir -p kllvm
[ -f data/words.txt ] || python3 gen_data.py
src_dir() { if [ "$1" = sum ]; then echo .; else echo cases/$1; fi; }
for c in $CASES; do
  echo "== $c"
  d=$(src_dir $c)
  rs=$d/rust/main.rs; [ "$c" = sum ] && rs=rust/src/main.rs
  if [ "$ONLY" != newkotlin ]; then
    rustc -C opt-level=3 -C codegen-units=1 --edition 2021 "$rs" -o $d/rust/bench_rust
    (cd $d/zig && zig build-exe main.zig -OReleaseFast -lc -femit-bin=bench_zig)
    (cd $d/kotlin && "$KN" Main.kt -opt -o bench_kn)
  fi
  if [ "$KLLVM_COMPILER" != none ]; then
    out=kllvm/$c; [ "$c" = sum ] && out=kllvm/bench_kllvm
    $KLLVM_COMPILER $d/kotlin/Main.kt -o "$out"
    $KLLVM_COMPILER $d/kotlin/Main.kt -o "$out.pool" --alloc pool
  fi
done
