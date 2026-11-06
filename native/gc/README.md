# Native GC integration (no git submodule)

KotlinX vendors the GC in-tree under `native/gc/` instead of a `compiler/gc` git submodule.

| Path | Contents |
|---|---|
| `c/` | Frozen C collectors from pyrlyn/gc `@6acde64` (`kxgc.h`, `epoch/gc.c`, …) |
| `zig/` | Frozen native Zig port from pyrlyn/gc `@cf79057` (`src/`, `tests/native`, …) |
| `smoke/` | Scoped C and Zig smoke programs (not stubs; real collector sources) |
| `build.zig` | Umbrella: Zig modules + C sources via `link_libc` |

## How `newkotlinc` finds the C GC

`bin/newkotlinc` sets `NEWKOTLIN_GC_DIR` to `native/gc/c` when unset. `Main.kt` uses the same
default relative to the runtime (`compiler/runtime/../../native/gc/c`). The compiler still compiles
`<gcDir>/<name>/gc.c` together with `runtime.c` via clang (`--gc=epoch|perceus|…`). This is path
configuration for the existing C codegen, not a rewrite of the Kotlin/Native compiler into Zig.

## Zig modules

From this directory with official Zig **0.17.1**:

```sh
zig build smoke                 # C smoke + Zig smoke (separate processes)
zig build c-smoke -Doptimize=Debug
zig build c-smoke -Doptimize=ReleaseFast
zig build zig-smoke -Doptimize=Debug
zig build zig-smoke -Doptimize=ReleaseFast
```

Consumer-style imports in the Zig smoke:

```zig
const kxgc = @import("kxgc");
const host = @import("host");       // runtime-symbol adapter (tests/benches)
const collector = @import("epoch"); // real Zig epoch collector
```

C and Zig smokes are **separate executables** so `host` exports do not collide with `runtime.c`.

The vendored Zig package can also be built on its own: `cd zig && zig build test` (still needs 0.17.1).

## Provenance

See [`PROVENANCE.md`](PROVENANCE.md). Do not replace collector bodies with stubs.
