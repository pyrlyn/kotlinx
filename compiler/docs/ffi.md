# FFI: calling C from KotlinX (prototype)

## Usage

```kotlin
external fun ffi_add(a: Int, b: Int): Int        // C: int32_t ffi_add(int32_t, int32_t);
external fun ffi_now(): Long
external fun make_obj(): CPointer                 // C: void *make_obj(void);
fun main() { println(ffi_add(40, 2)) }
```

```sh
bin/newkotlinc main.kt --link mylib.c -o main          # .c is compiled with the program (LTO: C can inline)
bin/newkotlinc main.kt --link mylib.o --link -lm -o main # objects, archives, -l/-L, linker flags
```

`--link` is repeatable; every argument goes to the final clang/linker command after the runtime and GC.

## Design

KotlinX already emits LLVM IR and links it with the C runtime and GC through one clang command, and the runtime is
reached from IR by plain `declare`d C symbols. FFI reuses exactly that:

- `external fun name(...): R` (no body) is parsed as a top-level function and recorded in `Externs`.
- The backend emits `declare R @name(...)` (the C symbol has the Kotlin name) and an `alwaysinline` thunk
  `@k.name`, so call sites, overload checks and type inference stay unchanged. After `opt` it is a direct C call:
  no marshalling, no thread-state switch, no wrapper object.
- C ABI attributes: `Boolean` is `zeroext i1` (C `bool`), `Byte` is `signext i8`.
- `--link` appends inputs to the link line. A `.c` input is compiled under the same `-O3 -flto`, so small C
  functions can be inlined into Kotlin (link a prebuilt `.o` to keep a real call).

Not chosen: JNI (KotlinX has no JVM), Kotlin/Native-style cinterop klibs (needs a stub compiler and klib format;
the header is turned into plain `external fun` text instead, see kxbindgen below).

## Supported types

`Byte`, `Int`, `Long`, `UInt`, `ULong`, `Boolean`, `Float`, `Double`, `Unit` (return only) and `CPointer`
(opaque `void*`/`T*`, never traced or freed by the GC). Anything else is a compile error.

## Limitations

- No strings, arrays or Kotlin objects across the boundary (no pinning or ownership contract: the GC may free them; KotlinX strings
  are not guaranteed NUL-terminated C strings).
- No structs by value, no callbacks (C -> Kotlin function pointers), no varargs.
- The C symbol name equals the Kotlin name (no `@CName` rename yet).
- C code must not throw/longjmp through Kotlin frames; KotlinX exceptions are not checked after C calls.
- C functions must not hold Kotlin references; there is no GC safepoint/thread-state contract yet (fine for
  the epoch GC's background thread since only scalars/raw pointers cross).

## kxbindgen (prototype)

`compiler/tools/kxbindgen.py header.h -o bindings.kt [--link lib.c] [--cc clang] [-- clang args]` parses the
header with clang's own JSON AST (`-Xclang -ast-dump=json`; no new dependency) and writes `external fun`s for
functions with supported signatures, `const val`s for enum constants and integer `#define`s, and comments for
what it skipped. KotlinX compiles one input file, so prepend the bindings to the program
(`cat bindings.kt main.kt > all.kt`). Test: `compiler/test.sh` (kxbindgen case, `tests/ffi/gen_demo.*`).

Plan:
1. Types: typedef chains via `desugaredQualType` (done), `char*` -> a future `CString` with explicit copy,
   fixed-width ints only on LP64 (`long` = Long).
2. Structs: `CPointer` today; next a generated accessor set (`fun demo_pair_a(p: CPointer): Long` thunks in C
   emitted by the generator), then by-value structs via LLVM aggregate lowering following the AArch64/x86-64 ABI
   (clang's `-emit-llvm` of a generated shim avoids implementing ABI classification in the compiler).
3. Enums as `const val` (done); typed enum classes later.
4. Macros: integer literals (done); expressions via the clang probe; function-like macros skipped.
5. Callbacks: `staticCFunction`-like references to top-level Kotlin functions with scalar signatures (emit an
   exported `define` with the C ABI); closures need a context pointer and GC rooting.
6. Naming: C names kept; Kotlin keywords back-quoted (done); optional prefix strip (`--strip-prefix demo_`).
7. Linking: `// kx-link:` header line (done); next have `newkotlinc` read it so no flags are needed.

## Benchmark

`bench/ffi/run.sh` (RUNS=5, MANY=1000): KotlinX `external fun` vs Kotlin/Native 2.4.21 cinterop (`-opt`), the same
C object (`clang -O2`, no LTO, so neither side can inline the callee), 100M calls per case, warm-up, median of
interleaved runs, results checked for equality. Also binary size, peak RSS, startup and compile time.
