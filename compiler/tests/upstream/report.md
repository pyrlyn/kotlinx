# Upstream Kotlin tests on KotlinX compiler

Upstream: https://github.com/JetBrains/kotlin @ `19c36e179da4ff601be5043468aefb63dcb1f201`  
Sources: compiler/testData/codegen/box, compiler/testData/codegen/boxInline, native/native.tests/testData, kotlin-native/backend.native/tests

## Counts

| status | tests |
|---|---|
| UNSUPPORTED | 3000 |
| N/A | 2984 |
| PASS | 2472 |
| MISCOMPILE | 19 |

Kept in tree (re-runnable with `run.py`): 2472

## Unsupported features (roadmap, by test count)

| feature | tests |
|---|---|
| strings / templates | 238 |
| interfaces | 234 |
| generics | 183 |
| operator/infix functions | 177 |
| lambdas / function types / references | 149 |
| property accessors / delegation | 138 |
| class bodies / constructors | 83 |
| type inference / mismatch | 73 |
| objects / companion objects | 58 |
| local / extension functions | 52 |
| inheritance (open/abstract/sealed) | 47 |
| other: expected '=' (at ' | 44 |
| other syntax (at '.') | 43 |
| type inference (expression bodies) | 42 |
| inline/value classes | 42 |
| other: unknown type T | 39 |
| nullable types | 37 |
| destructuring declarations | 29 |
| other syntax (at ':') | 28 |
| annotations | 23 |
| other: local classes that capture local variables are not supported | 23 |
| other syntax (at ',') | 21 |
| other syntax (at 'var') | 19 |
| other syntax (at 'val') | 18 |
| other syntax (at 'FunIFace') | 18 |
| other: unknown type Number | 17 |
| when expressions | 15 |
| other: unknown type * | 15 |
| other syntax (at 'where') | 15 |
| other syntax (at 'suspend') | 14 |
| unknown x (stdlib API / unsupported decl) | 12 |
| enum classes | 11 |
| other: unsupported call | 11 |
| other: unknown type Nothing | 11 |
| default arguments | 11 |
| unknown A (stdlib API / unsupported decl) | 10 |
| types: UIntArray | 10 |
| unknown Any (stdlib API / unsupported decl) | 10 |
| other: local declarations are only supported in function bodies | 10 |
| other: override of 'foo' in C changes its signature | 9 |

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
- MISCOMPILE `box/box/initializers/correctOrder3.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: Expected <1>, actual <0>.\n'
- MISCOMPILE `box/box/inlineClasses/callableRefClassMergingGenerics.kt` — exit=0 stdout='FAIL n3: null\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/defaultFunctionsFromAnyForInlineClass.kt` — exit=0 stdout='Fail 4\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/defaultFunctionsFromAnyForInlineClassGeneric.kt` — exit=0 stdout='Fail 4\n' stderr=''
- MISCOMPILE `box/box/inlineClasses/inlineClassWithDefaultFunctionsFromAny.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError: -1341387808\n'
- MISCOMPILE `box/box/inlineClasses/inlineClassWithDefaultFunctionsFromAnyGeneric.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.AssertionError: 789536\n'
- MISCOMPILE `box/box/inlineClasses/interfaceMethodCalls/complexGenericMethodWithInlineClassOverride2Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: ClassCastException: class D cannot 
- MISCOMPILE `box/box/inlineClasses/interfaceMethodCalls/complexGenericMethodWithInlineClassOverride3Generic.kt` — exit=1 stdout='' stderr='Exception in thread "main" java.lang.IllegalStateException: ClassCastException: class D cannot 
- MISCOMPILE `box/box/inlineClasses/kt31994.kt` — exit=-11 stdout='' stderr=''
- MISCOMPILE `box/box/innerClass/superOuter.kt` — exit=0 stdout='Fail\n' stderr=''
- MISCOMPILE `box/box/intrinsics/kt12125.kt` — exit=0 stdout='fail 2: -32769\n' stderr=''
- MISCOMPILE `box/box/strings/stringPlusOverride.kt` — exit=0 stdout='OK Fail\n' stderr=''
