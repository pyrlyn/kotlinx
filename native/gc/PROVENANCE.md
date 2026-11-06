# Vendored GC provenance

In-tree path dependencies (no git submodule, no out-of-repo symlinks, no network fetch).

| Tree | Upstream | Commit | What is frozen |
|---|---|---|---|
| `native/gc/c/` | pyrlyn/gc | `6acde649c454e710ca1bc79e04651b0ef07b1b26` | **Collector bodies**: `kxgc.h`, `*/gc.c`, `common/*.inc`, caps. Snapshot docs/scripts are imported as-is and may mention the old submodule workflow. |
| `native/gc/zig/src/{kxgc,host,epoch,rc,perceus,incremental,common}*` | pyrlyn/gc | `cf79057` | **Zig collector bodies** from that commit (byte-matched). |
| `native/gc/zig/src/runtime_host.zig` | KotlinX adapter | (local) | Host for linking with `runtime.c` (externs; no duplicate runtime symbols). |
| `native/gc/smoke/`, `native/gc/build.zig` | KotlinX | (local) | Umbrella modules (`kxgc_c` via `addTranslateC`), smokes, docs. |

KotlinX wiring: `NEWKOTLIN_GC_DIR` → `native/gc/c`; default `--gc-runtime=zig` links `native/gc/zig/zig-out/lib/libgc-*.a`.
