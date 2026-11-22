# Sourced from the repo root by compiler/test.sh, compiler/tests/leaks/run.sh and native/gc/c/tests/archive/run.sh.
#
# Both suites run every compile serially, so the parallelism of the suites themselves is not a factor. What made
# them flaky on a busy machine was the build volume filling up (other jobs' build output): clang and newkotlinc
# then fail or abort ("No space left on device", "mutex lock failed", "Uncaught Kotlin exception") and every
# following test fails with them. kx_require_free_space turns that into one clear failure up front;
# kx_wrap_toolchain retries a crashed compile (only a crash, see toolchain-retry.sh) and records it.

kx_free_mb() { local p=${1:-.}; [ -e "$p" ] || p=.; df -Pk "$p" 2>/dev/null | awk 'NR==2{print int($4/1024)}'; }

# KX_TEST_MIN_FREE_MB (default 2048; 0 turns the check off).
kx_require_free_space() {
  local need=${KX_TEST_MIN_FREE_MB:-2048} have
  have=$(kx_free_mb compiler/build)
  if [ "$need" -gt 0 ] && [ -n "$have" ] && [ "$have" -lt "$need" ]; then
    echo "FAIL preflight: $have MB free on the build volume, need $need MB (KX_TEST_MIN_FREE_MB);" \
      "compiles would fail with 'No space left on device'"
    return 1
  fi
}

# Points NKC and NK_CC at wrappers that run the real tool through toolchain-retry.sh. Idempotent, so a suite
# started by compiler/test.sh keeps the same wrappers and the same retry log.
kx_wrap_toolchain() {
  [ "${KX_TOOL_WRAPPED:-}" = 1 ] && return 0
  local dir root real_nkc real_cc
  root=$(pwd); dir="$root/compiler/build/toolchain"; mkdir -p "$dir" || return 1
  real_nkc="$(cd "$(dirname "$NKC")" && pwd)/$(basename "$NKC")"
  real_cc=${NK_CC:-$( [ -x /usr/bin/clang ] && echo /usr/bin/clang || command -v clang )}
  printf '#!/bin/bash\nexec %q %q "$@"\n' "$root/compiler/tests/toolchain-retry.sh" "$real_nkc" > "$dir/newkotlinc"
  printf '#!/bin/bash\nexec %q %q "$@"\n' "$root/compiler/tests/toolchain-retry.sh" "$real_cc" > "$dir/cc"
  chmod +x "$dir/newkotlinc" "$dir/cc"
  export NKC="$dir/newkotlinc" NK_CC="$dir/cc" KX_TOOL_WRAPPED=1 KX_TOOL_WRAP_OWNER=$$
  export KX_TOOL_RETRY_LOG=${KX_TOOL_RETRY_LOG:-$root/compiler/build/toolchain-retry.log}
  : > "$KX_TOOL_RETRY_LOG"
}

# kx_with_timeout <seconds> <command...>: a test binary that never exits would otherwise stall the suite until the CI
# job's timeout. macOS ships no `timeout`; perl's alarm survives the exec and SIGALRM ends the program (exit 142).
kx_with_timeout() { local s=$1; shift; perl -e 'alarm shift; exec { $ARGV[0] } @ARGV or exit 127' "$s" "$@"; }

KX_ASAN_CFLAGS="-O1 -g -fsanitize=address -fno-omit-frame-pointer"
# Exports KX_ASAN=0 when an ASan build of an empty program hangs at start-up, else KX_ASAN=1; once per run, so
# suites started by compiler/test.sh reuse the answer. ASan runtimes before LLVM 22 deadlock in their own start-up on
# macOS 26.4+: get_dyld_hdr walks the shared cache with a block, the block copy calls malloc, and malloc re-enters
# ASan's init (llvm/llvm-project#182943). Then no ASan build runs at all, whatever the test, so the suites skip
# their ASan checks instead of hanging on every one. A probe that fails to compile or crashes keeps KX_ASAN=1:
# only a hang is the toolchain's start-up bug, and anything else must still fail the ASan tests.
kx_probe_asan() {
  [ -n "${KX_ASAN:-}" ] && return 0
  local d=compiler/build/asan-probe; rm -rf "$d"; mkdir -p "$d" || return 1
  printf 'fun main() {\n  println("ok")\n}\n' > "$d/probe.kt"
  KX_ASAN=1
  if "$NKC" --alloc malloc --cflags "$KX_ASAN_CFLAGS" "$d/probe.kt" -o "$d/probe" 2>/dev/null; then
    kx_with_timeout 30 "$d/probe" >/dev/null 2>&1
    [ $? -eq 142 ] && KX_ASAN=0
  fi
  rm -rf "$d"; export KX_ASAN
}
KX_ASAN_SKIP="an ASan build of an empty program hangs at start-up (ASan runtime from LLVM < 22 on macOS 26.4+?)"

# Prints the crashes that were retried (or gave up) during the run, if any; only in the suite that set up the
# wrappers, so a nested suite does not print them twice.
kx_report_retries() {
  [ "${KX_TOOL_WRAP_OWNER:-}" = $$ ] && [ -s "${KX_TOOL_RETRY_LOG:-}" ] || return 0
  echo "NOTE: $(wc -l < "$KX_TOOL_RETRY_LOG" | tr -d ' ') toolchain crash(es) during this run ($KX_TOOL_RETRY_LOG):"
  sed 's/^/  /' "$KX_TOOL_RETRY_LOG" | head -20
}
