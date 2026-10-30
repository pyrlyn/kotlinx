#!/bin/bash
# Stress the macOS `leaks` check of one leak test with the parallel epoch collector:
#   compiler/tests/leaks/stress.sh [name=dlist] [runs=50]   (env: KX_GC_THREADS, default 4; JOBS, default 6)
# Builds compiler/tests/leaks/<name>.kt with --gc=epoch --alloc malloc and runs it RUNS times under
# `leaks --atExit`, JOBS at a time (the original failure needed a loaded machine: ~20% of runs with 6
# concurrent checks, ~0% one at a time), and RUNS times as a --leak-check build (live object count).
# Any failure keeps the full report in compiler/build/leak-stress/ and exits 1.
set -u
cd "$(dirname "$0")/../../.."
NKC=${NKC:-compiler/build/newkotlinc.kexe}
n=${1:-dlist}; runs=${2:-50}; export KX_GC_THREADS=${KX_GC_THREADS:-4}
out=compiler/build/leak-stress; mkdir -p "$out"; fail=0
"$NKC" --gc=epoch --alloc malloc "compiler/tests/leaks/$n.kt" -o "$out/$n" 2>/dev/null || { echo "FAIL compile"; exit 1; }
"$NKC" --gc=epoch --leak-check "compiler/tests/leaks/$n.kt" -o "$out/$n.lc" 2>/dev/null || { echo "FAIL compile lc"; exit 1; }
one() {
  local i=$1
  leaks --atExit -- "$out/$n" >"$out/$n.leaks.$i" 2>&1; local rc=$?
  if grep -q " 0 leaks for 0 total leaked bytes" "$out/$n.leaks.$i"; then rm -f "$out/$n.leaks.$i"
  else echo "FAIL leaks $n run $i (rc=$rc): $out/$n.leaks.$i"; grep -E "leaks for|ROOT LEAK" "$out/$n.leaks.$i" | head -3; fi
}
jobs_n=${JOBS:-6}
res=$(for i in $(seq "$runs"); do one "$i" & if (( i % jobs_n == 0 )); then wait; fi; done; wait)
[ -n "$res" ] && { echo "$res"; fail=1; }
for i in $(seq "$runs"); do
  "$out/$n.lc" >/dev/null 2>"$out/$n.lc.$i"; rc=$?
  if [ $rc -eq 0 ] && grep -q "leak-check: OK" "$out/$n.lc.$i"; then rm -f "$out/$n.lc.$i"
  else echo "FAIL leak-check $n run $i (rc=$rc): $out/$n.lc.$i"; fail=1; fi
done
[ $fail -eq 0 ] && echo "PASS stress $n x$runs (KX_GC_THREADS=$KX_GC_THREADS)" || exit 1
