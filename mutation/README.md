# Mutation testing (KotlinX)

Nothing here changes the compiler, runtime or GC sources. Every mutant is written into a private copy
of the files it needs, and the existing test suites run against that copy.

## 1. GC and runtime C: `run-gc.sh` + `mutate.py`

```sh
mutation/run-gc.sh compiler/gc/epoch/gc.c epoch 80 1              # file, GCs to test, mutants, parallel jobs
mutation/run-gc.sh compiler/gc/common/trial_deletion.inc "default incremental" 40 1
MUT_TIMEOUT=300 MUT_WORK=~/Grok/mutation-tests/runs mutation/run-gc.sh ...
python3 mutation/mutate.py list --root . --file compiler/gc/epoch/gc.c --max 20   # preview mutants
python3 mutation/report.py ~/Grok/mutation-tests/runs/kx-*                         # summary table
```

- `gc-setup.sh` builds a mutant tree: copies of `compiler/gc` and `compiler/runtime` (newkotlinc
  resolves the GC dir relative to the runtime), the prebuilt `compiler/build/newkotlinc.kexe` symlinked.
- Tests: `compiler/gc/tests/archive/run.sh` with `GCS=<gcs>`: unit C tests, the Kotlin GC tests, `--leak-check`
  and ASan builds, the epoch queue, paced-free and madvise checks.
- Operators: comparison flips (`== != < <= > >=`), `&&`/`||`, `+`/`-`, `++`/`--`, constants
  (0↔1, n±1, low bit of hex constants), `<<`/`>>`, `true`/`false`, negated `if`, deleted statements,
  dropped RC / barrier / atomic / log lines (`drop-rc`).
- Sampling: a seeded shuffle (`--seed`, default 1) then the first `--max`, so a larger `--max` extends the
  same sample. Results go to `<work>/kx-<file>/results.jsonl` and a rerun resumes.
- Outcomes: `killed` (a test failed, the failing tests are recorded), `survived` (all green: a coverage
  gap or an equivalent mutant), `timeout` (counted as detected; the whole process group is killed).

Since #163/#167 the main GC suite is Zig (`compiler/gc/tests/zig`, with its own mutation runner in
`compiler/gc/tests/zig/mutation/`). `run-gc.sh` keeps the broader legacy shell suite
(`compiler/gc/tests/archive/run.sh`: Kotlin GC tests, `--leak-check`, ASan) as a second, slower oracle.

### Results 2026-11-03 (epoch/gc.c, 80 mutants, seed 1)

Killed 23, timeout 9, survived 48: score 40%. Most survivors are in heuristics, stats and tuning knobs
(budgets, step sizes, pause accounting), which no test asserts. Ones worth a test:
- `:459` dropping the log enqueue (`q_tail->next = cur`) survives: logs that never reach the queue are not detected.
- `:903` dropping the `CAND_BIT` early return (double candidate) survives.
- `:1385` deleting `pthread_mutex_lock(&mu)` survives: no multithreaded test.
- `:1032` dropping the `!sm_cap` guard survives.

## 2. Compiler on the JVM: PIT (`jvm/`)

The compiler is built with Kotlin/Native, which PIT cannot mutate (PIT works on JVM bytecode). But all
of `compiler/src` except `driver/Main.kt` (posix file I/O, clang driver) is plain common Kotlin, so
`jvm/` compiles the same sources for the JVM straight from `../../compiler/src` and runs PIT on them.

- Tests: `GoldenTest`, the feature golden suite of `compiler/test.sh` (`compiler/tests/*.kt` → `*.out`)
  in-process: `compileToIr` on the JVM, then clang `-O1` (system malloc, `--gc=default`) and the
  program's output must equal `.out`. When the IR is byte-identical to the unmutated compiler's IR
  (`build/ir-baseline.txt`), the program is one that already passed, so clang is skipped.
- Not covered by PIT: `driver/Main.kt`, the benchmark checksums and upstream tests of `test.sh`, the
  GC (C, see section 1).

```sh
cd mutation/jvm
gradle -Dkx.writeBaseline=true test           # once: every golden test through clang, records IR hashes
gradle pitest                                 # report: build/reports/pitest/index.html (+ mutations.xml/csv)
gradle pitest -Dkx.pit.classes='newkotlin.Lexer*' -Dkx.pit.threads=2 -Dkx.pit.mutators=STRONGER
gradle pitest -Dkx.skip=name1,name2           # leave out golden tests that fail on the current main
```

Needs JDK 21 and `/usr/bin/clang` (or `NK_CC`). Dependencies: Kotlin JVM plugin, JUnit 5,
`info.solidsoft.pitest` (dev/test only, nothing ships).

## Kotlin/Native

PIT and Arcmutate mutate JVM bytecode only. The one tool that mutates Kotlin/Native code is
[mutflow](https://github.com/anschnapp/mutflow), a K2 compiler plugin that compiles all mutants into
one binary and switches one on per run; it needs a Gradle KMP build with `kotlin-test` tests and
publishes only linuxX64/mingwX64 (macosArm64 via a local publish). The compiler here is built by a bare
`kotlinc-native` call and tested by golden shell scripts, so the JVM build above (same sources) gives
the compiler mutation coverage, and `mutate.py` covers native-only C code.
