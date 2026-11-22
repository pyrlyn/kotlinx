#!/bin/bash
# Driver regression tests (command line, argv quoting, LTO flags): behaviour of newkotlinc itself, not of the compiled
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

# argv: tools are started without a shell, so every value is one argument as written. A --cc whose path has spaces,
# quotes and `$` is run as that one executable; --link and -o paths with `;`, `$(...)` and quotes reach it unchanged.
real_cc=${NK_CC:-$( [ -x /usr/bin/clang ] && echo /usr/bin/clang || command -v clang )}
argv_dir="$out/c c'\$HOME;x"; mkdir -p "$argv_dir"
fake_cc="$argv_dir/my cc \$(touch PWNED)"
printf '#!/bin/sh\nprintf "%%s\\n" "$@" >> "$KX_ARGV_LOG"\nexec %q "$@"\n' "$real_cc" > "$fake_cc"; chmod +x "$fake_cc"
printf 'int kx_unused_argv_test(void) { return 1; }\n' > "$argv_dir/l;ink \$x 'q'.c"
export KX_ARGV_LOG="$out/argv.log"; : > "$KX_ARGV_LOG"
if (cd "$out" && "$NKC" $FLAGS --cc "$fake_cc" --link "$argv_dir/l;ink \$x 'q'.c" --runtime "$root/compiler/runtime/runtime.c" \
      "$out/hi.kt" -o "$argv_dir/o;ut \$HOME" 2>"$out/err") \
    && [ "$("$argv_dir/o;ut \$HOME")" = hi ] && [ ! -e "$out/PWNED" ] && [ ! -e PWNED ] \
    && grep -qxF "$argv_dir/l;ink \$x 'q'.c" "$KX_ARGV_LOG" && grep -qxF "$argv_dir/o;ut \$HOME" "$KX_ARGV_LOG"; then pass argv-cc-link-output
else bad argv-cc-link-output "$(head -c 300 "$out/err") argv: $(tr '\n' ' ' < "$KX_ARGV_LOG" | head -c 300)"; fi
# --cflags is split on whitespace only: each word is one clang argument, nothing else is interpreted.
: > "$KX_ARGV_LOG"
if "$NKC" $FLAGS --cc "$fake_cc" --cflags "-O1  -DKX_A='x' -DKX_B=\$y" "$out/hi.kt" -o "$out/cf" 2>"$out/err" && [ "$("$out/cf")" = hi ] \
    && grep -qxF -- "-DKX_A='x'" "$KX_ARGV_LOG" && grep -qxF -- "-DKX_B=\$y" "$KX_ARGV_LOG" && grep -qxF -- "-O1" "$KX_ARGV_LOG"; then pass argv-cflags-words
else bad argv-cflags-words "$(head -c 300 "$out/err") argv: $(tr '\n' ' ' < "$KX_ARGV_LOG" | head -c 300)"; fi
unset KX_ARGV_LOG
# A tool that cannot be started is a failed compile with the command in the message, not a crash or a hang.
"$NKC" $FLAGS --no-opt --cc "$out/no such cc" "$out/hi.kt" -o "$out/nocc" >/dev/null 2>"$out/err"; rc=$?
if [ $rc = 1 ] && grep -q "no such cc" "$out/err"; then pass argv-missing-tool; else bad argv-missing-tool "exit $rc: $(head -c 300 "$out/err")"; fi
# The js and jvm targets with the same paths, when their toolchains are installed (bin/newkotlinc builds the Zig
# bitcode the js target needs; java 21+ for jvm).
if command -v java >/dev/null 2>&1; then
  if "$NKC" --target jvm "$weird/in put.kt" -o "$argv_dir/j;ar \$HOME" 2>"$out/err" && [ "$("$argv_dir/j;ar \$HOME")" = hi ] \
      && [ ! -e "$out/PWNED" ]; then pass argv-jvm
  else bad argv-jvm "$(head -c 300 "$out/err")"; fi
fi
if command -v node >/dev/null 2>&1 && command -v zig >/dev/null 2>&1 && ls "$root"/native/gc/zig/zig-out/wasm32-wasi/lib/libgc-*.bc >/dev/null 2>&1; then
  if "$NKC" --target js "$weird/in put.kt" -o "$argv_dir/j;s \$HOME.js" 2>"$out/err" && [ "$(node "$argv_dir/j;s \$HOME.js")" = hi ] \
      && [ ! -e "$out/PWNED" ]; then pass argv-js
  else bad argv-js "$(head -c 300 "$out/err")"; fi
fi

# bug_flto: LTO flags are removed as words for the Zig collector archive (`-flto=thin` used to become `=thin`).
for f in "-O2 -flto=thin" "-O2 -flto=full" "-O3 -flto"; do
  if "$NKC" $FLAGS --cflags "$f" "$out/hi.kt" -o "$out/lto" 2>"$out/err" && [ "$("$out/lto")" = hi ]; then pass "cflags[$f]"
  else bad "cflags[$f]" "$(head -c 300 "$out/err")"; fi
done
exit $fail
