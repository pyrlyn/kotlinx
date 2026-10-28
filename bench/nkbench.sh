#!/bin/bash
# New Kotlin (default allocator; NKFLAGS adds compiler flags) on every case: warm-up + 5 runs, mean of the middle 3, in ms.
#   ./nkbench.sh <name> [git-ref]   -> results/nk/<name>.txt  ("case ms" per line)
# With a git ref the compiler is built from that ref (e.g. main = 'old NewKotlin' baseline);
# otherwise the working tree compiler is used.
set -eu
cd "$(dirname "$0")"
name=$1; ref=${2:-}
mkdir -p results/nk compiler/build/nk
if [ -n "$ref" ]; then
  d=compiler/build/nk-ref-$ref; mkdir -p "$d"; git archive "$ref" compiler | tar -x -C "$d"
  (cd "$d/compiler" && ./build.sh >/dev/null 2>&1)
  NKC=$d/compiler/build/newkotlinc.kexe; RT=$d/compiler/runtime/runtime.c
else
  [ -x compiler/build/newkotlinc.kexe ] || compiler/build.sh >/dev/null
  NKC=compiler/build/newkotlinc.kexe; RT=compiler/runtime/runtime.c
fi
[ -f data/words.txt ] || python3 gen_data.py >/dev/null
cat data/words.txt > /dev/null
out=results/nk/$name.txt; : > "$out"
echo "# $name ${ref:-working tree} $(date '+%Y-%m-%d %H:%M %Z') load: $(sysctl -n vm.loadavg)" >> "$out"
for c in sum $(ls cases); do
  src=cases/$c/kotlin/Main.kt; [ $c = sum ] && src=kotlin/Main.kt
  b=compiler/build/nk/$name.$c
  "$NKC" --runtime "$RT" ${NKFLAGS:-} "$src" -o "$b"
  "$b" >/dev/null
  ts=""; for r in 1 2 3 4 5; do ts="$ts $("$b" | grep -oE 'nanos=[0-9]+' | cut -d= -f2)"; done
  python3 -c "v=sorted(map(int,'$ts'.split()))[1:4]; print('$c', round(sum(v)/3/1e6, 3))" >> "$out"
done
cat "$out"
