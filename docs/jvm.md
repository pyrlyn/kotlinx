# `--target jvm`

```bash
bin/newkotlinc --target jvm hello.kt -o hello
./hello                      # or: java <the launcher's JVM options> -jar hello
```

The output is one self-contained executable jar: a `#!/bin/sh` launcher line followed by the jar (class `Main`, the
program, and `KxRt`, its runtime). It needs Java 21+ on `PATH` and nothing else (no kotlin-stdlib).

## How it works

The jvm target reuses the native pipeline up to LLVM IR and translates the optimized IR to bytecode:

1. The frontend and `NativeBackend` emit the program's IR, exactly as for the native target.
2. `driver/JvmLink.kt` compiles the C runtime (`runtime/runtime.c`), the collector (`<gc>/gc.c`, default `perceus`)
   and `runtime/jvm/libc.c` (qsort, bsearch, atexit, exit: libc code that calls back into the program) to IR with
   `clang -emit-llvm`, links everything with `llvm-link`, and runs `opt` (`internalize`, `default<O2>`, no
   vectorization).
3. `backend/jvm` parses the IR text (`LlvmIr.kt`) and emits class `Main` (`JvmBackend.kt`, `ClassFile.kt`):
   - SSA values become JVM locals, and phis become copies on CFG edges.
   - Pointers are `long`s into native memory, read and written through `jdk.internal.misc.Unsafe`, which HotSpot
     intrinsifies. Globals live in one native block.
   - Function pointers are small integer ids, dispatched by generated `icall$N` methods.
   - Integers wider than 64 bits (`i65`..`i128`) are not given locals. LLVM produces them as intermediates, for
     example the closed form of `n*(n-1)/2`. A `trunc` back to 64 bits or fewer evaluates their expression tree on
     (lo, hi) pairs of longs, using the `KxRt.w*` helpers. Any other use of a wide value is a compile error.
   - libc and libm calls go to static methods of `runtime/jvm/KxRt.java`: malloc, stdio, strtod, time and math.
     `KxRt` is compiled once with `javac` and cached in `compiler/build/jvm-runtime`. `memcpy` and `memset` of a
     small constant size are inlined as word loads and stores.
   - Loops nested two or more deep are outlined into methods of their own (`LoopOutline.kt`). The values a loop
     uses come in as parameters. The values it defines and the code after it uses come back as the return value,
     or through private static fields. The reason is HotSpot's on-stack replacement: a long-running loop is
     compiled with its header as the entry. In a nest, that header is an inner one, so the outer loops become
     irreducible and C2 optimizes them poorly. An outlined loop instead gets an ordinary compilation, entered from
     its first instruction.
   - A bounds check, i.e. a branch to `rt_oob(index, length)` on an unsigned compare, becomes
     `jdk.internal.util.Preconditions.checkIndex`. C2 intrinsifies it and removes it from counted loops (range
     check elimination), which it does not do for an explicit compare. A failed check throws `KxRt.Oob`.
     `Main.run`, the program thread's entry, catches it and calls `rt_oob`, which prints the same message as native
     and exits.
4. `jar` packs the classes. The manifest exports `jdk.internal.misc` (Unsafe) and `jdk.internal.util`
   (Preconditions) to the program.
5. The launcher runs `java -XX:-DontCompileHugeMethods` (translated functions can be large), plus
   `-XX:CompileCommand=CompileThresholdScaling,Main::*,0.05`. That compiles the program's methods after 1/20 of the
   usual invocation and loop counts: short programs otherwise spend most of their time in the interpreter and in
   C1 code.

Defaults differ from native:

- `--gc perceus` and `--gc-runtime c`: the C collector is translated with the program, and the Zig collectors cannot
  be.
- `--alloc pool`: malloc is KxRt's size-class allocator.

`--link` accepts C sources only, because they have to go through LLVM IR too. `--emit-llvm` keeps `<out>.ll` (the
program's IR), `<out>.linked.bc` (the linked module) and `<out>.opt.ll` (the module the backend translates).

The tool versions must match. `opt` and `llvm-link` must not be newer than `clang`, since the IR from a newer LLVM
uses a different `llvm.coro.end` signature. `bin/newkotlinc` takes `opt` from `mise.toml` (LLVM 21).

## Tests and benchmark

`compiler/test.sh` runs the golden tests (with `--leak-check`) and the benchmark checksums on jvm too, whenever `java`
is on `PATH`.

`bench/jvm/run.sh` compares speed, peak RSS and file size against KotlinX native, Kotlin/Native and Kotlin/JVM. It
writes `bench/results/jvm/summary.md`.
