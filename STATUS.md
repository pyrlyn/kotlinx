# STATUS

## Rows
- Rust (rustc/cargo, opt-level 3), Zig 0.16 (ReleaseFast), Kotlin Native 2.4.21 (`-opt`).
- New Kotlin (Kotlin -> LLVM, ours): `compiler/` — newkotlinc, written in Kotlin (Kotlin/Native 2.4.21, debug build), emits LLVM IR text and links with `clang -O3 -flto` + `compiler/runtime/runtime.c`. Compiles the same `Main.kt` sources unchanged; all 4 checksums match.
- `zigjit/` is the old ZigJit participant, unused now (kept; deleting needs Ivan's yes).

## Cases (every program prints `checksum=` / `sum=` and `nanos=` for the timed kernel)
| Case | Sources | Kernel | Checksum |
|---|---|---|---|
| sum | `rust/`, `zig/`, `kotlin/` | 1..10M, +i² if i%3==0 else -i² | -430663335525468082 |
| alloc | `cases/alloc/*` | 50 rounds × 200k boxed `P(x,y)` in a list, summed | 31012435000000 |
| recursion | `cases/recursion/*` | naive fib(32) + non-tail `deep(100_000)` | 5002228309 |
| file | `cases/file/*` | read `data/words.txt` (~50 MB, `gen_data.py`), count lines/words, h=h*31+byte | 3412301833168394113 |

## Measured
Kernel time ns (in-process clock), peak RSS (`/usr/bin/time -l`), CPU % = (user+sys)/wall over 200 launches ×5 (alloc: 20). Mean of middle 3 of 5. See `table.md`.

## New Kotlin compiler (`compiler/`)
- Pipeline: `Lexer.kt` -> `Parser.kt` (AST in `Ast.kt`) -> `Gen.kt` (type checking + LLVM IR, types in `Types.kt`) -> `clang`.
- Subset: top-level functions (block or `=` body, recursion), `class C(val/var ...)` (no bodies), Int/Long/Byte/Double/Boolean/String,
  val/var, if/when (as expressions too), while, for over `a..b`, `a until b`, ArrayList, ByteArray; `+ - * / %`, comparisons,
  `&& || !`, `and or xor shl shr ushr`, string templates, `?.`, `?:`, `!!`, `ArrayList<T>()` add/get/set/[]/size,
  `ByteArray(n)`, `println/print`, `error/check/require`.
- Intrinsics (no source edits needed): `kotlin.system.getTimeNanos()` / `System.nanoTime()` -> `rt_nanos`; `platform.posix`
  `getenv/fopen/fseek/ftell/fread/fclose`, `SEEK_*`; `toKString()`; `ByteArray.usePinned { it.addressOf(i) }`.
  `@file:` annotations and imports are skipped.
- Memory: classes and lists are reference counted (scope-exit release); class instances come from a free-list allocator.
  Strings built by templates and ByteArrays are never freed.
- Limitations: no generics beyond ArrayList<T>, no lambdas except `usePinned`/`check` blocks, no class bodies/methods,
  no interfaces/inheritance, no nullable value types (only nullable pointers), no break/continue, no overloads,
  if/when branches must have the same type, Char literals are Ints, no exceptions (error() aborts), reference cycles leak.
- Tests: `compiler/test.sh` (feature golden `compiler/tests/*.kt|.out`, the 4 bench checksums, an error message).

## Resume
```
./build.sh                         # all cases; generates data/ if missing
ALL=1 CASES="alloc recursion file" ./run.sh && ./summarize.sh
compiler/build.sh && compiler/test.sh          # build newkotlinc (debug), run compiler tests
ONLY=newkotlin ./build.sh                      # -> kllvm/<case>, kllvm/bench_kllvm (KLLVM_COMPILER overrides)
./run.sh && ./summarize.sh                     # NewKotlin rows only
compiler/build/newkotlinc.kexe X.kt -o X --emit-llvm --cflags "-O2"   # inspect IR / try flags
```
Then copy the NewKotlin rows into `table.md`.
