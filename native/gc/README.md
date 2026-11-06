# Native GC integration (path dependency)

KotlinX vendors GC sources under `native/gc/` (no `compiler/gc` git submodule).

| Path | Role |
|---|---|
| `c/` | C collectors @`6acde64` (`kxgc.h`, `*/gc.c`, benches, legacy tests) |
| `zig/` | Zig collectors @`cf79057` + `runtime_host.zig` adapter |
| `smoke/` | Scoped smokes (`kxgc_c` via `addTranslateC`, Zig `@import`) |
| `build.zig` | Umbrella: path-dep modules + C `kxgc_c` via translate-c |

## Toolchain

- **Native Zig port / `gc-libs` / umbrella smokes:** official **Zig 0.17.1** (`ZIG=` or PATH).
- **Legacy** `c/tests/zig` (C collectors driven from Zig): Zig **0.16.x** (CI historically 0.16.0).
- Do not use mise 0.17.0 or 0.18.0-dev for the native port.

```sh
cd native/gc/zig && zig build gc-libs          # libgc-{epoch,perceus,incremental,rc}.a
cd native/gc && zig build smoke                # Debug/ReleaseFast via -Doptimize=
bin/newkotlinc Main.kt -o main                 # default --gc-runtime=zig
bin/newkotlinc --gc-runtime=c Main.kt -o main  # C collector .c sources
```

## Modules

| `@import` | Source |
|---|---|
| `kxgc` / `host` / `epoch` / … | `b.dependency("kxgc_zig").module(...)` (standalone `host` for Zig-only tests) |
| `runtime_host` | Same package; externs to `runtime.c` (used when building `libgc-*.a`) |
| `kxgc_c` | `b.addTranslateC(smoke/kxgc_c_root.h)` → vendored `c/kxgc.h` (Zig 0.17; `@cImport` removed) |

`host` (standalone exports) must not be linked together with `runtime.c`. Program links use `runtime_host`.

## What changes for programs

Only the **collector backend** is Zig or C. `compiler/runtime/runtime.c` is always linked.
Zig port parity is incomplete vs C (see `zig/docs/zig-port.md`); defaulting to Zig is experimental on this branch.

## Archive freshness (`libgc-*.a`)

- **`bin/newkotlinc`** (Zig backend): always runs `zig build gc-libs` (Zig **0.17.1**) before the compiler so changed collector sources rebuild incrementally. Pass `-Dgc=<name>` when `--gc` / `--gc=<name>` selects one collector; otherwise all four. `--gc-runtime=c` skips Zig entirely.
- **Direct `compiler/build/newkotlinc.kexe`**: does **not** invoke Zig. It links `$NEWKOTLIN_GC_ZIG_LIB_DIR/libgc-<name>.a` if present, otherwise errors with build instructions. There is no silent use of a missing archive, but a **stale** archive is possible if you edit Zig sources and call the `.kexe` without rebuilding — run `cd native/gc/zig && zig build gc-libs` (or use `bin/newkotlinc`) first.
