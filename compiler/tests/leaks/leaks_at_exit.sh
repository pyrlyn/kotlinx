# Sourced by run.sh and stress.sh: leaks_at_exit <report> <binary> runs `leaks --atExit -- <binary>` with its
# output in <report> and returns leaks' exit code.
# On the macOS 15 GitHub runner image leaks leaves the inspected process stopped (state T, reparented to launchd)
# after the report, and the job never completes ("The hosted runner lost communication with the server" in its
# post steps). The process named in the report is killed if it is still there.
leaks_at_exit() {
  local report=$1 bin=$2 rc pid
  leaks --atExit -- "$bin" >"$report" 2>&1; rc=$?
  pid=$(sed -n 's/^Process: .*\[\([0-9][0-9]*\)\]$/\1/p' "$report" | head -1)
  if [ -n "$pid" ] && [ "$(ps -p "$pid" -o command= 2>/dev/null)" = "$bin" ]; then kill -KILL "$pid" 2>/dev/null; fi
  return $rc
}
