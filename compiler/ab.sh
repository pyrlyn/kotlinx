#!/bin/bash
# Native no-regression check: New Kotlin built from a git ref (default: main) vs the working tree.
# Compiles all benchmark cases with both compilers (default flags, malloc), runs them interleaved
# (A, B, A, B, ...) 5 times after a warm-up, and prints the mean of the middle 3 (ns) and the B/A ratio.
set -u
cd "$(dirname "$0")/.."
REF=${1:-main}
B=compiler/build/newkotlinc.kexe; [ -x "$B" ] || compiler/build.sh >/dev/null
old=compiler/build/ab-ref; mkdir -p "$old" compiler/build/ab
git archive "$REF" compiler | tar -x -C "$old"
(cd "$old/compiler" && ./build.sh >/dev/null 2>&1) || { echo "cannot build $REF"; exit 1; }
A=$old/compiler/build/newkotlinc.kexe
[ -f data/words.txt ] || python3 gen_data.py >/dev/null
cat data/words.txt > /dev/null
echo "load: $(sysctl -n vm.loadavg)"
printf "| case | %s, ns | working tree, ns | ratio |\n|---|---|---|---|\n" "$REF"
for c in sum alloc recursion file strings hashmap sort nbody matmul dispatch closures; do
  src=cases/$c/kotlin/Main.kt; [ $c = sum ] && src=kotlin/Main.kt
  "$A" --runtime "$old/compiler/runtime/runtime.c" "$src" -o compiler/build/ab/$c.a || exit 1
  "$B" "$src" -o compiler/build/ab/$c.b || exit 1
  compiler/build/ab/$c.a >/dev/null; compiler/build/ab/$c.b >/dev/null
  ta=(); tb=()
  for r in 1 2 3 4 5; do
    ta+=($(compiler/build/ab/$c.a | grep -oE 'nanos=[0-9]+' | cut -d= -f2))
    tb+=($(compiler/build/ab/$c.b | grep -oE 'nanos=[0-9]+' | cut -d= -f2))
  done
  python3 -c "
import sys
a=sorted(map(int,'${ta[*]}'.split()))[1:4]; b=sorted(map(int,'${tb[*]}'.split()))[1:4]
ma=sum(a)/3; mb=sum(b)/3
print(f'| $c | {ma:,.0f} | {mb:,.0f} | {mb/ma:.3f} |'.replace(',', ' '))"
done
