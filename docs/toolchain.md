# Allocator, LLVM opt and garbage collector

## Allocator and LLVM opt

`newkotlinc` links [mimalloc](https://github.com/microsoft/mimalloc) by default (`--alloc mimalloc`).
The library directory is taken from `$NEWKOTLIN_MIMALLOC_DIR`, then `pkg-config --variable=libdir mimalloc`,
then `/opt/homebrew/opt/mimalloc/lib`, `/usr/local/opt/mimalloc/lib`, `/usr/local/lib`, `/usr/lib`.
If none has `libmimalloc`, compilation fails with an error. Install with `brew install mimalloc`.

Before linking, `newkotlinc` runs LLVM `opt` on the generated IR (`lto-pre-link<O3>` with the default `-flto`,
otherwise `default<O3>`); `--no-opt` skips it. `opt` is taken from `--opt <path>`, then `$NK_OPT`, `opt` on `PATH`,
`xcrun --find opt`, `$CONDA_PREFIX/bin/opt`, `/opt/homebrew/opt/llvm/bin/opt`, `/usr/local/opt/llvm/bin/opt`,
mise `conda:llvm-tools` installs (`~/.local/share/mise/installs/conda-llvm-tools/*`, newest version), the newest
Homebrew `llvm@N`, then mise `llvm` installs. Apple's Xcode toolchain does not ship `opt`. The repo's `mise.toml`
installs `conda:llvm-tools` and `conda:clang` (both latest, same LLVM version): run `mise install` once (no shell
activation needed). The C compiler/linker is `--cc`, then `$NK_CC`, then Apple clang at `/usr/bin/clang`, then the newest mise
`conda:clang`, else `clang` on `PATH`. opt must not be newer than the C compiler's LLVM (older clangs cannot read newer IR); discovery picks
a compatible opt and rejects an incompatible explicit one. Alternatively `brew install llvm` or point `NK_OPT` /
`NK_CC` at any matching LLVM.
If none is found, compilation fails with an error.

- `--alloc malloc`: system malloc/free (used for ASan and macOS `leaks` in `compiler/tests/leaks/run.sh`).
- `--alloc pool`: built-in free-list pool.

## Garbage collector

The memory manager lives in [`compiler/gc`](../compiler/gc/README.md) behind a stable interface and is
selected per build: `--gc=default` (RC + cycle collector, the default), `--gc=rc` (no cycle collector),
`--gc=boehm` ([bdwgc](https://github.com/bdwgc/bdwgc), installed by `mise install`). GC benchmarks: [`bench/gc`](../bench/gc/README.md).

`--gc=incremental` (budgeted cycle collector steps) and `--gc=epoch` (deferred RC + background cycle collection) are also available; see [usage.md](usage.md).
