# KotlinX

KotlinX compiler: a Kotlin -> LLVM native compiler (`newkotlinc`), written in Kotlin (Kotlin/Native),
with a Perceus-style reference-counting runtime. Moved from `listepo/mini-bench` with history.

- `compiler/`: the KotlinX compiler (sources, C runtime, tests, upstream Kotlin tests).
- `bench/`: all benchmarks (Rust / Zig / Kotlin Native / KotlinX compiler), scripts and committed results.
- `bin/newkotlinc`: launcher (builds the compiler on first use).

## Requirements (macOS)

Kotlin/Native `kotlinc-native` 2.4.21 on PATH, Xcode command line tools, `brew install mimalloc`, and `mise install`
in the repo root (LLVM `opt` and matching `clang` from `mise.toml`).

## Build and run

```
./build.sh                                   # -> compiler/build/newkotlinc.kexe
printf 'fun main() {\n    println("Hello from KotlinX")\n}\n' > /tmp/hello.kt
bin/newkotlinc /tmp/hello.kt -o /tmp/hello   # works from any directory
/tmp/hello                                   # Hello from KotlinX
```

## Tests

```
compiler/test.sh                             # golden tests, benchmark checksums, leak tests, GC tests
python3 compiler/tests/upstream/run.py       # kept upstream Kotlin tests (unmodified)
```

## Benchmarks

```
cd bench
./build.sh && ./round.sh && RESULTS=results/round ./summarize.sh   # all languages
ONLY=newkotlin ./build.sh && ./run.sh && ./summarize.sh            # KotlinX compiler rows only
./nkbench.sh mytest                                                 # -> bench/results/nk/mytest.txt
```

See `STATUS.md` and `bench/table.md`.


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
activation needed). The C compiler/linker is `--cc`, then `$NK_CC`, then the newest mise `conda:clang`, else `clang`
(Apple clang). opt must not be newer than the C compiler's LLVM (older clangs cannot read newer IR); discovery picks
a compatible opt and rejects an incompatible explicit one. Alternatively `brew install llvm` or point `NK_OPT` /
`NK_CC` at any matching LLVM.
If none is found, compilation fails with an error.

- `--alloc malloc`: system malloc/free (used for ASan and macOS `leaks` in `compiler/tests/leaks/run.sh`).
- `--alloc pool`: built-in free-list pool.

## Garbage collector

The memory manager lives in [`compiler/gc`](compiler/gc/README.md) behind a stable interface and is
selected per build: `--gc=default` (RC + cycle collector, the default), `--gc=rc` (no cycle collector),
`--gc=boehm` ([bdwgc](https://github.com/bdwgc/bdwgc), installed by `mise install`). GC benchmarks: [`bench/gc`](bench/gc/README.md).

## Benchmark tooling (`bench/`)

Run from `bench/` (`cd bench`); the scripts use the in-repo compiler (`../compiler`).

All scripts: warm-up + 5 runs per case, mean of the middle 3, in ms.

- `./nkbench.sh <name> [git-ref]`: KotlinX compiler on every case → `results/nk/<name>.txt`.
  With a ref (for example `main`) the compiler is built from that ref; without one, the working tree is used.
  `NKFLAGS="--alloc malloc" ./nkbench.sh sys` passes extra compiler flags.
- `./allocbench.sh [name]`: system malloc vs jemalloc vs mimalloc, plus Kotlin Native (`bench_kn.kexe`
  from `./build.sh`) in the same session, interleaved → `results/alloc/<name>.txt` (`case variant ms checksum`).
  jemalloc is optional: it is used if found at `$JEMALLOC_DIR` or `$(brew --prefix jemalloc)/lib`.
- `compiler/ab.sh [ref]`: working tree vs a ref, ratio per case.

Results kept in the repo:
- `bench/results/baseline-old-newkotlin.txt`: "old NewKotlin" baseline (main, malloc).
- `bench/results/alloc/sys-je-mi-2026-10-28.txt`: allocator comparison (system / jemalloc / mimalloc).
- `bench/results/alloc/mimalloc-default.txt`, `results/nk/baseline-main-session.txt`: mimalloc-default run with
  K/N and the main baseline in the same session.
