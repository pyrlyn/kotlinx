# Upstream Kotlin tests on KotlinX compiler

Upstream: https://github.com/JetBrains/kotlin @ `19c36e179da4ff601be5043468aefb63dcb1f201`  
Sources: compiler/testData/codegen/box, compiler/testData/codegen/boxInline, native/native.tests/testData, kotlin-native/backend.native/tests

## Counts

| status | tests |
|---|---|
| UNSUPPORTED | 3445 |
| N/A | 2984 |
| PASS | 2006 |
| MISCOMPILE | 38 |
| COMPILER_CRASH | 2 |

Kept in tree (re-runnable with `run.py`): 2006

## Unsupported features (roadmap, by test count)

| feature | tests |
|---|---|
| interfaces | 304 |
| strings / templates | 215 |
| operator/infix functions | 205 |
| type inference (expression bodies) | 160 |
| class bodies / constructors | 145 |
| generics | 132 |
| other syntax (at '.') | 132 |
| lambdas / function types / references | 125 |
| property accessors / delegation | 109 |
| other syntax (at ':') | 89 |
| type inference / mismatch | 79 |
| inheritance (open/abstract/sealed) | 60 |
| other syntax (at 'f') | 56 |
| objects / companion objects | 52 |
| other: expected '=' (at ' | 51 |
| local / extension functions | 48 |
| other syntax (at 'context') | 46 |
| inline/value classes | 43 |
| nullable types | 41 |
| unknown log (stdlib API / unsupported decl) | 33 |
| other syntax (at 'Local') | 29 |
| destructuring declarations | 28 |
| other syntax (at 'F') | 26 |
| unknown Unit (stdlib API / unsupported decl) | 21 |
| annotations | 20 |
| other syntax (at ',') | 19 |
| other syntax (at 'foo') | 19 |
| types: Float | 18 |
| other syntax (at 'A') | 18 |
| other syntax (at 'FunIFace') | 18 |
| unknown Array (stdlib API / unsupported decl) | 16 |
| other syntax (at 'where') | 15 |
| other: unknown type Number | 14 |
| break / continue | 14 |
| other: safe call result must be a reference | 13 |
| other syntax (at 'suspend') | 13 |
| other syntax (at 'var') | 13 |
| other: unknown type * | 12 |
| when expressions | 11 |
| default arguments | 11 |

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

- MISCOMPILE `box/box/argumentOrder/arguments.kt` — exit=0 stdout='fail1: 132 != 123 or xab != xab\n' stderr=''
- MISCOMPILE `box/box/argumentOrder/captured.kt` — exit=0 stdout='fail 1: 0_1_9 != 1_0_9 or 0_1_9 != 0_1_9\n' stderr=''
- MISCOMPILE `box/box/argumentOrder/defaults.kt` — exit=0 stdout='fail: xOyK != KOxy or 1OK1 != 1OK1\n' stderr=''
- MISCOMPILE `box/box/argumentOrder/extension.kt` — exit=0 stdout='fail 1: 0_1_L != 1_0_L or 1_0_1_L != 1_0_1_L\n' stderr=''
- MISCOMPILE `box/box/argumentOrder/simple.kt` — exit=0 stdout='fail 1: OKL != KOL or OKL != OKL\n' stderr=''
- MISCOMPILE `box/box/argumentOrder/simpleInClass.kt` — exit=0 stdout='fail 1: OKL != KOL or OKLZ != OKLZ\n' stderr=''
- MISCOMPILE `box/box/argumentOrder/singleSideEffect.kt` — exit=0 stdout='fail: cba != abc\n' stderr=''
- MISCOMPILE `box/box/bridges/overrideAbstractProperty.kt` — exit=0 stdout='null\n' stderr=''
- MISCOMPILE `box/box/classes/inner/properOuter.kt` — exit=0 stdout='B_A\n' stderr=''
- MISCOMPILE `box/box/classes/inner/properSuperLinking.kt` — exit=0 stdout='FailFail\n' stderr=''
- MISCOMPILE `box/box/classes/kt2477.kt` — exit=0 stdout='FAIL\n' stderr=''
- MISCOMPILE `box/box/classes/kt2480.kt` — exit=0 stdout='null\n' stderr=''
- MISCOMPILE `box/box/closures/captureInSuperConstructorCall/properValueCapturedByClosure2.kt` — exit=0 stdout='Fail: Outer.ok\n' stderr=''
- MISCOMPILE `box/box/dataClasses/equals/genericarray.kt` — exit=0 stdout='fail\n' stderr=''
- MISCOMPILE `box/box/functions/prefixRecursiveCall.kt` — exit=0 stdout='FAIL\n' stderr=''
- MISCOMPILE `box/box/initializers/correctOrder3.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: Expected <1>, actual <0>.\n'
- MISCOMPILE `box/box/inlineClasses/callableRefClassMergingGenerics.kt` — exit=0 stdout='FAIL n3: null\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/defaultFunctionsFromAnyForInlineClass.kt` — exit=0 stdout='Fail 4\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/defaultFunctionsFromAnyForInlineClassGeneric.kt` — exit=0 stdout='Fail 4\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/inlineClassInInitBlock.kt` — exit=0 stdout='null\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/inlineClassInInitBlockGeneric.kt` — exit=0 stdout='null\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/inlineClassWithDefaultFunctionsFromAny.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError: -267645728\n'
- MISCOMPILE `box/box/inlineClasses/inlineClassWithDefaultFunctionsFromAnyGeneric.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError: 269225056\n'
- MISCOMPILE `box/box/inlineClasses/interfaceMethodCalls/complexGenericMethodWithInlineClassOverride2Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: ClassCastException: class D cannot 
- MISCOMPILE `box/box/inlineClasses/interfaceMethodCalls/complexGenericMethodWithInlineClassOverride3Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: ClassCastException: class D cannot 
- MISCOMPILE `box/box/inlineClasses/kt27140.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError\n'
- MISCOMPILE `box/box/inlineClasses/kt27140Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError\n'
- MISCOMPILE `box/box/inlineClasses/kt31994.kt` — exit=-11 stdout='' stderr=''
- MISCOMPILE `box/box/innerClass/superOuter.kt` — exit=0 stdout='Fail\n' stderr=''
- MISCOMPILE `box/box/intrinsics/kt12125.kt` — exit=0 stdout='fail 2: -32769\n' stderr=''
- COMPILER_CRASH `box/box/intrinsics/trimMarginWithBlankString.kt` — kotlin.IllegalArgumentException: marginPrefix must be non-blank string.
- MISCOMPILE `box/box/operatorConventions/augmentedAssignmentWithArrayLHS.kt` — exit=0 stdout='fail3: foo;foo;bar;!;foo;foo;bar;\n' stderr=''
- MISCOMPILE `box/box/operatorConventions/infixFunctionOverBuiltinMember.kt` — exit=0 stdout='fail 3\n' stderr=''
- MISCOMPILE `box/box/primitiveTypes/kt753.kt` — run timeout
- MISCOMPILE `box/box/primitiveTypes/kt757.kt` — run timeout
- MISCOMPILE `box/box/properties/lateinit/kt30548.kt` — exit=0 stdout="'Test.foo()' should throw\n" stderr=''
- MISCOMPILE `box/box/ranges/unsigned/outOfBoundsInMixedContains.kt` — exit=0 stdout='Failed\n' stderr=''
- MISCOMPILE `box/box/strings/stringPlusOverride.kt` — exit=0 stdout='OK Fail\n' stderr=''
- COMPILER_CRASH `box/box/unit/closureReturnsNullableUnit.kt` — /Users/listepo/Grok/nk-opt-mlgo/build/bin/opt: /Users/listepo/grok/mini-bench-wt-cov/compiler/build/upstream-tmp/nkupx5f
- MISCOMPILE `native/native.tests/codegen/escapeAnalysis/negativeArraySize.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: NegativeArraySizeException\n'
