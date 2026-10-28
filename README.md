# mini-bench

Rust / Zig / Kotlin Native / New Kotlin benchmarks. Status and full results: `STATUS.md`, `table.md`.

## New Kotlin allocator

`newkotlinc` links [mimalloc](https://github.com/microsoft/mimalloc) by default (`--alloc mimalloc`).
The library directory is taken from `$NEWKOTLIN_MIMALLOC_DIR`, then `pkg-config --variable=libdir mimalloc`,
then `/opt/homebrew/opt/mimalloc/lib`, `/usr/local/opt/mimalloc/lib`, `/usr/local/lib`, `/usr/lib`.
If none has `libmimalloc`, compilation fails with an error. Install with `brew install mimalloc`.

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
