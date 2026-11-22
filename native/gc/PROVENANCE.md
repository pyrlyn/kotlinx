# Vendored GC provenance

In-tree path dependencies (no git submodule, no out-of-repo symlinks, no network fetch).

| Tree | Upstream | Commit | What is frozen |
|---|---|---|---|
| `native/gc/c/` | pyrlyn/gc | `6acde649c454e710ca1bc79e04651b0ef07b1b26` | **Collector bodies**: `kxgc.h`, `*/gc.c`, `common/*.inc`, caps. Snapshot docs/scripts are imported as-is and may mention the old submodule workflow. |
| `native/gc/zig/src/{kxgc,host,epoch,rc}.zig`, `src/common/object.zig`, `tests/native/README.md`, `bench/native/`, `docs/zig-port.md`, `build.zig.zon` | pyrlyn/gc | `a65a3f66f7ece6afa73fee64510d990819961ad6` | **Zig collector bodies** from that commit (byte-matched). |
| `native/gc/zig/src/perceus.zig`, `src/common/trial_deletion.zig`, `kxgc.h` | pyrlyn/gc + KotlinX | `a65a3f6` + local | Upstream plus class layouts in `KxVTab.cyclic` and the recorded gray set (scanGray/collectGray) for perceus. |
| `native/gc/zig/src/incremental.zig` | pyrlyn/gc + KotlinX | `a65a3f6` + local | Upstream with one change: `usToNs` clamps microseconds before converting, so `--target=js` gets a plain multiply instead of a saturating `*\|` (LLVM's `umul.fix.sat`, which the JS backend does not lower). |
| `native/gc/zig/src/common/regions.zig` | pyrlyn/gc + KotlinX | `a65a3f6` + local | Upstream page pool, with the retry loop around `refill` moved out of the inlined allocation path (it made every allocation save callee-saved registers: perceus `short_lived` 18% slower), plus the `-Dleakcheck` (tagged prefixes, region report, `rt_leak_obj`), `-Dpool` and `-Dplain-malloc` modes that `bin/newkotlinc` selects. |
| `native/gc/zig/build.zig`, `tests/native/suite.zig` | pyrlyn/gc + KotlinX | `a65a3f6` + local | Upstream plus the KotlinX steps (`gc-libs`, `gc-bc`, `compact`, build options) and tests for the local changes. |
| `native/gc/zig/src/runtime_host.zig`, `src/lib/` | KotlinX adapter | (local) | Host for linking with `runtime.c` (externs; no duplicate runtime symbols); `lib/host_util.zig` mirrors the helpers of upstream `host.zig`. `host.zig` and `runtime_host.zig` are never linked into one executable. |
| `native/gc/zig/src/compact.zig`, `native/gc/zig/tests/native/compact.zig`, `native/gc/c/compact/caps` | KotlinX | (local) | `compact` collector (the `--target=js` default) and its tests; not from pyrlyn/gc, not byte-matched. |
| `native/gc/smoke/`, `native/gc/build.zig` | KotlinX | (local) | Umbrella modules (`kxgc_c` via `addTranslateC`), smokes, docs. |

KotlinX wiring: `NEWKOTLIN_GC_DIR` → `native/gc/c`; default `--gc-runtime=zig` links `native/gc/zig/zig-out/lib/libgc-*.a`.
