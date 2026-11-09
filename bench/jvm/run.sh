#!/bin/bash
# Speed and file size of `newkotlinc --target jvm` (LLVM IR -> JVM bytecode) against KotlinX native,
# Kotlin/Native and Kotlin/JVM, on the bench sources (bench/kotlin, bench/cases/*/kotlin).
#
#   bench/jvm/run.sh [case...]      default: every case
#   RUNS=5                          timed runs per row and case (median is reported; default 3)
#   ROWS="kxjvm kxnative kn kjvm"   rows to build and run (default: all four)
#   NOBUILD=1                       reuse the builds in bench/jvm/out
#
# Rows (equal-conditions policy of bench/build.sh: release builds, no -march/-mcpu=native):
#   kxjvm     newkotlinc --target jvm: executable jar (sh prefix + jar), needs java 21+
#   kxnative  newkotlinc with native defaults (builds the Zig GC libs: set ZIG to an official Zig 0.17.1)
#   kn        Kotlin/Native kotlinc-native -opt
#   kjvm      Kotlin/JVM kotlinc; java -Xss1g -cp <jar>:kotlin-stdlib.jar (posix/cinterop cases are skipped;
#             -Xss1g matches the 1 GiB thread stack kxjvm runs main on, for the deep recursion case)
# Both JVM rows run on the same `java`. Time: `nanos=` printed by the program (its timed section, after start-up;
# JVM rows include JIT warm-up inside it) and wall time of the whole process (/usr/bin/time, includes JVM start).
# Output: bench/results/jvm/runs.tsv (every run), medians.tsv and summary.md (rewritten each time).
set -u
cd "$(dirname "$0")/.."
root=$(cd .. && pwd)
NKC=$root/bin/newkotlinc
KN=${KONANC:-$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21/bin/kotlinc-native}
KOTLINC=${KOTLINC:-$HOME/.local/share/mise/installs/kotlin/2.4.21/kotlinc/bin/kotlinc}
STDLIB=$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar
RUNS=${RUNS:-3}
ROWS=${ROWS:-kxjvm kxnative kn kjvm}
CASES=${*:-sum $(ls cases | tr '\n' ' ')}
out=jvm/out; res=results/jvm
mkdir -p "$out" "$res"
[ -f data/words.txt ] || python3 gen_data.py >/dev/null
export BENCH_DATA=$PWD/data/words.txt
runs=$res/runs.tsv; med=$res/medians.tsv
printf 'case\trow\trun\tchecksum\tnanos\twall_s\trss_bytes\n' > "$runs"
printf 'case\trow\tchecksum\tnanos\twall_s\trss_bytes\tsize_bytes\n' > "$med"

src_of() { if [ "$1" = sum ]; then echo kotlin/Main.kt; else echo "cases/$1/kotlin/Main.kt"; fi; }
bin_of() { case $1 in kn) echo "$out/kn/$2.kexe";; kjvm) echo "$out/kjvm/$2.jar";; *) echo "$out/$1/$2";; esac; }
uses_posix() { grep -qE '^import (platform\.posix|kotlinx\.cinterop)' "$1"; }
size() { stat -f %z "$1"; }
median() { sort -g | awk 'NF{a[++n]=$1} END{if (n==0) print "-"; else print a[int((n+1)/2)]}'; }

build() { # row case; returns 2 when the row cannot build this case
    local row=$1 c=$2 src; src=$(src_of "$c")
    mkdir -p "$out/$row"
    case $row in
        kxjvm) "$NKC" --target jvm "$src" -o "$out/kxjvm/$c" ;;
        kxnative) "$NKC" "$src" -o "$out/kxnative/$c" ;;
        kn) "$KN" "$src" -opt -o "$out/kn/$c" >/dev/null ;;
        kjvm) uses_posix "$src" && return 2
            "$KOTLINC" -Xallow-kotlin-package -nowarn "$src" jvm/shim/system.kt -d "$out/kjvm/$c.jar" ;;
    esac
}

run() { # row case -> "checksum nanos wall_s rss_bytes"
    local row=$1 c=$2 b o; b=$(bin_of "$row" "$c")
    if [ "$row" = kjvm ]; then o=$( { /usr/bin/time -lp java -Xss1g -cp "$b:$STDLIB" MainKt; } 2>&1 )
    else o=$( { /usr/bin/time -lp "$b"; } 2>&1 ); fi
    echo "$(echo "$o" | grep -oE '(sum|checksum)=-?[0-9]+' | head -1 | cut -d= -f2)" \
         "$(echo "$o" | grep -oE 'nanos=[0-9]+' | head -1 | cut -d= -f2)" \
         "$(echo "$o" | awk '/^real/{print $2}')" "$(echo "$o" | awk '/maximum resident/{print $1}')"
}

for c in $CASES; do
    for row in $ROWS; do
        b=$(bin_of "$row" "$c")
        if [ -z "${NOBUILD:-}" ]; then
            rm -f "$b"
            build "$row" "$c" 2>"$out/$row.$c.err"; rc=$?
            if [ $rc = 2 ]; then printf '%s\t%s\tn/a\t-\t-\t-\tn/a\n' "$c" "$row" >> "$med"; continue; fi
            if [ $rc != 0 ] || [ ! -f "$b" ]; then
                echo "$c $row: build failed: $(head -c 300 "$out/$row.$c.err")" >&2
                printf '%s\t%s\tbuild-failed\t-\t-\t-\t-\n' "$c" "$row" >> "$med"; continue
            fi
        fi
        [ -f "$b" ] || { printf '%s\t%s\tn/a\t-\t-\t-\tn/a\n' "$c" "$row" >> "$med"; continue; }
        ns=""; wall=""; rss=""; ck=""
        for r in $(seq "$RUNS"); do
            read -r k n w m <<< "$(run "$row" "$c")"
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$c" "$row" "$r" "${k:--}" "${n:--}" "${w:--}" "${m:--}" >> "$runs"
            ck=${k:-none}; ns="$ns $n"; wall="$wall $w"; rss="$rss $m"
        done
        line=$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s' "$c" "$row" "$ck" "$(echo $ns | tr ' ' '\n' | median)" \
            "$(echo $wall | tr ' ' '\n' | median)" "$(echo $rss | tr ' ' '\n' | median)" "$(size "$b")")
        echo "$line" >> "$med"
        echo "$line" | tr '\t' ' ' >&2
    done
done

kb() { awk -v n="$1" 'BEGIN{printf "%.0f", n/1024}'; }
{
    echo "# --target jvm benchmark"
    echo
    echo "$(date '+%Y-%m-%d'), $(sysctl -n machdep.cpu.brand_string 2>/dev/null), $(java -version 2>&1 | head -1)."
    echo "Median of $RUNS runs; built and run by bench/jvm/run.sh. Rows:"
    echo
    echo "- kxjvm: newkotlinc --target jvm (Kotlin -> LLVM IR -> JVM bytecode), one executable jar"
    echo "- kxnative: newkotlinc, native defaults"
    echo "- kn: Kotlin/Native kotlinc-native -opt"
    echo "- kjvm: Kotlin/JVM kotlinc + kotlin-stdlib.jar, java -Xss1g (cases using posix/cinterop: n/a)"
    echo
    echo "A checksum that differs from the kxnative row is shown as \`!checksum\`."
    awk -F'\t' -v rows="$ROWS" -v stdlib_kb="$(kb "$(size "$STDLIB")")" '
        NR == 1 { next }
        { k = $1 SUBSEP $2; ck[k] = $3; ns[k] = $4; wall[k] = $5; rss[k] = $6; sz[k] = $7
          if (!($1 in seen)) { seen[$1] = 1; cases[++nc] = $1 } }
        function cell(c, r, what,   k, v) {
            k = c SUBSEP r
            if (!(k in ck)) return "-"
            if (ck[k] == "n/a") return "n/a"
            if (ck[k] == "build-failed") return "build failed"
            if (what == "ns") v = ns[k] == "-" ? "-" : sprintf("%.1f", ns[k] / 1e6)
            else if (what == "wall") v = wall[k]
            else if (what == "rss") v = rss[k] == "-" ? "-" : sprintf("%.1f", rss[k] / 1048576)
            else v = sprintf("%.0f", sz[k] / 1024)
            ref = (c SUBSEP "kxnative") in ck ? ck[c SUBSEP "kxnative"] : ""
            if (what == "ns" && ref != "" && ref ~ /^-?[0-9]+$/ && ck[k] != ref) v = v " !checksum"
            return v
        }
        function table(title, what, note,   i, j, line) {
            printf "\n## %s\n\n", title
            if (note != "") printf "%s\n\n", note
            line = "| case"; for (j = 1; j <= nr; j++) line = line " | " row[j]; print line " |"
            line = "|---";   for (j = 1; j <= nr; j++) line = line "|---:"; print line "|"
            for (i = 1; i <= nc; i++) {
                line = "| " cases[i]; for (j = 1; j <= nr; j++) line = line " | " cell(cases[i], row[j], what); print line " |"
            }
        }
        END {
            nr = split(rows, row, " ")
            table("Time of the measured section (ms, the program'"'"'s own nanos)", "ns", "")
            table("Wall time of the whole process (s, includes JVM start-up)", "wall", "")
            table("Peak RSS (MiB)", "rss", "")
            table("File size (KiB)", "size", "kxjvm is self-contained (program + runtime classes in one jar). kjvm is the program jar alone; it also needs kotlin-stdlib.jar (" stdlib_kb " KiB). Both JVM rows need a JRE.")
        }' "$med"
} > "$res/summary.md"
echo "wrote bench/$res/summary.md" >&2
