# KotlinX plan

Single source for what is done, where the numbers stand and what is left in the KotlinX compiler
(`compiler/`, Kotlin -> LLVM IR, Kotlin/Native target) and its kotlinx library subsets.
State as of main `3ae5715` (#178) plus the `feat/kotlinx-lib-3` and `feat/kotlinx-lib-4` PRs, 2026-11-04. Update this file with every PR that changes the picture.

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
- Collectors: default (Perceus RC + synchronous cycle collector), incremental, epoch, rc.
- Cycle collection follows references held in `ArrayList` elements (#176, default + incremental) and `HashMap` values (#177).
- Fixed (#176, `ff51827`, golden tests): constructor arguments leaked when a later argument throws; leading-dot continuation line in a class body; companion `val` with a custom getter.
- Leak fixes: nullable primitive in a template, owned temp receiver when a call/`lateinit` read throws (#175), owned receiver of a throwing generic member call (#176).
- GC test runner uses a per-run temp dir (two parallel runs no longer delete each other's builds; the rc=126 flake).

### Stdlib
- ArrayList, HashMap, StringBuilder, arrays, ranges/progressions, strings, Int/Long/unsigned/Float/Double/Boolean/Char.

### Tests
- `compiler/test.sh`: golden tests under `--leak-check` (exempt: `progressions`, `throw`, `try_catch`), IR checks (`compiler/tests/ir/*.kt`, escape analysis, captured vars), bench checksums, leak and GC tests (#175).
- Golden tests: `kotlinx_coroutines`, `kotlinx_serialization`, builder-inference tests, regression tests for every leak fixed.
- `run.py --leak-check`: upstream under the leak checker (a passing test that leaks is LEAK). `compiler/tests/reference/fetch.py` re-fetches the 119 reference tests (list.txt + extra/).
- CI: `.github/workflows/compiler.yml` (macOS 15 arm64: build, test.sh, upstream, upstream --leak-check report).

### Added in feat/kotlinx-lib-4
- Leaks fixed (upstream --leak-check 3068 -> 3088/3093): inner instance made in its outer's constructor and `by this` delegates count as cycle edges; a finally block that throws over a pending result; a finally block that returns/jumps over a pending exception; `return` inside call arguments; owned `when` subjects; owned collections a `for` body returns from; a `with` receiver no longer double-released by that return path.
- Cycles through `Array<T?>` (arrays of references are lists) - test `cycles_through_arrays`; lambdas/coroutines capturing collections - `leaks_lambdas_coroutines`.
- Maps: insertion-ordered runtime map (iteration as LinkedHashMap / mutableMapOf), `for ((k, v) in m)`, `for (e in m)` with `key`/`value`, `keys`, `values`, `remove`, `clear`, `isEmpty`/`isNotEmpty`, `mapOf`/`mutableMapOf`/`hashMapOf`/`linkedMapOf(k to v, ...)`, `LinkedHashMap`.
- Empty `listOf()` / `mutableMapOf()` typed by the expected type.
- Sorting: `sorted`, `sortedDescending`, `sortedBy`, `sortedByDescending`, `sortBy`, `sortByDescending`.
- `when` over a sealed class/interface without `else` (exhaustive `is` branches, smart casts kept).
- Strings: `isEmpty`, `isNotEmpty`, `indexOf(String)`, `lastIndexOf`, `contains`, `startsWith`, `endsWith`, `trim`, `isBlank`, `lowercase`/`uppercase` (ASCII), `replace`, `split(String)`, `repeat`, `lines`, `encodeToByteArray`, `ByteArray.decodeToString`.
- Coroutines: Flow `take`/`drop`/`onEach`/`transform`/`fold`/`count`/`first`, `Channel.receiveAsFlow`, `withTimeout`/`withTimeoutOrNull` (virtual time), `withContext`, `Dispatchers.Default/IO/Main/Unconfined` (single-threaded), `List<Job>.joinAll()`, `List<Deferred>.awaitAll()`.
- Serialization: `@Serializable` enums (by name), `@SerialName`, default values for missing keys, nested lists, `Json.decodeFromString<T>(s)` / `encodeToString<T>(v)`.
- FFI demos: HTTP/1.0 server on sockets (`ffi/ffi_http`) and SQLite DAO (`ffi/ffi_sqlite`, `-lsqlite3` via `<test>.link`), both under --leak-check. Analysis: `docs/KTOR_ROOM.md`.

## 2. Current numbers
- `compiler/test.sh`: ALL PASSED (Mac).
- Upstream (`compiler/tests/upstream`, JetBrains/kotlin @ 19c36e1): 3093/3093 kept tests pass, MISCOMPILE 0.
  Under `--leak-check`: 3088/3093; LEAK 5 (bce/withGetter, enum/initEnumAfterObjectAccess, inline/kt66017,
  inlineClasses/.../nullableResult, primitiveTypes/kt37505). STATUS.md recomputed from manifest.json (PASS 3093 / 8475).
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
1. Done: ArrayList, HashMap values (#177), `Array<T>` (a list); epoch through collection owners and write barriers (#180). HashMap keys of objects are not supported at all (keys are Int/Long/Char/String).
2. Done: lambdas and coroutines capturing collections and their owners are leak-free (`leaks_lambdas_coroutines`, `kotlinx_coroutines_more`).
3. Done: replace the "neighbouring builder within a few lines" heuristic (`8a57783`, `f525d34`) with a real common type of `if`/`when` branches.
4. Done: fix codegen for nullable result `K?` (LLVM got `ret i32` for `ptr`) and turn substituteStub `K?` inference back on.
5. Done (partly): `run.py --leak-check`; 20 of 25 leaks fixed. Left: withGetter (temp receiver of a throwing indexed set inside a lambda), kt66017 (non-local return from `repeat` inside inline `forEach`), initEnumAfterObjectAccess (string global of an uninitialized enum), nullableResult (Result in an anonymous object), kt37505 (boxes in a `?.` chain).
6. Done (`ff51827`, moved to section 1): constructor arguments already evaluated leak when a later argument throws; leading-dot continuation line inside a class body fails to parse; companion `val` with a custom getter makes the class unknown (#175).

### P1 - remaining reference tests (39)
7. kt67993: blocked - property types are inferred (inferFields) before method return types (inferReturnTypes), so moving the initializer into a method fails ("unknown method"); even `fun m() = build { consume("x") }; val p = m()` in a class fails. Needs on-demand return-type inference from field inference.
8. kt52838b/c: blocked - needs star projections and casts to generic classes (`generic class DerivedBuildee used as Any is not supported`) plus an unconstrained type variable defaulting to `Any?`.
9. kt66359a/b (constraintsBetweenTwoStubVariables done): blocked - `GenericType<*, OT>` (star projection in a function type) and an unconstrained `X: Any`.
10. Done: contextParameters, context parameters in builder lambdas.
11. kt51988, memberScope, propagateInference (needs `Throwable`); substituteStub done.
12. Local classes capturing locals (kt65300h/i/j), suspend conversion/overloads, unit/SAM conversion, labels, rest of UNSUPPORTED by group.
13. KT-60855 (`GenericLocalClassWithLeakingTypeParameter`) - deliberately skipped.

### P2 - coroutines
14. Flow operators done (take/drop/onEach/transform/fold/count/first, receiveAsFlow). Left: `select`, `Channel` variants (CONFLATED/UNLIMITED constants, `produce`), `flatMap*`, `zip`/`combine`.
15. Done single-threaded: `withTimeout`/`withTimeoutOrNull` (virtual time), `withContext`, `Dispatchers.*`. Left: `delay` on a real clock (time is virtual by design of the deterministic scheduler).
16. Done: cancelled child runs `finally`, frames freed under --leak-check (`kotlinx_coroutines_more`); finally-over-exception leaks fixed in codegen.
17. `runBlocking`/`coroutineScope` returning values; `flow<T> {}` without explicit type argument.
18. Run upstream coroutines tests like upref.

### P3 - serialization
19-21. Done: data classes, nested classes, lists (also nested), nullable, defaults, `@SerialName`, enums, reified decode/encode; golden `kotlinx_serialization(_more)`. Left: polymorphism, maps, custom serializers.

### P4 - language and stdlib
22. Real type inference instead of local builder-inference heuristics (accumulated debt).
23. Done: `Set` (existed), `LinkedHashMap`/ordered maps, sorting, common String functions. Left: `Regex` (needs a regex engine in the runtime), `String.format`, `indexOf(Char)` overload (overloads of extensions are not supported).
24. Done: sealed/enum exhaustiveness; `inline`/`reified` `is T` works; `by lazy` works. Left: `by map` (needs `KProperty.name` in delegates).
25. js/wasm/jvm targets are stubs (exit non-zero) - not planned yet.

### P5 - path to Ktor/Room
26-27. Done: `docs/KTOR_ROOM.md`; `ffi/ffi_http` (socket HTTP server) and `ffi/ffi_sqlite` (SQLite DAO). Verdict: upstream Ktor/Room cannot be built yet (multi-module, expect/actual, star projections, KSP); see the doc's priority list.

### P6 - process and infrastructure
28. Done: `.github/workflows/compiler.yml` (first run happens on the PR).
29. Done: from manifest.json (3093 / 2398 / 2984 of 8475); import.py not run (rule).
30. Done: `compiler/tests/reference/fetch.py` + `list.txt` + `extra/` (verified identical to ~/Grok/upref).
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
