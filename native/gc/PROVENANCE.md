# Vendored GC provenance

In-tree path dependencies (no git submodule, no out-of-repo symlinks, no network fetch).

Upstream: [pyrlyn/zgc](https://github.com/pyrlyn/zgc) `2dea030e9276cbc6cd8cab603489a27146d32d88` (`zig.sha`); the C side is
from `a65a3f66f7ece6afa73fee64510d990819961ad6` (`c.sha`), which upstream has not changed since.
Updating: copy the upstream files below at the new commit, then re-apply the ZKotlin patches listed here (a 3-way
merge with the previous upstream commit as base keeps them).

| Tree | Upstream | What is frozen |
|---|---|---|
| `native/gc/c/` | pyrlyn/zgc `a65a3f6`: the C side of the repository, i.e. the files of the pre-Zig snapshot `6acde649` (`kxgc.h`, `*/gc.c`, `*/caps`, `*/config.sh`, `common/*.inc`, `bench/` without `bench/native/`, `docs/` without `zig-port.md`, `tests/` without `tests/native/`, `README.md`, `.gitignore`) | **Collector bodies** byte-matched. Path-adapted for ZKotlin (`compiler/gc` -> `native/gc/c`, toolchain wrappers, Zig 0.17): `README.md`, `docs/extraction-plan.md`, `tests/archive/run.sh`, `tests/archive/unit/traversal.c`, `tests/zig/build.zig`, `tests/zig/src/runner.zig`. |
| `native/gc/zig/` | pyrlyn/zgc `2dea030`: `src/`, `tests/native/`, `bench/native/`, `build.zig`, `build.zig.zon`, `kxgc.h`, `docs/zig-port.md` | Byte-matched: `src/epoch.zig`, `src/incremental.zig`, `src/rc.zig`, `src/kxgc.zig`, `src/common/object.zig`, `bench/native/*`, `build.zig.zon`, `tests/native/README.md`. The rest carries the ZKotlin patches below. Not vendored: `artifacts/`, `scripts/`, `renovate.json`. |
| `native/gc/zig/src/runtime_host.zig` | ZKotlin adapter (local) | Host for linking with `runtime.c` (externs; no duplicate runtime symbols). |
| `native/gc/zig/src/compact.zig`, `native/gc/zig/tests/native/compact.zig`, `native/gc/c/compact/caps` | ZKotlin (local) | `compact` collector (the `--target=js` default) and its tests; not from pyrlyn/zgc. |
| `native/gc/smoke/`, `native/gc/build.zig` | ZKotlin (local) | Umbrella modules (`kxgc_c` via `addTranslateC`), smokes, docs. |

## ZKotlin patches on the upstream Zig tree

| Files | Patch |
|---|---|
| `src/lib/host_options.zig`, `src/lib/leakcheck.zig`, `src/lib/host_util.zig` (local files) | Build options `-Dleakcheck`, `-Dpool`, `-Dplain-malloc`; the `rt_leak_obj` hook; allocation and environment helpers shared by `host.zig` and `runtime_host.zig`. |
| `src/host.zig` | Allocation/environment helpers re-exported from `lib/host_util.zig` instead of defined inline. |
| `src/common/regions.zig` | ZKotlin allocation modes on top of the upstream page pool: `--leak-check` tagged prefixes, region counters and traversal validation (`visit`, `noteRun`, `report`); `--alloc pool` pools the RC region natively; `--alloc malloc` takes the tracing region from malloc, and on wasm the RC region too (upstream pools it there). Without the pool, the tracing region reports growth in pages of its live high-water mark (`grow_hook`, `debugPoolPages`), so the incremental collector still starts major cycles. |
| `src/common/trial_deletion.zig`, `src/perceus.zig`, `kxgc.h` | Trial deletion over class layouts in the vtable's `cyclic` slot (`NativeBackend.kt` `cyclicSlot`) and a recorded gray set (`scanGray`, `collectGray`, capped natively by `GRAY_RECORD_MAX`); perceus frees layout garbage with `rt_free_trace(size)` and skips `relo` when it releases nothing. |
| `build.zig` | `compact` collector; build options wired into every module; `gc-libs` (static `libgc-<name>.a` with `runtime_host`) and `gc-bc` (LLVM bitcode for `--target=js`) steps. |
| `tests/native/suite.zig` | Tests for the patches above (`layoutCollect`, `rootBufferReuse`, the gray-record visit counts in `traversal`). |
| `docs/zig-port.md` | ZKotlin notes (compact, `--target=js`). |

ZKotlin wiring: `NEWKOTLIN_GC_DIR` → `native/gc/c`; default `--gc-runtime=zig` links `native/gc/zig/zig-out/lib/libgc-*.a`.

## License

**TODO (unresolved):** upstream [pyrlyn/zgc](https://github.com/pyrlyn/zgc) has no `LICENSE` file, no SPDX headers and no
license metadata on GitHub (checked 2026-11-22 at `2dea030`); none of the vendored files under `native/gc/` carry a
license notice either. Both repositories belong to the pyrlyn organization, but no license is inferred here: until
pyrlyn/zgc publishes one, the terms of `native/gc/c/` and `native/gc/zig/` (except the ZKotlin-local files listed above,
which are covered by the repository's root [`LICENSE`](../../LICENSE)) are undetermined. Resolve by adding a license
to pyrlyn/zgc and recording it here.
