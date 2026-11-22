#!/bin/bash
# Driver regression tests (command line, shell quoting, LTO flags): behaviour of newkotlinc itself, not of the compiled
# program, so they are not golden tests. Run from the repository root by compiler/test.sh (NKC = the compiler,
# RUNTIME_FLAGS = extra flags such as --gc-runtime=c --alloc malloc for hosts without the Zig archives or mimalloc).
set -u
NKC=${NKC:-compiler/build/newkotlinc.kexe}; case "$NKC" in /*) ;; *) NKC="$PWD/$NKC";; esac
root=$PWD
FLAGS=${RUNTIME_FLAGS:-}
out=$(mktemp -d "${TMPDIR:-/tmp}/kx-driver.XXXXXX"); fail=0
trap 'rm -rf "$out"' EXIT
pass() { echo "PASS driver-$1"; }
bad() { echo "FAIL driver-$1: $2"; fail=1; }
printf 'fun main() {\n    println("hi")\n}\n' > "$out/hi.kt"

# bug_args: an option without its value is a usage error (exit 2 and a message), not an index out of bounds.
for opt in -o --target --gc --alloc --cflags --link; do
  "$NKC" "$out/hi.kt" $opt >/dev/null 2>"$out/err"; rc=$?
  if [ $rc = 2 ] && grep -q "option $opt needs a value" "$out/err"; then pass "missing-value$opt"
  else bad "missing-value$opt" "exit $rc: $(head -c 200 "$out/err")"; fi
done
"$NKC" "$out/hi.kt" "$out/hi.kt" -o "$out/x" >/dev/null 2>"$out/err"; rc=$?
if [ $rc = 2 ] && grep -q "unexpected argument" "$out/err"; then pass second-input; else bad second-input "exit $rc: $(head -c 200 "$out/err")"; fi
"$NKC" "$out/hi.kt" --bogus -o "$out/x" >/dev/null 2>"$out/err"; rc=$?
if [ $rc = 2 ] && grep -q "unknown option: --bogus" "$out/err"; then pass unknown-option; else bad unknown-option "exit $rc: $(head -c 200 "$out/err")"; fi
"$NKC" "$out/hi.kt" >/dev/null 2>"$out/err"; rc=$?
if [ $rc = 2 ] && grep -q "no output file" "$out/err"; then pass no-output; else bad no-output "exit $rc: $(head -c 200 "$out/err")"; fi
"$NKC" "$out/hi.kt" --gc ../epoch -o "$out/x" >/dev/null 2>"$out/err"; rc=$?
if [ $rc = 2 ] && grep -q "unknown --gc" "$out/err"; then pass gc-path; else bad gc-path "exit $rc: $(head -c 200 "$out/err")"; fi
"$NKC" "$out/hi.kt" --alloc tcmalloc -o "$out/x" >/dev/null 2>"$out/err"; rc=$?
if [ $rc = 2 ] && grep -q -- "--alloc must be" "$out/err"; then pass bad-alloc; else bad bad-alloc "exit $rc: $(head -c 200 "$out/err")"; fi

# bug_shell_quoting: paths with spaces, quotes and $(...) reach clang/opt literally (no word splitting, no injection).
weird="$out/it's a \$(touch PWNED) dir"; mkdir -p "$weird"; cp "$out/hi.kt" "$weird/in put.kt"
if (cd "$out" && "$NKC" $FLAGS --runtime "$root/compiler/runtime/runtime.c" "$weird/in put.kt" -o "$weird/o'ut" 2>"$out/err") \
    && [ "$("$weird/o'ut")" = hi ] && [ ! -e "$out/PWNED" ] && [ ! -e PWNED ]; then pass shell-quoting
else bad shell-quoting "$(head -c 300 "$out/err")"; fi
if "$NKC" -S "$weird/in put.kt" -o "$weird/o'ut.ll" 2>"$out/err" && grep -q "define" "$weird/o'ut.ll"; then pass shell-quoting-ir
else bad shell-quoting-ir "$(head -c 300 "$out/err")"; fi

# bug_flto: LTO flags are removed as words for the Zig collector archive (`-flto=thin` used to become `=thin`).
for f in "-O2 -flto=thin" "-O2 -flto=full" "-O3 -flto"; do
  if "$NKC" $FLAGS --cflags "$f" "$out/hi.kt" -o "$out/lto" 2>"$out/err" && [ "$("$out/lto")" = hi ]; then pass "cflags[$f]"
  else bad "cflags[$f]" "$(head -c 300 "$out/err")"; fi
done
exit $fail
