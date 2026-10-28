# Upstream Kotlin tests on New Kotlin

Upstream: https://github.com/JetBrains/kotlin @ `19c36e179da4ff601be5043468aefb63dcb1f201`  
Sources: compiler/testData/codegen/box, compiler/testData/codegen/boxInline, native/native.tests/testData, kotlin-native/backend.native/tests

## Counts

| status | tests |
|---|---|
| UNSUPPORTED | 5377 |
| N/A | 2984 |
| PASS | 114 |

Kept in tree (re-runnable with `run.py`): 114

## Unsupported features (roadmap, by test count)

| feature | tests |
|---|---|
| generics | 693 |
| top-level properties | 607 |
| inline/value classes | 537 |
| interfaces | 435 |
| inheritance (open/abstract/sealed) | 431 |
| ranges (step/downTo/until, ranges as values) | 370 |
| operator/infix functions | 291 |
| class bodies / constructors | 233 |
| objects / companion objects | 187 |
| lambdas / function types / references | 186 |
| enum classes | 154 |
| strings / templates | 92 |
| type checks / casts (is, as) | 88 |
| modifiers (private/const/lateinit/...) | 79 |
| annotations | 64 |
| extension functions | 64 |
| exceptions | 60 |
| default arguments | 60 |
| type inference (expression bodies) | 57 |
| stdlib: kotlin.test asserts | 35 |
| destructuring declarations | 35 |
| other syntax (at 'f') | 28 |
| other syntax (at ',') | 24 |
| other syntax (at ':') | 24 |
| other syntax (at 'context') | 21 |
| typealias | 17 |
| other: expected '=' (at ' | 17 |
| other syntax (at 'in') | 17 |
| nullable types | 16 |
| other syntax (at 'foo') | 16 |
| other syntax (at 'A') | 15 |
| other syntax (at '!') | 13 |
| other syntax (at 'Local') | 12 |
| property accessors / delegation | 11 |
| unknown listOf (stdlib API / unsupported decl) | 11 |
| unknown intArrayOf (stdlib API / unsupported decl) | 10 |
| types: Any? | 10 |
| other syntax (at 'KRunnable') | 9 |
| other: unexpected character '`' | 9 |
| other syntax (at 'suspend') | 9 |

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

Mechanism: per-backend path lists in our harness: `exclude-jvm.txt`, `exclude-js.txt`, `exclude-wasm.txt` (directories ending in `/` or single files, relative to the upstream repo; a future backend of that kind would not apply its own list). Test files are never edited. Tests that New Kotlin's harness cannot run as-is (directives such as `IGNORE_BACKEND: NATIVE`, multi-file/module, reflection, coroutines) are still counted, as N/A, by `run.py`.


## Bugs (MISCOMPILE / COMPILER_CRASH)

Minimal repros in `bugs/`:

- `bugs/char_postinc_in_template.kt` — BUG: postfix increment on a Char inside a string template yields the wrong value/type.
- `bugs/ulong_compare.kt` — BUG: unsigned comparison of ULong compiled as signed.
- `bugs/ulong_literal_lexer_crash.kt` — BUG: newkotlinc crashes in Lexer.lex with kotlin.NumberFormatException on a ULong literal > Long.MAX_VALUE.
- `bugs/when_null_branch_on_int.kt` — BUG: `null` branch in `when` over a non-null Int produces invalid LLVM IR
- `bugs/when_string_nullable_null_segfault.kt` — BUG: `when` over a String? subject compares string constants against null without a null check -> SIGSEGV.

Affected upstream tests:

