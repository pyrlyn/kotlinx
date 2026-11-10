#!/bin/bash
# Size regression tests for --target=wasm: module size budgets, libc code that must stay out of every module and
# functions that must stay out of line (size-budget.txt), plus the KX_GC_STATS=1 report line. Needs Zig 0.17.1 and node. Exits 1 on any failure.
cd "$(dirname "$0")/../../.."
root=$PWD; d=compiler/tests/wasm
NKC=${NKC:-$root/compiler/build/newkotlinc.kexe}
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$root/compiler/runtime/runtime.c}
out=$(mktemp -d); fail=0
"$root/bin/newkotlinc" --gc-libs-only --target=wasm >/dev/null || exit 1
while read -r kind a b c; do
  case "$kind" in
    size)
      if ! "$NKC" --target=wasm "$b" -o "$out/$a.wasm" 2>"$out/$a.err"; then echo "FAIL size $a: compile: $(grep -v unused "$out/$a.err" | head -3)"; fail=1; continue; fi
      n=$(wc -c < "$out/$a.wasm" | tr -d ' ')
      if [ "$n" -le "$c" ]; then echo "PASS size $a: $n <= $c bytes"; else echo "FAIL size $a: $n > $c bytes"; fail=1; fi ;;
    keep) # a function that must stay out of line (a name-section build of the source)
      if ! NK_WASM_CC="$root/$d/cc-names.sh" "$NKC" --target=wasm "$b" -o "$out/keep-$a.wasm" 2>"$out/keep-$a.err"; then echo "FAIL keep $a: compile: $(grep -v unused "$out/keep-$a.err" | head -3)"; fail=1; continue; fi
      if python3 "$d/funcs.py" "$out/keep-$a.wasm" | awk '{print $2}' | grep -qE "$c"; then echo "PASS keep $a: $c"; else echo "FAIL keep $a: no function matches $c (inlined?)"; fail=1; fi ;;
  esac
done < "$d/size-budget.txt"
if ! NK_WASM_CC="$root/$d/cc-names.sh" "$NKC" --target=wasm "$d/size/hello.kt" -o "$out/names.wasm" 2>"$out/names.err"; then
  echo "FAIL size names: compile: $(grep -v unused "$out/names.err" | head -3)"; fail=1
else
  python3 "$d/funcs.py" "$out/names.wasm" > "$out/funcs.txt"
  while read -r kind re; do
    [ "$kind" = forbid ] || continue
    hit=$(awk '{print $2}' "$out/funcs.txt" | grep -E "$re" | head -3 | tr '\n' ' ')
    if [ -z "$hit" ]; then echo "PASS forbid $re"; else echo "FAIL forbid $re: linked $hit"; fail=1; fi
  done < "$d/size-budget.txt"
  # The GC report is formatted without printf; keep its format (bench/gc/micro.sh parses it).
  st=$(KX_GC_STATS=1 node --no-warnings "$root/bench/wasm/run.mjs" "$out/names.wasm" 2>&1 >/dev/null)
  if printf '%s\n' "$st" | grep -qE '^gc-stats: gc=perceus collections=[0-9]+ reclaimed=[0-9]+ pause_total=[0-9]+\.[0-9]{3} pause_max=[0-9]+\.[0-9]{3} pause_p50=[0-9]+\.[0-9]{3} pause_p99=[0-9]+\.[0-9]{3}$'
  then echo "PASS gc-stats line"; else echo "FAIL gc-stats line: $st"; fail=1; fi
fi
rm -rf "$out"
exit $fail
