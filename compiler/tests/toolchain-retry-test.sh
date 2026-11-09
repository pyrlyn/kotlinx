#!/bin/bash
# Checks compiler/tests/toolchain-retry.sh with fake tools: a crash is retried, an ordinary error is not.
set -u
cd "$(dirname "$0")/../.."
w=compiler/tests/toolchain-retry.sh; d=$(mktemp -d "${TMPDIR:-/tmp}/kx-tr.XXXXXX"); fail=0
trap 'rm -rf "$d"' EXIT
ok() { echo "PASS toolchain-retry $1"; }
bad() { echo "FAIL toolchain-retry $1"; fail=1; }
# Aborts (SIGABRT) on the first call, then succeeds.
cat > "$d/crash-once" <<T
#!/bin/bash
if [ ! -e "$d/seen" ]; then : > "$d/seen"; kill -ABRT \$\$; fi
echo out; echo warn >&2
T
# Prints a crash signature and exits 1 on the first call, then succeeds.
cat > "$d/enospc-once" <<T
#!/bin/bash
if [ ! -e "$d/seen2" ]; then : > "$d/seen2"; echo "error: x.o: No space left on device" >&2; exit 1; fi
echo out
T
printf '#!/bin/bash\necho n >> %q/calls; echo "a.kt:1: error: unresolved name" >&2; exit 1\n' "$d" > "$d/compile-error"
printf '#!/bin/bash\necho n >> %q/calls2; kill -SEGV $$\n' "$d" > "$d/always-crash"
chmod +x "$d"/*
o=$(KX_TOOL_RETRY_LOG="$d/log" "$w" "$d/crash-once" 2>"$d/e"); rc=$?
[ $rc = 0 ] && [ "$o" = out ] && grep -q '^warn$' "$d/e" && grep -q 'crashed (signal 6), attempt 1/3' "$d/log" && ok crash || bad "crash (rc=$rc)"
o=$(KX_TOOL_RETRY_LOG="$d/log" "$w" "$d/enospc-once" 2>/dev/null); rc=$?
[ $rc = 0 ] && [ "$o" = out ] && grep -q 'No space left on device' "$d/log" && ok enospc || bad "enospc (rc=$rc)"
"$w" "$d/compile-error" 2>"$d/e"; rc=$?
[ $rc = 1 ] && [ "$(wc -l < "$d/calls" | tr -d ' ')" = 1 ] && grep -q 'unresolved name' "$d/e" && ! grep -q toolchain-retry "$d/e" \
  && ok compile-error || bad "compile-error (rc=$rc)"
KX_TOOL_RETRIES=1 "$w" "$d/always-crash" 2>"$d/e"; rc=$?
[ $rc = 139 ] && [ "$(wc -l < "$d/calls2" | tr -d ' ')" = 2 ] && grep -q 'on all 2 attempts, giving up' "$d/e" && ok give-up || bad "give-up (rc=$rc)"
m=$( . compiler/tests/toolchain.sh; KX_TEST_MIN_FREE_MB=999999999 kx_require_free_space ); rc=$?
[ $rc = 1 ] && case "$m" in "FAIL preflight: "*) true;; *) false;; esac && ok preflight || bad "preflight (rc=$rc)"
exit $fail
