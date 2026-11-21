# Upstream Kotlin tests on KotlinX compiler

Upstream: https://github.com/JetBrains/kotlin @ `19c36e179da4ff601be5043468aefb63dcb1f201`  
Sources: compiler/testData/codegen/box, compiler/testData/codegen/boxInline, native/native.tests/testData, kotlin-native/backend.native/tests

## Counts

| status | tests |
|---|---|
| PASS | 3495 |
| N/A | 2984 |
| UNSUPPORTED | 1977 |
| MISCOMPILE | 17 |
| COMPILER_CRASH | 2 |

Kept in tree (re-runnable with `run.py`): 3495

## Unsupported features (roadmap, by test count)

| feature | tests |
|---|---|
| strings / templates | 211 |
| interfaces | 146 |
| generics | 140 |
| type inference / mismatch | 96 |
| operator/infix functions | 82 |
| lambdas / function types / references | 63 |
| class bodies / constructors | 59 |
| objects / companion objects | 50 |
| inheritance (open/abstract/sealed) | 45 |
| annotations | 34 |
| nullable types | 30 |
| property accessors / delegation | 25 |
| other: unknown type * | 23 |
| other syntax (at ':') | 23 |
| other syntax (at '.') | 18 |
| other: local classes that capture local variables are not supported | 18 |
| unknown x (stdlib API / unsupported decl) | 18 |
| inline/value classes | 17 |
| other syntax (at '<eof>') | 16 |
| local / extension functions | 14 |
| unknown ok (stdlib API / unsupported decl) | 14 |
| other syntax (at 'val') | 13 |
| default arguments | 13 |
| other syntax (at 'var') | 13 |
| other: unknown type T | 11 |
| other: unsupported call | 11 |
| other: local declarations are only supported in function bodies | 11 |
| unknown context (stdlib API / unsupported decl) | 11 |
| unknown A (stdlib API / unsupported decl) | 10 |
| enum classes | 10 |
| stdlib: kotlin.test asserts | 10 |
| varargs | 10 |
| other: override of 'foo' in C changes its signature | 10 |
| modifiers (private/const/lateinit/...) | 10 |
| other syntax (at 'inner') | 10 |
| exceptions | 10 |
| other: unknown method IntRange.map() | 9 |
| unknown contract (stdlib API / unsupported decl) | 9 |
| loops / labels | 8 |
| other: unknown method Double.isNaN() | 8 |

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

- MISCOMPILE `box/box/boxing/kt84727.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: NullPointerException\n'
- MISCOMPILE `box/box/boxing/kt84727_if.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: NullPointerException\n'
- MISCOMPILE `box/box/boxing/kt84727_inline.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: NullPointerException\n'
- MISCOMPILE `box/box/boxingOptimization/kt19767_2.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.NullPointerException: key  not in map\n'
- MISCOMPILE `box/box/boxingOptimization/kt5588.kt` — exit=0 stdout='fail\n' stderr=''
- MISCOMPILE `box/box/callableReference/equality/overloadedFunctionEquality.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError: Any and Any should be equal\n'
- MISCOMPILE `box/box/companionBlocksAndExtensions/initializersExtensions.kt` — exit=0 stdout='FAIL: initialized=true\n' stderr=''
- MISCOMPILE `box/box/controlStructures/forInCharSequenceWithIndex/forInCharSeqWithIndexStops.kt` — exit=0 stdout="fail: '0:a;1:b;2:c;3:d;'\n" stderr=''
- MISCOMPILE `box/box/controlStructures/kt513.kt` — exit=0 stdout='[1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10]\nOK\n' stde
- COMPILER_CRASH `box/box/coroutines/infiniteLoopInNextMeaningful.kt` — compile timeout: LLVM 18 opt (lto-pre-link<O3>, the CI toolchain) does not terminate on this IR; passes with LLVM 21+
- MISCOMPILE `box/box/extensionFunctions/kt606.kt` — exit=0 stdout='OKFAIL\n' stderr=''
- COMPILER_CRASH `box/box/functions/invoke/implicitInvokeWithFunctionLiteralArgument.kt` — /Users/listepo/.local/share/mise/installs/conda-llvm-tools/21/bin/opt: /Users/listepo/GitHub/listepo/apps/kotlinx/.rtok/
- MISCOMPILE `box/box/inference/noCoercionToUnitWithEqualityConstraintForNullableReturnType.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.Exception: fail, result is not null: kotlin.Unit\n'
- MISCOMPILE `box/box/initializers/initializers7.kt` — exit=-11 stdout='' stderr=''
- MISCOMPILE `box/box/jdk/hashMap.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.NullPointerException: key eeeee not in map\n'
- MISCOMPILE `box/box/suspendConversion/suspendConversionNullableArgumentStaysNull.kt` — exit=0 stdout='fail: null argument arrived non-null\n' stderr=''
- MISCOMPILE `box/box/unit/closureReturnsNullableUnit.kt` — exit=0 stdout='Fail 1\n' stderr=''
- MISCOMPILE `box/box/unit/nullableUnit.kt` — exit=0 stdout='Fail 1\n' stderr=''
- MISCOMPILE `box/box/unit/nullableUnitInWhen3.kt` — exit=0 stdout='Fail\n' stderr=''
