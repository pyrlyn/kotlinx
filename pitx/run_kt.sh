#!/bin/bash
# pitx on a KotlinX golden test: compile <test>.kt with --emit-llvm --no-opt into a scratch dir, check the
# unmutated binary matches <test>.out, then mutate the captured .ll and relink each mutant with runtime + GC.
# Usage: pitx/run_kt.sh compiler/tests/<name>.kt [scratch-dir] [extra pitx args...]
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
kt=$1; work=${2:-$(mktemp -d -t pitx)}; shift; [ $# -gt 0 ] && shift
NKC=${NKC:-$root/compiler/build/newkotlinc.kexe}
n=$(basename "$kt" .kt); mkdir -p "$work"
NEWKOTLIN_RUNTIME="$root/compiler/runtime/runtime.c" "$NKC" --emit-llvm --no-opt --alloc malloc "$kt" -o "$work/$n"
want=$(cat "${kt%.kt}.out"); got=$("$work/$n" 2>&1) || true
[ "$got" = "$want" ] || { echo "pitx: baseline output differs from ${kt%.kt}.out" >&2; exit 1; }
pitx_bin=${PITX:-$root/pitx/zig-out/bin/pitx}
[ -x "$pitx_bin" ] || (cd "$root/pitx" && mise exec -- zig build -Doptimize=ReleaseSafe) >&2
exec "$pitx_bin" "$work/$n.ll" --harness link --runtime "$root/compiler/runtime/runtime.c" \
  --gc-dir "$root/compiler/gc" --json "$work/$n.pitx.json" "$@"
