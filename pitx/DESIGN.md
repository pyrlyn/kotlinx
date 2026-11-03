# pitx: mutation testing on KotlinX LLVM IR

pitx is to the KotlinX compiler what PIT is to JVM bytecode. It mutates the LLVM IR that `newkotlinc` emits,
not the Kotlin sources. Each mutant is relinked and run, and pitx checks whether the tests notice.
It answers two questions:
- Which compiled behaviour is not pinned down by the golden tests (survivors = coverage gaps)?
- Do the tests observe the code the compiler actually generates?

Constraints: stdlib Python plus the LLVM tools the repo already pins via mise (`llvm-as`, `lli`, `opt`, `clang`).
pitx works on temporary copies only; sources and input `.ll` files are never modified.

## Pipeline

```
newkotlinc --emit-llvm --no-opt  ──>  X.ll  (+ baseline run, must equal tests/X.out)
        │
        ▼
llvm-as (verify input)  ── invalid ──> reject (exit 2, "invalid input IR")
        │
        ▼
enumerate sites (operators × instructions in reachable functions)
        │
        ▼  per mutant, in its own temp dir
copy IR, apply one textual edit ─> llvm-as verify ── fails ──> INVALID
        │
        ▼
harness: lli (standalone IR)  |  clang IR + runtime.c + gc/<name>/gc.c (KotlinX module)
        │                                   └─ link fails ──> INVALID
        ▼
run with timeout, compare (stdout, exit code) to the unmutated baseline
        ├─ differs ──> CAUGHT
        ├─ same    ──> SURVIVED
        └─ timeout ──> TIMEOUT (counted as detected, as in PIT)
```

Score = (caught + timeout) / (caught + survived + timeout). Invalid mutants are excluded from the score
and are never reported as caught.

Why `--no-opt` and `-O1` for the link: mutate the IR the frontend wrote. Then a survivor points at the
compiler's own output, not at an `opt` rewrite. A light link keeps relinking cheap. A later mode can
mutate post-`opt` IR to model what really ships.

## Operators

| Operator | Status | Edit |
|---|---|---|
| `icmp-flip` | prototype | `icmp P` → negated predicate (eq↔ne, slt↔sge, sgt↔sle, ult↔uge, ugt↔ule) |
| `arith-swap` | prototype | integer `add` ↔ `sub`; drops `nuw`/`nsw` so the mutant does not become poison |
| `const-step` | planned | integer immediate `C` → `C+1` / `C-1` |
| `negate` | planned | insert `sub 0, %v` before a use |
| `remove-store` | planned | delete a `store` (not to GC/refcount fields) |
| `remove-call` | planned | delete a `void` call (skipping `rt_*`/GC intrinsics) |
| `br-swap`, `select-swap` | planned | swap the targets/arms |

Operators are functions `line -> [(start, end, original, replacement)]` registered in `pitx.OPERATORS`.
Each mutant is one textual edit identified by (operator, line, original). The `.ll` text format is
stable enough that a textual edit plus `llvm-as` verification is enough. Operators that need SSA
rewrites (negate, remove-with-uses) will use a small `opt` pass plugin or an `llvmlite`-free IR model later.

## Filtering

- Default: only functions reachable from `@main` are mutated. Edges are every textual `@ref` from a
  reachable body or from a global (vtables) that one references. Without this, the stdlib prelude that
  every module carries produces about 90 meaningless survivors on a 10-line test.
- `--skip-functions REGEX`: e.g. `^(rt_|rel|relz|relf|free|trav)` to leave refcount/GC glue alone.
  Those mutants mostly cause leaks or crashes, which are a different signal.
- `--all-functions` turns reachability off.

## Mapping back to Kotlin

Every mutant records its IR line and enclosing function (`k.<name>` = user code, `m.`/`k.ext$` = prelude).
If the IR carries `!dbg !N` and `!N = !DILocation(line: L, ...)`, the mutant gets `loc = "line L"`.
newkotlinc does not emit debug info yet; adding it is a next step. Until then, the function name is the link.

## Reporting

- Text: summary line, then every non-caught mutant.
- JSON (`--json`): input, baseline, counts, score, and a per-mutant record
  (id, op, line, function, original, replacement, loc, status, detail).

## Timeouts and parallelism

- Per-run timeout (`--timeout`, default 10 s). Pick a few times the baseline runtime.
- `--jobs N` runs mutants in a thread pool; each mutant has its own temp dir. Default 1, because GC
  benchmarks share the machine and parallel runs distort their timings.

## Layout

- `pitx.py`: library + CLI (operators, enumeration, reachability, verify, harnesses, analyze, report).
- `run_kt.sh`: one golden test end to end (`compiler/tests/X.kt` → `.ll` → pitx with the link harness).
- `tests/test_pitx.py`, `tests/fixtures/*.ll`: operator unit tests, enumeration tests, and runner
  classification on hand-written IR (valid and invalid).

## Next steps

1. Remaining operators (const-step, remove-store/call, br/select swap).
2. A multi-test runner: the mutants of a shared function are killed by any golden test that reaches it.
   Run tests per mutant until the first kill, like PIT's test prioritisation.
3. Emit `!DILocation` from newkotlinc for Kotlin line mapping.
4. Optional post-`opt` mode; a cache of the baseline object for the runtime/GC so a relink only compiles the module.
5. A CI job on a handful of tests, with a score threshold.
