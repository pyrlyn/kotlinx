# Sourced by the leak scripts: leaks_at_exit <report> <binary> runs `leaks --atExit -- <binary>` into <report>
# and returns the leaks exit code.
# On the macos-15 hosted runners `leaks --atExit` leaves the inspected process stopped (state T, reparented to
# launchd) once the report is written. Each one still carries the job's RUNNER_TRACKING_ID, and the runner loses
# contact with GitHub while it cleans such processes up at the end of the job, so the job fails after every step
# has passed. The pid comes from the report, so only this run's process is killed; the command check guards
# against a pid that was reused after the process had already exited.
leaks_at_exit() {
  local report=$1 bin=$2 rc pid
  leaks --atExit -- "$bin" >"$report" 2>&1; rc=$?
  pid=$(sed -nE 's/^Process: +.* \[([0-9]+)\]$/\1/p' "$report" | head -1)
  if [ -n "$pid" ] && [ "$(ps -ww -p "$pid" -o command= 2>/dev/null)" = "$bin" ]; then kill -KILL "$pid" 2>/dev/null; fi
  return $rc
}
