#!/bin/bash
# FFI call benchmark: KotlinX `external fun` vs Kotlin/Native cinterop, same C object (clang -O2, no LTO, so the
# callee is never inlined into either side). Reports ns/call (median of RUNS interleaved runs), binary size, peak RSS,
# startup time and compile time. Usage: bench/ffi/run.sh [RUNS=5] [MANY=1000]
set -eu
here="$(cd "$(dirname "$0")" && pwd)"; root="$here/../.."
RUNS=${RUNS:-5}; MANY=${MANY:-1000}
KN_HOME=${KN_HOME:-$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21}
CC=${CC:-/usr/bin/clang}
out=${OUT:-$root/compiler/build/ffi-bench}; rm -rf "$out"; mkdir -p "$out"
python3 "$here/gen.py" "$out" "$MANY"
"$CC" -O2 -c "$here/ffi_bench.c" -o "$out/ffi_bench.o"
"$CC" -O2 -c "$out/many.c" -I"$out" -o "$out/many.o"
ms() { python3 -c 'import time;print(int(time.time()*1000))'; }
t=$(ms); "$root/bin/newkotlinc" "$out/kx.kt" --link "$out/ffi_bench.o" --link "$out/many.o" -o "$out/kx"; kx_ct=$(( $(ms) - t ))
t=$(ms)
"$KN_HOME/bin/cinterop" -def "$out/fb.def" -o "$out/fb" >/dev/null 2>&1
"$KN_HOME/bin/kotlinc-native" -opt "$out/kn.kt" -l "$out/fb.klib" -linker-option "$out/ffi_bench.o" -linker-option "$out/many.o" -o "$out/kn" >/dev/null 2>&1
kn_ct=$(( $(ms) - t ))
# Startup / minimal footprint: a program making a single FFI call.
cat > "$out/kx_start.kt" <<K
external fun fb_add(acc: Long, x: Long): Long
fun main() { println(fb_add(1L, 2L)) }
K
printf '@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)\nimport fb.*\nfun main() { println(fb_add(1L, 2L)) }\n' > "$out/kn_start.kt"
"$root/bin/newkotlinc" "$out/kx_start.kt" --link "$out/ffi_bench.o" -o "$out/kx_start"
"$KN_HOME/bin/kotlinc-native" -opt "$out/kn_start.kt" -l "$out/fb.klib" -linker-option "$out/ffi_bench.o" -o "$out/kn_start" >/dev/null 2>&1
for r in $(seq "$RUNS"); do
  for v in kx kn; do
    e="$out/$v"; [ -x "$e.kexe" ] && e="$e.kexe"
    /usr/bin/time -l "$e" >"$out/$v.$r.out" 2>"$out/$v.$r.time"
  done
done
python3 - "$out" "$RUNS" <<'P'
import sys, re, os, statistics as st, subprocess, time
out, runs = sys.argv[1], int(sys.argv[2])
def exe(v): return f"{out}/{v}.kexe" if os.path.exists(f"{out}/{v}.kexe") else f"{out}/{v}"
res = {}
for v in ("kx", "kn"):
    d = {}
    for r in range(1, runs + 1):
        o = open(f"{out}/{v}.{r}.out").read(); tm = open(f"{out}/{v}.{r}.time").read()
        for k, x in re.findall(r"(\w+_ns)=([\d.]+)", o): d.setdefault(k, []).append(float(x))
        d.setdefault("rss_mib", []).append(int(re.search(r"(\d+)\s+maximum resident", tm).group(1)) / 2**20)
        d.setdefault("check", []).append(re.search(r"check=(.*)", o).group(1))
    starts = []
    for _ in range(30):
        t = time.perf_counter(); subprocess.run([exe(v + "_start")], stdout=subprocess.DEVNULL); starts.append((time.perf_counter() - t) * 1000)
    p = subprocess.run(["/usr/bin/time", "-l", exe(v + "_start")], capture_output=True, text=True)
    res[v] = {k: (st.median(x) if k != "check" else x[0]) for k, x in d.items()}
    res[v]["start_ms"] = st.median(starts)
    res[v]["start_rss_mib"] = int(re.search(r"(\d+)\s+maximum resident", p.stderr).group(1)) / 2**20
    res[v]["size_kib"] = os.path.getsize(exe(v)) / 1024
    res[v]["start_size_kib"] = os.path.getsize(exe(v + "_start")) / 1024
assert res["kx"]["check"] == res["kn"]["check"], (res["kx"]["check"], res["kn"]["check"])
print(f"check: {res['kx']['check']} (identical)")
print(f"{'metric':<16}{'KotlinX':>12}{'K/N':>12}{'KX/KN':>8}")
for k in ("add_ns", "scale_ns", "bigptr_ns", "many_ns", "rss_mib", "size_kib", "start_ms", "start_rss_mib", "start_size_kib"):
    a, b = res["kx"][k], res["kn"][k]
    print(f"{k:<16}{a:>12.3f}{b:>12.3f}{a / b:>8.2f}")
P
echo "compile_ms KotlinX=$kx_ct K/N(cinterop+konanc)=$kn_ct"
