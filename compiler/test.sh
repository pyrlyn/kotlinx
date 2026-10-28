#!/bin/bash
# Compiler tests: feature golden outputs (tests/*.kt -> tests/*.out) and the four
# benchmark sources, whose checksums must match Rust/Zig/Kotlin Native.
set -u
cd "$(dirname "$0")/.."
NKC=${NKC:-compiler/build/newkotlinc.kexe}
[ -x "$NKC" ] || compiler/build.sh >/dev/null
[ -f data/words.txt ] || python3 gen_data.py >/dev/null
out=compiler/build/test-tmp; rm -rf "$out"; mkdir -p "$out"; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected '$3', got '$2'"; fail=1; fi; }
for t in compiler/tests/*.kt; do
  n=$(basename "$t" .kt)
  if ! "$NKC" "$t" -o "$out/$n" 2>"$out/$n.err"; then echo "FAIL $n: compile: $(cat "$out/$n.err")"; fail=1; continue; fi
  check "$n" "$("$out/$n" 2>&1)" "$(cat compiler/tests/$n.out)"
done
while read -r case src key expected; do
  "$NKC" "$src" -o "$out/bench_$case" || { echo "FAIL bench $case: compile"; fail=1; continue; }
  got=$("$out/bench_$case" | grep -oE "$key=[-0-9]+" | cut -d= -f2)
  check "bench $case" "$got" "$expected"
done <<'G'
sum kotlin/Main.kt sum -430663335525468082
alloc cases/alloc/kotlin/Main.kt checksum 31012435000000
recursion cases/recursion/kotlin/Main.kt checksum 5002228309
file cases/file/kotlin/Main.kt checksum 3412301833168394113
G
# Error reporting: an unknown name must fail with a line number.
printf 'fun main() {\n  println(nope)\n}\n' > "$out/bad.kt"
msg=$("$NKC" "$out/bad.kt" -o "$out/bad" 2>&1)
case "$msg" in *"line 2: unknown name 'nope'"*) echo "PASS error-message";; *) echo "FAIL error-message: $msg"; fail=1;; esac
rm -rf "$out"
[ $fail = 0 ] && echo "ALL PASSED" || { echo "SOME FAILED"; exit 1; }
