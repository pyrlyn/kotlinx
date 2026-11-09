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
# FFI: `external fun` + --link (tests/ffi/: C sources are linked, so they are not in the tests/*.kt loop).
for t in compiler/tests/ffi/ffi*.kt; do
  n=ffi_$(basename "$t" .kt)
  # Extra linker inputs (e.g. -lsqlite3) in <test>.link; programs run under --leak-check.
  extra=(); if [ -f "${t%.kt}.link" ]; then for l in $(cat "${t%.kt}.link"); do extra+=(--link "$l"); done; fi
  if ! "$NKC" "$t" --leak-check --link "${t%.kt}.c" ${extra[@]+"${extra[@]}"} -o "$out/$n" 2>"$out/$n.err"; then echo "FAIL $n: compile: $(cat "$out/$n.err")"; fail=1; continue; fi
  check "$n" "$("$out/$n" 2>"$out/$n.leak")" "$(cat "${t%.kt}.out")"
  grep -q "leak-check: OK" "$out/$n.leak" || { echo "FAIL $n: leak: $(grep -v 'live=0' "$out/$n.leak" | head -3)"; fail=1; }
done
# kxbindgen: bindings generated from a C header, prepended to the program (one input file per compile).
if python3 compiler/tools/kxbindgen.py compiler/tests/ffi/gen_demo.h -o "$out/gen_demo.bind.kt" >/dev/null; then
  cat "$out/gen_demo.bind.kt" compiler/tests/ffi/gen_demo.main.kt > "$out/gen_demo.kt"
  if "$NKC" "$out/gen_demo.kt" --link compiler/tests/ffi/gen_demo.c -o "$out/gen_demo" 2>"$out/gen_demo.err"; then
    check kxbindgen "$("$out/gen_demo" 2>&1)" "$(cat compiler/tests/ffi/gen_demo.out)"
  else echo "FAIL kxbindgen: compile: $(cat "$out/gen_demo.err")"; fail=1; fi
else echo "FAIL kxbindgen: generator failed"; fail=1; fi
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
# --target=js: front-end errors are reported as for native; a program runs under node (when installed).
msg=$("$NKC" --target=js "$out/bad.kt" -o "$out/x.js" 2>&1)
case "$msg" in *"line 2: unknown name 'nope'"*) echo "PASS target-js-error";; *) echo "FAIL target-js-error: $msg"; fail=1;; esac
if command -v node >/dev/null; then
  printf 'fun main() {\n  val xs = ArrayList<Long>()\n  for (i in 0 until 10) xs.add(i * 3000000000L)\n  var s = 0L\n  for (x in xs) s += x\n  println("sum=$s")\n}\n' > "$out/js.kt"
  if "$NKC" --target=js "$out/js.kt" -o "$out/js.js" 2>"$out/js.err" && [ "$(node "$out/js.js")" = "sum=135000000000" ]; then echo "PASS target-js"
  # js needs LLVM opt 22+ and the wasm32 collector bitcode (`zig build gc-bc -Dtarget=wasm32-wasi`); without them
  # (e.g. CI's LLVM 18) skip.
  elif grep -qE "LLVM opt [0-9]+\+|Zig GC bitcode missing" "$out/js.err"; then echo "SKIP target-js ($(head -1 "$out/js.err"))"
  else echo "FAIL target-js: $(head -c 300 "$out/js.err")"; fail=1; fi
else echo "SKIP target-js (no node)"; fi
for t in jvm; do
  msg=$("$NKC" --target=$t "$out/bad.kt" -o "$out/x" 2>&1); rc=$?
  if [ $rc -ne 0 ] && [ "$msg" = "target $t is not supported yet" ]; then echo "PASS target-$t"; else echo "FAIL target-$t: rc=$rc $msg"; fail=1; fi
done
# Leak tests (own tests, compiler/tests/leaks): runtime live-object count, ASan, macOS leaks.
NKC="$NKC" compiler/tests/leaks/run.sh || fail=1
# GC tests:
# - Zig 0.17.1: native Zig collectors (native/gc/zig) + C collectors via archive/run.sh.
# - Zig 0.16.x: legacy C-via-Zig suite (native/gc/c/tests/zig; uses @cImport, not valid on 0.17).
NKC_ABS="$(cd "$(dirname "$NKC")" && pwd)/$(basename "$NKC")"
zig_ver="$(command -v zig >/dev/null && zig version || true)"
if [ "$zig_ver" = "0.17.1" ]; then
  ( cd native/gc/zig && zig build test ) || fail=1
  ( export NKC="$NKC_ABS"; bash native/gc/c/tests/archive/run.sh ) || fail=1
else
  ( export NKC="$NKC_ABS"; cd native/gc/c/tests/zig && zig build ported ) || fail=1
  ( cd native/gc/c/tests/zig && zig build mt ) || fail=1
fi
rm -rf "$out"
[ $fail = 0 ] && echo "ALL PASSED" || { echo "SOME FAILED"; exit 1; }
