#!/bin/bash
# Golden tests (compiler/tests/*.kt + .out) compiled with --target=wasm and run under Node's WASI.
# Needs zig (or NK_WASM_CC) and node. Usage: compiler/tests/wasm/run.sh [test.kt ...]; JOBS=8 by default.
# Prints PASS/FAIL/CFAIL per test and a summary; exits 1 if any test fails. Output files: $OUT (default a temp dir).
cd "$(dirname "$0")/../../.."
root=$PWD
export NKC=${NKC:-$root/compiler/build/newkotlinc.kexe}
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$root/compiler/runtime/runtime.c}
export OUT=${OUT:-$(mktemp -d)}
export ROOT=$root

one() {
  local f=$1 b o out; b=$(basename "$f" .kt); o=${f%.kt}.out
  [ -f "$o" ] || return 0
  if ! "$NKC" --target=wasm "$f" -o "$OUT/$b.wasm" > "$OUT/$b.cerr" 2>&1; then echo "CFAIL $b"; return; fi
  # Same stack limits as bench/wasm/run.py (deep recursion runs on the engine stack).
  out=$(cd "$ROOT/compiler/tests" && ulimit -s 65520 && node --no-warnings --stack-size=60000 "$ROOT/bench/wasm/run.mjs" "$OUT/$b.wasm" 2>&1)
  if [ "$out" = "$(cat "$o")" ]; then echo "PASS $b"; else echo "FAIL $b"; printf '%s' "$out" > "$OUT/$b.got"; fi
}
export -f one
if [ $# -gt 0 ]; then tests=("$@"); else tests=(compiler/tests/*.kt); fi
printf '%s\n' "${tests[@]}" | xargs -P "${JOBS:-8}" -I{} bash -c 'one {}' | sort > "$OUT/summary.txt"
grep -v '^PASS' "$OUT/summary.txt"
echo "wasm golden: $(grep -c '^PASS' "$OUT/summary.txt") passed, $(grep -vc '^PASS' "$OUT/summary.txt") failed (outputs in $OUT)"
! grep -qv '^PASS' "$OUT/summary.txt"
