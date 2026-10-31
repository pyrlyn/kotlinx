# Upstream Kotlin tests on KotlinX compiler

Upstream: https://github.com/JetBrains/kotlin @ `19c36e179da4ff601be5043468aefb63dcb1f201`  
Sources: compiler/testData/codegen/box, compiler/testData/codegen/boxInline, native/native.tests/testData, kotlin-native/backend.native/tests

## Counts

| status | tests |
|---|---|
| N/A | 2984 |
| UNSUPPORTED | 2924 |
| PASS | 2567 |

Kept in tree (re-runnable with `run.py`): 2567

## Unsupported features (roadmap, by test count)

| feature | tests |
|---|---|
| strings / templates | 253 |
| generics | 208 |
| interfaces | 200 |
| operator/infix functions | 186 |
| lambdas / function types / references | 143 |
| property accessors / delegation | 90 |
| class bodies / constructors | 86 |
| type inference / mismatch | 79 |
| objects / companion objects | 64 |
| type inference (expression bodies) | 45 |
| other syntax (at '.') | 44 |
| other: expected '=' (at ' | 44 |
| inline/value classes | 43 |
| inheritance (open/abstract/sealed) | 40 |
| other: unknown type T | 39 |
| nullable types | 38 |
| destructuring declarations | 29 |
| other syntax (at ':') | 28 |
| annotations | 27 |
| other syntax (at ',') | 22 |
| other syntax (at 'where') | 20 |
| other: unknown type Number | 17 |
| unknown x (stdlib API / unsupported decl) | 17 |
| when expressions | 15 |
| other: unknown type * | 15 |
| other: local classes that capture local variables are not supported | 15 |
| other syntax (at 'suspend') | 14 |
| enum classes | 13 |
| local / extension functions | 13 |
| other syntax (at '<eof>') | 13 |
| other syntax (at 'var') | 13 |
| other syntax (at 'val') | 12 |
| other: unsupported call | 12 |
| other: unknown type R | 12 |
| other syntax (at '(') | 11 |
| other: unknown type Nothing | 11 |
| default arguments | 11 |
| unknown A (stdlib API / unsupported decl) | 10 |
| types: UIntArray | 10 |
| unknown Any (stdlib API / unsupported decl) | 10 |

## N/A reasons

| reason | tests |
|---|---|
| multi-file/module test | 1875 |
| WITH_COROUTINES | 577 |
| platform/reflect import | 189 |
| IGNORE_BACKEND NATIVE/ANY | 119 |
| WITH_REFLECT | 100 |
| native test-runner directive | 69 |
| no oracle (no box() and no .out) | 27 |
| DISABLE_NATIVE | 18 |
| IGNORE_NATIVE | 10 |

Scope: Native target only. Excluded by path (not copied, not counted): JVM-only 277, JS-only 73, Wasm-only 130 tests.

Targets: only `--target=native` exists. Any remaining test whose `TARGET_BACKEND` excludes NATIVE is reported as SKIPPED_TARGET (the exclude lists should keep this at 0).

All kept tests are byte-for-byte copies of upstream; no test is adapted or patched.

Mechanism: per-backend path lists in our harness: `exclude-jvm.txt`, `exclude-js.txt`, `exclude-wasm.txt` (directories ending in `/` or single files, relative to the upstream repo; a future backend of that kind would not apply its own list). Test files are never edited. Tests that KotlinX compiler's harness cannot run as-is (directives such as `IGNORE_BACKEND: NATIVE`, multi-file/module, reflection, coroutines) are still counted, as N/A, by `run.py`.


## Bugs (MISCOMPILE / COMPILER_CRASH)

Minimal repros in `bugs/`:

- `bugs/char_postinc_in_template.kt` — BUG: postfix increment on a Char inside a string template yields the wrong value/type.
- `bugs/ulong_compare.kt` — BUG: unsigned comparison of ULong compiled as signed.
- `bugs/ulong_literal_lexer_crash.kt` — BUG: newkotlinc crashes in Lexer.lex with kotlin.NumberFormatException on a ULong literal > Long.MAX_VALUE.
- `bugs/when_null_branch_on_int.kt` — BUG: `null` branch in `when` over a non-null Int produces invalid LLVM IR
- `bugs/when_string_nullable_null_segfault.kt` — BUG: `when` over a String? subject compares string constants against null without a null check -> SIGSEGV.

Affected upstream tests:

