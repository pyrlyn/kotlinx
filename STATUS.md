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
- Extension properties: top-level `val`/`var` with getter and setter (extension functions `$prop$x` / `$set$x`) and
  member extension properties in class/object/interface bodies (member extensions `mext$$prop$x` / `mext$$set$x`).
- `where` clauses on generic functions, classes and interfaces: the first bound of each type parameter acts like an
  inline bound (generic code is monomorphized, further intersection bounds only constrain callers).
- Bridges: an override whose return representation differs from the interface method (`Int`/`String` for `Any`)
  gets a boxing bridge `@br.C.m` in the interface slot; class-typed virtual calls use the `m#narrow` slot.
- Lambdas with receiver: typed parameters (`{ x: Int -> }`), builder inference from unqualified receiver properties
  (`scoped { emit(key) }`); `r.f(x)` counts as a use of a local function value `f` in last-use analysis.
- Locals without an initializer (`val x: T` assigned later): a variable starting at the type's zero value;
  definite assignment is not checked.
- Referential equality `===` / `!==`: pointer identity for class/interface/array/collection values and against null,
  value equality for primitives; strings and boxed `Any` values are refused (interning/box caching differ).
- Class literals `T::class` / `e::class` with `simpleName`, `qualifiedName`, `==` and `toString` for primitives, String,
  standard types and final user classes (static runtime class only; no `KClass<*>` types or full reflection).
- Multi-field value classes (`+FullValueClasses`): compiled like data classes (boxed, structural equals/hashCode/
  toString); recursive and self-bounded multi-field value classes are refused.
- Generic super-interfaces of interfaces (`interface B : A<String>`, `interface I2<T> : I<T>`): inherited methods are
  substituted, the instance `A$String` becomes a super of B and of implementing classes. Inherited default methods from
  a generic super-interface are still unsupported.
- Local classes and anonymous objects in generic functions may use the function's type parameters: the lifted class
  becomes generic over them and is instantiated per monomorphized function. Classes with abstract methods always dispatch virtually.
- Stdlib additions: `String.toInt/toLong/toIntOrNull/toLongOrNull`, `substring` (prelude), `List.sum()` of numeric
  lists, `HashMap`/`hashMapOf` with String keys (content-hashed, keys retained by the map; values stay primitive), computed
  `var` properties with `get()` and `set(v)` accessors.
- kotlinx subsets (`compiler/src/frontend/Kotlinx.kt`, tests `kotlinx_coroutines`, `kotlinx_serialization`):
  coroutines (`runBlocking` (also nested), `coroutineScope`, `launch`, `async`/`await`, `Job` with cancellation
  (`cancel`, `isActive`, `CancellationException` at suspension points, children cancelled with the parent), `Channel`,
  cold `Flow`): CPS. `suspend fun` and suspend function types (`suspend () -> T`, `suspend R.() -> T`) compile to LLVM
  switched-resume coroutines (state machines whose live locals are spilled into a heap frame by CoroSplit); a suspend
  call from a coroutine creates the callee's coroutine and awaits it. A single-threaded scheduler drives the jobs, so
  `join`/`await`/`receive`/`send`/`yield`/`delay` really suspend; `delay` is virtual time. Structured concurrency: a failing child cancels its siblings and the
  parent's body, and the failure is rethrown by `runBlocking`/`coroutineScope`/`await`; on a deadlock every suspended
  coroutine is cancelled and unwound (its frame and objects are freed) before runBlocking throws. `suspend fun main()`
  runs in a generated `runBlocking`. Suspend calls are only
  allowed in suspend functions/lambdas (and in lambdas the compiler inlines, e.g. `forEach`). Serialization: `@Serializable`
  classes (Int/Long/Double/Boolean/String, nested @Serializable, `List<T>`, nullable) with `X.serializer()`,
  `Json.encodeToString/decodeFromString(serializer, ...)`, `parseToJsonElement` and the `JsonElement` tree.
- Not yet: user generics, function types and general lambdas, inheritance from classes (open/abstract/sealed), objects and
  companions, enums, inline/value classes, extension and operator functions, default/named arguments, exceptions,
  labeled jumps. `compiler/tests/upstream/report.md` ranks the missing features by upstream test count.
- Memory: Perceus-style reference counting (drop after last use, moves, FBIP reuse, stack allocation of non-escaping
  objects), two regions (RC region for acyclic objects, tracing region for possibly cyclic ones) and a trial-deletion
  cycle collector with an adaptive root threshold. Leak tests (`compiler/tests/leaks`) check live count = 0, ASan and macOS `leaks`.
- Tests: `compiler/test.sh` (golden `compiler/tests/*.kt|.out`, benchmark checksums, leak tests);
  upstream Kotlin tests in `compiler/tests/upstream` (unmodified, JetBrains/kotlin @ 19c36e1), 8475 candidates:
  PASS 2614, MISCOMPILE 0, COMPILER_CRASH 0, UNSUPPORTED 2877, N/A 2984 (passing ones are kept and re-runnable with
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
  (default = RC + cycle collector; boehm = RC + bdwgc). Tests: `compiler/gc/tests/zig` (`zig build ported`/`zig build gc`; legacy shell suite in `compiler/gc/tests/archive`); benchmarks: `bench/gc`.
- Resume: `cd bench && ./build.sh && ./round.sh && RESULTS=results/round ./summarize.sh`; `compiler/test.sh` for compiler tests; `compiler/tests/upstream/run.py` for upstream tests.

## Status 2026-11-03

In-progress items are marked as such; numbers are only those already committed or in open PRs.

- **GC (C):** all collectors are C (`compiler/gc/{default,incremental,epoch,rc,boehm}/gc.c`, shared
  `common/*.inc`, API `kxgc.h`). The GC is planned to become a separate project; see
  [`compiler/gc/docs/extraction-plan.md`](compiler/gc/docs/extraction-plan.md) (planned, nothing moved).
- **Epoch collector:** #147 (short adaptive logs) is merged: `cyclic_garbage` peak RSS 22.6 -> 4.1 MiB.
  Making `epoch` the default collector (Polar Quest) is in progress; `default` is still the default on `main`.
  GC benchmark re-run: #148 (open). Faster GC benchmark runs (build cache, parallel builds, opt-in skip of slow
  pairs): #154 (open).
- **GC targets vs Boehm** (equal priority): `cyclic_garbage` epoch peak RSS at or below Boehm (3.4 MiB; 4.1 MiB
  after #147); beat Boehm on big live heaps (`huge_live_churn` / `big_live_churn`) and on `lru`. No regression
  beyond the post-#147 baseline in other categories, except up to ~5% where epoch already strongly beats Boehm.
  Variant exploration is in progress; no results yet.
- **Zig GC regression benchmarks:** `bench/gc/zig`, PR #155 (open). Median of N runs (time from `nanos=`, peak
  RSS, max pause from `KX_GC_STATS=1`); thresholds: +5% allowed where boehm/epoch >= 1.5, no regression elsewhere,
  plus a 3% noise margin; `cyclic_garbage` peak RSS must be <= 3.4 MiB and <= Boehm in the same run (expected to
  fail until the cyclic-garbage work lands). Baselines in `baselines.zon`; refresh with `zig build update-baselines`
  after every known-good improvement, so baselines follow the latest known-good numbers.
- **GC tests in Zig:** all new GC tests are written in Zig. Porting the existing C unit tests and Kotlin GC tests
  (byte-for-byte identical output, small tolerances only for timing/floating point), new regular API tests,
  mutation tests of the GC C code and `llvm-cov` coverage: in progress (branch `test/gc-zig-port`, not pushed).
- **pitx** (in progress, branch `feat/pitx`, not pushed): PIT-style mutation testing on the LLVM IR emitted by
  `newkotlinc`. Works only on IR copies. First operators: `icmp` predicate flip and integer `add`/`sub` swap;
  a runner reports caught / survived / invalid mutants. Tests use standalone IR fixtures (conditions, arithmetic,
  memory, branches/loops with timeouts, neutral mutations, invalid IR) rather than compiled programs.
- **PIT for compiler code** (in progress, branch `mutation-tests`, not pushed): the compiler is Kotlin/Native,
  so PIT runs on a JVM build of the compiler sources against the golden tests (`mutation/jvm`); a source-level
  mutation script (`mutation/mutate.py`) covers native-only code. No scores published yet.
- **FFI prototype** (in progress, branch `feat/ffi-prototype`, not pushed): `external fun` declarations in
  Kotlin become LLVM `declare`s; C implementations are linked with `--link <file.c|.o|.a|-l<lib>|-L<dir>>`
  (repeatable). Round-trip test `compiler/tests/ffi` (Int, Long, Double, Boolean, Unit). Planned: benchmark
  against Kotlin/Native cinterop (same C code, same iteration count, warm-up), binary size, peak memory, startup
  time, many functions and large structs.
- **FFI autogenerator** (planned): C header -> `clang -Xclang -ast-dump=json` -> Kotlin file with `external fun`
  declarations and the matching `--link` setup. Prototype scope: one header in, one Kotlin file out; then
  typedefs, structs, enums, constant macros and callbacks.
