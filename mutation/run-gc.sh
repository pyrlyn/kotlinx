#!/bin/bash
# Mutation run over the GC C sources. usage: mutation/run-gc.sh <file> <gcs> <max> [jobs] [extra mutate.py args]
# e.g. mutation/run-gc.sh compiler/gc/epoch/gc.c epoch 120 2
# Work dirs, logs and results: ${MUT_WORK:-~/Grok/mutation-tests/runs}/kx-<file>/ (results.jsonl, resumable).
set -eu
here=$(cd "$(dirname "$0")" && pwd); root=$(cd "$here/.." && pwd)
f=$1; gcs=$2; max=$3; jobs=${4:-1}; shift $(( $# < 4 ? $# : 4 ))
[ -x "$root/compiler/build/newkotlinc.kexe" ] || "$root/compiler/build.sh"
work=${MUT_WORK:-$HOME/Grok/mutation-tests/runs}/kx-$(echo "$f" | tr / _)
export TMPDIR=${TMPDIR:-$work/tmp}
exec nice -n 10 python3 "$here/mutate.py" run --root "$root" --file "$f" --work "$work" --max "$max" --jobs "$jobs" \
  --timeout "${MUT_TIMEOUT:-300}" --setup "$here/gc-setup.sh" --test "GCS='$gcs' bash compiler/gc/tests/archive/run.sh" "$@"
