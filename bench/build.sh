#!/bin/bash
# Builds every case for Rust, Zig, Kotlin Native (optimized). Outputs are git-ignored.
#   ./build.sh [case...]   default: all cases (sum + cases/*)
# New Kotlin: KLLVM_COMPILER defaults to compiler/build/newkotlinc.kexe (built if missing);
#   $KLLVM_COMPILER <Main.kt> -o kllvm/<case>   (sum -> kllvm/bench_kllvm). KLLVM_COMPILER=none skips it.
# ONLY=newkotlin builds only the New Kotlin row.
set -e
cd "$(dirname "$0")"
KN=${KONANC:-$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21/bin/kotlinc-native}
CASES=${*:-sum alloc recursion file}
KLLVM_COMPILER=${KLLVM_COMPILER:-compiler/build/newkotlinc.kexe}
if [ "$KLLVM_COMPILER" = compiler/build/newkotlinc.kexe ] && [ ! -x "$KLLVM_COMPILER" ]; then compiler/build.sh; fi
mkdir -p kllvm
for c in $CASES; do
  echo "== $c"
  if [ "$ONLY" = newkotlin ]; then src=cases/$c/kotlin/Main.kt; [ "$c" = sum ] && src=kotlin/Main.kt
  elif [ "$c" = sum ]; then
    (cd rust && cargo build --release -q)
    (cd zig && zig build-exe main.zig -OReleaseFast -femit-bin=bench_zig)
    (cd kotlin && "$KN" Main.kt -opt -o bench_kn)
    src=kotlin/Main.kt
  else
    d=cases/$c
    rustc -C opt-level=3 -C codegen-units=1 --edition 2021 $d/rust/main.rs -o $d/rust/bench_rust
    (cd $d/zig && zig build-exe main.zig -OReleaseFast -lc -femit-bin=bench_zig)
    (cd $d/kotlin && "$KN" Main.kt -opt -o bench_kn)
    src=$d/kotlin/Main.kt
  fi
  if [ "$KLLVM_COMPILER" != none ]; then
    out=kllvm/$c; [ "$c" = sum ] && out=kllvm/bench_kllvm
    $KLLVM_COMPILER "$src" -o "$out"
  fi
done
[ -f data/words.txt ] || python3 gen_data.py
