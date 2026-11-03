#!/bin/bash
# Compiler tests: feature golden outputs (tests/*.kt -> tests/*.out, incl. bug_* regressions),
# --target handling, and the benchmark sources, whose checksums must match Rust/Zig/Kotlin Native.
set -u
cd "$(dirname "$0")/.."
NKC=${NKC:-compiler/build/newkotlinc.kexe}
[ -x "$NKC" ] || compiler/build.sh >/dev/null
[ -f bench/data/words.txt ] || python3 bench/gen_data.py >/dev/null
out=compiler/build/test-tmp; rm -rf "$out"; mkdir -p "$out"; fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected '$3', got '$2'"; fail=1; fi; }
# Golden tests run with --leak-check: the report lines are filtered from the output, and a program that ends normally
# must exit 0 (exit 3 = live objects at exit). Programs that end with an uncaught exception are exempt from the leak
# gate (unwinding to the top leaves the exception and its frames' objects live by design).
for t in compiler/tests/*.kt; do
  n=$(basename "$t" .kt)
  if ! "$NKC" --leak-check "$t" -o "$out/$n" 2>"$out/$n.err"; then echo "FAIL $n: compile: $(cat "$out/$n.err")"; fail=1; continue; fi
  raw=$("$out/$n" 2>&1); rc=$?
  check "$n" "$(printf '%s\n' "$raw" | grep -v -e '^leak-check: ' -e '^region: ')" "$(cat compiler/tests/$n.out)"
  if [ $rc -ne 0 ] && ! grep -q '^Exception in thread' compiler/tests/$n.out; then
    echo "FAIL $n: leak-check exit $rc: $(printf '%s\n' "$raw" | grep -E '^leak-check: .* live=[1-9]' | tr '\n' ' ')"; fail=1
  fi
done
while read -r case src key expected; do
  "$NKC" "$src" -o "$out/bench_$case" || { echo "FAIL bench $case: compile"; fail=1; continue; }
  got=$(BENCH_DATA=bench/data/words.txt "$out/bench_$case" | grep -oE "$key=[-0-9]+" | cut -d= -f2)
  check "bench $case" "$got" "$expected"
done <<'G'
sum bench/kotlin/Main.kt sum -430663335525468082
alloc bench/cases/alloc/kotlin/Main.kt checksum 31012435000000
recursion bench/cases/recursion/kotlin/Main.kt checksum 5002228309
file bench/cases/file/kotlin/Main.kt checksum 3412301833168394113
strings bench/cases/strings/kotlin/Main.kt checksum 2000030888890
hashmap bench/cases/hashmap/kotlin/Main.kt checksum 3000006000000
sort bench/cases/sort/kotlin/Main.kt checksum -5669152051897089489
nbody bench/cases/nbody/kotlin/Main.kt checksum 1419289376832
matmul bench/cases/matmul/kotlin/Main.kt checksum -9228
dispatch bench/cases/dispatch/kotlin/Main.kt checksum 5597155580000
closures bench/cases/closures/kotlin/Main.kt checksum 3258209507
trees bench/cases/trees/kotlin/Main.kt checksum 14723759
free bench/cases/free/kotlin/Main.kt checksum 1200035933190
refpass bench/cases/refpass/kotlin/Main.kt checksum -538200000
cycles bench/cases/cycles/kotlin/Main.kt checksum 120488000000
lru bench/cases/lru/kotlin/Main.kt checksum 322182091481
G
# IR checks (compiler/tests/ir/*.kt): `// IR <fn>: <ERE>` must match a line of function @k.<fn> in the unoptimized IR,
# `// IR-NOT <fn>: <ERE>` must match none (escape decisions, allocation kinds).
for t in compiler/tests/ir/*.kt; do
  n=ir_$(basename "$t" .kt)
  if ! "$NKC" -S "$t" -o "$out/$n.ll" 2>"$out/$n.err"; then echo "FAIL $n: compile: $(cat "$out/$n.err")"; fail=1; continue; fi
  bad=0
  while IFS= read -r line; do
    kind=${line%% *}; rest=${line#* }; fn=${rest%%:*}; re=${rest#*: }
    body=$(awk -v f="@k.$fn(" 'index($0, "define ") == 1 && index($0, f) { on = 1; next } on && /^}/ { exit } on' "$out/$n.ll")
    [ -n "$body" ] || { echo "FAIL $n: no function @k.$fn"; bad=1; continue; }
    if printf '%s\n' "$body" | grep -qE -- "$re"; then hit=1; else hit=0; fi
    if [ "$kind" = IR ] && [ $hit = 0 ]; then echo "FAIL $n: $fn: no match for /$re/"; bad=1; fi
    if [ "$kind" = IR-NOT ] && [ $hit = 1 ]; then echo "FAIL $n: $fn: unexpected /$re/"; bad=1; fi
  done < <(sed -n 's|^// \(IR\(-NOT\)\{0,1\} [A-Za-z0-9_]*: .*\)$|\1|p' "$t")
  [ $bad = 0 ] && echo "PASS $n" || fail=1
done
# Error reporting: an unknown name must fail with a line number.
printf 'fun main() {\n  println(nope)\n}\n' > "$out/bad.kt"
msg=$("$NKC" "$out/bad.kt" -o "$out/bad" 2>&1)
case "$msg" in *"line 2: unknown name 'nope'"*) echo "PASS error-message";; *) echo "FAIL error-message: $msg"; fail=1;; esac
# An unclosed block at the end of the file is a compile error, not a crash.
printf 'fun main() {\n  if (true) {\n' > "$out/eof.kt"
msg=$("$NKC" "$out/eof.kt" -o "$out/eof" 2>&1)
case "$msg" in *"error: line 3:"*) echo "PASS eof-error";; *) echo "FAIL eof-error: $msg"; fail=1;; esac
for t in js wasm jvm; do
  msg=$("$NKC" --target=$t "$out/bad.kt" -o "$out/x" 2>&1); rc=$?
  if [ $rc -ne 0 ] && [ "$msg" = "target $t is not supported yet" ]; then echo "PASS target-$t"; else echo "FAIL target-$t: rc=$rc $msg"; fail=1; fi
done
# Leak tests (own tests, compiler/tests/leaks): runtime live-object count, ASan, macOS leaks.
NKC="$NKC" compiler/tests/leaks/run.sh || fail=1
# GC tests (compiler/gc/tests/zig): Zig suite, ported interface unit tests + GC-agnostic Kotlin tests for every --gc
# implementation (legacy shell suite archived in compiler/gc/tests/archive).
( export NKC="$(cd "$(dirname "$NKC")" && pwd)/$(basename "$NKC")"; cd compiler/gc/tests/zig && zig build ported ) || fail=1
# Multithreaded collector test (epoch: background collector + helpers; plain -O1/-O3 and a TSan build).
( cd compiler/gc/tests/zig && zig build mt ) || fail=1
rm -rf "$out"
[ $fail = 0 ] && echo "ALL PASSED" || { echo "SOME FAILED"; exit 1; }
