# mini-bench

Rust / Zig / Kotlin Native / New Kotlin benchmarks. Status and full results: `STATUS.md`, `table.md`.

## New Kotlin allocator

`newkotlinc` links [mimalloc](https://github.com/microsoft/mimalloc) by default (`--alloc mimalloc`).
The library directory is taken from `$NEWKOTLIN_MIMALLOC_DIR`, then `pkg-config --variable=libdir mimalloc`,
then `/opt/homebrew/opt/mimalloc/lib`, `/usr/local/opt/mimalloc/lib`, `/usr/local/lib`, `/usr/lib`.
If none has `libmimalloc`, compilation fails with an error. Install with `brew install mimalloc`.

Before linking, `newkotlinc` runs LLVM `opt` on the generated IR (`lto-pre-link<O3>` with the default `-flto`,
otherwise `default<O3>`); `--no-opt` skips it. `opt` is taken from `--opt <path>`, then `$NK_OPT`, `opt` on `PATH`,
`xcrun --find opt`, `$CONDA_PREFIX/bin/opt`, `/opt/homebrew/opt/llvm/bin/opt`, `/usr/local/opt/llvm/bin/opt`,
mise `conda:llvm-tools` installs (`~/.local/share/mise/installs/conda-llvm-tools/*`, newest version), the newest
Homebrew `llvm@N`, then mise `llvm` installs. Apple's Xcode toolchain does not ship `opt`. The repo's `mise.toml`
pins `conda:llvm-tools` 21.1.8 (the LLVM major of Apple clang 21; newer opt output cannot be read by an older
clang, and newkotlinc rejects that combination): run `mise install` once (no shell activation needed). Alternatively `brew install llvm`
or point `NK_OPT` at any LLVM `opt`.
If none is found, compilation fails with an error.

- `--alloc malloc`: system malloc/free (used for ASan and macOS `leaks` in `compiler/tests/leaks/run.sh`).
- `--alloc pool`: built-in free-list pool.

## Benchmark tooling

All scripts: warm-up + 5 runs per case, mean of the middle 3, in ms.

- `./nkbench.sh <name> [git-ref]`: New Kotlin on every case → `results/nk/<name>.txt`.
  With a ref (for example `main`) the compiler is built from that ref; without one, the working tree is used.
  `NKFLAGS="--alloc malloc" ./nkbench.sh sys` passes extra compiler flags.
- `./allocbench.sh [name]`: system malloc vs jemalloc vs mimalloc, plus Kotlin Native (`bench_kn.kexe`
  from `./build.sh`) in the same session, interleaved → `results/alloc/<name>.txt` (`case variant ms checksum`).
  jemalloc is optional: it is used if found at `$JEMALLOC_DIR` or `$(brew --prefix jemalloc)/lib`.
- `compiler/ab.sh [ref]`: working tree vs a ref, ratio per case.

Results kept in the repo:
- `results/baseline-old-newkotlin.txt`: "old NewKotlin" baseline (main, malloc).
- `results/alloc/sys-je-mi-2026-10-28.txt`: allocator comparison (system / jemalloc / mimalloc).
- `results/alloc/mimalloc-default.txt`, `results/nk/baseline-main-session.txt`: mimalloc-default run with
  K/N and the main baseline in the same session.
