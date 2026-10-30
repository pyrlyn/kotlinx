# Upstream Kotlin tests on KotlinX compiler

Upstream: https://github.com/JetBrains/kotlin @ `19c36e179da4ff601be5043468aefb63dcb1f201`  
Sources: compiler/testData/codegen/box, compiler/testData/codegen/boxInline, native/native.tests/testData, kotlin-native/backend.native/tests

## Counts

| status | tests |
|---|---|
| UNSUPPORTED | 3145 |
| N/A | 2984 |
| PASS | 2318 |
| MISCOMPILE | 28 |

Kept in tree (re-runnable with `run.py`): 2318

## Unsupported features (roadmap, by test count)

| feature | tests |
|---|---|
| interfaces | 275 |
| strings / templates | 229 |
| operator/infix functions | 218 |
| generics | 180 |
| lambdas / function types / references | 129 |
| property accessors / delegation | 120 |
| class bodies / constructors | 110 |
| type inference / mismatch | 107 |
| other syntax (at ':') | 99 |
| inheritance (open/abstract/sealed) | 66 |
| other syntax (at 'f') | 55 |
| objects / companion objects | 52 |
| other: expected '=' (at ' | 51 |
| other syntax (at 'context') | 47 |
| local / extension functions | 45 |
| nullable types | 44 |
| inline/value classes | 42 |
| other syntax (at '.') | 42 |
| type inference (expression bodies) | 36 |
| destructuring declarations | 29 |
| other syntax (at 'F') | 27 |
| annotations | 21 |
| other syntax (at ',') | 20 |
| types: Float | 18 |
| other syntax (at 'FunIFace') | 18 |
| other: safe call result must be a reference | 17 |
| other: unknown type Number | 16 |
| other syntax (at 'where') | 15 |
| when expressions | 14 |
| other: unknown type * | 14 |
| other syntax (at 'suspend') | 13 |
| other syntax (at 'var') | 13 |
| other: unknown type T | 12 |
| other: local classes that capture local variables are not supported | 12 |
| other syntax (at 'val') | 11 |
| default arguments | 11 |
| unknown A (stdlib API / unsupported decl) | 10 |
| types: UIntArray | 10 |
| other: unknown type Nothing | 10 |
| enum classes | 9 |

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
- MISCOMPILE `box/box/inlineClasses/inlineClassWithDefaultFunctionsFromAny.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError: 1611402400\n'
- MISCOMPILE `box/box/inlineClasses/inlineClassWithDefaultFunctionsFromAnyGeneric.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError: -267645856\n'
- MISCOMPILE `box/box/inlineClasses/interfaceMethodCalls/complexGenericMethodWithInlineClassOverride2Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: ClassCastException: class D cannot 
- MISCOMPILE `box/box/inlineClasses/interfaceMethodCalls/complexGenericMethodWithInlineClassOverride3Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: ClassCastException: class D cannot 
- MISCOMPILE `box/box/inlineClasses/kt27140.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError\n'
- MISCOMPILE `box/box/inlineClasses/kt27140Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError\n'
- MISCOMPILE `box/box/inlineClasses/kt31994.kt` — exit=-11 stdout='' stderr=''
- MISCOMPILE `box/box/innerClass/superOuter.kt` — exit=0 stdout='Fail\n' stderr=''
- MISCOMPILE `box/box/intrinsics/kt12125.kt` — exit=0 stdout='fail 2: -32769\n' stderr=''
- MISCOMPILE `box/box/operatorConventions/augmentedAssignmentWithArrayLHS.kt` — exit=0 stdout='fail3: foo;foo;bar;!;foo;foo;bar;\n' stderr=''
- MISCOMPILE `box/box/operatorConventions/infixFunctionOverBuiltinMember.kt` — exit=0 stdout='fail 3\n' stderr=''
- MISCOMPILE `box/box/ranges/unsigned/outOfBoundsInMixedContains.kt` — exit=0 stdout='Failed\n' stderr=''
- MISCOMPILE `box/box/strings/stringPlusOverride.kt` — exit=0 stdout='OK Fail\n' stderr=''
- MISCOMPILE `native/native.tests/codegen/escapeAnalysis/negativeArraySize.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: NegativeArraySizeException\n'
