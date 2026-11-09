# `--target=js`: Kotlin -> LLVM IR -> JavaScript

`newkotlinc --target=js Main.kt -o main.js` writes one JavaScript file that runs under node (`node main.js`).
It reuses the whole native pipeline up to LLVM IR and translates the optimized IR to JavaScript, the way
Emscripten's old "fastcomp" asm.js backend did: the same frontend, `NativeBackend`, `runtime.c` and garbage
collector as the native target, so programs behave the same (same output, same checksums in `bench/`).

## Pipeline (`compiler/src/driver/JsDriver.kt`)

1. Program IR from `compileToIr`, as for native.
2. `clang -S -emit-llvm -O2 -fno-vectorize -fno-slp-vectorize -DRT_PLAIN_MALLOC -DRT_LEAN_EXIT` on
   `compiler/runtime/runtime.c` and `compiler/runtime/js/libc.c` (malloc/free on one growable heap, qsort, the stdio
   handles). `RT_LEAN_EXIT` drops the `KX_GC_STATS` report and the final collection at exit, so a program that never
   collects does not carry the cycle collector. The runtime's datalayout gets native integer width `n32` only, so
   opt keeps loop counters in `i32`.
3. `llvm-link` the runtime, libc, program and the Zig collector's bitcode `libgc-<gc>.bc`
   (`zig build gc-bc` in `native/gc/zig`; `bin/newkotlinc` builds it). The bitcode is ReleaseSmall (about 12% less
   JavaScript); the hot helpers of `perceus` and `incremental` (`PtrStack.push`, the trial-deletion claims) are
   `inline fn`, so they run as fast as ReleaseFast.
4. `opt -passes='internalize,scalarizer<load-store>,default<O3>'` with only `main` exported and vectorizers off.
5. `compiler/src/backend/js`: `LlParser.kt` reads the IR text, `JsGen.kt` emits one JS function per LLVM function,
   `JsBackend.kt` prepends the parts of `compiler/runtime/js/runtime.js` the code uses and appends the data
   segment (base64) and the start call.

## Output

- Everything sits in one `(function(){ ... })();`, so runtime state (`var` in `runtime.js`) is closure variables,
  which V8 accesses faster than script-level `let`.
- Only reachable code is kept: opt's `internalize` and global DCE drop unused IR (only `main` is exported), and
  `JsBackend.usedRuntime` keeps only the `runtime.js` functions the translated code (and `_start`) reaches.
- Compact by default: defined functions get short names (`$a`, `$b`, ...), function bodies have no line breaks, and
  `runtime.js` loses its comments and indentation. Locals are declared once per function (`let a,b,...;`, no
  initializers). `JsBackend.translate(compact = false)` keeps LLVM names and one statement per line, for
  profiling.
- The data segment holds initialized globals only: zero-initialized globals are laid out last (like `.bss`) and the
  trailing zeros are not emitted (the heap starts zeroed).
- A minifier (`bun build --minify`, esbuild, terser) still saves 20-25% (it renames the remaining locals).

## Code generation (`JsGen.kt`)

- Memory is one `ArrayBuffer` with typed-array views (`I8`, `U8`, `I16`, `U16`, `I32`, `F32`, `F64`); pointers are
  int32 byte offsets, stored in 8-byte slots (the high word is 0) because the IR keeps the 64-bit datalayout.
  The data segment starts at 16, a 4 MB shadow stack (`S`) follows it, the heap grows from `js_sbrk`.
- `i64` is a pair of int32 words (extra parts are returned through `H`, `H1`, ...); helpers in `runtime.js`
  do `mul`/`div`/shifts. Results whose high word is always 0 (zext from 32 bits, masks) carry a literal `0`, and
  compares against such values become one 32-bit compare. Other odd widths (`i65`, `i128`, ...) are `BigInt`.
- `memcpy`/`memmove`/`memset` of a small constant length become inline loads and stores (all loads first, so
  overlapping `memmove` is correct); longer ones use `copyWithin`/`fill`. 64-bit `udiv`/`urem` whose operands fit
  in 32 bits take a fast path in `runtime.js`.
- Control flow is structured with Ramsey's algorithm ("Beyond Relooper", labeled blocks and loops). Loop exits
  are placed after their loop, so V8's on-stack-replaced code for an inner loop is not deoptimized each time the
  loop ends. Irreducible control flow falls back to a dispatch loop (with a warning).
- Single-use, same-block, side-effect-free values are inlined into their use; everything else is a `let`.
- `frameaddress` / `stacksave` (the bounded release in `runtime.c`) use a depth counter `D`.
- libc lives in `runtime.js` (stdio with buffered stdout, `printf` formatting, strings, `strtod`, time, exit).

## Requirements and limits

- LLVM `opt` and `llvm-link` 22 or newer (they read the Zig 0.17.1 bitcode). `--opt`, then `$NK_OPT` if it is new
  enough (it usually pins the native target's opt to the C compiler's LLVM), then the usual search
  ([toolchain.md](toolchain.md)). `llvm-link` is taken from the same directory as `opt`.
- Collector: `compact` by default (`native/gc/zig/src/compact.zig`: reference counting plus a small synchronous cycle
  collector, written for this target's size and speed); `incremental`, `perceus` and `rc` also work. `epoch` needs a
  background thread and is rejected.
- Not supported: `--leak-check`, `--link`. Threads do not exist; `runBlocking`/coroutines run on the single-threaded
  scheduler as on native.
- Tested on macOS (arm64, node 26). Linux is untested.
- All 234 `compiler/tests/*.kt` golden tests pass on `--target=js`.
