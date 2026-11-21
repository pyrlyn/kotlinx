# Sourced from the repo root by compiler/test.sh and native/gc/c/tests/archive/run.sh.
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

# Prints the crashes that were retried (or gave up) during the run, if any; only in the suite that set up the
# wrappers, so a nested suite does not print them twice.
kx_report_retries() {
  [ "${KX_TOOL_WRAP_OWNER:-}" = $$ ] && [ -s "${KX_TOOL_RETRY_LOG:-}" ] || return 0
  echo "NOTE: $(wc -l < "$KX_TOOL_RETRY_LOG" | tr -d ' ') toolchain crash(es) during this run ($KX_TOOL_RETRY_LOG):"
  sed 's/^/  /' "$KX_TOOL_RETRY_LOG" | head -20
}
