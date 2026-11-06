# Vendored GC provenance

Frozen snapshots vendored into this repository (no git submodule, no out-of-repo symlinks).

| Tree | Upstream | Commit | Role |
|---|---|---|---|
| `native/gc/c/` | pyrlyn/gc | `6acde649c454e710ca1bc79e04651b0ef07b1b26` | Immutable C collectors (`kxgc.h`, `*/gc.c`, benches, C-via-Zig tests) |
| `native/gc/zig/` | pyrlyn/gc | `cf79057Inline epoch alloc/drop like C so no-LTO op_alloc stays tight.` full: `cf79057` | Native Zig collectors (`src/`, `tests/native`, `bench/native`) |

Sources under `c/` and `zig/` are unmodified archives of those commits.
KotlinX wiring (default `NEWKOTLIN_GC_DIR`, umbrella `build.zig`, smoke tests, docs) lives outside those trees.

Zig toolchain for the native port: official **0.17.1** (not mise 0.16/0.17.0).
