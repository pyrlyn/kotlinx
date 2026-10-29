#!/bin/bash
# Kotlin-X compiler output under explicit LLVM pipelines vs Kotlin Native, nkbench.sh method (same cases,
# warm-up + N runs), reporting the median in ms.
#   LLVM_BIN=<dir with opt/llc/llvm-link/clang> tools/optbench.sh [runs]  -> results/optbench/<config>.txt
# Configs (IR = newkotlinc -S output + runtime.c as unoptimized IR (clang -O3 -Xclang -disable-llvm-passes: TBAA kept, no passes run), merged with llvm-link):
#   O0     llc -O0                                           (no optimization at all)
#   opt    opt -passes='default<O3>' ; llc -O3
#   mlgo   opt -passes='default<O3>' -enable-ml-inliner=release ; llc -O3 -regalloc-enable-advisor=release
#   kn     kotlinc-native -opt  (cases/*/kotlin/bench_kn.kexe, built by build.sh)
#   default  newkotlinc as shipped (system clang -O3 -flto), for reference
# Needs an LLVM built with MLGO release models for 'mlgo' (see PR description). Link: system clang + mimalloc.
set -eu
cd "$(dirname "$0")/.."
export NEWKOTLIN_RUNTIME=${NEWKOTLIN_RUNTIME:-$PWD/../compiler/runtime/runtime.c}
L=${LLVM_BIN:?set LLVM_BIN}; RUNS=${1:-7}
NKC=../compiler/build/newkotlinc.kexe; RT=../compiler/runtime/runtime.c
MI=${NEWKOTLIN_MIMALLOC_DIR:-/opt/homebrew/opt/mimalloc/lib}
W=../compiler/build/optbench; mkdir -p $W results/optbench
[ -f data/words.txt ] || python3 gen_data.py >/dev/null
"$L/clang" -isysroot "$(xcrun --show-sdk-path)" -O3 -Xclang -disable-llvm-passes -emit-llvm -S "$RT" -o $W/runtime.ll
med() { python3 -c "import statistics,sys; v=list(map(int,sys.argv[1:])); print(round(statistics.median(v)/1e6,3))" "$@"; }
time_bin() { "$1" >/dev/null; ts=(); for r in $(seq $RUNS); do ts+=("$("$1" | grep -oE 'nanos=[0-9]+' | cut -d= -f2)"); done; med "${ts[@]}"; }
for k in O0 opt mlgo kn default; do : > results/optbench/$k.txt; done
for c in sum $(ls cases); do
  src=cases/$c/kotlin/Main.kt; kn=cases/$c/kotlin/bench_kn.kexe; [ $c = sum ] && { src=kotlin/Main.kt; kn=kotlin/bench_kn.kexe; }
  "$NKC" -S "$src" -o $W/$c.ll
  "$L/llvm-link" -S $W/$c.ll $W/runtime.ll -o $W/$c.all.ll
  "$L/llc" -O0 -filetype=obj $W/$c.all.ll -o $W/$c.O0.o
  "$L/opt" -passes='default<O3>' $W/$c.all.ll -o $W/$c.opt.bc
  "$L/llc" -O3 -filetype=obj $W/$c.opt.bc -o $W/$c.opt.o
  "$L/opt" -passes='default<O3>' -enable-ml-inliner=release $W/$c.all.ll -o $W/$c.mlgo.bc
  "$L/llc" -O3 -regalloc-enable-advisor=release -filetype=obj $W/$c.mlgo.bc -o $W/$c.mlgo.o
  for k in O0 opt mlgo; do clang $W/$c.$k.o -L"$MI" -lmimalloc -Wl,-rpath,"$MI" -o $W/$c.$k; done
  "$NKC" --runtime "$RT" "$src" -o $W/$c.default
  for k in O0 opt mlgo default; do echo "$c $(time_bin $W/$c.$k)" >> results/optbench/$k.txt; done
  if [ -x "$kn" ]; then echo "$c $(time_bin $kn)" >> results/optbench/kn.txt; else echo "$c -" >> results/optbench/kn.txt; fi
  echo "$c done" >&2
done
python3 - <<'PY'
cols=["O0","opt","mlgo","kn","default"]; d={k:dict(l.split() for l in open(f"results/optbench/{k}.txt")) for k in cols}
print("| case | "+" | ".join(cols)+" |"); print("|"+"---|"*(len(cols)+1))
for c in d["O0"]:
    vals={k:d[k].get(c,"-") for k in cols}; nums={k:float(v) for k,v in vals.items() if v!="-" and k!="default"}
    b=min(nums,key=nums.get) if nums else None
    print(f"| {c} | "+" | ".join(f"**{vals[k]}**" if k==b else vals[k] for k in cols)+" |")
PY
