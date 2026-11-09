#!/bin/bash
# Golden tests (compiler/tests/*.kt + .out) compiled with --target=wasm and run under Node's WASI.
# Needs Zig 0.17.1 (GC archives, and the linker unless NK_WASM_CC is set) and node. Usage: compiler/tests/wasm/run.sh [test.kt ...]; JOBS=8 by default.
# Prints PASS/FAIL/CFAIL per test and a summary; exits 1 if any test fails. Tests in known-failures.txt are XFAIL
# (XPASS when they pass: the listed failure is undefined behaviour that can pass by luck, so neither fails the run). Output files: $OUT (default a temp dir).
cd "$(dirname "$0")/../../.."
root=$PWD
export NKC=${NKC:-$root/compiler/build/newkotlinc.kexe}
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$root/compiler/runtime/runtime.c}
export OUT=${OUT:-$(mktemp -d)}
export ROOT=$root
# Zig GC archives for wasm32-wasi (default --gc-runtime=zig), built once before the parallel compiles.
"$root/bin/newkotlinc" --gc-libs-only --target=wasm || exit 1

one() {
  local f=$1 b o out; b=$(basename "$f" .kt); o=${f%.kt}.out
  [ -f "$o" ] || return 0
  if ! "$NKC" --target=wasm "$f" -o "$OUT/$b.wasm" > "$OUT/$b.cerr" 2>&1; then echo "CFAIL $b"; return; fi
  # Same stack limits as bench/wasm/run.py (deep recursion runs on the engine stack).
  out=$(cd "$ROOT/compiler/tests" && ulimit -s 65520 && node --no-warnings --stack-size=60000 "$ROOT/bench/wasm/run.mjs" "$OUT/$b.wasm" 2>&1)
  local ok=PASS bad=FAIL
  if grep -qx "$b" "$ROOT/compiler/tests/wasm/known-failures.txt"; then ok=XPASS; bad=XFAIL; fi
  if [ "$out" = "$(cat "$o")" ]; then echo "$ok $b"; else echo "$bad $b"; printf '%s' "$out" > "$OUT/$b.got"; fi
}
export -f one
if [ $# -gt 0 ]; then tests=("$@"); else tests=(compiler/tests/*.kt); fi
printf '%s\n' "${tests[@]}" | xargs -P "${JOBS:-8}" -I{} bash -c 'one {}' | sort > "$OUT/summary.txt"
grep -v '^PASS' "$OUT/summary.txt"
echo "wasm golden: $(grep -c '^PASS' "$OUT/summary.txt") passed, $(grep -cE '^X(FAIL|PASS)' "$OUT/summary.txt") known failures (known-failures.txt), $(grep -vEc '^(PASS|XFAIL|XPASS)' "$OUT/summary.txt") failed (outputs in $OUT)"
! grep -qvE '^(PASS|XFAIL|XPASS)' "$OUT/summary.txt"
