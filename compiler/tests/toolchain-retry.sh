#!/bin/bash
# Runs one toolchain command (newkotlinc, clang) for the test suites and retries it only when the tool itself
# crashed, never when it reported an ordinary error. Usage: toolchain-retry.sh <tool> [args...]
#
# A crash is an exit by signal (status >= 128), or a failing exit whose stderr shows a crash or out-of-disk
# signature (see crash_re). Compile errors exit 1 or 2 without these and are passed through unchanged, so
# tests that expect a compile error still see it on the first attempt.
# KX_TOOL_RETRIES: extra attempts after a crash (default 2). KX_TOOL_RETRY_LOG: file that gets one line per crash.
set -u
tool=$1; shift
tries=$(( ${KX_TOOL_RETRIES:-2} + 1 ))
crash_re='No space left on device|mutex lock failed|PLEASE submit a bug report|Stack dump:|unable to execute command|Uncaught Kotlin exception|(Abort trap|Segmentation fault|Bus error|Killed): [0-9]+'
free_mb() { df -Pk . 2>/dev/null | awk 'NR==2{print int($4/1024)}'; }
note() {
  echo "toolchain-retry: $1" >&2
  [ -n "${KX_TOOL_RETRY_LOG:-}" ] && echo "$1" >> "$KX_TOOL_RETRY_LOG" 2>/dev/null
  return 0
}
err=$(mktemp "${TMPDIR:-/tmp}/kx-tool.XXXXXX" 2>/dev/null) || exec "$tool" "$@"
trap 'rm -f "$err"' EXIT
for ((i = 1; ; i++)); do
  "$tool" "$@" 2>"$err"; rc=$?
  why=""
  if [ $rc -ge 128 ]; then why="signal $((rc - 128))"
  elif [ $rc -ne 0 ] && grep -qE "$crash_re" "$err"; then why=$(grep -m1 -oE "$crash_re" "$err")
  fi
  if [ -z "$why" ] || [ $i -ge $tries ]; then
    cat "$err" >&2
    [ -n "$why" ] && note "$(basename "$tool") crashed ($why) on all $tries attempts, giving up; $(free_mb) MB free: $tool $*"
    exit $rc
  fi
  note "$(basename "$tool") crashed ($why), attempt $i/$tries, retrying; $(free_mb) MB free: $tool $*"
  sleep $i
done
