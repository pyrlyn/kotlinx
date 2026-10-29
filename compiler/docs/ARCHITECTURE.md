# KotlinX compiler: architecture map for contributors

A short map of `newkotlinc` so a feature can be added without re-reading the whole compiler. Function names are
given instead of line numbers (they drift). GC internals are documented separately in
[`compiler/gc/docs/architecture.md`](../gc/docs/architecture.md).

## Pipeline

```
src/driver/Main.kt        compileToIr(src) = compileGenerics(withAny(src, withPrelude(src, Parser(Lexer(src).lex()).program())))
  frontend/Lexer.kt       tokens (K.ID / K.OP / K.NL / literals / EOF), each with a line
  frontend/Parser.kt      recursive descent -> AST (frontend/Ast.kt) AND most desugaring
  frontend/Prelude.kt     library code written in Kotlin (ranges/progressions, exceptions, Pair, Comparable, list
                          helpers, `Any` boxing); merged only when the source uses it (regex triggers)
  backend/native/Generics.kt  rewriteStatics, boxCapturedVars, monomorphization of generic classes/interfaces
  backend/native/NativeBackend.kt  type checking + LLVM IR text emission (one big class)
  frontend/Uses.kt        last-use analysis for Perceus RC (drop after last use, moves)
then: opt (lto-pre-link<O3>) -> clang -O3 -flto with runtime/runtime.c + gc/<name>/gc.c (+ mimalloc)
```

There is no separate typed IR: the parser produces an AST, desugars it into a smaller AST, and the backend type-checks
while emitting IR.

## Key data structures (`frontend/Ast.kt`, `frontend/Types.kt`)

- Expressions `E` (`NameE`, `CallE`, `MemberE`, `LambdaE`, `IfE`, `WhenE`, `TryE`, `IsE`, `AsE`, `UnE`, `BinE`, ...),
  statements `S` (`VarS`, `AssignS`, `ForS`, `WhileS`, `RetS`, `ExprS`, `JumpS`), `Block` (also lambda bodies:
  `params`, `paramTypes`).
- Declarations: `FunD` (name, params, ret, body/exprBody, typeParams, `recv` for extensions), `ClassD` (fields,
  ctorParams, inits, supers, superArgs, typeParams, enumEntries, isValue, superTypeArgs), `InterfaceD`, `Program`.
- Backend types `Ty`: primitives, `Str`, `Arr`, `ListTy`, `MapTy`, `Cls`, `Iface`, `RawPtr`. Function types are
  interfaces named `$Fn...` (see `fnTy` / `fnSig` in NativeBackend).

## Where features live (copy these patterns)

| feature | how it is implemented |
|---|---|
| top-level decls | `Parser.program()` dispatch on the first token; unknown -> "unexpected top-level token" |
| class bodies | `Parser.classDecl` / `classBody` / `members`; secondary ctors lowered by `lowerSecondaries` |
| nested classes | parsed in class bodies, hoisted to top level with mangled names (`hoisted`, `resolveNested`; outer access through `outer$`) |
| objects / companions | `objectDecl`; lazy singletons via generated `$init$N` / `$get$N` and globals `$o$N` (end of `program()`) |
| extensions | `function()` reads `Recv.` receivers; `resolveExtensions` turns them into functions/methods; member extensions -> `mext$f` |
| property accessors | `getter` / `accessors` -> hidden `$prop$x` methods |
| enums | `classDecl(isEnum = true)`, `enumGlobals`, statics rewritten by `rewriteStatics` (Generics.kt) |
| data classes | `Parser.dataClass` generates equals/hashCode/toString/componentN/copy as AST |
| generics | classes/interfaces monomorphized in `compileGenerics` (`Box<Int>` -> `Box$Int`, `Subst`); generic functions per call in `genGenericCall`; instances discovered via `NeedInstance` / `NeedClass` / `NeedIface` exceptions that restart codegen |
| lambdas / closures | `NativeBackend.genLambda`: free vars from `Uses`, captured values become fields of a generated class `$LamN` implementing `$Fn...`; captured `var`s are boxed into `$Ref<T>` by `boxCapturedVars` |
| callable refs `::f` | `genRef` / `genRef0` (UnE "::") |
| calls | `genCall` -> `genNamedCall` (functions, constructors, built-ins) / `method` (receiver calls) / `invokeFn` (function values); default and named args `normalize`, varargs `packVararg` |
| type checks | `isCheck`, `genAs`, smart casts `smartCasts` / `applyCasts` |
| exceptions | `genTry`, `throwValue`, `unwind` (no C++ EH; status checked after calls in `afterCall`) |
| return type inference | `inferReturnTypes` (expression bodies); failure -> "needs an explicit return type" |
| stdlib methods | built-in methods on String/List/Map/arrays are hand-written cases in `method`, `listLambda`, `genNamedCall`; or Kotlin code in `Prelude.kt` (preferred for new API when performance is not critical) |
| runtime | `runtime/runtime.c` (`rt_*`: strings, lists, maps, printing); memory manager behind `compiler/gc/kxgc.h` |

## How to add a feature

1. Reproduce: pick a failing upstream test from `compiler/tests/upstream/manifest.json` (status `UNSUPPORTED`, field
   `detail` holds the first error). Run it directly:
   `compiler/build/newkotlinc.kexe /tmp/t.kt -o /tmp/t && /tmp/t` (box tests need a `main` calling `box()`;
   `run.py` adds it - see `DRIVER` in `compiler/tests/upstream/run.py`).
2. Prefer desugaring in `Parser.kt` (or a rewrite pass like `rewriteStatics`) into constructs the backend already
   supports; touch NativeBackend only when a new runtime representation is needed.
3. Add a golden test `compiler/tests/<feature>.kt` + `.out` (own test; upstream tests are never modified).
4. If the feature allocates, add a leak test in `compiler/tests/leaks/` or make sure existing ones cover it.
5. Regression (all must hold before merging):
   - `compiler/build.sh && compiler/test.sh` -> `ALL PASSED` (golden, bench checksums, leak count, ASan, macOS `leaks`, GC tests);
   - `python3 compiler/tests/upstream/import.py --no-fetch --jobs 8` -> PASS must not drop, MISCOMPILE and
     COMPILER_CRASH must not grow (counts printed at the end, stored in `manifest.json`; newly passing upstream tests
     are copied into `compiler/tests/upstream/{box,native}` - commit them with the manifest);
   - `cd bench && ./nkbench.sh <name>` -> within noise of main (one result file per benchmark in `bench/results/nk/<case>.json`).
6. One feature per PR, branched from fresh `main`; PR body: `Closes #N`, tests newly passing, new totals.

## Ranking what to do next

`compiler/tests/upstream/report.py` summarizes the manifest. The roadmap issue [#22](https://github.com/pyrlyn/kotlinx/issues/22) (label `upstream-feature`) ranks
missing features by the number of UNSUPPORTED upstream tests whose first error they cause. Counts are lower bounds:
a test may hit a second missing feature after the first one is fixed.
