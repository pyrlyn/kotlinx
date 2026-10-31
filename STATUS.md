# STATUS

## Rows
- Rust (rustc/cargo, opt-level 3), Zig 0.16 (ReleaseFast), Kotlin Native 2.4.21 (`-opt`).
- KotlinX compiler (Kotlin -> LLVM, ours): `compiler/` — newkotlinc, written in Kotlin (Kotlin/Native 2.4.21, debug build), emits LLVM IR text and links with `clang -O3 -flto` + `compiler/runtime/runtime.c`. Compiles the same `Main.kt` sources unchanged; all checksums match.
- `bench/zigjit/` is the old ZigJit participant, unused now (kept; deleting needs Ivan's yes).

## Cases (every program prints `checksum=` / `sum=` and `nanos=` for the timed kernel)
| Case | Sources | Kernel | Checksum |
|---|---|---|---|
| sum | `bench/rust/`, `bench/zig/`, `bench/kotlin/` | 1..10M, +i² if i%3==0 else -i² | -430663335525468082 |
| alloc | `bench/cases/alloc/*` | 50 rounds × 200k boxed `P(x,y)` in a list, summed | 31012435000000 |
| recursion | `bench/cases/recursion/*` | naive fib(32) + non-tail `deep(100_000)` | 5002228309 |
| file | `bench/cases/file/*` | read `bench/data/words.txt` (~50 MB, `bench/gen_data.py`), count lines/words, h=h*31+byte | 3412301833168394113 |

## Measured
Kernel time ns (in-process clock), peak RSS (`/usr/bin/time -l`), CPU % = (user+sys)/wall over 200 launches ×5 (alloc: 20). Mean of middle 3 of 5. See `bench/table.md`.

## KotlinX compiler (`compiler/`)
- Pipeline: `src/frontend/Lexer.kt` -> `Parser.kt` (AST in `Ast.kt`, types in `Types.kt`) -> `Uses.kt` (last-use analysis for
  Perceus) -> `src/backend/native/NativeBackend.kt` (type checking + LLVM IR) -> `clang -O3 -flto` with `runtime/runtime.c`.
  Entry point `src/driver/Main.kt`; `--target=native` is the default and the only implemented target (js/wasm/jvm stubs exit non-zero).
- Language subset: top-level functions, top-level properties (`val`/`var`/`const val`), classes with primary-constructor
  fields and methods, interfaces with virtual dispatch, val/var, if/when expressions, while, for over ranges/arrays/lists/strings,
  unlabeled `break`/`continue`, lambdas as trailing blocks for built-ins, ArrayList/HashMap/StringBuilder/arrays, string
  templates, nullable references and nullable primitives (`Int?`, boxed) with `?.` `?:` `!!`, Int/Long/unsigned/Float/Double/Boolean/Char/String.
- Not yet: user generics, function types and general lambdas, inheritance from classes (open/abstract/sealed), objects and
  companions, enums, inline/value classes, extension and operator functions, default/named arguments, exceptions,
  labeled jumps. `compiler/tests/upstream/report.md` ranks the missing features by upstream test count.
- Memory: Perceus-style reference counting (drop after last use, moves, FBIP reuse, stack allocation of non-escaping
  objects), two regions (RC region for acyclic objects, tracing region for possibly cyclic ones) and a trial-deletion
  cycle collector with an adaptive root threshold. Leak tests (`compiler/tests/leaks`) check live count = 0, ASan and macOS `leaks`.
- Tests: `compiler/test.sh` (golden `compiler/tests/*.kt|.out`, benchmark checksums, leak tests);
  upstream Kotlin tests in `compiler/tests/upstream` (unmodified, JetBrains/kotlin @ 19c36e1), 8475 candidates:
  PASS 2554, MISCOMPILE 0, COMPILER_CRASH 0, UNSUPPORTED 2937, N/A 2984 (passing ones are kept and re-runnable with
  `run.py`); `import.py --no-fetch` re-scans all candidates and keeps the newly passing ones.

## Resume
```
cd bench   # benchmark commands below run from bench/; compiler/ paths are ../compiler there
./build.sh                         # all cases; generates data/ if missing
ALL=1 CASES="alloc recursion file" ./run.sh && ./summarize.sh
../compiler/build.sh && ../compiler/test.sh        # build newkotlinc (debug), run compiler tests
ONLY=newkotlin ./build.sh                      # -> kllvm/<case>, kllvm/bench_kllvm (KLLVM_COMPILER overrides)
./run.sh && ./summarize.sh                     # NewKotlin rows only
../bin/newkotlinc X.kt -o X --emit-llvm --cflags "-O2"   # inspect IR / try flags
```
Then copy the NewKotlin rows into `bench/table.md`.

## Status 2026-10-29
- Cases: sum, alloc, recursion, file, strings, hashmap, sort, nbody, matmul, dispatch, closures, trees, free, refpass, cycles, lru, bigheap (Rust/Zig/Kotlin; Kotlin built by Kotlin Native and KotlinX compiler).
- KotlinX compiler: frontend (`compiler/src/frontend`) + compile-time backends (`compiler/src/backend/{native,js,wasm,jvm}`); `--target=native` default and only implemented; js/wasm/jvm exit non-zero with "target <x> is not supported yet".
- Allocation: mimalloc default (`--alloc mimalloc`, found via `$NEWKOTLIN_MIMALLOC_DIR`, pkg-config or Homebrew; `brew install mimalloc`; clear error if missing), `--alloc malloc` (system) and `--alloc pool` optional. Leak tests: leak-count on the default build; ASan and macOS `leaks` on `--alloc malloc` builds. Benchmarks: see README.md.
- Memory manager: `compiler/gc` behind `kxgc.h`, selected with `newkotlinc --gc=default|rc|boehm`
  (default = RC + cycle collector; boehm = RC + bdwgc). Tests: `compiler/gc/tests/run.sh`; benchmarks: `bench/gc`.
- Resume: `cd bench && ./build.sh && ./round.sh && RESULTS=results/round ./summarize.sh`; `compiler/test.sh` for compiler tests; `compiler/tests/upstream/run.py` for upstream tests.
