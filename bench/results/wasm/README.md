# KotlinX `--target=wasm` vs JetBrains Kotlin/Wasm

Raw tables: [table.md](table.md) (written by `wasm/run.py`), raw runs: `runs.txt`, machine load: `conditions.txt`.
Reproduce: `bench/wasm/build.sh && bench/wasm/run.py` (Zig 0.17.1 on PATH or in `$ZIG`).

## Setup (2026-11-09)

- Apple M3 Max, macOS 27.0.1, Node v26.7.0 (V8, WASI preview1). **Load average 15 at start, 12 at the end**
  (other jobs were running), so absolute times are noisy. All five variants of a case ran interleaved
  (warm-up, then 5 rounds), so comparisons within a case are fair.
- Same sources for both compilers: `bench/kotlin/Main.kt` (sum) and `bench/cases/*/kotlin/Main.kt`, unchanged.
  Kotlin/Wasm additionally compiles `bench/wasm/TimeShim.kt` (`kotlin.system.getTimeNanos` exists only on Kotlin/Native).
- **KotlinX** (`newkotlinc --target=wasm`): the LLVM IR of the native backend for wasm32-wasi, `opt` LLVM 21,
  `zig cc -target wasm32-wasi -O3 -flto` (clang 21, wasi-libc, wasm-ld). Objects live in linear memory and are
  managed by pyrlyn/gc's perceus collector (RC plus synchronous cycle collection) on wasi-libc malloc:
  - `kx`: the default, the Zig port (`native/gc/zig`, `libgc-perceus.a` built for wasm32-wasi);
  - `kx-opt`: `kx` after `wasm-opt -O3` (Binaryen 132);
  - `kx-c`: the C collector (`--gc-runtime=c`, `native/gc/c/perceus`).
- **Kotlin/Wasm** 2.4.20 (`kotlinc-wasm -Xwasm-target=wasm-wasi -Xir-dce`, stdlib-wasm-wasi 2.4.20): WasmGC, so
  V8's garbage collector manages the objects. `kw-opt` = the same module after the Binaryen passes the Kotlin
  Gradle plugin runs for production builds (`--closed-world --type-ssa -O3 -O3 --gufa -O3 --type-merging -O3 -Oz` ...).
- Every variant runs with a 64 MB stack (`ulimit -s 65520`, `node --stack-size=60000`). Kotlin/Wasm overflows
  V8's default stack on `recursion`'s `deep(100_000)`; KotlinX does not need the larger stack.
- All checksums match across all variants. `file` (reads `data/words.txt` through `platform.posix`) is KotlinX
  only: Kotlin/Wasm has no file API on WASI.

## Results

Ratio = best default KotlinX variant (`kx`, `kx-opt`) / best Kotlin/Wasm variant over the 16 shared cases.
Below 1 means KotlinX is better.

| Metric | Geomean ratio | KotlinX better in |
|---|---|---|
| Kernel time | 1.05× | 10 of 16 |
| Process wall time (startup + compile + run) | 0.99× | 11 of 16 |
| Peak RSS | 0.61× | 16 of 16 |
| `main.wasm` size | 2.19× (34–39 KB vs 15–20 KB) | 0 of 16 |
| `main.wasm` gzip -9 | 1.89× (14–16 KB vs 7–10 KB) | 0 of 16 |

- **Where KotlinX is faster:** mostly arithmetic and dispatch code, where LLVM's optimizer does more than the Kotlin/Wasm
  pipeline plus Binaryen: closures 0.31×, sum 0.43×, strings 0.45×, matmul 0.61×, alloc 0.66×, dispatch 0.66×, free 0.69×.
- **Where Kotlin/Wasm is faster:** programs that build many small objects or cyclic graphs: cycles 7.8×,
  bigheap 5.6×, trees 2.3×. V8's generational GC handles these better than RC plus a single-threaded cycle collector in
  linear memory. The collector is most of the gap: the same sources compiled natively with perceus
  (`--gc=perceus --gc-runtime=c`) take 47 / 117 / 124 ms, against 17 / 86 / 66 ms for Kotlin/Wasm under V8.
  nbody (2.05×) is a floating-point case where unoptimized Kotlin/Wasm was fastest (56 ms) and Binaryen slowed
  it down (124 ms).
- **Memory:** KotlinX frees at last use, so allocation-heavy cases use 2–6× less memory than WasmGC
  (closures 91 vs 548 MB, alloc 75 vs 250 MB).
- **Size:** each KotlinX module carries its own runtime: `runtime.c`, the collector, wasi-libc's malloc and stdio.
  Kotlin/Wasm relies on the engine's GC instead and comes out about half the size after Binaryen. `wasm-opt -O3`
  cuts KotlinX modules by about 18%. Without Binaryen, Kotlin/Wasm modules are 3× larger than KotlinX's
  (120–156 KB).

### Zig vs C collector on wasm (`kx` / `kx-c`, all 17 cases)

| Metric | Geomean | Range |
|---|---|---|
| Kernel time | 0.99× | 0.91–1.13× |
| Process wall time | 1.00× | 0.93–1.09× |
| Peak RSS | 1.00× | 1.00–1.00× |
| `main.wasm` size | 1.06× (+0.5–5 KB) | 1.01–1.14× |

The two implementations of perceus run at the same speed and use the same memory on wasm; the spread is
within this run's noise (cycles 137 vs 135 ms, bigheap 402 vs 411 ms, trees 163 vs 164 ms). The Zig archive
adds 0.5–5 KB per module.

## Correctness

- All 16 benchmark checksums match the native build. Golden tests on wasm (`compiler/tests/wasm/run.sh`), with
  either collector: 233 of 234 pass. `coroutine_cps_core` fails on a pre-existing use-after-free (`kxCoStart` does
  not retain its function value; the native build of `main` also crashes with `--alloc malloc --gc=perceus`).
- Native IR is unchanged by the target split: for all 251 programs (benchmarks plus golden tests),
  `newkotlinc -S` output from `main` and from this branch differs only in the order of six `declare` lines.
  `compiler/test.sh` (default Zig collectors) passes.

## Limits of the wasm target

- Single-threaded: the epoch collector (native default) needs threads, so the wasm target defaults to `--gc perceus`.
- No `--link` (FFI); `--alloc malloc|pool`. `--gc-runtime=zig` (default) needs the wasm32-wasi Zig archives in
  `native/gc/zig/zig-out/wasm32-wasi/lib` (`bin/newkotlinc --target=wasm` builds them; `--gc-libs-only` only builds
  them); `--gc-runtime=c` compiles the C collector.
- Linking needs `zig cc` (or `NK_WASM_CC`) and an `opt` no newer than its LLVM (21).
