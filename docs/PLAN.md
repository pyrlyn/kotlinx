# KotlinX plan

Single source for what is done, where the numbers stand and what is left in the KotlinX compiler
(`compiler/`, Kotlin -> LLVM IR, Kotlin/Native target) and its kotlinx library subsets.
State as of main `3ae5715` (#178) plus the `feat/kotlinx-lib-3` PR, 2026-11-04. Update this file with every PR that changes the picture.

## 1. Done

### Language
- Frontend `Lexer.kt` -> `Parser.kt` (`Ast.kt`, `Types.kt`) -> `Uses.kt` (Perceus last-use) -> `NativeBackend.kt` -> `clang -O3 -flto` + `runtime/runtime.c`.
- Top-level functions/properties, classes, interfaces with virtual dispatch, objects/companions, enums (entries, `values`/`entries`), `where` clauses, extension and member-extension properties, nullable references and boxed nullable primitives, string templates, if/when expressions, loops, lambdas, overloads, local classes and anonymous objects (incl. inside builder lambdas).
- `suspend` modifier on functions and function types (#176).
- `Nothing` / `Nothing?` as type arguments; generic constructors infer type parameters from the expected type; a captured lambda with receiver can be called as `x.f()` inside lambdas.

### Type / builder inference (PCLA)
- Builder inference through receiver function properties, local classes, anonymous objects, a local class as the builder type argument itself, identity-like generic functions, a builder passed as a lambda parameter.
- Common type of `if`/`when` branches, `listOf` elements and vararg arguments: a branch typed only by its siblings (bare `Buildee()`, `build {}`) takes their type (kt60291c); replaces the "neighbouring builder" heuristic. `listOf(bar<Char> {}, bar {})` reuses a sibling's explicit type arguments (kt35914).
- A nullable lambda result `K?` binds K (`parse { it as Int }`); casts between different representations stay well-typed in IR.
- Context-parameter functions/properties used in a builder lambda fix the receiver's type (`context(c: Controller<String>) fun findUser()`).
- Unification of two builder parameters when one is passed into the other (`emit2(get())`); `null as T` with `T = Int` throws NPE at runtime instead of failing to compile.

### Coroutines
- kotlinx.coroutines subset (#176): `runBlocking`, `coroutineScope`, `launch`, `async`/`await`, `Job`, `joinAll`, `awaitAll`, `yield`, `delay`, `Channel`, cold `Flow` with `map`/`filter`/`collect`/`toList`, single-threaded FIFO scheduler.
- CPS transform of suspend functions (landed in #176 series), so coroutines can suspend mid-body.

### Serialization
- kotlinx.serialization subset: `Json`, `JsonElement` tree, parser, `KSerializer`.
- `@Serializable` codegen: a `KSerializer` singleton per class, `X.serializer()` rewritten.

### GC / leaks
- Collectors: default (Perceus RC + synchronous cycle collector), incremental, epoch, rc, boehm.
- Cycle collection follows references held in `ArrayList` elements (#176, default + incremental) and `HashMap` values (#177).
- Fixed (#176, `ff51827`, golden tests): constructor arguments leaked when a later argument throws; leading-dot continuation line in a class body; companion `val` with a custom getter.
- Leak fixes: nullable primitive in a template, owned temp receiver when a call/`lateinit` read throws (#175), owned receiver of a throwing generic member call (#176).
- GC test runner uses a per-run temp dir (two parallel runs no longer delete each other's builds; the rc=126 flake).

### Stdlib
- ArrayList, HashMap, StringBuilder, arrays, ranges/progressions, strings, Int/Long/unsigned/Float/Double/Boolean/Char.

### Tests
- `compiler/test.sh`: golden tests under `--leak-check` (exempt: `progressions`, `throw`, `try_catch`), IR checks (`compiler/tests/ir/*.kt`, escape analysis, captured vars), bench checksums, leak and GC tests (#175).
- Golden tests: `kotlinx_coroutines`, `kotlinx_serialization`, builder-inference tests, regression tests for every leak fixed.

## 2. Current numbers
- `compiler/test.sh`: ALL PASSED (Mac).
- Upstream (`compiler/tests/upstream`, JetBrains/kotlin @ 19c36e1): 3093/3093 kept tests pass, MISCOMPILE 0.
  STATUS.md still shows a stale `PASS 2614 / 8475 candidates` - to be recomputed (P6.2).
- Reference tests (`~/Grok/upref`, 119 files, rerun on this branch): 80/119 PASS, 39 UNSUPPORTED, no MISCOMPILE/crash.
  UNSUPPORTED by cause:
  - PCLA "lambda with receiver needs a known receiver type" (6): kt52838b/c (`this as Derived<*>`), kt66359a/b (typealias / two linked variables), kt67993, plus KT-60855 (skipped).
  - Other PCLA (3): kt51988 (`unknown type B1`, generic member of the receiver), memberScope (`emit(null)` -> nullable Int vs pointer), propagateInference (`Throwable?`).
  - Context parameters (6): default value / vararg in adapted references, `CharSequence`, companion object in context, functional type, kt74998, same arg for context and extension, context properties without explicit type.
  - Suspend conversion / callable references (6): `suspend` call outside coroutine (2), `::write`, `$FnS` vs `$Fn` (kt44710, unitConversionWithSuspend), `suspend` as a function name (kt51718).
  - Fun interface / SAM / unit conversion / big arity (`expected identifier`) (6), incl. localObjectReturnedFromWhen.
  - Parser: labels `@` (nonLocalReturn x2), top-level `->` (startCoroutineWithCallerContinuation).
  - Misc (7): `Char? == Char`, `Int as String` on extension receiver, `$FnR` vs `$Fn` (kt4228), function expression extension, `MutableCollection`, type parameter in local data class, vararg call from suspend.

## 3. Remaining work

Rule for every step: one change -> `compiler/test.sh` green, upstream 3093/3093, new golden test under `--leak-check` -> commit, push to a feature branch, PR. main only via PR.

### P0 - correctness and memory
1. Cycle leaks through collections: ArrayList done (default/incremental), HashMap done (#177); still arrays (`Array<T>`), HashMap keys, other collections; epoch - reading collections during concurrent collection is unsafe, do only if it can be made safe.
2. Leaks in coroutines (CPS frames capturing collections) and lambdas.
3. Done: replace the "neighbouring builder within a few lines" heuristic (`8a57783`, `f525d34`) with a real common type of `if`/`when` branches.
4. Done: fix codegen for nullable result `K?` (LLVM got `ret i32` for `ptr`) and turn substituteStub `K?` inference back on.
5. Run upstream under `--leak-check` and count/fix leaks.
6. Done (`ff51827`, moved to section 1): constructor arguments already evaluated leak when a later argument throws; leading-dot continuation line inside a class body fails to parse; companion `val` with a custom getter makes the class unknown (#175).

### P1 - remaining reference tests (39)
7. kt67993: anonymous object in a property-initializer lambda loses its receiver - move such initializers into a function (or capture the receiver as a field).
8. kt52838b/c: `this as Derived<*>`, star projection in builder inference.
9. kt66359a/b (constraintsBetweenTwoStubVariables done): two linked type variables - constraint system instead of one at a time.
10. Done: contextParameters, context parameters in builder lambdas.
11. kt51988, memberScope, propagateInference (needs `Throwable`); substituteStub done.
12. Local classes capturing locals (kt65300h/i/j), suspend conversion/overloads, unit/SAM conversion, labels, rest of UNSUPPORTED by group.
13. KT-60855 (`GenericLocalClassWithLeakingTypeParameter`) - deliberately skipped.

### P2 - coroutines
14. `Flow` operators, `Channel` variants, `select`.
15. `withTimeout`, `delay` on a real timer, `Dispatchers` (Default/IO, at least single-threaded).
16. Cancellation: exceptions in `finally`, nested scopes; frames freed under `--leak-check`.
17. `runBlocking`/`coroutineScope` returning values; `flow<T> {}` without explicit type argument.
18. Run upstream coroutines tests like upref.

### P3 - serialization
19. `@Serializable` data classes end to end.
20. JSON encode/decode: nested classes, lists, nullable, defaults, `@SerialName`.
21. Golden tests per type.

### P4 - language and stdlib
22. Real type inference instead of local builder-inference heuristics (accumulated debt).
23. Stdlib: `Set`, `LinkedHashMap`, sorting, `String` formatting, `Regex`, `Throwable` hierarchy.
24. `sealed`/`enum` exhaustiveness in `when`, `inline`/`reified`, property delegates (`by lazy`, `by map`).
25. js/wasm/jvm targets are stubs (exit non-zero) - not planned yet.

### P5 - path to Ktor/Room
26. List what Ktor and Room need: reflection, annotations / annotation processing (KSP), `expect`/`actual`, multi-module builds, C interop (sockets, SQLite).
27. Minimal HTTP server on sockets and SQLite via cinterop; then judge whether Ktor can be built.

### P6 - process and infrastructure
28. macOS CI on PRs running `test.sh` and upstream (now only CodeRabbit, skipped).
29. Recompute STATUS.md (stale 2614/8475).
30. Reference-tests fetch script (`~/Grok/upref`, 119 files) or vendor them, so runs are reproducible.
31. Note: `2c0230e` does not build on its own (fixed in `e85b3d2`); squash-merged, matters only for bisecting branch history.
32. `bench/zigjit/` unused; deleting needs Ivan's yes.

### GC / Polar Quest
- Epoch as the default GC, old default renamed `perceus` (#161, open, needs Ivan's go).
- Deferred live old-generation roots in epoch (#174, being merged after resolving conflicts with main).
- Benchmark thresholds track the latest baseline.

## 4. Open PRs and decisions
- #161 Polar Quest: epoch default - waiting for Ivan's go.
- #174 epoch deferred live roots - conflicts with main resolved and merged by a separate worker if tests are green.
- Merged: #175 (leak-check golden, IR checks), #176 (coroutines/serialization, `afeeae3`), #177 (HashMap cycles, `20b175c`).
- Decisions for Ivan: delete `~/Grok/kotlinx-main`; delete `bench/zigjit/`; stop the two-hourly #176 report routine.
