#!/bin/bash
# Sequential PIT batches (one JVM per batch, 4g minions, 2 threads, at most 40 mutants per class).
# usage: mutation/jvm/pit-batches.sh [outdir]   results: <outdir>/<batch>/mutations.csv, resumable.
set -u
cd "$(dirname "$0")"; out=${1:-$HOME/Grok/mutation-tests/runs/pit}
for b in "lexer:newkotlin.Lexer*,newkotlin.Tok*" "parser:newkotlin.Parser*" \
  "types:newkotlin.Ty*,newkotlin.Types*,newkotlin.Generics*,newkotlin.Subst*,newkotlin.TypeRef*,newkotlin.TypeParam*" \
  "escape:newkotlin.Escape*,newkotlin.Prelude*,newkotlin.Scope*,newkotlin.Uses*,newkotlin.LiftedLocals*" \
  "native:newkotlin.NativeBackend*"; do
  n=${b%%:*}; c=${b#*:}
  [ -s "$out/$n/mutations.xml" ] && continue
  nice -n 10 gradle --no-daemon -q pitest -Dkx.skip=${KX_SKIP:-} -Dkx.pit.threads=2 -Dkx.pit.xmx=4g \
    -Dkx.pit.timeoutFactor=3 -Dkx.pit.timeoutConst=60000 -Dkx.pit.maxPerClass=${MAX_PER_CLASS:-40} -Dkx.pit.classes="$c"
  mkdir -p "$out/$n"; cp -R build/reports/pitest/. "$out/$n/"
done
