# Native Zig port

Zig **0.17.1** (official latest 0.17 stable, 2026-11-02,
https://ziglang.org/download/). `master` is **0.18.0-dev** — do not use it.

Frozen C collector SHA: `21f34a960f8b56d1dcdf1ab55b8e5e14e4e8ff31`.

## Layout

```
src/kxgc.zig                 KXGC_API_VERSION 1 types / ABI
src/host.zig                 standalone runtime symbols (clock, panic, pause accounting)
src/runtime_host.zig         KotlinX adapter; runtime callbacks stay in runtime.c
src/lib/host_util.zig        shared allocator / environment / RSS helpers for both hosts
src/common/{object,regions,trial_deletion}.zig
src/{rc,perceus,incremental,epoch}.zig
tests/native/suite.zig       gc_unit + api_test + traversal + one_pass
bench/native/ops.zig         port of bench/ops/ops.c
build.zig                    zig build test / zig build bench
scripts/macos/               local Mac C + Zig 0.17.1 commands
artifacts/c-baseline/        frozen Linux VM C measurements (not Mac)
artifacts/zig-bench/         Linux VM Zig measurements (not Mac)
artifacts/macos-local/       local Mac destination + runner-reported C medians
```

Original C files stay as the immutable reference. They are not compiled by
`build.zig`. The Zig collectors export the same `kxgc.h` functions.

## Build / test / bench

```sh
# Official Zig 0.17.1 on PATH (not 0.17.0, not 0.18.0-dev)
zig version   # must print 0.17.1
zig build test
zig build --release=fast
./zig-out/bin/gc-test-<gc>
./zig-out/bin/gc-ops-<gc> [N] [op]
```

### Local Mac (paired C vs Zig on that machine)

```sh
./scripts/macos/install-zig-0.17.1.sh   # refuses anything other than 0.17.1
# mise 0.17.0 / 0.16.0 on the Mac are not 0.17.1
export PATH="$PWD/.zig-0.17.1:$PATH"
./scripts/macos/c-baseline.sh
./scripts/macos/zig-regression.sh
./scripts/macos/zig-bench.sh
python3 scripts/macos/test_compare.py
python3 scripts/macos/compare.py --pair mac-local --label "local Mac"
```

Zig 0.17.1 has no LLVM LTO on Mach-O. Official `zig build --release=fast` uses
ReleaseFast everywhere and full LTO only when the object format is ELF/COFF.

Paired Mac numbers for SHA `31bdbcf` live on the runner at
`~/Grok/gc-local-bench/` (not in this repo). `compare.py` now keys
`(N, collector, op)` so mixed-N C logs are not aggregated against Zig 4M.

## Ported

| Piece | Status |
|---|---|
| `kxgc.h` types / C ABI exports | yes |
| `common/object.inc` | yes |
| `common/regions.inc` (non-`RT_LEAKCHECK` path) | yes |
| `common/trial_deletion.inc` | yes |
| `rc/gc.c` | yes |
| `perceus/gc.c` | yes |
| `incremental/gc.c` | yes |
| `epoch/gc.c` logs, CRC TD, Sigma/Delta, generations, drain | yes |
| epoch incremental-major and side-minor traversal | serial; native regression covers 2,605,000 objects |
| epoch leak/debug counters, paced free, madvise accounting | yes |
| `RT_LEAKCHECK` region tags / counters, traversal validation | yes for common-region collectors |
| epoch slab-release allocator (default on, `KX_GC_SLAB_RELEASE=0` restores bump/free-list) | yes (serial) |
| `tests/archive/unit/gc_unit.c` | yes (native) |
| `tests/zig/src/unit/api_test.zig` | yes (native) |
| `tests/archive/unit/traversal.c` | yes (perceus / incremental) |
| `tests/archive/incremental/one_pass.c` | yes |
| `bench/ops/ops.c` | yes |

## Remaining / unportable (not silently dropped)

| Piece | Why it stays |
|---|---|
| `c/tests/kt/*.kt`, `c/tests/epoch/*.kt`, `c/tests/incremental/*.kt` | Kotlin integration tests, verified via `bin/newkotlinc`; not standalone module tests. |
| `bench/micro.sh`, `bench/compare.sh`, `bench/zig` | Need `newkotlinc` / `nkbench.sh`. Kotlin harnesses, not collectors. |
| `bench/third_party/boehm` | Third-party C (bdwgc). Benchmark-only. |
| `tests/zig/*` existing harness | Still links **C** collectors. Historical C-via-Zig suite. |
| `tests/zig/mutation` | Mutates C source. |
| epoch parallel MarkGray/Scan, adaptive worker cost model and early-validation helpers | C pause paths; not claimed equivalent. Serial = `KX_GC_THREADS=1`. |
| epoch full diagnostic dump / stack release / major slack | Only the numeric debug fields asserted by the regression are ported. |
| epoch common-region traversal accounting | C epoch bypasses those regions; leak-object counts and the other collectors' region reports remain available. |
| KotlinX `runtime.c` | Shared runtime at `compiler/runtime/runtime.c`; not replaced by this port. Host stub matches its clock on macOS. |

## Runtime integration and duplication review

`host.zig` and `runtime_host.zig` are separate ABI adapters, not alternative
collectors: the former exports runtime callbacks for standalone tests; the
latter imports the same symbols from KotlinX `runtime.c`. They must not be linked
into the same executable. Both use the shared non-callback helpers in
`lib/host_util.zig`. `kxgc.zig` is the Zig ABI view; translate-C `kxgc_c` in the
umbrella build is its C-header view, not a second implementation. Collectors reuse
`lib/leakcheck.zig`, which only gates a declaration/call to `rt_leak_obj`; the
actual object-accounting hook remains in `runtime.c`.

The connected Zig module already covers object header/color/count operations and
pointer stacks in `common/object.zig`, allocation/region tags, pool/malloc modes
and traversal accounting in `common/regions.zig`, and the MarkGray/Scan/CollectWhite
passes in `common/trial_deletion.zig`. RC, Perceus and incremental reuse these
implementations rather than translating a second copy. Perceus and incremental
still have collector-specific scheduling/root-buffer code. Epoch reuses the
object/stack helpers but requires its own concurrent CRC/Sigma/Delta, candidate
validation and generational traversal; its superficially similar MarkGray/Scan
is not interchangeable with the immediate-RC trial-deletion module.

The build always rebuilds the selected collector's static archive (cached when
unchanged). `bin/newkotlinc` maps `--leak-check`, `--alloc pool`, and `--alloc
malloc` to archive options; direct compiler-binary invocations must build the
corresponding archive themselves. On Darwin, compiler LTO is disabled for the
Zig archive link; the generated IR is still optimized normally.

## Baseline shared runtime leaks

Upstream `--leak-check` reports 3 existing shared-runtime leaks reproduced
identically on the unchanged original C collectors and Zig; they are not fixed
by this port and are not a Zig-specific regression. Results remain **3090 PASS,
3 LEAK / 3093**, not all green: `bce/withGetter.kt` (ArrayList),
`enum/initEnumAfterObjectAccess.kt` (string), and `inline/kt66017.kt` (ArrayList),
each with `live=1`. The three C-reference reruns also report **3 LEAK / 3**;
no tests are excluded or reclassified as passed.

## Verdict

GC selection requires an explicit collector name and the matching runtime
backend. Native and Kotlin-linked regressions exercise the Zig backend,
including the epoch queue, paced-free, madvise, and incremental-major paths. Full
collector parity is not claimed: the C parallel helpers and detailed diagnostics
remain as documented above. Do not update C benchmark baselines from this port.
