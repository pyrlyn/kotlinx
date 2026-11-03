# Plan: a type-constraint solver for generic calls and builder inference

Status: design, not implemented. Tracking: #15 (function types with receiver / builder inference) and the
`inference/pcla/*` upstream tests (about 55 still UNSUPPORTED with "lambda with receiver needs a known receiver type").

## Today

Type arguments of a generic call are bound eagerly in `genGenericCall` (`backend/native/NativeBackend.kt`):

1. non-lambda arguments are generated and `unify(paramType, argType)` binds type parameters (first binding wins);
2. `bindExpectedRet` binds from the expected result type;
3. lambdas are typed from the partially bound signature; three heuristics fill gaps before that:
   `builderInfer` (calls on the implicit receiver, `yield(x)`), `usageInfer` (#120: `val x: T = this`,
   `consume(it)`), and producing positions (#121: `consume(materialize())`).
4. any type parameter still unbound fails with "cannot infer type parameter ...".

Each heuristic is a syntactic pattern match, so every new context (`yield(id(arg))`, reassigned locals,
nested lambdas, local/anonymous classes, extension properties, two type variables constraining each other)
needs another rule. #121 added two rules for +2 tests: the approach has saturated.

## Proposal

Replace eager binding with a small constraint system per generic call (Kotlin's "postponed type variables",
simplified):

- **Type variables**: one per type parameter of the call (`T#1`), plus *stub* variables for the receiver/parameter
  types of postponed lambdas (PCLA: `Buildee<T#1>` is the lambda's `this` while `T#1` is unknown).
- **Constraints**: `sub <: sup` between `Ty`s containing variables; `=` for invariant positions. Sources:
  argument/parameter pairs, expected type, and - while generating a postponed lambda body *in probe mode*
  (IR rolled back, as `probeGenericRet` and the lambda return probe already do) - every place a stub-typed
  value flows into a typed sink or a typed value flows into a stub-typed parameter (method calls on the stub
  receiver, assignments, arguments to non-generic and generic calls, returns).
- **Solving**: after all arguments and lambda probes, fix each variable to the least upper bound of its lower bounds
  (or the greatest lower bound of upper bounds when there are none), in dependency order; the existing class
  hierarchy (`ClassInfo.ancestors`, interfaces) gives subtyping and LUB. Nullability: needs a nullable `Ty` for
  class types first (today `UserKlass?` and `UserKlass` share a `Ty`, which is why #121 skips nullable sinks).
- **Then** generate the call for real with the fixed substitution (monomorphization stays as it is).

## Steps (one PR each, each with golden tests and the usual regression checklist)

1. Nullable class/interface types in `Ty` (`Ty.Nullable(inner)` or a flag), only as far as inference needs it;
   no behavior change otherwise. Unblocks the nullable PCLA tests.
2. `Constraints` data structure + solver (LUB/GLB over the existing hierarchy) with unit-style golden tests;
   `genGenericCall` collects constraints from non-lambda arguments and the expected type and solves them;
   eager `unify` becomes the fallback when the solver has nothing. Must keep PASS identical (pure refactor).
3. Probe-mode lambda generation with stub receiver/parameter types: sinks and sources inside the body add
   constraints instead of failing. Replaces `builderInfer` / `usageInfer` / producing-position rules.
4. Nested generic calls inside postponed lambdas (`yield(id(arg))`): the inner call's variables join the outer
   system instead of being solved early.
5. Multiple postponed lambdas / two stub variables constraining each other
   (`constraintsBetweenTwoStubVariables.kt`, `topDownCompletionWithThreeBuilderInferenceCalls.kt`).
6. Declarations inside builder lambdas (local classes, anonymous objects, local functions, extension
   properties) referring to stub types.

## Risks

- Compile time: probing lambda bodies twice; keep probes bounded and cached per call site (as `RefLambdas`).
- Error messages: keep "cannot infer type parameter" with the variable's name when the system is unsolvable.
- No new dependencies; no change to the runtime or GC.
