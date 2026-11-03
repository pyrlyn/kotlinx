# Zig GC test suite

**All new GC tests are written in Zig, here.** This directory is a 1:1 port of
[`../run.sh`](../run.sh) (C unit tests + Kotlin-program tests) plus new unit tests of the C interface,
coverage and mutation testing. The original suite stays in place until the port is accepted; `compare.sh`
checks that both produce the same results.

Requirements: Zig 0.16+, the C compiler `run.sh` uses (`/usr/bin/clang` on macOS, or `$NK_CC`), and
`compiler/build/newkotlinc.kexe` (built on first use, as by `run.sh`). No other dependencies.

```
cd compiler/gc/tests/zig
zig build gc                    # whole suite: ported cases + new gc-api tests (non-zero exit on any FAIL)
zig build ported                # only the cases ported from run.sh (same output as run.sh)
zig build gc -Dgcs="default epoch"   # subset of collectors (GCS= works too)
zig build test                  # unit tests of the suite's own helpers
./compare.sh                    # run.sh vs Zig port: full output + every unit program, byte for byte
zig build coverage              # clang source-based coverage -> coverage/ (gitignored)
mutation/run.sh compiler/gc/epoch/gc.c 20 && mutation/report.py ~/Grok/kotlinx-gc-zig-tests/mutants
```

## Layout

| Path | What |
|---|---|
| `build.zig` | builds the unit-test objects and the `gc-suite` runner; steps `gc`, `ported`, `test`, `coverage` |
| `src/runner.zig` | port of `run.sh`: same cases, same order, same inputs, same checks, same `PASS`/`FAIL`/`SKIP` lines |
| `src/proc.zig` | process / file helpers (libc only), the shell redirections `run.sh` used |
| `src/unit/kx.zig` | `@cImport` of `kxgc.h`, `CHECK` / `assert` equivalents, capability arguments |
| `src/unit/gc_unit.zig` | port of `unit/gc_unit.c` |
| `src/unit/traversal.zig` + `traversal_shim.c` | port of `unit/traversal.c`; the shim includes the collector and exports the internals the test reads (no test logic) |
| `src/unit/one_pass.zig` | port of `incremental/one_pass.c` |
| `src/unit/api_test.zig` | **new** tests: acyclic allocation, storage hooks, RC inc/dec, chains, sharing, trial deletion (garbage / referenced rings, exact counts), stats, epoch's deferred-RC log protocol (`rt_gc_log_inc/dec`, write barrier, cycle reclaimed) |
| `compare.sh` | original vs port verification |
| `coverage.sh` | `zig build coverage` backend |
| `mutation/run.sh`, `mutation/report.py` | source-level mutation testing of the GC C code against this suite |

Unit-level tests are Zig objects exporting `main`, linked by the runner with `compiler/runtime/runtime.c`
and one `compiler/gc/<name>/gc.c` using exactly the compiler and flags `run.sh` used (`-O1`/`-O3`, the
`-DKX_CAP_*` defines, `config.sh` flags, ASan builds), so the collector code under test is compiled the same
way. Capabilities that were `#ifdef KX_CAP_*` in the C tests reach the Zig tests as arguments.
Kotlin-program tests (`../kt`, `../epoch`, `../incremental`) are inputs shared with `run.sh`: the runner
compiles them with `newkotlinc` and checks stdout, exit status, `leak-check: OK` and the debug counters
exactly as `run.sh` does. Scratch files go to `compiler/build/gc-zig-tmp` (`run.sh` uses `gc-tmp`).

## Mapping (original -> port)

| Original (`run.sh` line prefix) | Port | Cases on macOS arm64 |
|---|---|---|
| `gc-unit <gc> -O1/-O3` (`unit/gc_unit.c`) | `src/unit/gc_unit.zig` | 8 + 2 SKIP (epoch, deferred RC) |
| `gc-traversal <gc> -O1/-O3`, `gc-traversal-asan` (`unit/traversal.c`) | `src/unit/traversal.zig` | 4 + 2 |
| `gc <gc> <kt>` (`kt/*.kt` = `.out`) | `ktTests` in `src/runner.zig` | 44 + 6 SKIP (rc, no cycles) |
| `gc-leaks <gc> <kt>` (`--leak-check`) | `ktTests` | 34 |
| `gc-asan <gc> <kt>` (ASan build) | `ktTests` | 34 |
| `gc-epoch queue_spike` | `epochTests` | 1 |
| `gc-epoch paced_free` (+ `leaks keep=16384/0`) | `epochTests` | 3 |
| `gc-epoch madvise_big madvise=1/0` (+ leaks) | `epochTests` | 4 |
| `gc-incremental slice_black` | `incrementalTests` | 1 |
| `gc-incremental one_pass` (`incremental/one_pass.c`) | `src/unit/one_pass.zig` | 1 |
| `gc-unknown` | runner | 1 |
| — (new) | `gc-api <gc> -O1/-O3` (`src/unit/api_test.zig`) | 10 |

`compare.sh` masks only timing-dependent values that each suite asserts bounds on itself
(`paced=`/`boundary=`, `qmax=`/`logs=`, `madvised=`, pause times); everything else, including exit codes,
must match byte for byte.

## Coverage

`zig build coverage` runs the whole suite with `-fprofile-instr-generate -fcoverage-mapping` on the
collectors, the runtime and every test program (Kotlin programs via `newkotlinc --cflags`), merges the
profiles with the `llvm-profdata` matching the C compiler (`xcrun` on macOS) and writes `coverage/summary.txt`
(line / branch / region coverage per GC file), `coverage/html/index.html` and `coverage/uncovered.txt`.

## Mutation testing

`mutation/run.sh FILE [MAX]` uses the shared `mutate.py` engine (`$MUTATE_PY`) to apply one source mutation
at a time to a private copy of `compiler/gc` + `compiler/runtime` (never the checkout) and runs this suite
there for the collectors that use the file. `mutation/report.py WORK` prints killed / timeout / survived per
file and marks each survivor as on an uncovered line (coverage gap) or a covered one (assertion gap).
