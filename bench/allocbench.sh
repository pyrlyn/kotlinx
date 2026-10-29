#!/bin/bash
# Allocator comparison for Kotlin-X compiler: system malloc vs jemalloc (optional) vs mimalloc, plus
# Kotlin Native (prebuilt bench_kn.kexe from ./build.sh, if present) in the same session.
# Every case: warm-up + 5 runs per variant, interleaved, mean of the middle 3, in ms.
#   ./allocbench.sh [name]   -> results/alloc/<name>.txt  ("case variant ms checksum" per line)
# jemalloc is used if found at $JEMALLOC_DIR or $(brew --prefix jemalloc)/lib; otherwise skipped.
set -eu
cd "$(dirname "$0")"
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$PWD/../compiler/runtime/runtime.c}
name=${1:-alloc-compare}
NKC=../compiler/build/newkotlinc.kexe; [ -x "$NKC" ] || ../compiler/build.sh >/dev/null
J=${JEMALLOC_DIR:-$(brew --prefix jemalloc 2>/dev/null)/lib}
VARS="sys mi"; [ -f "$J/libjemalloc.dylib" ] || [ -f "$J/libjemalloc.so" ] && VARS="sys je mi"
[ -f data/words.txt ] || python3 gen_data.py >/dev/null
cat data/words.txt > /dev/null
b=../compiler/build/alloc; mkdir -p "$b" results/alloc; out=results/alloc/$name.txt
CASES="sum $(ls cases)"
for c in $CASES; do
  src=cases/$c/kotlin/Main.kt; [ $c = sum ] && src=kotlin/Main.kt
  "$NKC" --alloc malloc "$src" -o $b/$c.sys
  "$NKC" --alloc mimalloc "$src" -o $b/$c.mi
  case " $VARS " in *" je "*) "$NKC" --alloc malloc --cflags "-O3 -flto -L$J -ljemalloc -Wl,-rpath,$J" "$src" -o $b/$c.je;; esac
  kn=$(dirname "$src")/bench_kn.kexe; [ -x "$kn" ] && ln -sf "$PWD/$kn" $b/$c.kn
done
echo "# $name $(date '+%Y-%m-%d %H:%M %Z') load: $(sysctl -n vm.loadavg)" > "$out"
for c in $CASES; do for v in $VARS kn; do
  x=$b/$c.$v; [ -x "$x" ] || continue
  "$x" >/dev/null
  ts=""; for r in 1 2 3 4 5; do ts="$ts $("$x" | grep -oE '(^| )nanos=[0-9]+' | head -1 | cut -d= -f2)"; done
  cs=$("$x" | grep -oE '(sum|checksum)=[-0-9]+' | head -1)
  python3 -c "v=sorted(map(int,'$ts'.split()))[1:4]; print('$c $v', round(sum(v)/3/1e6, 3), '$cs')" | tee -a "$out"
done; done
echo "# load_end $(sysctl -n vm.loadavg)" >> "$out"
