# Upstream Kotlin tests on New Kotlin

Upstream: https://github.com/JetBrains/kotlin @ `19c36e179da4ff601be5043468aefb63dcb1f201`  
Sources: compiler/testData/codegen/box, compiler/testData/codegen/boxInline, native/native.tests/testData, kotlin-native/backend.native/tests

## Counts

| status | tests |
|---|---|
| UNSUPPORTED | 5387 |
| N/A | 2984 |
| SKIPPED_TARGET | 203 |
| PASS | 89 |
| COMPILER_CRASH | 13 |
| MISCOMPILE | 2 |

Kept in tree (re-runnable with `run.py`): 89

## Unsupported features (roadmap, by test count)

| feature | tests |
|---|---|
| class bodies / constructors | 705 |
| top-level properties | 592 |
| generics | 520 |
| inline/value classes | 508 |
| interfaces | 476 |
| inheritance (open/abstract/sealed) | 386 |
| ranges (step/downTo/until, ranges as values) | 358 |
| operator/infix functions | 274 |
| lambdas / function types / references | 163 |
| enum classes | 146 |
| objects / companion objects | 98 |
| strings / templates | 92 |
| type checks / casts (is, as) | 77 |
| modifiers (private/const/lateinit/...) | 76 |
| annotations | 59 |
| exceptions | 56 |
| type inference (expression bodies) | 54 |
| extension functions | 41 |
| default arguments | 35 |
| destructuring declarations | 34 |
| stdlib: kotlin.test asserts | 33 |
| other syntax (at ',') | 31 |
| other syntax (at 'f') | 28 |
| other syntax (at ':') | 21 |
| other syntax (at 'in') | 18 |
| typealias | 17 |
| other: expected '=' (at ' | 17 |
| other syntax (at 'foo') | 16 |
| other syntax (at 'A') | 15 |
| other syntax (at 'context') | 15 |
| nullable types | 13 |
| break / continue | 13 |
| other syntax (at '!') | 12 |
| other syntax (at 'Local') | 11 |
| other syntax (at 'KRunnable') | 9 |
| other: unexpected character '`' | 9 |
| other syntax (at 'suspend') | 9 |
| other syntax (at 'bar') | 8 |
| types: Any? | 8 |
| other syntax (at '++') | 7 |

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

Scope: all backends except JVM-only. Excluded as JVM-only: 277 tests (not copied, not counted). JS/Wasm/Native tests are kept.

Targets: only `--target=native` exists. Tests whose `TARGET_BACKEND` names other non-JVM backends only (JS/Wasm) are kept as candidates and reported as SKIPPED_TARGET until those targets exist.

All kept tests are byte-for-byte copies of upstream; no test is adapted or patched.

Mechanism: JVM-only tests are excluded by path via `exclude-jvm.txt` in our harness (a per-backend list: a future JVM backend run would not apply it) (directories ending in `/` or single files, relative to the upstream repo); test files are never edited. Tests that New Kotlin's harness cannot run as-is (directives such as `IGNORE_BACKEND: NATIVE`, non-JVM `TARGET_BACKEND`, multi-file/module, reflection, coroutines) are still counted, as N/A, by `run.py`.


## Bugs (MISCOMPILE / COMPILER_CRASH)

Minimal repros in `bugs/`:

- `bugs/char_postinc_in_template.kt` — BUG: postfix increment on a Char inside a string template yields the wrong value/type.
- `bugs/ulong_compare.kt` — BUG: unsigned comparison of ULong compiled as signed.
- `bugs/ulong_literal_lexer_crash.kt` — BUG: newkotlinc crashes in Lexer.lex with kotlin.NumberFormatException on a ULong literal > Long.MAX_VALUE.
- `bugs/when_null_branch_on_int.kt` — BUG: `null` branch in `when` over a non-null Int produces invalid LLVM IR
- `bugs/when_string_nullable_null_segfault.kt` — BUG: `when` over a String? subject compares string constants against null without a null check -> SIGSEGV.

Affected upstream tests:

- COMPILER_CRASH `box/box/evaluate/ulongOperations.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/expression/downTo/maxValueToMinValueStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/expression/downTo/maxValueToOneStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/expression/downTo/maxValueToZeroStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/expression/rangeTo/oneToMaxValueStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/literal/downTo/maxValueToMinValueStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/literal/downTo/maxValueToOneStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/literal/downTo/maxValueToZeroStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/stepped/unsigned/literal/rangeTo/oneToMaxValueStepMaxValue.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/ranges/unsigned/kt67383.kt` — kotlin.NumberFormatException
- MISCOMPILE `box/box/strings/kt3652.kt` — exit=0 stdout='fail1\n' stderr=''
- MISCOMPILE `box/box/unsignedTypes/unsignedLiteralsForMaxLongValue.kt` — exit=0 stdout='Fail\n' stderr=''
- COMPILER_CRASH `box/box/unsignedTypes/unsignedLongToString.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/unsignedTypes/whenByUnsigned.kt` — kotlin.NumberFormatException
- COMPILER_CRASH `box/box/when/kt2457.kt` — /var/folders/1q/t_vv3x8x07x3s6b30wdj43kr0000gn/T/nkupf5gm_da8/t.ll:65:21: error: '%t1' defined with type 'i32' but expec
