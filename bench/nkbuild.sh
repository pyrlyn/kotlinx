#!/bin/bash
# Content-addressed build cache for benchmark binaries (build only; never runs the binary).
#   ./nkbuild.sh <out> <compiler> <src> [compiler args...]
# The key is a hash of the compiler binary, runtime + GC sources, the source file, the compiler args and
# the relevant environment (NEWKOTLIN_RUNTIME, NEWKOTLIN_GC, NK_OPT, clang version). On a hit the cached
# binary is copied to <out> without recompiling; its code and data are identical to a fresh build of the
# same inputs (only LC_UUID and the ad-hoc signature differ, as between any two builds), so timings are unaffected. NKBUILD_NOCACHE=1 always rebuilds.
set -eu
out=$1; nkc=$2; src=$3; shift 3
here=$(cd "$(dirname "$0")" && pwd); root=$(cd "$here/.." && pwd)
cache=$root/compiler/build/nkcache; mkdir -p "$cache" "$(dirname "$out")"
exe=$nkc; [ -f "$root/compiler/build/newkotlinc.kexe" ] && case $nkc in *bin/newkotlinc) exe=$root/compiler/build/newkotlinc.kexe;; esac
rt=${NEWKOTLIN_RUNTIME:-$root/compiler/runtime/runtime.c}; rtdir=$(dirname "$rt")
key=$( { echo "args: $* | gc=${NEWKOTLIN_GC:-} opt=${NK_OPT:-} rt=$rt"; clang --version 2>/dev/null | head -1
  find "$rtdir" "$rtdir/../gc" -path '*/tests' -prune -o -path '*/docs' -prune -o -type f \( -name '*.c' -o -name '*.h' \) -print | LC_ALL=C sort | xargs cat
  cat "$exe" "$src"; } | shasum -a 256 | cut -c1-24)
if [ -z "${NKBUILD_NOCACHE:-}" ] && [ -x "$cache/$key" ]; then cp -p "$cache/$key" "$out"; exit 0; fi
"$nkc" "$@" "$src" -o "$cache/$key.tmp.$$"
mv -f "$cache/$key.tmp.$$" "$cache/$key"; cp -p "$cache/$key" "$out"
