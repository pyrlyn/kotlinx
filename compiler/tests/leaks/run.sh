#!/bin/bash
# Leak tests for KotlinX compiler's own memory management (not upstream / Kotlin Native tests).
# For each tests/leaks/*.kt:
#   1. --leak-check build: stdout must equal <name>.out and the runtime must report zero live
#      reference-counted objects at exit ("leak-check: OK", exit 0).
#   2. AddressSanitizer build (--alloc malloc, so ASan sees every object): no use-after-free / double free / overflow (exit 0, same stdout).
#   1b. <name>.regions (optional): lines the region report must contain (see runtime.c regions).
#   3. macOS `leaks --atExit` on a --alloc malloc build: "0 leaks" (leaks cannot inspect the mimalloc heap).
set -u
cd "$(dirname "$0")/../../.."
NKC=${NKC:-compiler/build/newkotlinc.kexe}
[ -x "$NKC" ] || compiler/build.sh >/dev/null
out=compiler/build/leak-tmp; rm -rf "$out"; mkdir -p "$out"; fail=0
for t in compiler/tests/leaks/*.kt; do
  n=$(basename "$t" .kt); exp=$(cat "compiler/tests/leaks/$n.out")
  if ! "$NKC" --leak-check "$t" -o "$out/$n.lc" 2>"$out/$n.err"; then echo "FAIL leak $n: compile: $(cat "$out/$n.err")"; fail=1; continue; fi
  got=$("$out/$n.lc" 2>"$out/$n.lcerr"); rc=$?
  if [ $rc -eq 0 ] && [ "$got" = "$exp" ] && grep -q "leak-check: OK" "$out/$n.lcerr"; then echo "PASS leak-count $n"
  else echo "FAIL leak-count $n (rc=$rc)"; cat "$out/$n.lcerr"; fail=1; fi
  # Region tests: <name>.regions lists lines the --leak-check region report must contain
  # (per-region allocation counts, collector visits; RC-region objects are never visited).
  if [ -f "compiler/tests/leaks/$n.regions" ]; then
    ok=1; while IFS= read -r line; do grep -qF -- "$line" "$out/$n.lcerr" || { ok=0; echo "  missing: $line"; }; done < "compiler/tests/leaks/$n.regions"
    if [ $ok = 1 ]; then echo "PASS regions $n"; else echo "FAIL regions $n"; grep '^region:' "$out/$n.lcerr"; fail=1; fi
  fi
  "$NKC" --alloc malloc --cflags "-O1 -g -fsanitize=address -fno-omit-frame-pointer" "$t" -o "$out/$n.asan" 2>/dev/null
  got=$("$out/$n.asan" 2>"$out/$n.asanerr"); rc=$?
  if [ $rc -eq 0 ] && [ "$got" = "$exp" ]; then echo "PASS asan $n"; else echo "FAIL asan $n (rc=$rc)"; head -20 "$out/$n.asanerr"; fail=1; fi
  "$NKC" --alloc malloc "$t" -o "$out/$n" 2>/dev/null
  if leaks --atExit -- "$out/$n" 2>/dev/null | grep -q " 0 leaks for 0 total leaked bytes"; then echo "PASS leaks $n"
  else echo "FAIL leaks $n"; fail=1; fi
done
[ $fail -eq 0 ] && echo "LEAKS ALL PASSED" || { echo "LEAKS FAILED"; exit 1; }
