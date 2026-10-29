#!/bin/bash
# GC tests (separate from feature and upstream tests). For every implementation in compiler/gc/*/gc.c
# (or $GCS, e.g. GCS="default boehm"), according to its caps file
# (cycles = reclaims unreachable cycles, precise = live object count is exact at exit, asan = ASan-compatible):
#   1. unit/gc_unit.c: C tests of the kxgc.h interface (built with clang, -O1 and -O3).
#   2. kt/*.kt: GC-agnostic Kotlin tests; stdout must equal <name>.out. Tests marked "// gc-needs: cycles"
#      are skipped for implementations without the cycles capability.
#      precise: a --leak-check build must report zero live objects. asan: an ASan build (--alloc malloc) must pass.
set -u
cd "$(dirname "$0")/../../.."
NKC=${NKC:-compiler/build/newkotlinc.kexe}
[ -x "$NKC" ] || compiler/build.sh >/dev/null
CC=${NK_CC:-$( [ -x /usr/bin/clang ] && echo /usr/bin/clang || echo clang )}
gcs=${GCS:-$(for d in compiler/gc/*/gc.c; do basename "$(dirname "$d")"; done)}
out=compiler/build/gc-tmp; rm -rf "$out"; mkdir -p "$out"; fail=0
for g in $gcs; do
  caps=$(cat "compiler/gc/$g/caps" 2>/dev/null)
  has() { case " $caps " in *" $1 "*) return 0;; esac; return 1; }
  extra=""; if [ -f "compiler/gc/$g/config.sh" ]; then extra=$(sh "compiler/gc/$g/config.sh") || { echo "FAIL gc $g: config.sh"; fail=1; continue; }; fi
  def=""; has cycles && def=-DKX_CAP_CYCLES; has precise && def="$def -DKX_CAP_PRECISE"
  for o in -O1 -O3; do
    if eval "$CC" $o -g -Icompiler/gc $def $extra compiler/gc/tests/unit/gc_unit.c compiler/runtime/runtime.c "compiler/gc/$g/gc.c" -o "$out/unit-$g" 2>"$out/unit-$g.err" \
       && "$out/unit-$g" >"$out/unit-$g.log" 2>&1; then echo "PASS gc-unit $g $o"
    else echo "FAIL gc-unit $g $o"; cat "$out/unit-$g.err" "$out/unit-$g.log" 2>/dev/null | head -20; fail=1; fi
  done
  for t in compiler/gc/tests/kt/*.kt; do
    n=$(basename "$t" .kt); exp=$(cat "compiler/gc/tests/kt/$n.out")
    if grep -q '^// gc-needs: cycles' "$t" && ! has cycles; then echo "SKIP gc $g $n (no cycle collection)"; continue; fi
    if "$NKC" --gc="$g" "$t" -o "$out/$g.$n" 2>"$out/$g.$n.err" && [ "$("$out/$g.$n" 2>&1)" = "$exp" ]; then echo "PASS gc $g $n"
    else echo "FAIL gc $g $n"; head -5 "$out/$g.$n.err"; fail=1; fi
    if has precise; then
      "$NKC" --gc="$g" --leak-check "$t" -o "$out/$g.$n.lc" 2>/dev/null; got=$("$out/$g.$n.lc" 2>"$out/$g.$n.lcerr"); rc=$?
      if [ $rc -eq 0 ] && [ "$got" = "$exp" ] && grep -q "leak-check: OK" "$out/$g.$n.lcerr"; then echo "PASS gc-leaks $g $n"
      else echo "FAIL gc-leaks $g $n (rc=$rc)"; grep -v "live=0" "$out/$g.$n.lcerr" | head; fail=1; fi
    fi
    if has asan; then
      "$NKC" --gc="$g" --alloc malloc --cflags "-O1 -g -fsanitize=address -fno-omit-frame-pointer" "$t" -o "$out/$g.$n.asan" 2>/dev/null
      got=$("$out/$g.$n.asan" 2>"$out/$g.$n.asanerr"); rc=$?
      if [ $rc -eq 0 ] && [ "$got" = "$exp" ]; then echo "PASS gc-asan $g $n"; else echo "FAIL gc-asan $g $n (rc=$rc)"; head -20 "$out/$g.$n.asanerr"; fail=1; fi
    fi
  done
done
# Selecting an unknown implementation is a compile error.
if "$NKC" --gc=nope compiler/gc/tests/kt/reuse.kt -o "$out/nope" 2>/dev/null; then echo "FAIL gc-unknown"; fail=1; else echo "PASS gc-unknown"; fi
rm -rf "$out"
[ $fail -eq 0 ] && echo "GC ALL PASSED" || { echo "GC FAILED"; exit 1; }
