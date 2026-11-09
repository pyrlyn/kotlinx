#!/bin/bash
# Performance regression checks for --target=wasm under node. Absolute times depend on the machine, so every
# check compares two builds or two engine settings of the same benchmark, run interleaved (median of $RUNS, default
# 5), and fails when the ratio exceeds the limit in perf-limits.txt. Checksums of both sides must match.
# Needs Zig 0.17.1 and node. Usage: compiler/tests/wasm/perf.sh [check name ...]; exits 1 on any failure.
cd "$(dirname "$0")/../../.."
root=$PWD; d=compiler/tests/wasm
NKC=${NKC:-$root/compiler/build/newkotlinc.kexe}
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$root/compiler/runtime/runtime.c}
RUNS=${RUNS:-5}; out=$(mktemp -d); [ -n "${KEEP:-}" ] || trap 'rm -rf "$out"' EXIT; fail=0
"$root/bin/newkotlinc" --gc-libs-only --target=wasm >/dev/null 2>&1 || exit 1
# The old default (--alloc malloc) needs its own archive: a separate prefix keeps zig-out/wasm32-wasi intact.
plain=$out/plain
( cd native/gc/zig && "${ZIG:-zig}" build gc-libs -Dgc=perceus -Dtarget=wasm32-wasi -Dplain-malloc=true -p "$plain" ) >/dev/null 2>&1 || { echo "FAIL perf: zig build (plain malloc)"; exit 1; }
build() { # build <name> <case> <variant>: variant default | malloc
  local src=bench/cases/$2/kotlin/Main.kt
  case "$3" in
    default) "$NKC" --target=wasm "$src" -o "$out/$1.wasm" ;;
    malloc) NEWKOTLIN_GC_ZIG_WASM_LIB_DIR=$plain/lib "$NKC" --target=wasm --alloc malloc "$src" -o "$out/$1.wasm" ;;
  esac 2>"$out/$1.err"
}
run() { # run <wasm> <node flags>: prints "<nanos> <checksum>"
  local o; o=$(cd bench && ulimit -s 65520 && node --no-warnings --stack-size=60000 $2 "$root/bench/wasm/run.mjs" "$1" 2>&1)
  echo "$(echo "$o" | grep -o 'nanos=[0-9]*' | cut -d= -f2) $(echo "$o" | grep -oE '(checksum|sum)=-?[0-9]+' | head -1)"
}
med() { sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }
while read -r name case a b limit; do
  case "$name" in ''|\#*) continue ;; esac
  [ $# -gt 0 ] && [[ " $* " != *" $name "* ]] && continue
  # a/b: <variant>[:<node flags with , for spaces>]; the check is median(a) / median(b) <= limit.
  va=${a%%:*}; fa=$( [[ $a == *:* ]] && echo "${a#*:}" | tr , ' ' ); vb=${b%%:*}; fb=$( [[ $b == *:* ]] && echo "${b#*:}" | tr , ' ' )
  if ! build "$name.a" "$case" "$va" || ! build "$name.b" "$case" "$vb"; then echo "FAIL perf $name: compile: $(grep -hv unused "$out/$name".*.err | head -3)"; fail=1; continue; fi
  : > "$out/ta"; : > "$out/tb"; ca=""; cb=""
  run "$out/$name.a.wasm" "$fa" >/dev/null; run "$out/$name.b.wasm" "$fb" >/dev/null # warm-up
  for i in $(seq "$RUNS"); do
    r=$(run "$out/$name.a.wasm" "$fa"); echo "${r%% *}" >> "$out/ta"; ca=${r#* }
    r=$(run "$out/$name.b.wasm" "$fb"); echo "${r%% *}" >> "$out/tb"; cb=${r#* }
  done
  ma=$(med < "$out/ta"); mb=$(med < "$out/tb")
  ratio=$(awk -v a="$ma" -v b="$mb" 'BEGIN{ if (a > 0 && b > 0) printf "%.2f", a / b; else print "nan" }')
  msg="$name ($case): $a $(awk -v x="$ma" 'BEGIN{printf "%.1f", x/1e6}') ms / $b $(awk -v x="$mb" 'BEGIN{printf "%.1f", x/1e6}') ms = ${ratio}x (limit ${limit}x)"
  if [ -z "$ca" ] || [ "$ca" != "$cb" ]; then echo "FAIL perf $msg: checksums '$ca' vs '$cb'"; fail=1
  elif awk -v r="$ratio" -v l="$limit" 'BEGIN{exit !(r != "nan" && r <= l)}'; then echo "PASS perf $msg"
  else echo "FAIL perf $msg"; fail=1; fi
done < "$d/perf-limits.txt"
exit $fail
