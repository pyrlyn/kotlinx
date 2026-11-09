#!/bin/bash
# Builds the benchmark cases (bench/kotlin = sum, bench/cases/*/kotlin) to WebAssembly two ways:
#   kx      newkotlinc --target=wasm: KotlinX LLVM IR -> wasm32-wasi (linear memory, pyrlyn/gc perceus RC + cycle
#           collector from native/gc/zig built for wasm32-wasi, wasi-libc malloc), linked by
#           `zig cc -target wasm32-wasi -O3 -flto`
#   kx-opt  kx + `wasm-opt -O3`
#   kx-c    kx with the C collector (--gc-runtime=c, native/gc/c/perceus) instead of the Zig port
#   kw      JetBrains Kotlin/Wasm (kotlinc-wasm -Xwasm-target=wasm-wasi -Xir-dce; WasmGC, engine GC)
#   kw-opt  kw + the Binaryen passes the Kotlin Gradle plugin runs for production builds
# Same sources for both; Kotlin/Wasm also gets wasm/TimeShim.kt (kotlin.system.getTimeNanos is Kotlin/Native-only).
# The file case uses platform.posix, which Kotlin/Wasm does not have: kx only.
# Output: wasm/out/<case>/<variant>/main.wasm (kw also main.mjs).
# Env: KOTLIN_HOME (kotlinc with bin/kotlinc-wasm, default mise kotlin 2.4.20), KW_STDLIB (kotlin-stdlib-wasm-wasi
#      klib of the same version, default: Gradle cache), CASES (subset), JOBS (parallel builds, default 4).
set -e
cd "$(dirname "$0")/.."
NKC=${KLLVM_COMPILER:-../compiler/build/newkotlinc.kexe}
[ -x "$NKC" ] || ../compiler/build.sh
# Zig GC archives for wasm32-wasi (default --gc-runtime=zig), built once before the parallel compiles.
../bin/newkotlinc --gc-libs-only --target=wasm
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$PWD/../compiler/runtime/runtime.c}
KOTLIN_HOME=${KOTLIN_HOME:-$HOME/.local/share/mise/installs/kotlin/2.4.20/kotlinc}
KWC="$KOTLIN_HOME/bin/kotlinc-wasm"
kv=$("$KOTLIN_HOME/bin/kotlinc" -version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
KW_STDLIB=${KW_STDLIB:-$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-wasm-wasi/"$kv"/*/*.klib 2>/dev/null | head -1)}
[ -f "$KW_STDLIB" ] || { echo "kotlin-stdlib-wasm-wasi $kv klib not found; set KW_STDLIB" >&2; exit 1; }
[ -f data/words.txt ] || python3 gen_data.py
# Kotlin Gradle plugin production defaults (BinaryenExec) for Kotlin/Wasm.
BINARYEN_KW="--enable-gc --enable-reference-types --enable-exception-handling --enable-bulk-memory \
--enable-nontrapping-float-to-int --closed-world --inline-functions-with-loops --traps-never-happen --fast-math \
--type-ssa -O3 -O3 --gufa -O3 --type-merging -O3 -Oz"
# clang 21's default wasm32 features (the stripped module has no target_features section for wasm-opt to read).
FEATURES_KX="--enable-bulk-memory --enable-sign-ext --enable-mutable-globals --enable-nontrapping-float-to-int \
--enable-multivalue --enable-reference-types"
CASES=${CASES:-"sum $(ls cases | tr '\n' ' ')"}

src() { if [ "$1" = sum ]; then echo kotlin/Main.kt; else echo "cases/$1/kotlin/Main.kt"; fi; }

build_case() {
  local c=$1 o=wasm/out/$1 s; s=$(src "$1")
  mkdir -p "$o/kx" "$o/kx-opt" "$o/kx-c"
  "$NKC" --target=wasm "$s" -o "$o/kx/main.wasm" 2> >(grep -v 'unused during compilation' >&2)
  wasm-opt $FEATURES_KX -O3 "$o/kx/main.wasm" -o "$o/kx-opt/main.wasm"
  "$NKC" --target=wasm --gc-runtime=c "$s" -o "$o/kx-c/main.wasm" 2> >(grep -v 'unused during compilation' >&2)
  [ "$c" = file ] && { echo "built $c (kx only)"; return; }
  mkdir -p "$o/klib" "$o/kw" "$o/kw-opt"
  "$KWC" -Xwasm-target=wasm-wasi -Xallow-kotlin-package -nowarn -libraries "$KW_STDLIB" \
    -ir-output-dir "$o/klib" -ir-output-name main "$s" wasm/TimeShim.kt
  "$KWC" -Xwasm-target=wasm-wasi -Xir-produce-js -Xir-dce -nowarn -libraries "$KW_STDLIB" \
    -Xinclude="$o/klib/main.klib" -ir-output-dir "$o/kw" -ir-output-name main
  cp "$o/kw/main.mjs" "$o/kw-opt/main.mjs"
  wasm-opt $BINARYEN_KW "$o/kw/main.wasm" -o "$o/kw-opt/main.wasm"
  echo "built $c"
}
export -f build_case src
export NKC KWC KW_STDLIB BINARYEN_KW FEATURES_KX
printf '%s\n' $CASES | xargs -P "${JOBS:-4}" -I{} bash -ec 'build_case {}'
